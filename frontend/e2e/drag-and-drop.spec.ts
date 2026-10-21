import { APIResponse, Browser, BrowserContext, expect, Locator, Page, Request, test as base } from '@playwright/test';
import { execFileSync } from 'node:child_process';

/*
 * E2E for specs/undo-trash-dnd-bulk.md Part C (T5c: drag and drop "My order", AC23-AC28), contract 0.18.0.
 * The Decisions log of 2026-10-21 overrides the AC wording:
 *  - no row menu: "Move up" / "Move down" are buttons in the row (disabled at a group's edge);
 *  - hint "Switch to My order to drag" (other sort) / "Show All with no tag or search to drag" (manual + filter/tag/search);
 *  - the sidebar drop works by hit-testing `[data-drop-list-id]`; highlight class list-picker__link--drop;
 *  - cursor v4; manual cursor anchored on the last row's id; PUT /api/tasks/{id}/position {afterId|null} -> 204.
 * The page size is 50 (contract). Needs `docker compose up -d db mail oidc`.
 * Every test fails on browser console errors (other than the browser's own 4xx/5xx lines when a test allows them),
 * page errors, or 5xx responses (unless allowed).
 */

const RUN = `${Date.now()}-${Math.floor(Math.random() * 1e6)}`;
const SHOTS = '../test-results/t5c';
const PASSWORD = 'password123';
const BASE = process.env['E2E_BASE_URL'] ?? 'http://localhost:4200';
let counter = 0;
function uid(): string {
  counter += 1;
  return `${RUN}-${counter}`;
}

const TASK_KEYS = ['addedBy', 'createdAt', 'description', 'done', 'dueDate', 'dueTime', 'id', 'listId', 'myReminder', 'myReminderPassed', 'priority', 'repeat', 'status', 'tags', 'title', 'updatedAt'];
const TASK_RESULT_KEYS = [...TASK_KEYS, 'nextTask'].sort();
const KEPT = 'Undone: the next copy was already changed, so it was kept';
const DAILY = { frequency: 'DAILY', weekdays: [] };
const FAR = '2031-05-05';

interface Task {
  id: number;
  listId: number;
  title: string;
  description: string | null;
  priority: string;
  status: string;
  tags: string[];
  done: boolean;
  dueDate: string | null;
  dueTime: string | null;
  repeat: { frequency: string; weekdays: string[] } | null;
  myReminder: string | null;
  myReminderPassed: boolean;
  createdAt: string;
  updatedAt: string;
  addedBy: { displayName: string } | null;
  nextTask?: Task | null;
  nextTaskOutcome?: string;
}
interface BulkResult {
  count: number;
  tasks: Task[];
}
interface TaskList {
  id: number;
  name: string;
  personal: boolean;
}

// ---------- fixtures: users + console / page / 5xx watch ----------

interface U {
  ctx: BrowserContext;
  page: Page;
  id: number;
  email: string;
  displayName: string;
  consoleErrors: string[];
  pageErrors: string[];
  serverErrors: string[];
}
interface Kit {
  allowNetworkErrors: boolean;
  allow5xx: boolean;
  newUser(tag?: string, verified?: boolean): Promise<U>;
}
const NETWORK_LOG = [
  /^Failed to load resource: net::ERR_FAILED/,
  /^Failed to load resource: the server responded with a status of (400|401|403|404|409|500|503)/,
];

const test = base.extend<{ kit: Kit }>({
  kit: async ({ browser }, use) => {
    const users: U[] = [];
    const kit: Kit = {
      allowNetworkErrors: false,
      allow5xx: false,
      newUser: async (tag = 'u', verified = true) => {
        const u = await newUser(browser, tag, verified);
        users.push(u);
        return u;
      },
    };
    await use(kit);
    const problems: string[] = [];
    for (const u of users) {
      const errors = kit.allowNetworkErrors ? u.consoleErrors.filter((e) => !NETWORK_LOG.some((r) => r.test(e))) : u.consoleErrors;
      if (errors.length) problems.push(`${u.displayName} console errors: ${JSON.stringify(errors)}`);
      if (u.pageErrors.length) problems.push(`${u.displayName} page errors: ${JSON.stringify(u.pageErrors)}`);
      if (!kit.allow5xx && u.serverErrors.length) problems.push(`${u.displayName} 5xx: ${JSON.stringify(u.serverErrors)}`);
    }
    for (const u of users) await u.ctx.close();
    expect(problems, 'browser console / page errors / 5xx').toEqual([]);
  },
});

// ---------- helpers ----------

function sql(query: string): string {
  return execFileSync(
    'docker',
    ['compose', 'exec', '-T', 'db', 'mysql', '--default-character-set=utf8mb4', '-utaskapp', '-ptaskapp', 'taskapp', '-N', '-B', '-e', query],
    { cwd: '..', encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] },
  ).trim();
}
function num(query: string): number {
  return Number(sql(query));
}
async function xsrf(page: Page): Promise<string> {
  let c = (await page.context().cookies()).find((x) => x.name === 'XSRF-TOKEN');
  if (!c) {
    await page.request.get('/api/auth/me');
    c = (await page.context().cookies()).find((x) => x.name === 'XSRF-TOKEN');
  }
  return c!.value;
}
async function api(page: Page, method: string, path: string, data?: unknown): Promise<APIResponse> {
  return page.request.fetch(path, { method, data, headers: { 'X-XSRF-TOKEN': await xsrf(page) } });
}
async function json<T>(res: APIResponse, status = 200): Promise<T> {
  expect(res.status(), `${res.url()} ${await res.text()}`).toBe(status);
  return (status === 204 ? null : await res.json()) as T;
}
interface ProblemBody {
  type: string;
  title: string;
  status: number;
  detail: string;
  code: string;
  errors?: { field: string; code: string; message: string }[];
  failedIds?: number[];
}
async function problem(res: APIResponse, status: number, code: string): Promise<ProblemBody> {
  expect(res.status(), await res.text()).toBe(status);
  expect(res.headers()['content-type']).toContain('application/problem+json');
  const body = (await res.json()) as ProblemBody;
  expect(body.code).toBe(code);
  for (const k of ['type', 'title', 'status', 'detail', 'code']) expect(body, `problem has ${k}`).toHaveProperty(k);
  return body;
}

async function newUser(browser: Browser, tag: string, verified: boolean): Promise<U> {
  const ctx = await browser.newContext({ baseURL: BASE });
  const page = await ctx.newPage();
  const u = { ctx, page, consoleErrors: [], pageErrors: [], serverErrors: [] } as unknown as U;
  page.on('console', (m) => {
    if (m.type() === 'error') u.consoleErrors.push(m.text());
  });
  page.on('pageerror', (e) => u.pageErrors.push(e.message));
  page.on('response', (r) => {
    if (r.status() >= 500) u.serverErrors.push(`${r.status()} ${r.request().method()} ${r.url()}`);
  });
  u.displayName = `${tag} ${uid()}`;
  u.email = `qa-t5c-${tag}-${uid()}@example.com`;
  const res = await page.request.post('/api/auth/register', {
    data: { displayName: u.displayName, email: u.email, password: PASSWORD },
    headers: { 'X-XSRF-TOKEN': await xsrf(page) },
  });
  expect(res.status(), await res.text()).toBe(201);
  u.id = ((await res.json()) as { id: number }).id;
  if (verified) sql(`UPDATE users SET email_verified_at = NOW(6) WHERE id = ${u.id}`);
  return u;
}

async function personal(p: Page): Promise<TaskList> {
  return (await json<TaskList[]>(await p.request.get('/api/lists'))).find((l) => l.personal)!;
}
async function createList(p: Page, name: string): Promise<TaskList> {
  return json<TaskList>(await api(p, 'POST', '/api/lists', { name }), 201);
}
async function share(owner: U, listId: number, member: U) {
  await json(await api(owner.page, 'POST', `/api/lists/${listId}/invitations`, { email: member.email }), 201);
  const invs = await json<{ id: number; list: { id: number } }[]>(await member.page.request.get('/api/invitations'));
  const inv = invs.find((i) => i.list.id === listId)!;
  await json(await api(member.page, 'POST', `/api/invitations/${inv.id}/accept`));
}
interface Mk {
  listId?: number;
  description?: string | null;
  priority?: string;
  status?: string;
  tags?: string[];
  dueDate?: string;
  dueTime?: string;
  reminder?: string;
  repeat?: unknown;
}
async function mk(p: Page, title: string, o: Mk = {}): Promise<Task> {
  const { listId, ...rest } = o;
  const t = await json<Task>(await api(p, 'POST', '/api/tasks', listId ? { title, listId } : { title }), 201);
  if (Object.keys(rest).length === 0) return t;
  return json<Task>(await api(p, 'PATCH', `/api/tasks/${t.id}`, rest));
}
async function get(p: Page, id: number): Promise<Task> {
  return json<Task>(await p.request.get(`/api/tasks/${id}`));
}
async function mkMany(p: Page, prefix: string, n: number, o: Mk = {}): Promise<Task[]> {
  const out: Task[] = [];
  for (let i = 0; i < n; i += 10) {
    const batch = await Promise.all(Array.from({ length: Math.min(10, n - i) }, (_, k) => mk(p, `${prefix}${String(i + k).padStart(3, '0')}`, o)));
    out.push(...batch);
  }
  return out;
}
/** Every task of a list (all pages), keyed by title. */
async function allTasks(p: Page, listId?: number): Promise<Task[]> {
  const out: Task[] = [];
  let cursor: string | null = null;
  do {
    const qs: string = `?${listId ? `list=${listId}&` : ''}${cursor ? `cursor=${cursor}` : ''}`;
    const page: { items: Task[]; nextCursor: string | null } = await json(await p.request.get(`/api/tasks${qs}`));
    out.push(...page.items);
    cursor = page.nextCursor;
  } while (cursor);
  return out;
}
async function byTitle(p: Page, listId?: number): Promise<Record<string, Task>> {
  return Object.fromEntries((await allTasks(p, listId)).map((t) => [t.title, t]));
}
async function trashedIds(p: Page): Promise<number[]> {
  const body = await json<{ items: Task[] }>(await p.request.get('/api/trash'));
  return body.items.map((t) => t.id);
}
function bulk(p: Page, body: unknown): Promise<APIResponse> {
  return api(p, 'POST', '/api/tasks/bulk', body);
}


function mutations(p: Page): string[] {
  const seen: string[] = [];
  p.on('request', (r) => {
    if (['POST', 'PUT', 'PATCH', 'DELETE'].includes(r.method()) && new URL(r.url()).pathname.startsWith('/api/') && !/\/api\/(auth|account|notifications)/.test(new URL(r.url()).pathname)) {
      seen.push(r.method() + ' ' + new URL(r.url()).pathname);
    }
  });
  return seen;
}
function requests(p: Page, method: string, re: RegExp): Request[] {
  const seen: Request[] = [];
  p.on('request', (r) => {
    if (r.method() === method && re.test(new URL(r.url()).pathname)) seen.push(r);
  });
  return seen;
}
// ---------- DnD helpers ----------

function toastRegion(p: Page): Locator {
  return p.locator('app-toast-host [role=status]');
}
function toast(p: Page): Locator {
  return toastRegion(p).locator('.toast');
}
function undoBtn(p: Page): Locator {
  return toast(p).getByRole('button', { name: 'Undo' });
}
function banner(p: Page): Locator {
  return p.getByRole('alert');
}
function rowOf(p: Page, id: number): Locator {
  return p.locator(`app-task-row[data-task-id="${id}"]`);
}
function handleOf(p: Page, id: number): Locator {
  return p.locator(`[data-handle-for="${id}"]`);
}
async function shot(p: Page, name: string) {
  await p.screenshot({ path: `${SHOTS}/${name}.png`, fullPage: true });
}
/** Row ids in DOM order (all groups). */
async function uiIds(p: Page): Promise<number[]> {
  return p.locator('app-task-row[data-task-id]').evaluateAll((els) => els.map((e) => Number(e.getAttribute('data-task-id'))));
}
async function groupIds(p: Page, group: 'open' | 'done'): Promise<number[]> {
  return p.getByTestId(`sort-group-${group}`).locator('app-task-row[data-task-id]').evaluateAll((els) => els.map((e) => Number(e.getAttribute('data-task-id'))));
}
/** Server order of a list (all pages) with sort=manual. */
async function manualIds(p: Page, listId: number, extra = ''): Promise<number[]> {
  const out: number[] = [];
  let cursor: string | null = null;
  do {
    const page: { items: Task[]; nextCursor: string | null } = await json(await p.request.get(`/api/tasks?list=${listId}&sort=manual${extra}${cursor ? `&cursor=${cursor}` : ''}`));
    out.push(...page.items.map((t) => t.id));
    cursor = page.nextCursor;
  } while (cursor);
  return out;
}
async function putPos(p: Page, id: number, afterId: number | null): Promise<APIResponse> {
  return api(p, 'PUT', `/api/tasks/${id}/position`, { afterId });
}
/** Open "My order" for a list and wait for the draggable list. */
async function openManual(p: Page, listId?: number, extraQuery = '') {
  const path = listId ? `/lists/${listId}` : '/tasks';
  await p.goto(`${path}?sort=manual${extraQuery}`);
  await expect(p.getByText('Loading tasks…')).toHaveCount(0);
  await expect(p.getByText('Loading lists…')).toHaveCount(0);
  await expect(p.getByTestId('sort-group-open').or(p.getByTestId('sort-group-done')).first()).toBeVisible();
  await expect(p.locator('#task-sort')).toHaveValue('manual');
}
/**
 * Mouse drag with the grip. `to` is a locator (a row or a sidebar link); `where` picks the vertical spot
 * in it (0 top .. 1 bottom). Waits for the PUT unless `expectPut` is false.
 */
async function dragTo(p: Page, id: number, to: Locator, where: number, o: { release?: boolean } = {}) {
  const h = await handleOf(p, id).boundingBox();
  const t = await to.boundingBox();
  expect(h, 'handle box').toBeTruthy();
  expect(t, 'target box').toBeTruthy();
  const sx = h!.x + h!.width / 2;
  const sy = h!.y + h!.height / 2;
  await p.mouse.move(sx, sy);
  await p.mouse.down();
  await p.mouse.move(sx + 3, sy + (t!.y > sy ? 6 : -6), { steps: 3 });
  await p.mouse.move(t!.x + Math.min(t!.width / 2, 200), t!.y + t!.height * where, { steps: 25 });
  await p.mouse.move(t!.x + Math.min(t!.width / 2, 200), t!.y + t!.height * where + 1, { steps: 3 });
  if (o.release !== false) await p.mouse.up();
}
function putResponse(p: Page) {
  return p.waitForResponse((r) => r.request().method() === 'PUT' && /\/api\/tasks\/\d+\/position$/.test(new URL(r.url()).pathname));
}
async function dragRow(p: Page, id: number, overId: number, dir: 'above' | 'below') {
  const put = putResponse(p);
  await dragTo(p, id, rowOf(p, overId), 0.5);
  const r = await put;
  expect(r.status()).toBe(204);
  return r;
}
async function closeToast(p: Page) {
  const close = toast(p).getByRole('button', { name: /close|dismiss/i });
  if (await close.count()) await close.first().click();
  else await p.keyboard.press('Escape');
}
async function announcements(p: Page): Promise<string> {
  return p.locator('.cdk-live-announcer-element').evaluateAll((els) => els.map((e) => e.textContent).join(' | '));
}
const sortDelay = () => new Promise((r) => setTimeout(r, 150));

test.use({ viewport: { width: 1280, height: 900 } });

// =====================================================================================
// AC23: the "My order" option, NULL fallback, new task on top
// =====================================================================================

test.describe('AC23 My order option and the fallback order', () => {
  test('AC23: the sort menu has "My order"; choosing it sets sort=manual and shows newest-first for never-ordered tasks', async ({ kit }) => {
    const u = await kit.newUser('ac23a');
    const p = u.page;
    const [a, b, c] = [await mk(p, 'A-first'), await mk(p, 'B-second'), await mk(p, 'C-third')];
    await p.goto('/tasks');
    await expect(p.getByText('Loading tasks…')).toHaveCount(0);
    await expect(p.locator('#task-sort option', { hasText: 'My order' })).toHaveCount(1);
    await expect(p.getByTestId('drag-handle')).toHaveCount(0);
    await p.locator('#task-sort').selectOption({ label: 'My order' });
    await expect(p).toHaveURL(/sort=manual/);
    await expect(p.getByTestId('drag-handle')).toHaveCount(3);
    expect(await uiIds(p)).toEqual([c.id, b.id, a.id]);
    await shot(p, 'ac23-my-order');
  });

  test('AC23: tasks that never had a position are newest-first; positioned tasks come first (SQL-simulated V15 state)', async ({ kit }) => {
    const u = await kit.newUser('ac23b');
    const p = u.page;
    const l = await personal(p);
    const t = await mkMany(p, 'T', 5); // creation order t0..t4 (parallel batches: use API ids)
    const byNew = (await allTasks(p, l.id)).map((x) => x.id); // newest first
    sql(`UPDATE tasks SET position = NULL WHERE list_id = ${l.id}`);
    expect(await manualIds(p, l.id)).toEqual(byNew);
    // One positioned task comes before every unpositioned one.
    const last = byNew[byNew.length - 1];
    sql(`UPDATE tasks SET position = 5000 WHERE id = ${last}`);
    expect(await manualIds(p, l.id)).toEqual([last, ...byNew.slice(0, -1)]);
    void t;
  });

  test('AC23: a new task goes to the top (UI add, also above already-ordered tasks)', async ({ kit }) => {
    const u = await kit.newUser('ac23c');
    const p = u.page;
    const l = await personal(p);
    const [a, b, c] = [await mk(p, 'N1'), await mk(p, 'N2'), await mk(p, 'N3')];
    // Order it by hand first: a, b, c (reverse of newest).
    await json(await putPos(p, a.id, null), 204);
    await json(await putPos(p, b.id, a.id), 204);
    await json(await putPos(p, c.id, b.id), 204);
    expect(await manualIds(p, l.id)).toEqual([a.id, b.id, c.id]);
    await openManual(p);
    expect(await uiIds(p)).toEqual([a.id, b.id, c.id]);
    const create = p.waitForResponse((r) => r.request().method() === 'POST' && new URL(r.url()).pathname === '/api/tasks');
    await p.getByLabel('New task').fill('Brand new on top');
    await p.getByLabel('New task').press('Enter');
    const created = (await (await create).json()) as Task;
    await expect(rowOf(p, created.id)).toBeVisible();
    await expect.poll(() => uiIds(p)).toEqual([created.id, a.id, b.id, c.id]);
    expect((await manualIds(p, l.id))[0]).toBe(created.id);
    await shot(p, 'ac23-new-on-top');
  });
});

// =====================================================================================
// AC24: drag with the mouse, persistence, sharing, groups, hidden handles + hint
// =====================================================================================

test.describe('AC24 drag, persist, groups, hint', () => {
  test('AC24: dragging a row with the mouse sends one PUT, keeps the new place after a reload', async ({ kit }) => {
    const u = await kit.newUser('ac24a');
    const p = u.page;
    const l = await personal(p);
    const ts = [await mk(p, 'D1'), await mk(p, 'D2'), await mk(p, 'D3'), await mk(p, 'D4')];
    const [d1, d2, d3, d4] = ts;
    await openManual(p);
    expect(await uiIds(p)).toEqual([d4.id, d3.id, d2.id, d1.id]);
    const puts = requests(p, 'PUT', /\/position$/);
    // bottom row (d1) above the top row (d4) -> top
    const r = await dragRow(p, d1.id, d4.id, 'above');
    expect(JSON.parse(r.request().postData() ?? '{}')).toEqual({ afterId: null });
    expect(await uiIds(p)).toEqual([d1.id, d4.id, d3.id, d2.id]);
    // top row (d1) below d3 -> after d3
    const r2 = await dragRow(p, d1.id, d3.id, 'below');
    expect(JSON.parse(r2.request().postData() ?? '{}')).toEqual({ afterId: d3.id });
    expect(puts.length).toBe(2);
    const expected = [d4.id, d3.id, d1.id, d2.id];
    expect(await uiIds(p)).toEqual(expected);
    await shot(p, 'ac24-after-drag');
    await p.reload();
    await expect(p.getByTestId('sort-group-open')).toBeVisible();
    expect(await uiIds(p)).toEqual(expected);
    expect(await manualIds(p, l.id)).toEqual(expected);
  });

  test('AC24: a second member of a shared list sees the same order after reload', async ({ kit }) => {
    const owner = await kit.newUser('ac24b-o');
    const member = await kit.newUser('ac24b-m');
    const list = await createList(owner.page, 'Shared order');
    await share(owner, list.id, member);
    const [s1, s2, s3] = [await mk(owner.page, 'S1', { listId: list.id }), await mk(member.page, 'S2', { listId: list.id }), await mk(owner.page, 'S3', { listId: list.id })];
    await openManual(member.page, list.id);
    expect(await uiIds(member.page)).toEqual([s3.id, s2.id, s1.id]);
    await dragRow(member.page, s3.id, s1.id, 'below'); // member drags
    await openManual(owner.page, list.id);
    expect(await uiIds(owner.page)).toEqual([s2.id, s1.id, s3.id]);
    // and the owner reorders; the member sees it after a reload
    await dragRow(owner.page, s2.id, s3.id, 'below');
    await member.page.reload();
    await expect(member.page.getByTestId('sort-group-open')).toBeVisible();
    expect(await uiIds(member.page)).toEqual([s1.id, s3.id, s2.id]);
  });

  test('AC24: done tasks form their own group below; a row cannot be dragged into the other group', async ({ kit }) => {
    const u = await kit.newUser('ac24c');
    const p = u.page;
    const l = await personal(p);
    const [a, b, c, d] = [await mk(p, 'G1'), await mk(p, 'G2'), await mk(p, 'G3'), await mk(p, 'G4')];
    await json(await api(p, 'PATCH', `/api/tasks/${a.id}`, { status: 'DONE' })); // a, b done
    await json(await api(p, 'PATCH', `/api/tasks/${b.id}`, { status: 'DONE' }));
    await openManual(p);
    expect(await groupIds(p, 'open')).toEqual([d.id, c.id]);
    expect(await groupIds(p, 'done')).toEqual([b.id, a.id]);
    // the open group sits above the done group
    const ob = (await p.getByTestId('sort-group-open').boundingBox())!;
    const db = (await p.getByTestId('sort-group-done').boundingBox())!;
    expect(ob.y).toBeLessThan(db.y);
    await shot(p, 'ac24-groups');
    // drag an open row over a done row: no PUT, nothing changes
    const puts = requests(p, 'PUT', /\/position$/);
    await dragTo(p, d.id, rowOf(p, a.id), 0.8);
    await sortDelay();
    await p.waitForTimeout(600);
    expect(puts.length).toBe(0);
    expect(await groupIds(p, 'open')).toEqual([d.id, c.id]);
    expect(await groupIds(p, 'done')).toEqual([b.id, a.id]);
    // dragging inside the done group works
    await dragRow(p, a.id, b.id, 'above');
    expect(await groupIds(p, 'done')).toEqual([a.id, b.id]);
    expect(await manualIds(p, l.id)).toEqual([d.id, c.id, a.id, b.id]);
    // API guard: a position across groups is refused
    const bad = await putPos(p, d.id, a.id);
    const body = await problem(bad, 400, 'VALIDATION_FAILED');
    expect(body.errors).toEqual([expect.objectContaining({ field: 'afterId', code: 'OTHER_GROUP' })]);
  });

  test('AC24: handles are hidden and a hint shows with another sort, tab, tag, search or in selection mode', async ({ kit }) => {
    const u = await kit.newUser('ac24d');
    const p = u.page;
    await mk(p, 'H1 milk', { tags: ['errand'] });
    await mk(p, 'H2');
    await mk(p, 'H3');
    // newest sort: no handles, hint to switch
    await p.goto('/tasks');
    await expect(p.getByText('Loading tasks…')).toHaveCount(0);
    await expect(p.getByTestId('drag-handle')).toHaveCount(0);
    await expect(p.getByTestId('drag-hint')).toHaveText('Switch to My order to drag');
    for (const s of ['oldest', 'due', 'priority', 'title']) {
      await p.goto(`/tasks?sort=${s}`);
      await expect(p.getByText('Loading tasks…')).toHaveCount(0);
      await expect(p.getByTestId('drag-handle')).toHaveCount(0);
      await expect(p.getByTestId('drag-hint')).toHaveText('Switch to My order to drag');
    }
    // manual + All: handles, no hint
    await openManual(p);
    await expect(p.getByTestId('drag-handle')).toHaveCount(3);
    await expect(p.getByTestId('drag-hint')).toHaveCount(0);
    // manual + Active tab
    await p.goto('/tasks?sort=manual&filter=active');
    await expect(p.getByText('Loading tasks…')).toHaveCount(0);
    await expect(p.getByTestId('drag-handle')).toHaveCount(0);
    await expect(p.getByTestId('drag-hint')).toHaveText('Show All with no tag or search to drag');
    // manual + tag
    await p.goto('/tasks?sort=manual&tag=errand');
    await expect(p.getByText('Loading tasks…')).toHaveCount(0);
    await expect(p.getByTestId('drag-handle')).toHaveCount(0);
    await expect(p.getByTestId('drag-hint')).toHaveText('Show All with no tag or search to drag');
    // manual + search
    await p.goto('/tasks?sort=manual&q=milk');
    await expect(p.getByText('Loading tasks…')).toHaveCount(0);
    await expect(p.getByTestId('drag-handle')).toHaveCount(0);
    await expect(p.getByTestId('drag-hint')).toHaveText('Show All with no tag or search to drag');
    await shot(p, 'ac24-hint-search');
    // selection mode hides the handles
    await openManual(p);
    await p.getByTestId('select-mode').click();
    await expect(p.getByTestId('bulk-bar')).toBeVisible();
    await expect(p.getByTestId('drag-handle')).toHaveCount(0);
  });

  test('AC24: touch drag (CDP touch events on the grip) sends one PUT and the order survives a reload', async ({ kit, browser }) => {
    const u = await kit.newUser('ac24t');
    const l = await personal(u.page);
    const [t1, t2, t3] = [await mk(u.page, 'T1'), await mk(u.page, 'T2'), await mk(u.page, 'T3')];
    const tctx = await browser.newContext({ baseURL: BASE, hasTouch: true, storageState: await u.ctx.storageState(), viewport: { width: 1280, height: 900 } });
    const p = await tctx.newPage();
    const errors: string[] = [];
    p.on('console', (m) => m.type() === 'error' && errors.push(m.text()));
    p.on('pageerror', (e) => errors.push(e.message));
    await openManual(p);
    expect(await uiIds(p)).toEqual([t3.id, t2.id, t1.id]);
    const puts = requests(p, 'PUT', /\/position$/);
    const cdp = await tctx.newCDPSession(p);
    const hb = (await handleOf(p, t1.id).boundingBox())!;
    const tb = (await rowOf(p, t3.id).boundingBox())!;
    const sx = hb.x + hb.width / 2;
    const sy = hb.y + hb.height / 2;
    const ty = tb.y + tb.height * 0.3;
    const touch = (type: string, x: number, y: number) => cdp.send('Input.dispatchTouchEvent', { type, touchPoints: type === 'touchEnd' ? [] : [{ x, y }] });
    const put = putResponse(p);
    await touch('touchStart', sx, sy);
    await p.waitForTimeout(300);
    const steps = 20;
    for (let i = 1; i <= steps; i++) {
      await touch('touchMove', sx, sy + ((ty - sy) * i) / steps);
      await p.waitForTimeout(20);
    }
    await touch('touchMove', sx, ty + 1);
    await p.waitForTimeout(100);
    await touch('touchEnd', sx, ty);
    expect((await put).status()).toBe(204);
    expect(puts.length).toBe(1);
    expect(JSON.parse(puts[0].postData() ?? '{}')).toEqual({ afterId: null });
    expect(await uiIds(p)).toEqual([t1.id, t3.id, t2.id]);
    await p.reload();
    await expect(p.getByTestId('sort-group-open')).toBeVisible();
    expect(await uiIds(p)).toEqual([t1.id, t3.id, t2.id]);
    expect(await manualIds(p, l.id)).toEqual([t1.id, t3.id, t2.id]);
    expect(errors).toEqual([]);
    await tctx.close();
  });

  test('AC24: the server accepts sort=manual with a filter/tag/q (server-side), and the touch/pointer path is the same pointer events', async ({ kit }) => {
    const u = await kit.newUser('ac24e');
    const p = u.page;
    const l = await personal(p);
    await mk(p, 'Q one', { tags: ['x'] });
    await mk(p, 'Q two');
    const r = await json<{ items: Task[]; total: number }>(await p.request.get(`/api/tasks?list=${l.id}&sort=manual&filter=active&tag=x&q=one`));
    expect(r.items.map((t) => t.title)).toEqual(['Q one']);
  });
});

// =====================================================================================
// AC25: keyboard and accessibility
// =====================================================================================

test.describe('AC25 keyboard', () => {
  test('AC25: Space picks up, Down/Up move with announcements, Space drops with one PUT', async ({ kit }) => {
    const u = await kit.newUser('ac25a');
    const p = u.page;
    const l = await personal(p);
    const ts = await Promise.all([1, 2, 3, 4, 5].map((i) => mk(p, `K${i}`)));
    const order = (await allTasks(p, l.id)).map((t) => t.id); // newest first
    await openManual(p);
    expect(await uiIds(p)).toEqual(order);
    const puts = requests(p, 'PUT', /\/position$/);
    const first = order[0];
    await handleOf(p, first).focus();
    await p.keyboard.press('Space');
    await expect(handleOf(p, first)).toHaveAttribute('aria-pressed', 'true');
    await expect.poll(() => announcements(p)).toContain('Picked up');
    await p.keyboard.press('ArrowDown');
    await expect.poll(() => announcements(p)).toContain('Moved to position 2 of 5');
    await p.keyboard.press('ArrowDown');
    await expect.poll(() => announcements(p)).toContain('Moved to position 3 of 5');
    await p.keyboard.press('ArrowUp');
    await expect.poll(() => announcements(p)).toContain('Moved to position 2 of 5');
    expect(puts.length).toBe(0); // nothing is saved while moving
    await p.keyboard.press('ArrowDown');
    const put = putResponse(p);
    await p.keyboard.press('Space');
    expect((await put).status()).toBe(204);
    await expect(handleOf(p, first)).toHaveAttribute('aria-pressed', 'false');
    expect(puts.length).toBe(1);
    expect(JSON.parse(puts[0].postData() ?? '{}')).toEqual({ afterId: order[2] });
    await expect.poll(() => announcements(p)).toContain('Dropped at position 3 of 5');
    const expected = [order[1], order[2], order[0], order[3], order[4]];
    expect(await uiIds(p)).toEqual(expected);
    expect(await manualIds(p, l.id)).toEqual(expected);
    void ts;
    await shot(p, 'ac25-keyboard-dropped');
  });

  test('AC25: Esc cancels (order restored, no PUT)', async ({ kit }) => {
    const u = await kit.newUser('ac25b');
    const p = u.page;
    const l = await personal(p);
    await Promise.all([1, 2, 3].map((i) => mk(p, `E${i}`)));
    const order = (await allTasks(p, l.id)).map((t) => t.id);
    await openManual(p);
    const puts = requests(p, 'PUT', /\/position$/);
    await handleOf(p, order[0]).focus();
    await p.keyboard.press('Space');
    await p.keyboard.press('ArrowDown');
    await p.keyboard.press('ArrowDown');
    await expect.poll(() => announcements(p)).toContain('Moved to position 3 of 3');
    await p.keyboard.press('Escape');
    await expect(handleOf(p, order[0])).toHaveAttribute('aria-pressed', 'false');
    await expect.poll(() => announcements(p)).toContain('Cancelled');
    expect(await uiIds(p)).toEqual(order);
    await p.waitForTimeout(500);
    expect(puts.length).toBe(0);
    expect(await manualIds(p, l.id)).toEqual(order);
  });

  test('AC25: Move up / Move down buttons reorder, announce, and are disabled at a group edge', async ({ kit }) => {
    const u = await kit.newUser('ac25c');
    const p = u.page;
    const l = await personal(p);
    await Promise.all([1, 2, 3, 4].map((i) => mk(p, `M${i}`)));
    const order = (await allTasks(p, l.id)).map((t) => t.id);
    await openManual(p);
    await expect(rowOf(p, order[0]).getByTestId('move-up')).toBeDisabled();
    await expect(rowOf(p, order[0]).getByTestId('move-down')).toBeEnabled();
    await expect(rowOf(p, order[3]).getByTestId('move-down')).toBeDisabled();
    const put = putResponse(p);
    await rowOf(p, order[0]).getByTestId('move-down').click();
    expect((await put).status()).toBe(204);
    expect(await uiIds(p)).toEqual([order[1], order[0], order[2], order[3]]);
    await expect.poll(() => announcements(p)).toContain('Moved to position 2 of 4');
    const put2 = putResponse(p);
    await rowOf(p, order[3]).getByTestId('move-up').click();
    expect((await put2).status()).toBe(204);
    expect(await uiIds(p)).toEqual([order[1], order[0], order[3], order[2]]);
    await p.reload();
    await expect(p.getByTestId('sort-group-open')).toBeVisible();
    expect(await uiIds(p)).toEqual([order[1], order[0], order[3], order[2]]);
    // edges again after the move
    await expect(rowOf(p, order[1]).getByTestId('move-up')).toBeDisabled();
    // with a done group, the edge is per group
    await json(await api(p, 'PATCH', `/api/tasks/${order[2]}`, { status: 'DONE' }));
    await p.reload();
    await expect(p.getByTestId('sort-group-done')).toBeVisible();
    await expect(rowOf(p, order[2]).getByTestId('move-up')).toBeDisabled();
    await expect(rowOf(p, order[2]).getByTestId('move-down')).toBeDisabled();
    await expect(rowOf(p, order[3]).getByTestId('move-down')).toBeDisabled();
    await shot(p, 'ac25-move-buttons');
  });

  test('AC25: the grip is a labelled button ("Reorder: <title>") reachable by Tab', async ({ kit }) => {
    const u = await kit.newUser('ac25d');
    const p = u.page;
    const t = await mk(p, 'Labelled task');
    await openManual(p);
    const h = p.getByRole('button', { name: 'Reorder: Labelled task' });
    await expect(h).toHaveCount(1);
    await expect(h).toHaveAttribute('data-handle-for', String(t.id));
    await h.focus();
    await expect(h).toBeFocused();
  });
});

// =====================================================================================
// AC26: paging
// =====================================================================================

test.describe('AC26 paging', () => {
  test('AC26: dropping onto the last loaded row puts the task right after it; Load more has no repeats or gaps', async ({ kit }) => {
    test.setTimeout(180_000);
    const u = await kit.newUser('ac26a');
    const p = u.page;
    const l = await personal(p);
    const made = await mkMany(p, 'P', 60);
    const all = new Set(made.map((t) => t.id));
    await p.setViewportSize({ width: 1280, height: 7000 });
    await openManual(p);
    const first = await uiIds(p);
    expect(first.length).toBe(50);
    await expect(p.getByText('Showing 50 of 60')).toBeVisible();
    const moving = first[0];
    const lastLoaded = first[49];
    const r = await dragRow(p, moving, lastLoaded, 'below');
    expect(JSON.parse(r.request().postData() ?? '{}')).toEqual({ afterId: lastLoaded });
    const afterDrop = await uiIds(p);
    expect(afterDrop.length).toBe(50);
    expect(afterDrop[49]).toBe(moving);
    expect(afterDrop[48]).toBe(lastLoaded);
    await closeToast(p);
    const more = p.waitForResponse((x) => x.url().includes('cursor=') && x.request().method() === 'GET');
    await p.getByRole('button', { name: 'Load more' }).click();
    expect((await more).status()).toBe(200);
    await expect.poll(async () => (await uiIds(p)).length).toBe(60);
    const ids = await uiIds(p);
    expect(new Set(ids).size, 'no repeated rows').toBe(60);
    expect(new Set(ids)).toEqual(all); // no gaps
    // the moved row is still right after the previously last loaded one
    expect(ids.indexOf(moving)).toBe(ids.indexOf(lastLoaded) + 1);
    await shot(p, 'ac26-after-load-more');
    // and the server's full order agrees with the screen
    expect(await manualIds(p, l.id)).toEqual(ids);
  });

  test('AC26 (API): a drop after the page-1 anchor, then page 2: every task shows up once across both pages (client de-dup)', async ({ kit }) => {
    test.setTimeout(120_000);
    const u = await kit.newUser('ac26b');
    const p = u.page;
    const l = await personal(p);
    const made = await mkMany(p, 'Q', 55);
    const page1 = await json<{ items: Task[]; nextCursor: string | null }>(await p.request.get(`/api/tasks?list=${l.id}&sort=manual`));
    expect(page1.items.length).toBe(50);
    expect(page1.nextCursor).toBeTruthy();
    const anchor = page1.items[49].id;
    const moving = page1.items[0].id;
    await json(await putPos(p, moving, anchor), 204);
    const page2 = await json<{ items: Task[]; nextCursor: string | null }>(await p.request.get(`/api/tasks?list=${l.id}&sort=manual&cursor=${page1.nextCursor}`));
    const seen = new Set<number>([...page1.items.map((t) => t.id)]);
    for (const t of page2.items) seen.add(t.id);
    expect(seen.size).toBe(55);
    expect(new Set(made.map((t) => t.id))).toEqual(seen); // none skipped
    // the moved task (dropped after the anchor) comes back on page 2, first
    expect(page2.items[0].id).toBe(moving);
    // after the move, the real order is page1(without moving) + moving + rest, same as a full read
    const full = await manualIds(p, l.id);
    expect(full.length).toBe(55);
    expect(full[49]).toBe(moving);
  });

  test('AC26: a v3 (pre-0.18) cursor gives 400 INVALID_CURSOR; the UI reloads from page 1 when Load more gets it', async ({ kit }) => {
    test.setTimeout(120_000);
    kit.allowNetworkErrors = true;
    const u = await kit.newUser('ac26c');
    const p = u.page;
    const l = await personal(p);
    await mkMany(p, 'C', 52);
    const v3 = Buffer.from(JSON.stringify({ v: 3, s: 'manual', f: 'x', k: [0, '2026-10-01T18:00:00Z', 7] })).toString('base64url');
    for (const sort of ['newest', 'manual']) {
      const res = await p.request.get(`/api/tasks?list=${l.id}&sort=${sort}&cursor=${v3}`);
      const body = await problem(res, 400, 'VALIDATION_FAILED');
      expect(body.errors).toEqual([expect.objectContaining({ field: 'cursor', code: 'INVALID_CURSOR' })]);
    }
    // UI: the next Load more answers INVALID_CURSOR once
    await openManual(p);
    await expect(p.getByText('Showing 50 of 52')).toBeVisible();
    let failed = false;
    await p.route(/\/api\/tasks\?.*cursor=/, async (route) => {
      if (!failed) {
        failed = true;
        await route.fulfill({
          status: 400,
          contentType: 'application/problem+json',
          body: JSON.stringify({ type: 'about:blank', title: 'Bad Request', status: 400, detail: 'Validation failed', instance: '/api/tasks', code: 'VALIDATION_FAILED', errors: [{ field: 'cursor', code: 'INVALID_CURSOR', message: 'bad' }] }),
        });
      } else await route.fallback();
    });
    await p.getByRole('button', { name: 'Load more' }).click();
    await expect.poll(() => failed).toBe(true);
    await expect(p.getByText('Loading tasks…')).toHaveCount(0);
    await expect(p.getByText('Showing 50 of 52')).toBeVisible(); // reloaded from the first page
    expect((await uiIds(p)).length).toBe(50);
    await shot(p, 'ac26-invalid-cursor');
  });
});

// =====================================================================================
// AC27: sidebar drop
// =====================================================================================

test.describe('AC27 sidebar drop', () => {
  test('AC27: dragging a row onto another list moves it; highlight while hovering; toast "Moved to <list>"; Undo puts it back', async ({ kit }) => {
    const u = await kit.newUser('ac27a');
    const p = u.page;
    const mine = await personal(p);
    const other = await createList(p, 'Elsewhere');
    const [x, y] = [await mk(p, 'Mover X', { tags: ['alpha', 'beta'] }), await mk(p, 'Stays Y')];
    await openManual(p);
    const link = p.locator(`[data-drop-list-id="${other.id}"]`);
    await expect(link).toBeVisible();
    await dragTo(p, x.id, link, 0.5, { release: false });
    await expect(link).toHaveClass(/list-picker__link--drop/);
    await shot(p, 'ac27-hover');
    const patch = p.waitForResponse((r) => r.request().method() === 'PATCH' && new URL(r.url()).pathname === `/api/tasks/${x.id}`);
    await p.mouse.up();
    expect((await patch).status()).toBe(200);
    await expect(link).not.toHaveClass(/list-picker__link--drop/);
    await expect(toast(p)).toContainText('Moved to Elsewhere');
    await expect(rowOf(p, x.id)).toHaveCount(0);
    expect(await uiIds(p)).toEqual([y.id]);
    const moved = await get(p, x.id);
    expect(moved.listId).toBe(other.id);
    expect(moved.tags.sort()).toEqual(['alpha', 'beta']);
    // top of the destination
    expect((await manualIds(p, other.id))[0]).toBe(x.id);
    await shot(p, 'ac27-moved-toast');
    await undoBtn(p).click();
    await expect(rowOf(p, x.id)).toHaveCount(1);
    const back = await get(p, x.id);
    expect(back.listId).toBe(mine.id);
    expect(back.tags.sort()).toEqual(['alpha', 'beta']);
  });

  test('AC27: dropping on the own list or on Trash does nothing; Trash is not a drop target', async ({ kit }) => {
    const u = await kit.newUser('ac27b');
    const p = u.page;
    const mine = await personal(p);
    const [a, b] = [await mk(p, 'Z1'), await mk(p, 'Z2')];
    await openManual(p);
    const muts = mutations(p);
    const own = p.locator(`[data-drop-list-id="${mine.id}"]`);
    await expect(own).toBeVisible();
    await dragTo(p, a.id, own, 0.5);
    await p.waitForTimeout(700);
    await expect(toast(p)).toHaveCount(0);
    expect(await uiIds(p)).toEqual([b.id, a.id]);
    // Trash entry
    const trash = p.getByRole('link', { name: /Trash/ });
    await expect(trash).toBeVisible();
    await expect(p.locator('[data-drop-list-id]').filter({ hasText: 'Trash' })).toHaveCount(0);
    await dragTo(p, a.id, trash, 0.5, { release: false });
    await expect(trash).not.toHaveClass(/list-picker__link--drop/);
    await p.mouse.up();
    await p.waitForTimeout(700);
    await expect(toast(p)).toHaveCount(0);
    await expect(p).toHaveURL(/\/tasks/);
    expect(await uiIds(p)).toEqual([b.id, a.id]);
    expect(muts.filter((m) => !m.includes('/api/tasks?')).length).toBe(0);
    expect((await get(p, a.id)).listId).toBe(mine.id);
    expect((await json<{ items: Task[] }>(await p.request.get('/api/trash'))).items.length).toBe(0);
  });

  test('AC27: a list I am a member of (shared) is a target; the moved task lands at its top', async ({ kit }) => {
    const owner = await kit.newUser('ac27c-o');
    const me = await kit.newUser('ac27c-m');
    const shared = await createList(owner.page, 'Team list');
    await share(owner, shared.id, me);
    const p = me.page;
    const t = await mk(p, 'Goes to team');
    await mk(owner.page, 'Already there', { listId: shared.id });
    await openManual(p);
    const link = p.locator(`[data-drop-list-id="${shared.id}"]`);
    await dragTo(p, t.id, link, 0.5);
    await expect(toast(p)).toContainText('Moved to Team list');
    expect((await get(p, t.id)).listId).toBe(shared.id);
    expect((await manualIds(p, shared.id))[0]).toBe(t.id);
  });

  test('AC27: moving into a list that would pass 200 tags shows the existing TAG_LIMIT message and changes nothing', async ({ kit }) => {
    test.setTimeout(120_000);
    kit.allowNetworkErrors = true;
    const u = await kit.newUser('ac27d');
    const p = u.page;
    const mine = await personal(p);
    const full = await createList(p, 'Full');
    for (let i = 0; i < 20; i++) {
      await mk(p, `Filler ${i}`, { listId: full.id, tags: Array.from({ length: 10 }, (_, j) => `f${i}-${j}`) });
    }
    const m = await mk(p, 'Taggy', { tags: ['brand-new-tag'] });
    await openManual(p);
    await dragTo(p, m.id, p.locator(`[data-drop-list-id="${full.id}"]`), 0.5);
    await expect(banner(p)).toContainText(`Can't move to "Full": a list can have at most 200 tags`);
    await expect(toast(p)).toHaveCount(0);
    await expect(rowOf(p, m.id)).toHaveCount(1);
    const after = await get(p, m.id);
    expect(after.listId).toBe(mine.id);
    expect(after.tags).toEqual(['brand-new-tag']);
    await shot(p, 'ac27-tag-limit');
  });

  test('AC27: a task whose list I lost access to or a gone target shows the existing message', async ({ kit }) => {
    kit.allowNetworkErrors = true;
    const owner = await kit.newUser('ac27e-o');
    const me = await kit.newUser('ac27e-m');
    const shared = await createList(owner.page, 'Vanishing');
    await share(owner, shared.id, me);
    const p = me.page;
    const t = await mk(p, 'Cannot go');
    await openManual(p);
    // the owner deletes the list after my sidebar loaded
    await json(await api(owner.page, 'DELETE', `/api/lists/${shared.id}`), 204);
    await dragTo(p, t.id, p.locator(`[data-drop-list-id="${shared.id}"]`), 0.5);
    await expect(banner(p)).toContainText('This list no longer exists');
    await expect(rowOf(p, t.id)).toHaveCount(1);
  });
});

// =====================================================================================
// AC28: failure and contract
// =====================================================================================

test.describe('AC28 save failure and contract', () => {
  test('AC28: if saving fails the row snaps back and "Couldn\'t save the new order" shows', async ({ kit }) => {
    kit.allowNetworkErrors = true;
    kit.allow5xx = true;
    const u = await kit.newUser('ac28a');
    const p = u.page;
    const l = await personal(p);
    const [a, b, c] = [await mk(p, 'F1'), await mk(p, 'F2'), await mk(p, 'F3')];
    await openManual(p);
    const before = [c.id, b.id, a.id];
    expect(await uiIds(p)).toEqual(before);
    await p.route('**/position', (route) => route.fulfill({ status: 500, contentType: 'application/problem+json', body: JSON.stringify({ type: 'about:blank', title: 'Internal Server Error', status: 500, detail: 'x', code: 'INTERNAL_ERROR' }) }));
    const put = putResponse(p);
    await dragTo(p, a.id, rowOf(p, c.id), 0.2);
    expect((await put).status()).toBe(500);
    await expect(banner(p)).toContainText("Couldn't save the new order");
    await expect.poll(() => uiIds(p)).toEqual(before);
    await shot(p, 'ac28-snap-back');
    expect(await manualIds(p, l.id)).toEqual(before);
    // the keyboard route fails the same way
    await handleOf(p, c.id).focus();
    await p.keyboard.press('Space');
    await p.keyboard.press('ArrowDown');
    const put2 = putResponse(p);
    await p.keyboard.press('Space');
    expect((await put2).status()).toBe(500);
    await expect(banner(p)).toContainText("Couldn't save the new order");
    await expect.poll(() => uiIds(p)).toEqual(before);
  });

  test('AC28: after a failure the next drag works (the list is not stuck)', async ({ kit }) => {
    kit.allowNetworkErrors = true;
    kit.allow5xx = true;
    const u = await kit.newUser('ac28b');
    const p = u.page;
    const [a, b] = [await mk(p, 'R1'), await mk(p, 'R2')];
    await openManual(p);
    let fail = true;
    await p.route('**/position', async (route) => {
      if (fail) await route.fulfill({ status: 500, contentType: 'application/problem+json', body: '{"type":"about:blank","title":"x","status":500,"detail":"x","code":"INTERNAL_ERROR"}' });
      else await route.fallback();
    });
    const put = putResponse(p);
    await rowOf(p, b.id).getByTestId('move-down').click();
    await put;
    await expect(banner(p)).toContainText("Couldn't save the new order");
    fail = false;
    await expect(rowOf(p, b.id).getByTestId('move-down')).toBeEnabled();
    const put2 = putResponse(p);
    await rowOf(p, b.id).getByTestId('move-down').click();
    expect((await put2).status()).toBe(204);
    expect(await uiIds(p)).toEqual([a.id, b.id]);
  });

  test('AC28 (contract): PUT /api/tasks/{id}/position responses and checks', async ({ kit }) => {
    const u = await kit.newUser('ac28c');
    const other = await kit.newUser('ac28c-x');
    const p = u.page;
    const l = await personal(p);
    const [a, b] = [await mk(p, 'C1'), await mk(p, 'C2')];
    const done = await mk(p, 'C3');
    await json(await api(p, 'PATCH', `/api/tasks/${done.id}`, { status: 'DONE' }));
    const alien = await mk(other.page, 'Alien');
    const before = await get(p, a.id);
    const ok = await putPos(p, a.id, b.id);
    expect(ok.status()).toBe(204);
    expect(await ok.text()).toBe('');
    expect((await get(p, a.id)).updatedAt, 'reorder must not touch updatedAt').toBe(before.updatedAt);
    expect(Object.keys(await get(p, a.id)).sort()).toEqual(TASK_KEYS);
    expect(JSON.stringify(await get(p, a.id))).not.toContain('position');
    expect((await putPos(p, a.id, null)).status()).toBe(204);
    expect((await api(p, 'PUT', `/api/tasks/${a.id}/position`, {})).status()).toBe(204); // missing = null
    // the order is applied
    expect((await manualIds(p, l.id)).slice(0, 2)).toEqual([a.id, b.id]);
    await problem(await putPos(p, a.id, a.id), 400, 'VALIDATION_FAILED').then((x) => expect(x.errors?.[0]).toMatchObject({ field: 'afterId', code: 'SELF' }));
    await problem(await putPos(p, a.id, done.id), 400, 'VALIDATION_FAILED').then((x) => expect(x.errors?.[0]).toMatchObject({ field: 'afterId', code: 'OTHER_GROUP' }));
    await problem(await putPos(p, a.id, 0), 400, 'VALIDATION_FAILED').then((x) => expect(x.errors?.[0]).toMatchObject({ field: 'afterId', code: 'OUT_OF_RANGE' }));
    await problem(await api(p, 'PUT', `/api/tasks/${a.id}/position`, { afterId: 'x' }), 400, 'MALFORMED_REQUEST');
    await problem(await putPos(p, a.id, alien.id), 404, 'NOT_FOUND');
    await problem(await putPos(p, 99999999, null), 404, 'NOT_FOUND');
    await problem(await putPos(other.page, a.id, null), 404, 'NOT_FOUND');
    // OTHER_LIST
    const l2 = await createList(p, 'Second');
    const inL2 = await mk(p, 'In second', { listId: l2.id });
    await problem(await putPos(p, a.id, inL2.id), 400, 'VALIDATION_FAILED').then((x) => expect(x.errors?.[0]).toMatchObject({ field: 'afterId', code: 'OTHER_LIST' }));
    // CSRF + auth
    const noCsrf = await p.request.fetch(`/api/tasks/${a.id}/position`, { method: 'PUT', data: { afterId: null } });
    expect(noCsrf.status()).toBe(403);
    // Not signed in: with a CSRF token but no session it is 401 (401 comes before the other checks), like PATCH.
    const anonCtx = await p.context().browser()!.newContext({ baseURL: BASE });
    await anonCtx.request.get('/api/auth/me');
    const tok = (await anonCtx.cookies()).find((c) => c.name === 'XSRF-TOKEN')!.value;
    const anon = await anonCtx.request.put(`/api/tasks/${a.id}/position`, { data: { afterId: null }, headers: { 'X-XSRF-TOKEN': tok } });
    const anonPatch = await anonCtx.request.patch(`/api/tasks/${a.id}`, { data: { title: 'x' }, headers: { 'X-XSRF-TOKEN': tok } });
    expect(anon.status(), 'anonymous PUT position').toBe(401);
    expect(anonPatch.status(), 'anonymous PATCH (reference)').toBe(401);
    await anonCtx.close();
    // a trashed task
    await json(await api(p, 'DELETE', `/api/tasks/${b.id}`), 204);
    await problem(await putPos(p, a.id, b.id), 404, 'NOT_FOUND');
    // sort=manual shape
    const page = await json<{ items: Task[]; total: number; nextCursor: string | null }>(await p.request.get(`/api/tasks?list=${l.id}&sort=manual`));
    expect(Object.keys(page).sort()).toEqual(['items', 'nextCursor', 'total']);
  });

  test('AC28: group moves keep the position; a list move puts the task on top of the destination (contract rules)', async ({ kit }) => {
    const u = await kit.newUser('ac28d');
    const p = u.page;
    const l = await personal(p);
    const [a, b, c] = [await mk(p, 'W1'), await mk(p, 'W2'), await mk(p, 'W3')];
    await json(await putPos(p, c.id, null), 204);
    await json(await putPos(p, b.id, c.id), 204);
    await json(await putPos(p, a.id, b.id), 204);
    expect(await manualIds(p, l.id)).toEqual([c.id, b.id, a.id]);
    await json(await api(p, 'PATCH', `/api/tasks/${b.id}`, { status: 'DONE' }));
    expect(await manualIds(p, l.id)).toEqual([c.id, a.id, b.id]); // done group last
    await json(await api(p, 'PATCH', `/api/tasks/${b.id}`, { status: 'TODO' }));
    expect(await manualIds(p, l.id)).toEqual([c.id, b.id, a.id]); // position kept
    const l2 = await createList(p, 'Dest');
    const d1 = await mk(p, 'D1', { listId: l2.id });
    await json(await api(p, 'PATCH', `/api/tasks/${a.id}`, { listId: l2.id }));
    expect(await manualIds(p, l2.id)).toEqual([a.id, d1.id]);
  });
});
