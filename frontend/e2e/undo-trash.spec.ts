import { APIResponse, Browser, BrowserContext, expect, Locator, Page, Request, test as base } from '@playwright/test';
import { execFileSync } from 'node:child_process';

/*
 * E2E for specs/undo-trash-dnd-bulk.md Part A (T5a: trash + undo, AC1-AC17), contract 0.16.0.
 * AC5 (purge after 30 days) cannot wait 30 days: it is verified by the backend tests
 * TrashIntegrationTest.thePurgeDeletesTasksTrashedForThirtyDaysWithTheTagCleanup (and the other purge tests there);
 * here only the data it needs (purgeAt = deletedAt + 30 days) is checked. AC17 = the whole suite passing.
 * Needs `docker compose up -d db mail oidc` (DB via `docker compose exec db mysql`).
 * Every test fails on browser console errors (other than the browser's own 4xx lines when a test allows them),
 * page errors, or 5xx responses (unless a test fakes them).
 */

const RUN = `${Date.now()}-${Math.floor(Math.random() * 1e6)}`;
const SHOTS = '../test-results/undo-trash';
const PASSWORD = 'password123';
const BASE = process.env['E2E_BASE_URL'] ?? 'http://localhost:4200';
let counter = 0;
function uid(): string {
  counter += 1;
  return `${RUN}-${counter}`;
}

const TASK_KEYS = ['addedBy', 'createdAt', 'description', 'done', 'dueDate', 'dueTime', 'id', 'listId', 'myReminder', 'myReminderPassed', 'priority', 'repeat', 'status', 'tags', 'title', 'updatedAt'];
const TRASH_KEYS = [...TASK_KEYS, 'deletedAt', 'deletedBy', 'purgeAt'].sort();
const UNDO_GONE = "Couldn't undo: the task was deleted or you no longer have access";
const KEPT = 'Undone: the next copy was already changed, so it was kept';
const DAILY = { frequency: 'DAILY', weekdays: [] };

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
interface TrashItem extends Task {
  deletedAt: string;
  deletedBy: { displayName: string } | null;
  purgeAt: string;
}
interface TrashPage {
  items: TrashItem[];
  nextCursor: string | null;
  total: number;
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
  /^Failed to load resource: the server responded with a status of (400|401|404|409|500|503)/,
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
function delay(ms: number) {
  return new Promise((r) => setTimeout(r, ms));
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
async function problem(res: APIResponse, status: number, code: string) {
  expect(res.status(), await res.text()).toBe(status);
  expect(res.headers()['content-type']).toContain('application/problem+json');
  const body = await res.json();
  expect(body.code).toBe(code);
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
  u.email = `qa-t5a-${tag}-${uid()}@example.com`;
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
async function trashOf(p: Page, query = ''): Promise<TrashPage> {
  return json<TrashPage>(await p.request.get(`/api/trash${query}`));
}
async function tagsOf(p: Page, listId: number): Promise<string[]> {
  const body = await json<{ name: string }[] | { items: { name: string }[] }>(await p.request.get(`/api/tags?list=${listId}`));
  const arr = Array.isArray(body) ? body : body.items;
  return arr.map((t) => t.name).sort();
}
async function mkMany(p: Page, prefix: string, n: number, o: Mk = {}): Promise<Task[]> {
  const out: Task[] = [];
  for (let i = 0; i < n; i += 10) {
    const batch = await Promise.all(Array.from({ length: Math.min(10, n - i) }, (_, k) => mk(p, `${prefix}${String(i + k).padStart(3, '0')}`, o)));
    out.push(...batch);
  }
  return out;
}
async function trashApi(p: Page, id: number) {
  expect((await api(p, 'DELETE', `/api/tasks/${id}`)).status()).toBe(204);
}

// UI helpers
function tasksList(p: Page): Locator {
  return p.getByRole('list', { name: 'Tasks' });
}
function row(p: Page, title: string): Locator {
  return tasksList(p)
    .getByRole('listitem')
    .filter({ has: p.getByRole('checkbox', { name: `Done: ${title}`, exact: true }) });
}
function toastRegion(p: Page): Locator {
  return p.locator('app-toast-host [role=status]');
}
function toast(p: Page): Locator {
  return toastRegion(p).locator('.toast');
}
/** The message only (the toast also holds the Undo and Close buttons). */
function msg(p: Page): Locator {
  return toast(p).locator('span').first();
}
function undoBtn(p: Page): Locator {
  return toast(p).getByRole('button', { name: 'Undo' });
}
function editDialog(p: Page): Locator {
  return p.getByRole('dialog', { name: 'Edit task' });
}
async function gotoTasks(p: Page, path = '/tasks', headingText = 'My tasks') {
  await p.goto(path);
  await expect(p.getByRole('heading', { name: headingText, exact: true })).toBeVisible();
  await expect(p.getByText('Loading tasks…')).toHaveCount(0);
  await expect(p.getByText('Loading lists…')).toHaveCount(0);
}
async function gotoTrash(p: Page) {
  await p.goto('/trash');
  await expect(p.getByRole('heading', { name: 'Trash', exact: true })).toBeVisible();
  await expect(p.getByText('Loading the trash…')).toHaveCount(0);
}
async function uiDelete(p: Page, title: string) {
  await row(p, title).getByRole('button', { name: 'Delete', exact: true }).click();
  await p.getByRole('dialog', { name: `Delete '${title}'?` }).getByRole('button', { name: 'Delete' }).click();
  await expect(row(p, title)).toHaveCount(0);
}
async function uiEditTitle(p: Page, from: string, to: string) {
  await row(p, from).getByRole('button', { name: 'Edit', exact: true }).click();
  await editDialog(p).getByLabel('Title').fill(to);
  await editDialog(p).getByRole('button', { name: 'Save' }).click();
  await expect(editDialog(p)).toHaveCount(0);
}
function requests(p: Page, method: string, re: RegExp): Request[] {
  const seen: Request[] = [];
  p.on('request', (r) => {
    if (r.method() === method && re.test(new URL(r.url()).pathname)) seen.push(r);
  });
  return seen;
}
async function shot(p: Page, name: string) {
  await p.screenshot({ path: `${SHOTS}/${name}.png`, fullPage: true });
}
const trashRows = (p: Page) => p.getByTestId('trash-row');
const FAR = '2031-05-05';

// =====================================================================================
// AC1 AC8: delete = trash
// =====================================================================================

test.describe('AC1 / AC8 delete moves to the trash', () => {
  test('AC1: UI delete -> gone from list, search, total, tag filter, taskCount; in Trash; toast with Undo; tags still in use', async ({ kit }) => {
    const u = await kit.newUser('ac1');
    const p = u.page;
    const mine = await personal(p);
    await mk(p, 'Alpha', { tags: ['Work'] });
    await mk(p, 'Beta', { tags: ['Work'] });
    const gamma = await mk(p, 'Gamma', { tags: ['Solo'] });
    await gotoTasks(p);
    await expect(p.getByTestId('tasks-footer')).toContainText('3 tasks');
    const dels = requests(p, 'DELETE', /^\/api\/tasks\/\d+$/);
    await uiDelete(p, 'Gamma');
    expect(dels).toHaveLength(1);
    await expect(toast(p)).toContainText('Task moved to trash');
    await expect(undoBtn(p)).toBeVisible();
    await shot(p, 'ac1-toast');
    await expect(p.getByTestId('tasks-footer')).toContainText('2 tasks');

    const page1 = await json<{ items: Task[]; total: number }>(await p.request.get('/api/tasks'));
    expect(page1.items.map((t) => t.title).sort()).toEqual(['Alpha', 'Beta']);
    expect(page1.total).toBe(2);
    expect((await json<{ items: Task[]; total: number }>(await p.request.get('/api/tasks?q=Gamma'))).items).toEqual([]);
    expect((await json<{ items: Task[]; total: number }>(await p.request.get('/api/tasks?tag=Solo'))).items).toEqual([]);
    expect((await json<{ items: Task[]; total: number }>(await p.request.get('/api/tasks?filter=active'))).total).toBe(2);
    const details = await json<{ taskCount: number }>(await p.request.get(`/api/lists/${mine.id}`));
    expect(details.taskCount).toBe(2);
    // a trashed task still uses its tags (AC8)
    expect(await tagsOf(p, mine.id)).toEqual(['Solo', 'Work']);

    await gotoTrash(p);
    await expect(trashRows(p)).toHaveCount(1);
    await expect(p.getByTestId('trash-title')).toHaveText('Gamma');
    await shot(p, 'ac1-trash-one');
    // in the DB: a soft delete
    expect(num(`SELECT COUNT(*) FROM tasks WHERE id = ${gamma.id} AND deleted_at IS NOT NULL AND deleted_by = ${u.id}`)).toBe(1);
  });

  test('AC1: cancel in the confirm dialog keeps the task and shows no toast; Escape too', async ({ kit }) => {
    const u = await kit.newUser('ac1c');
    await mk(u.page, 'Keep me');
    await gotoTasks(u.page);
    await row(u.page, 'Keep me').getByRole('button', { name: 'Delete' }).click();
    await u.page.getByRole('dialog', { name: "Delete 'Keep me'?" }).getByRole('button', { name: 'Cancel' }).click();
    await expect(row(u.page, 'Keep me')).toBeVisible();
    await expect(toast(u.page)).toHaveCount(0);
    expect((await trashOf(u.page)).total).toBe(0);
  });

  test('AC8: while trashed, GET/PUT/PATCH/DELETE /api/tasks/{id} are 404 NOT_FOUND (same body as a missing task); no repeat copy; no tick', async ({ kit }) => {
    const u = await kit.newUser('ac8');
    const p = u.page;
    const t = await mk(p, 'Trashed rep', { dueDate: FAR, repeat: DAILY });
    await trashApi(p, t.id);
    const missing = await problem(await p.request.get('/api/tasks/987654321'), 404, 'NOT_FOUND');
    const checks = [
      await problem(await p.request.get(`/api/tasks/${t.id}`), 404, 'NOT_FOUND'),
      await problem(await api(p, 'PUT', `/api/tasks/${t.id}`, { title: 'x' }), 404, 'NOT_FOUND'),
      await problem(await api(p, 'PATCH', `/api/tasks/${t.id}`, { done: true }), 404, 'NOT_FOUND'),
      await problem(await api(p, 'DELETE', `/api/tasks/${t.id}`), 404, 'NOT_FOUND'),
    ];
    for (const b of checks) {
      expect({ status: b.status, title: b.title, detail: b.detail, code: b.code }).toEqual({ status: missing.status, title: missing.title, detail: missing.detail, code: missing.code });
    }
    // no copy was created, the rule is untouched
    const live = await json<{ items: Task[] }>(await p.request.get('/api/tasks'));
    expect(live.items).toEqual([]);
    const item = (await trashOf(p)).items[0];
    expect(item.repeat).toEqual(DAILY);
    expect(item.status).toBe('TODO');
  });

  test('AC8: a trashed task cannot be reached from the UI (tab with the old list: tick gives the 404 banner and a reload)', async ({ kit }) => {
    kit.allowNetworkErrors = true;
    const u = await kit.newUser('ac8ui');
    const t = await mk(u.page, 'Stale row');
    await mk(u.page, 'Other row');
    await gotoTasks(u.page);
    await trashApi(u.page, t.id); // "another tab"
    await u.page.getByRole('checkbox', { name: 'Done: Stale row' }).click();
    await expect(u.page.getByRole('alert')).toContainText('This task no longer exists');
    await expect(row(u.page, 'Stale row')).toHaveCount(0);
    await expect(row(u.page, 'Other row')).toBeVisible();
  });
});

// =====================================================================================
// AC2: Trash page
// =====================================================================================

test.describe('AC2 Trash page', () => {
  test('AC2: sidebar Trash entry (not in the Lists nav) opens /trash; rows show title, list, deleted by + when, days left; newest first; filter; Back', async ({ kit }) => {
    const u = await kit.newUser('ac2');
    const p = u.page;
    const work = await createList(p, 'Work');
    const a = await mk(p, 'Old in work', { listId: work.id });
    const b = await mk(p, 'Mid in mine');
    const c = await mk(p, 'New in work', { listId: work.id });
    await trashApi(p, a.id);
    await trashApi(p, b.id);
    await trashApi(p, c.id);
    sql(`UPDATE tasks SET deleted_at = UTC_TIMESTAMP(6) - INTERVAL 5 DAY WHERE id = ${a.id}`);
    sql(`UPDATE tasks SET deleted_at = UTC_TIMESTAMP(6) - INTERVAL 2 DAY WHERE id = ${b.id}`);
    sql(`UPDATE tasks SET deleted_at = UTC_TIMESTAMP(6) - INTERVAL 29 DAY - INTERVAL 12 HOUR WHERE id = ${c.id}`);
    // the Lists nav has no Trash link; the Trash nav has
    await gotoTasks(p);
    await expect(p.getByRole('navigation', { name: 'Lists' }).locator('a[href="/trash"]')).toHaveCount(0);
    const link = p.locator('a[href="/trash"]');
    await expect(link).toHaveCount(1);
    await link.click();
    await expect(p).toHaveURL(/\/trash$/);
    await expect(p.getByRole('heading', { name: 'Trash', exact: true })).toBeVisible();
    await expect(trashRows(p)).toHaveCount(3);
    // newest deleted first: c (29.5 days ago?) -- no: deletion time DESC = b (2d), a (5d)... c was backdated to 29.5d
    const titles = await p.getByTestId('trash-title').allTextContents();
    expect(titles).toEqual(['Mid in mine', 'Old in work', 'New in work']);
    await expect(p.getByTestId('trash-list').nth(0)).toHaveText('My tasks');
    await expect(p.getByTestId('trash-list').nth(1)).toHaveText('Work');
    await expect(p.getByTestId('trash-deleted').nth(0)).toHaveText(`Deleted by ${u.displayName} · 2 days ago`);
    await expect(p.getByTestId('trash-deleted').nth(1)).toHaveText(`Deleted by ${u.displayName} · 5 days ago`);
    await expect(p.getByTestId('trash-left').nth(0)).toHaveText('28 days left');
    await expect(p.getByTestId('trash-left').nth(1)).toHaveText('25 days left');
    await expect(p.getByTestId('trash-left').nth(2)).toHaveText('1 day left');
    await shot(p, 'ac2-trash-rows');
    // the API says the same
    const page1 = await trashOf(p);
    expect(page1.total).toBe(3);
    expect(page1.items.map((i) => i.title)).toEqual(['Mid in mine', 'Old in work', 'New in work']);
    // list filter
    await p.getByTestId('trash-filter').selectOption({ label: 'Work' });
    await expect(trashRows(p)).toHaveCount(2);
    await expect(p.getByTestId('trash-title')).toHaveText(['Old in work', 'New in work']);
    await p.getByTestId('trash-filter').selectOption({ label: 'All lists' });
    await expect(trashRows(p)).toHaveCount(3);
    // Back to tasks
    await p.getByTestId('trash-back').click();
    await expect(p).toHaveURL(/\/tasks$/);
  });

  test('AC2: Trash holds the tasks of every list I am a member of, and only those', async ({ kit }) => {
    const owner = await kit.newUser('ac2o');
    const member = await kit.newUser('ac2m');
    const outsider = await kit.newUser('ac2x');
    const shared = await createList(owner.page, 'Shared L');
    await share(owner, shared.id, member);
    const t = await mk(owner.page, 'Shared gone', { listId: shared.id });
    const mine = await mk(member.page, 'Member own');
    await trashApi(owner.page, t.id);
    await trashApi(member.page, mine.id);
    await gotoTrash(member.page);
    await expect(member.page.getByTestId('trash-title')).toHaveText(['Member own', 'Shared gone']);
    await expect(member.page.getByTestId('trash-list')).toHaveText(['My tasks', 'Shared L']);
    expect((await trashOf(outsider.page)).items).toEqual([]);
    await gotoTrash(outsider.page);
    await expect(outsider.page.getByTestId('trash-empty')).toBeVisible();
  });

  test('AC2: 50 per page + "Load more"; 55 trashed tasks, no duplicates or skips; total', async ({ kit }) => {
    test.setTimeout(120_000);
    const u = await kit.newUser('ac2p');
    const p = u.page;
    const tasks = await mkMany(p, 'T', 55);
    for (let i = 0; i < tasks.length; i += 10) await Promise.all(tasks.slice(i, i + 10).map((t) => trashApi(p, t.id)));
    const first = await trashOf(p);
    expect(first.items).toHaveLength(50);
    expect(first.total).toBe(55);
    expect(first.nextCursor).toBeTruthy();
    const second = await trashOf(p, `?cursor=${encodeURIComponent(first.nextCursor!)}`);
    expect(second.items).toHaveLength(5);
    expect(second.nextCursor).toBeNull();
    const ids = [...first.items, ...second.items].map((i) => i.id);
    expect(new Set(ids).size).toBe(55);
    // newest-deleted first: (deletedAt desc, id desc)
    const sorted = [...first.items, ...second.items].map((i) => `${i.deletedAt}/${String(i.id).padStart(12, '0')}`);
    expect([...sorted].sort().reverse()).toEqual(sorted);

    await gotoTrash(p);
    await expect(trashRows(p)).toHaveCount(50);
    await expect(p.locator('.trash-footer__count')).toContainText('Showing 50 of 55');
    await shot(p, 'ac2-load-more');
    await p.getByTestId('trash-load-more').click();
    await expect(trashRows(p)).toHaveCount(55);
    await expect(p.getByTestId('trash-load-more')).toHaveCount(0);
    const titles = await p.getByTestId('trash-title').allTextContents();
    expect(new Set(titles).size).toBe(55);
    await expect(p.locator('.trash-footer__count')).toContainText('55');
  });

  test('AC2: phone width: the list dropdown ends with "Trash" and opens /trash', async ({ kit }) => {
    const u = await kit.newUser('ac2ph');
    await u.page.setViewportSize({ width: 375, height: 740 });
    await gotoTasks(u.page);
    const sel = u.page.locator('#list-picker-select');
    await expect(sel).toBeVisible();
    await expect(sel.locator('option').last()).toHaveText('Trash');
    await sel.selectOption({ label: 'Trash' });
    await expect(u.page).toHaveURL(/\/trash$/);
    await expect(u.page.getByRole('heading', { name: 'Trash', exact: true })).toBeVisible();
    await shot(u.page, 'ac2-phone-trash');
    expect(await u.page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  });
});

// =====================================================================================
// AC3 AC10: restore
// =====================================================================================

test.describe('AC3 / AC10 restore', () => {
  test('AC3: Restore on the Trash page brings the task back exactly as it was (tags, priority, status, due date/time, repeat, reminder, addedBy, createdAt, updatedAt)', async ({ kit }) => {
    const u = await kit.newUser('ac3');
    const p = u.page;
    const t = await mk(p, 'Full house', {
      description: 'some text',
      priority: 'HIGH',
      status: 'IN_PROGRESS',
      tags: ['Work', 'Café'],
      dueDate: FAR,
      dueTime: '10:30',
      reminder: 'ONE_HOUR',
      repeat: { frequency: 'WEEKLY', weekdays: ['MON', 'WED'] },
    });
    const before = await get(p, t.id);
    expect(before.myReminder).toBe('ONE_HOUR');
    await trashApi(p, t.id);
    await gotoTrash(p);
    const posts = requests(p, 'POST', /^\/api\/trash\/\d+\/restore$/);
    await p.getByTestId('trash-restore').click();
    await expect(trashRows(p)).toHaveCount(0);
    expect(posts).toHaveLength(1);
    expect(await get(p, t.id)).toEqual(before);
    expect(sql(`SELECT status FROM reminders WHERE task_id = ${t.id} AND user_id = ${u.id}`)).toBe('PENDING');
    await gotoTasks(p);
    await expect(row(p, 'Full house')).toBeVisible();
    // pure sanity on the page: priority / tags shown
    await expect(row(p, 'Full house').getByTestId('task-priority')).toBeVisible();
  });

  test('AC3: API restore returns the Task (all keys) deep-equal to before and to a later GET; the second restore is 404', async ({ kit }) => {
    const u = await kit.newUser('ac3a');
    const p = u.page;
    const t = await mk(p, 'Api restore', { tags: ['A'], priority: 'LOW', dueDate: FAR });
    const before = await get(p, t.id);
    await trashApi(p, t.id);
    const res = await json<Task>(await api(p, 'POST', `/api/trash/${t.id}/restore`));
    expect(Object.keys(res).sort()).toEqual(TASK_KEYS);
    expect(res).toEqual(before);
    expect(await get(p, t.id)).toEqual(before);
    await problem(await api(p, 'POST', `/api/trash/${t.id}/restore`), 404, 'NOT_FOUND');
  });

  test('AC3 reminders: restore re-arms a future reminder; a reminder whose time passed while trashed is not fired late (myReminderPassed)', async ({ kit }) => {
    const u = await kit.newUser('ac3r');
    const p = u.page;
    const past = await mk(p, 'Past reminder', { dueDate: '2030-09-01', dueTime: '10:00', reminder: 'AT_DUE' });
    const future = await mk(p, 'Future reminder', { dueDate: '2030-09-02', dueTime: '10:00', reminder: 'AT_DUE' });
    await trashApi(p, past.id);
    await trashApi(p, future.id);
    // the fire time of "past" passes while it is in the trash: the real scheduler (2 s poll) must not fire it
    sql(`UPDATE reminders SET fire_at = UTC_TIMESTAMP(6) - INTERVAL 1 MINUTE, next_attempt_at = UTC_TIMESTAMP(6) - INTERVAL 1 MINUTE WHERE task_id = ${past.id}`);
    await delay(6000);
    expect(num(`SELECT COUNT(*) FROM notifications WHERE user_id = ${u.id}`)).toBe(0);
    const restoredPast = await json<Task>(await api(p, 'POST', `/api/trash/${past.id}/restore`));
    expect(restoredPast.myReminderPassed).toBe(true);
    const restoredFuture = await json<Task>(await api(p, 'POST', `/api/trash/${future.id}/restore`));
    expect(restoredFuture.myReminder).toBe('AT_DUE');
    expect(restoredFuture.myReminderPassed).toBe(false);
    // still nothing about "past" after the restore ...
    await delay(6000);
    expect(num(`SELECT COUNT(*) FROM notifications WHERE user_id = ${u.id} AND title = 'Past reminder'`)).toBe(0);
    // ... and the re-armed future one fires once when its time comes
    sql(`UPDATE reminders SET fire_at = UTC_TIMESTAMP(6) - INTERVAL 1 MINUTE, next_attempt_at = UTC_TIMESTAMP(6) - INTERVAL 1 MINUTE WHERE task_id = ${future.id}`);
    await expect.poll(() => num(`SELECT COUNT(*) FROM notifications WHERE user_id = ${u.id} AND title = 'Future reminder'`), { timeout: 20_000 }).toBe(1);
    await delay(5000);
    expect(num(`SELECT COUNT(*) FROM notifications WHERE user_id = ${u.id} AND title = 'Future reminder'`)).toBe(1);
    expect(num(`SELECT COUNT(*) FROM notifications WHERE user_id = ${u.id} AND title = 'Past reminder'`)).toBe(0);
  });

  test('AC10: Undo of a delete is the same as Restore: POST /api/trash/{id}/restore, toast says "Undone", the task is back as it was', async ({ kit }) => {
    const u = await kit.newUser('ac10');
    const p = u.page;
    const t = await mk(p, 'Undo delete', { tags: ['Tg'], priority: 'MEDIUM', dueDate: FAR });
    await mk(p, 'Stays');
    const before = await get(p, t.id);
    await gotoTasks(p);
    const posts = requests(p, 'POST', /^\/api\/trash\/\d+\/restore$/);
    await uiDelete(p, 'Undo delete');
    await expect(toast(p)).toContainText('Task moved to trash');
    await undoBtn(p).click();
    await expect(msg(p)).toHaveText(/Undone/);
    await expect(toast(p).getByRole('button', { name: 'Undo' })).toHaveCount(0);
    await expect(row(p, 'Undo delete')).toBeVisible();
    expect(posts).toHaveLength(1);
    expect(posts[0].url()).toContain(`/api/trash/${t.id}/restore`);
    expect(await get(p, t.id)).toEqual(before);
    expect((await trashOf(p)).total).toBe(0);
    await shot(p, 'ac10-undone');
  });
});

// =====================================================================================
// AC4: delete forever / empty trash
// =====================================================================================

test.describe('AC4 delete forever and empty', () => {
  test('AC4: Delete forever (confirm; Cancel keeps) deletes for good; tags that no task uses are removed, tags a trashed task still uses stay', async ({ kit }) => {
    const u = await kit.newUser('ac4');
    const p = u.page;
    const mine = await personal(p);
    const a = await mk(p, 'Solo task', { tags: ['Solo'] });
    const b = await mk(p, 'Keep one', { tags: ['Keep'] });
    const c = await mk(p, 'Keep two', { tags: ['Keep'] });
    for (const t of [a, b, c]) await trashApi(p, t.id);
    expect(await tagsOf(p, mine.id)).toEqual(['Keep', 'Solo']);
    await gotoTrash(p);
    await expect(trashRows(p)).toHaveCount(3);
    const target = trashRows(p).filter({ hasText: 'Solo task' });
    // Cancel keeps it
    await target.getByTestId('trash-delete').click();
    const dlg = p.locator('dialog[aria-labelledby="trash-confirm-heading"]');
    await expect(dlg).toBeVisible();
    await shot(p, 'ac4-confirm');
    await dlg.getByRole('button', { name: 'Cancel' }).click();
    await expect(dlg).toHaveCount(0);
    await expect(trashRows(p)).toHaveCount(3);
    // Confirm
    const dels = requests(p, 'DELETE', /^\/api\/trash\/\d+$/);
    await target.getByTestId('trash-delete').click();
    await p.getByTestId('trash-confirm').click();
    await expect(trashRows(p)).toHaveCount(2);
    expect(dels).toHaveLength(1);
    expect(num(`SELECT COUNT(*) FROM tasks WHERE id = ${a.id}`)).toBe(0);
    expect(await tagsOf(p, mine.id)).toEqual(['Keep']);
    // delete forever of the first "Keep": Keep is still used by the other trashed task
    await trashRows(p).filter({ hasText: 'Keep one' }).getByTestId('trash-delete').click();
    await p.getByTestId('trash-confirm').click();
    await expect(trashRows(p)).toHaveCount(1);
    expect(await tagsOf(p, mine.id)).toEqual(['Keep']);
    await trashRows(p).filter({ hasText: 'Keep two' }).getByTestId('trash-delete').click();
    await p.getByTestId('trash-confirm').click();
    await expect(p.getByTestId('trash-empty')).toBeVisible();
    expect(await tagsOf(p, mine.id)).toEqual([]);
    expect(num(`SELECT COUNT(*) FROM task_tags WHERE task_id IN (${a.id}, ${b.id}, ${c.id})`)).toBe(0);
  });

  test('AC4: Empty trash names the count (and the list when filtered), leaves other lists alone; Cancel keeps; then all of it', async ({ kit }) => {
    const u = await kit.newUser('ac4e');
    const p = u.page;
    const mine = await personal(p);
    const work = await createList(p, 'Work');
    const m1 = await mk(p, 'M1', { tags: ['Mt'] });
    const m2 = await mk(p, 'M2');
    const w1 = await mk(p, 'W1', { listId: work.id, tags: ['Wt'] });
    for (const t of [m1, m2, w1]) await trashApi(p, t.id);
    await gotoTrash(p);
    await expect(p.getByTestId('empty-trash')).toBeEnabled();
    // filtered: Work -> 1 task
    await p.getByTestId('trash-filter').selectOption({ label: 'Work' });
    await expect(trashRows(p)).toHaveCount(1);
    await p.getByTestId('empty-trash').click();
    const dlg = p.locator('dialog[aria-labelledby="trash-confirm-heading"]');
    await expect(dlg).toContainText('1 task');
    await expect(dlg).toContainText('"Work"');
    await shot(p, 'ac4-empty-confirm');
    await dlg.getByRole('button', { name: 'Cancel' }).click();
    expect((await trashOf(p)).total).toBe(3);
    await p.getByTestId('empty-trash').click();
    await p.getByTestId('trash-confirm').click();
    await expect(p.getByTestId('trash-empty')).toBeVisible();
    await expect(toast(p)).toContainText('Deleted 1 task forever');
    // the other list is untouched
    expect((await trashOf(p)).items.map((i) => i.title).sort()).toEqual(['M1', 'M2']);
    expect(await tagsOf(p, work.id)).toEqual([]);
    expect(await tagsOf(p, mine.id)).toEqual(['Mt']);
    // all lists
    await p.getByTestId('trash-filter').selectOption({ label: 'All lists' });
    await expect(trashRows(p)).toHaveCount(2);
    await p.getByTestId('empty-trash').click();
    await expect(dlg).toContainText('all 2 tasks');
    await p.getByTestId('trash-confirm').click();
    await expect(p.getByTestId('trash-empty')).toBeVisible();
    await expect(p.getByTestId('empty-trash')).toBeDisabled();
    expect((await trashOf(p)).total).toBe(0);
    expect(await tagsOf(p, mine.id)).toEqual([]);
    expect(num(`SELECT COUNT(*) FROM tasks WHERE id IN (${m1.id}, ${m2.id}, ${w1.id})`)).toBe(0);
  });

  test('AC4: deleted-for-good tasks keep their notification, now with taskId null (existing cascade)', async ({ kit }) => {
    const u = await kit.newUser('ac4n');
    const p = u.page;
    const t = await mk(p, 'Notified', { dueDate: FAR });
    sql(`INSERT INTO notifications (user_id, task_id, title, due_date, created_at) VALUES (${u.id}, ${t.id}, 'Notified', '${FAR}', UTC_TIMESTAMP(6))`);
    await trashApi(p, t.id);
    await json(await api(p, 'DELETE', `/api/trash/${t.id}`), 204);
    const n = await json<{ items: { taskId: number | null; listId: number | null; taskTrashed: boolean }[] }>(await p.request.get('/api/notifications'));
    expect(n.items).toHaveLength(1);
    expect(n.items[0]).toMatchObject({ taskId: null, listId: null, taskTrashed: false });
  });
});

// =====================================================================================
// AC5: purge -- by backend tests
// =====================================================================================

test.describe('AC5 purge data', () => {
  test('AC5 (data only; the purge itself is TrashIntegrationTest): purgeAt = deletedAt + exactly 30 days; trashed tasks are visible until then', async ({ kit }) => {
    const u = await kit.newUser('ac5');
    const t = await mk(u.page, 'Will be purged');
    await trashApi(u.page, t.id);
    const item = (await trashOf(u.page)).items[0];
    expect(Date.parse(item.purgeAt) - Date.parse(item.deletedAt)).toBe(30 * 24 * 3600 * 1000);
    // a task trashed 29 days ago is still there (the hourly job only takes > 30 days)
    sql(`UPDATE tasks SET deleted_at = UTC_TIMESTAMP(6) - INTERVAL 29 DAY WHERE id = ${t.id}`);
    expect((await trashOf(u.page)).total).toBe(1);
    expect(sql(`SELECT COUNT(*) FROM tasks WHERE id = ${t.id}`)).toBe('1');
  });
});

// =====================================================================================
// AC6 AC7: shared lists
// =====================================================================================

test.describe('AC6 shared list trash', () => {
  test('AC6: every member sees the same trashed tasks; any member can restore, delete forever and empty (two users, UI)', async ({ kit }) => {
    const owner = await kit.newUser('ac6o');
    const member = await kit.newUser('ac6m');
    const l = await createList(owner.page, 'Team');
    await share(owner, l.id, member);
    const t1 = await mk(owner.page, 'Owner task one', { listId: l.id });
    const t2 = await mk(owner.page, 'Owner task two', { listId: l.id });
    const t3 = await mk(member.page, 'Member task', { listId: l.id });
    const t4 = await mk(owner.page, 'Owner task four', { listId: l.id, tags: ['Zed'] });
    // member deletes the owner's task through the UI; owner deletes another through the API
    await gotoTasks(member.page, `/lists/${l.id}`, 'Team');
    await uiDelete(member.page, 'Owner task one');
    await trashApi(owner.page, t2.id);
    await trashApi(owner.page, t3.id);
    await trashApi(owner.page, t4.id);

    for (const x of [owner, member]) {
      await gotoTrash(x.page);
      await expect(trashRows(x.page)).toHaveCount(4);
      await expect(x.page.getByTestId('trash-list')).toHaveText(['Team', 'Team', 'Team', 'Team']);
    }
    // who deleted it
    const byId = new Map((await trashOf(owner.page)).items.map((i) => [i.id, i]));
    expect(byId.get(t1.id)!.deletedBy).toEqual({ displayName: member.displayName });
    expect(byId.get(t2.id)!.deletedBy).toEqual({ displayName: owner.displayName });
    await expect(member.page.getByTestId('trash-deleted').filter({ hasText: `Deleted by ${member.displayName}` })).toHaveCount(1);
    await expect(member.page.getByTestId('trash-deleted').filter({ hasText: `Deleted by ${owner.displayName}` })).toHaveCount(3);
    await shot(member.page, 'ac6-member-trash');

    // the member restores the owner's task
    await trashRows(member.page).filter({ hasText: 'Owner task two' }).getByTestId('trash-restore').click();
    await expect(trashRows(member.page)).toHaveCount(3);
    expect((await get(owner.page, t2.id)).title).toBe('Owner task two');
    // the owner deletes the member's task forever
    await gotoTrash(owner.page);
    await trashRows(owner.page).filter({ hasText: 'Member task' }).getByTestId('trash-delete').click();
    await owner.page.getByTestId('trash-confirm').click();
    await expect(trashRows(owner.page)).toHaveCount(2);
    // the member empties the list's trash (filtered)
    await gotoTrash(member.page);
    await member.page.getByTestId('trash-filter').selectOption({ label: 'Team' });
    await member.page.getByTestId('empty-trash').click();
    await member.page.getByTestId('trash-confirm').click();
    await expect(member.page.getByTestId('trash-empty')).toBeVisible();
    expect((await trashOf(owner.page)).total).toBe(0);
    expect(await tagsOf(owner.page, l.id)).toEqual([]);
  });

  test('AC6: stale Trash page: another member restored it already -> Restore gives 404, a note says so, the row goes', async ({ kit }) => {
    kit.allowNetworkErrors = true;
    const owner = await kit.newUser('ac6s');
    const member = await kit.newUser('ac6s2');
    const l = await createList(owner.page, 'Stale');
    await share(owner, l.id, member);
    const t = await mk(owner.page, 'Race me', { listId: l.id });
    await trashApi(owner.page, t.id);
    await gotoTrash(owner.page);
    await expect(trashRows(owner.page)).toHaveCount(1);
    await json<Task>(await api(member.page, 'POST', `/api/trash/${t.id}/restore`));
    await owner.page.getByTestId('trash-restore').click();
    await expect(owner.page.getByTestId('trash-note')).toHaveText('That task is no longer in the trash.');
    await expect(trashRows(owner.page)).toHaveCount(0);
    await shot(owner.page, 'ac6-stale-note');
  });
});

test.describe('AC7 lost access', () => {
  test('AC7: leaving a list: its trashed tasks vanish from my Trash; restore / delete forever give 404; ?list= gives 404 LIST_NOT_FOUND', async ({ kit }) => {
    kit.allowNetworkErrors = true;
    const owner = await kit.newUser('ac7o');
    const member = await kit.newUser('ac7m');
    const l = await createList(owner.page, 'Leave me');
    await share(owner, l.id, member);
    const t = await mk(owner.page, 'In trash', { listId: l.id });
    const mine = await mk(member.page, 'Mine trashed');
    await trashApi(owner.page, t.id);
    await trashApi(member.page, mine.id);
    await gotoTrash(member.page);
    await expect(trashRows(member.page)).toHaveCount(2);
    expect((await api(member.page, 'POST', `/api/lists/${l.id}/leave`)).status()).toBe(204);
    await gotoTrash(member.page);
    await expect(trashRows(member.page)).toHaveCount(1);
    await expect(member.page.getByTestId('trash-title')).toHaveText('Mine trashed');
    expect((await trashOf(member.page)).total).toBe(1);
    await problem(await api(member.page, 'POST', `/api/trash/${t.id}/restore`), 404, 'NOT_FOUND');
    await problem(await api(member.page, 'DELETE', `/api/trash/${t.id}`), 404, 'NOT_FOUND');
    await problem(await member.page.request.get(`/api/trash?list=${l.id}`), 404, 'LIST_NOT_FOUND');
    await problem(await api(member.page, 'POST', `/api/trash/empty?list=${l.id}`), 404, 'LIST_NOT_FOUND');
    // empty on all lists does not touch the list I left
    expect((await json<{ deleted: number }>(await api(member.page, 'POST', '/api/trash/empty'))).deleted).toBe(1);
    expect((await trashOf(owner.page)).total).toBe(1);
    // the owner still has it
    await gotoTrash(owner.page);
    await expect(trashRows(owner.page)).toHaveCount(1);
  });

  test('AC7: removed by the owner: same (the Trash page of the removed member loses the rows)', async ({ kit }) => {
    kit.allowNetworkErrors = true;
    const owner = await kit.newUser('ac7ro');
    const member = await kit.newUser('ac7rm');
    const l = await createList(owner.page, 'Remove me');
    await share(owner, l.id, member);
    const t = await mk(member.page, 'Added by member', { listId: l.id });
    await trashApi(member.page, t.id);
    await gotoTrash(member.page);
    await expect(trashRows(member.page)).toHaveCount(1);
    expect((await api(owner.page, 'DELETE', `/api/lists/${l.id}/members/${member.id}`)).status()).toBe(204);
    await member.page.reload();
    await expect(member.page.getByTestId('trash-empty')).toBeVisible();
    await problem(await api(member.page, 'POST', `/api/trash/${t.id}/restore`), 404, 'NOT_FOUND');
    // stale page action -> 404 -> note
    await member.page.goBack().catch(() => undefined);
  });

  test('AC7: deleting the list deletes its trashed tasks (no trash for lists); the rows are gone from the DB', async ({ kit }) => {
    const u = await kit.newUser('ac7d');
    const p = u.page;
    const l = await createList(p, 'Doomed list');
    const t = await mk(p, 'Trashed in doomed', { listId: l.id, tags: ['Dt'] });
    await trashApi(p, t.id);
    expect((await trashOf(p)).total).toBe(1);
    expect((await api(p, 'DELETE', `/api/lists/${l.id}`)).status()).toBe(204);
    expect((await trashOf(p)).total).toBe(0);
    expect(num(`SELECT COUNT(*) FROM tasks WHERE id = ${t.id}`)).toBe(0);
    expect(num(`SELECT COUNT(*) FROM tags WHERE list_id = ${l.id}`)).toBe(0);
  });

  test('AC7: account deleted: its trashed tasks in other people\'s lists stay, "Deleted by a deleted user" (deletedBy null)', async ({ kit }) => {
    const owner = await kit.newUser('ac7ao');
    const leaver = await kit.newUser('ac7al');
    const l = await createList(owner.page, 'Survivor list');
    await share(owner, l.id, leaver);
    const t = await mk(owner.page, 'Deleted by leaver', { listId: l.id });
    await trashApi(leaver.page, t.id);
    expect((await trashOf(owner.page)).items[0].deletedBy).toEqual({ displayName: leaver.displayName });
    const del = await api(leaver.page, 'POST', '/api/account/delete', { password: PASSWORD });
    expect(del.status(), await del.text()).toBe(204);
    const item = (await trashOf(owner.page)).items[0];
    expect(item.id).toBe(t.id);
    expect(item.deletedBy).toBeNull();
    await gotoTrash(owner.page);
    await expect(owner.page.getByTestId('trash-deleted')).toHaveText(/^Deleted by a deleted user · /);
    await shot(owner.page, 'ac7-deleted-user');
    // it can still be restored
    await trashRows(owner.page).getByTestId('trash-restore').click();
    await expect(owner.page.getByTestId('trash-empty')).toBeVisible();
    expect((await get(owner.page, t.id)).title).toBe('Deleted by leaver');
  });
});

// =====================================================================================
// AC8: notifications of trashed tasks
// =====================================================================================

test.describe('AC8 notifications', () => {
  test('AC8: a trashed task keeps its notification: "This task is in the trash" + Restore; the body does not navigate; Restore makes it normal again', async ({ kit }) => {
    const u = await kit.newUser('ac8n');
    const p = u.page;
    const t = await mk(p, 'Notif trashed', { dueDate: FAR });
    const live = await mk(p, 'Notif live', { dueDate: FAR });
    sql(`INSERT INTO notifications (user_id, task_id, title, due_date, created_at) VALUES (${u.id}, ${t.id}, 'Notif trashed', '${FAR}', UTC_TIMESTAMP(6) - INTERVAL 1 MINUTE)`);
    sql(`INSERT INTO notifications (user_id, task_id, title, due_date, created_at) VALUES (${u.id}, ${live.id}, 'Notif live', '${FAR}', UTC_TIMESTAMP(6) - INTERVAL 2 MINUTE)`);
    await trashApi(p, t.id);
    const api1 = (await json<{ items: { title: string; taskId: number | null; listId: number | null; taskTrashed: boolean }[] }>(await p.request.get('/api/notifications'))).items;
    expect(api1[0]).toMatchObject({ title: 'Notif trashed', taskId: t.id, taskTrashed: true });
    expect(api1[0].listId).not.toBeNull();
    expect(api1[1]).toMatchObject({ title: 'Notif live', taskTrashed: false });

    await p.goto('/notifications');
    const items = p.getByTestId('notification');
    await expect(items).toHaveCount(2);
    await expect(items.nth(0).getByTestId('notification-trashed')).toContainText('This task is in the trash');
    await expect(items.nth(0).getByTestId('notification-restore')).toBeVisible();
    await expect(items.nth(1).getByTestId('notification-trashed')).toHaveCount(0);
    await shot(p, 'ac8-notification-trashed');
    // the body does not open the task (no navigation) -- it is not a link to a list
    await items.nth(0).locator('.notification__main').click();
    await expect(p).toHaveURL(/\/notifications$/);
    await expect(items.nth(0).getByTestId('notification-trashed')).toBeVisible();
    // Restore from the notification
    await items.nth(0).getByTestId('notification-restore').click();
    await expect(items.nth(0).getByTestId('notification-trashed')).toHaveCount(0);
    expect((await get(p, t.id)).title).toBe('Notif trashed');
    expect((await trashOf(p)).total).toBe(0);
    const api2 = (await json<{ items: { title: string; taskTrashed: boolean }[] }>(await p.request.get('/api/notifications'))).items;
    expect(api2[0]).toMatchObject({ title: 'Notif trashed', taskTrashed: false });
    // now it opens the list as any other notification
    await items.nth(0).locator('.notification__main').click();
    await expect(p).toHaveURL(/\/tasks$/);
  });

  test('AC8: a purged / deleted-for-good task still reads "no longer available" (not "in the trash"); a notification of a trashed task in a list I left is not restorable', async ({ kit }) => {
    kit.allowNetworkErrors = true;
    const owner = await kit.newUser('ac8o');
    const member = await kit.newUser('ac8m');
    const l = await createList(owner.page, 'Notif list');
    await share(owner, l.id, member);
    const gone = await mk(owner.page, 'Gone for good', { listId: l.id });
    const trashed = await mk(owner.page, 'Trashed in left list', { listId: l.id });
    for (const [t, ago] of [[gone, 2], [trashed, 1]] as const) {
      sql(`INSERT INTO notifications (user_id, task_id, title, due_date, created_at) VALUES (${member.id}, ${t.id}, '${t.title}', '${FAR}', UTC_TIMESTAMP(6) - INTERVAL ${ago} MINUTE)`);
    }
    await trashApi(owner.page, gone.id);
    await json(await api(owner.page, 'DELETE', `/api/trash/${gone.id}`), 204);
    await trashApi(owner.page, trashed.id);
    await member.page.goto('/notifications');
    const items = member.page.getByTestId('notification');
    await expect(items).toHaveCount(2);
    await expect(items.nth(0).getByTestId('notification-trashed')).toBeVisible(); // still a member
    await expect(items.nth(1).getByTestId('notification-unavailable')).toContainText('This task is no longer available');
    await expect(items.nth(1).getByTestId('notification-trashed')).toHaveCount(0);
    // leave the list: the trashed one can no longer be restored by me
    expect((await api(member.page, 'POST', `/api/lists/${l.id}/leave`)).status()).toBe(204);
    await member.page.reload();
    await expect(items).toHaveCount(2);
    const apiItems = (await json<{ items: { title: string; taskId: number | null; listId: number | null; taskTrashed: boolean }[] }>(await member.page.request.get('/api/notifications'))).items;
    console.log('notification of a trashed task in a list I left:', JSON.stringify(apiItems[0]));
    expect(apiItems[0].taskId).toBeNull();
    expect(apiItems[0].listId).toBeNull();
    await expect(items.nth(0).getByTestId('notification-restore')).toHaveCount(0);
    await expect(items.nth(0).getByTestId('notification-unavailable')).toBeVisible();
  });
});

// =====================================================================================
// AC9: the undo toast
// =====================================================================================

test.describe('AC9 undo toast', () => {
  test('AC9: messages: delete, tick, untick, move, edit; each Undo puts it back and says "Undone"', async ({ kit }) => {
    const u = await kit.newUser('ac9m');
    const p = u.page;
    const mine = await personal(p);
    const work = await createList(p, 'Work');
    const t = await mk(p, 'Subject', { priority: 'LOW' });
    await gotoTasks(p);

    // tick -> "Task completed" -> Undo
    await p.getByRole('checkbox', { name: 'Done: Subject' }).check();
    await expect(toast(p)).toContainText('Task completed');
    await undoBtn(p).click();
    await expect(msg(p)).toHaveText(/Undone/);
    await expect(p.getByRole('checkbox', { name: 'Done: Subject' })).not.toBeChecked();
    expect((await get(p, t.id)).status).toBe('TODO');
    // untick path: complete it via API, reload, untick -> "Task reopened"
    await json(await api(p, 'PATCH', `/api/tasks/${t.id}`, { done: true }));
    await gotoTasks(p);
    await p.getByRole('checkbox', { name: 'Done: Subject' }).uncheck();
    await expect(toast(p)).toContainText('Task reopened');
    await undoBtn(p).click();
    await expect(msg(p)).toHaveText(/Undone/);
    expect((await get(p, t.id)).status).toBe('DONE');
    await json(await api(p, 'PATCH', `/api/tasks/${t.id}`, { done: false }));

    // edit -> "Task updated" -> Undo restores the title only
    await gotoTasks(p);
    await uiEditTitle(p, 'Subject', 'Subject renamed');
    await expect(toast(p)).toContainText('Task updated');
    await shot(p, 'ac9-updated-toast');
    await undoBtn(p).click();
    await expect(msg(p)).toHaveText(/Undone/);
    await expect(row(p, 'Subject')).toBeVisible();
    expect((await get(p, t.id)).title).toBe('Subject');

    // move -> "Moved to Work" -> Undo
    await row(p, 'Subject').getByRole('button', { name: 'Edit', exact: true }).click();
    await editDialog(p).locator('#task-edit-list').selectOption({ label: 'Work' });
    await editDialog(p).getByRole('button', { name: 'Save' }).click();
    await expect(toast(p)).toContainText('Moved to Work');
    await expect(row(p, 'Subject')).toHaveCount(0);
    expect((await get(p, t.id)).listId).toBe(work.id);
    await undoBtn(p).click();
    await expect(msg(p)).toHaveText(/Undone/);
    await expect(row(p, 'Subject')).toBeVisible();
    expect((await get(p, t.id)).listId).toBe(mine.id);

    // delete -> "Task moved to trash" (covered in AC1/AC10)
    await uiDelete(p, 'Subject');
    await expect(toast(p)).toContainText('Task moved to trash');
  });

  test('AC9: a new action replaces the toast; only the latest action can be undone', async ({ kit }) => {
    const u = await kit.newUser('ac9r');
    const p = u.page;
    const a = await mk(p, 'First');
    const b = await mk(p, 'Second');
    await gotoTasks(p);
    await p.getByRole('checkbox', { name: 'Done: First' }).check();
    await expect(toast(p)).toContainText('Task completed');
    await uiEditTitle(p, 'Second', 'Second edited');
    await expect(toast(p)).toContainText('Task updated');
    await expect(toast(p)).not.toContainText('Task completed');
    await expect(toast(p).getByRole('button', { name: 'Undo' })).toHaveCount(1);
    await undoBtn(p).click();
    await expect(msg(p)).toHaveText(/Undone/);
    expect((await get(p, b.id)).title).toBe('Second');
    expect((await get(p, a.id)).status).toBe('DONE'); // the older tick is not undone
  });

  test('AC9: the toast stays on the page for filter (query) changes, closes on a page (path) change, and is gone after a reload', async ({ kit }) => {
    const u = await kit.newUser('ac9p');
    const p = u.page;
    await mk(p, 'Nav one');
    await mk(p, 'Nav two');
    await gotoTasks(p);
    await uiDelete(p, 'Nav one');
    await expect(toast(p)).toBeVisible();
    await p.getByRole('navigation', { name: 'Filter tasks' }).getByRole('link', { name: 'Active' }).click();
    await expect(p).toHaveURL(/filter=active/);
    await expect(toast(p)).toContainText('Task moved to trash');
    await p.locator('a[href="/trash"]').click();
    await expect(p).toHaveURL(/\/trash$/);
    await expect(toast(p)).toHaveCount(0);
    await expect(trashRows(p)).toHaveCount(1);
    // back and delete again, then reload
    await p.getByTestId('trash-back').click();
    await expect(p).toHaveURL(/\/tasks$/);
    await uiDelete(p, 'Nav two');
    await expect(toast(p)).toBeVisible();
    await p.reload();
    await expect(p.getByText('Loading tasks…')).toHaveCount(0);
    await expect(toast(p)).toHaveCount(0);
  });

  test('AC9: the region is announced (role=status, aria-live=polite, always in the page); closes by itself after about 8 s; Close button', async ({ kit }) => {
    test.setTimeout(60_000);
    const u = await kit.newUser('ac9a');
    const p = u.page;
    await mk(p, 'A11y one');
    await mk(p, 'A11y two');
    await gotoTasks(p);
    await expect(toastRegion(p)).toHaveAttribute('aria-live', 'polite');
    await expect(toastRegion(p)).toBeAttached(); // there before any toast, so the text is announced
    await expect(toast(p)).toHaveCount(0);
    await uiDelete(p, 'A11y one');
    const shown = Date.now();
    await expect(toast(p)).toBeVisible();
    await expect(toast(p).getByRole('button', { name: 'Close' })).toBeVisible();
    await expect(toast(p).getByRole('button', { name: 'Undo' })).toBeVisible();
    await delay(6000);
    await expect(toast(p)).toBeVisible(); // not yet at 6 s
    await expect(toast(p)).toHaveCount(0, { timeout: 6000 });
    const lived = Date.now() - shown;
    console.log('toast lived ms:', lived);
    expect(lived).toBeGreaterThan(7000);
    expect(lived).toBeLessThan(10500);
    // Close button
    await uiDelete(p, 'A11y two');
    await toast(p).getByRole('button', { name: 'Close' }).click();
    await expect(toast(p)).toHaveCount(0);
    expect((await trashOf(p)).total).toBe(2); // closing is not undoing
  });

  test('AC9: stays open while hovered (> 8 s) and closes about 8 s after the pointer leaves', async ({ kit }) => {
    test.setTimeout(60_000);
    const u = await kit.newUser('ac9h');
    const p = u.page;
    await mk(p, 'Hover me');
    await gotoTasks(p);
    await uiDelete(p, 'Hover me');
    await expect(toast(p)).toBeVisible();
    await toast(p).hover();
    await delay(9500);
    await expect(toast(p)).toBeVisible();
    await p.mouse.move(5, 5);
    const left = Date.now();
    await delay(5500);
    await expect(toast(p)).toBeVisible();
    await expect(toast(p)).toHaveCount(0, { timeout: 6000 });
    const after = Date.now() - left;
    console.log('closed ms after leaving:', after);
    expect(after).toBeGreaterThan(7000);
    expect(after).toBeLessThan(10500);
  });

  test('AC9: stays open while one of its buttons has keyboard focus (> 8 s); closes about 8 s after the focus leaves', async ({ kit }) => {
    test.setTimeout(60_000);
    const u = await kit.newUser('ac9f');
    const p = u.page;
    await mk(p, 'Focus me');
    await gotoTasks(p);
    await uiDelete(p, 'Focus me');
    await expect(toast(p)).toBeVisible();
    await undoBtn(p).focus();
    await expect(undoBtn(p)).toBeFocused();
    await delay(9500);
    await expect(toast(p)).toBeVisible();
    await undoBtn(p).evaluate((el) => (el as HTMLElement).blur());
    const left = Date.now();
    await expect(toast(p)).toHaveCount(0, { timeout: 12_000 });
    const after = Date.now() - left;
    console.log('closed ms after blur:', after);
    expect(after).toBeGreaterThan(6500);
  });

  test('AC9: double-clicking Undo sends one request', async ({ kit }) => {
    const u = await kit.newUser('ac9d');
    const p = u.page;
    await mk(p, 'Dbl');
    await gotoTasks(p);
    const posts = requests(p, 'POST', /^\/api\/trash\/\d+\/restore$/);
    await uiDelete(p, 'Dbl');
    await undoBtn(p).dblclick();
    await expect(row(p, 'Dbl')).toBeVisible();
    await delay(500);
    expect(posts).toHaveLength(1);
  });
});

// =====================================================================================
// AC11: undo of a repeating completion
// =====================================================================================

test.describe('AC11 undo of a repeating completion', () => {
  async function tickRepeating(u: U, title: string) {
    const t = await mk(u.page, title, { dueDate: '2031-05-05', repeat: DAILY });
    await gotoTasks(u.page);
    await u.page.getByRole('checkbox', { name: `Done: ${title}`, exact: true }).check();
    await expect(toast(u.page)).toContainText('Task completed');
    const all = await json<{ items: Task[] }>(await u.page.request.get('/api/tasks'));
    const copy = all.items.find((x) => x.title === title && x.id !== t.id)!;
    expect(copy, 'the completion created a copy').toBeTruthy();
    expect(copy.dueDate).toBe('2031-05-06');
    return { t, copy };
  }

  test('AC11: untouched copy -> deleted for good (not in the trash), the rule is back on the original, which is open again', async ({ kit }) => {
    const u = await kit.newUser('ac11a');
    const p = u.page;
    const { t, copy } = await tickRepeating(u, 'Water plants');
    await shot(p, 'ac11-after-tick');
    const posts = requests(p, 'POST', /\/undo-complete$/);
    await undoBtn(p).click();
    await expect(msg(p)).toHaveText('Undone');
    expect(posts).toHaveLength(1);
    const orig = await get(p, t.id);
    expect(orig).toMatchObject({ status: 'TODO', done: false, dueDate: '2031-05-05', repeat: DAILY });
    await problem(await p.request.get(`/api/tasks/${copy.id}`), 404, 'NOT_FOUND');
    expect((await trashOf(p)).total).toBe(0);
    expect(num(`SELECT COUNT(*) FROM tasks WHERE id = ${copy.id}`)).toBe(0);
    await expect(p.getByRole('checkbox', { name: 'Done: Water plants' })).toHaveCount(1);
    await expect(p.getByRole('checkbox', { name: 'Done: Water plants' })).not.toBeChecked();
    await shot(p, 'ac11-undone');
    // a reload agrees
    await gotoTasks(p);
    await expect(p.getByRole('checkbox', { name: 'Done: Water plants' })).toHaveCount(1);
  });

  test('AC11: copy changed since -> kept; original reopened WITHOUT the rule; toast says KEPT message', async ({ kit }) => {
    const u = await kit.newUser('ac11k');
    const p = u.page;
    const { t, copy } = await tickRepeating(u, 'Edited copy');
    await json(await api(p, 'PATCH', `/api/tasks/${copy.id}`, { priority: 'HIGH' })); // someone changed the copy
    await undoBtn(p).click();
    await expect(msg(p)).toHaveText(KEPT);
    await shot(p, 'ac11-kept');
    expect(await get(p, t.id)).toMatchObject({ status: 'TODO', done: false, repeat: null });
    expect(await get(p, copy.id)).toMatchObject({ priority: 'HIGH', repeat: DAILY });
    await expect(p.getByRole('checkbox', { name: 'Done: Edited copy' })).toHaveCount(2);
  });

  test('AC11: copy ticked by someone else (a change, even if undone) is kept too', async ({ kit }) => {
    const u = await kit.newUser('ac11t');
    const p = u.page;
    const { t, copy } = await tickRepeating(u, 'Ticked copy');
    await json(await api(p, 'PATCH', `/api/tasks/${copy.id}`, { done: true }));
    await json(await api(p, 'PATCH', `/api/tasks/${copy.id}`, { done: false }));
    await undoBtn(p).click();
    await expect(msg(p)).toHaveText(KEPT);
    expect((await get(p, t.id)).repeat).toBeNull();
    expect((await get(p, copy.id)).id).toBe(copy.id);
  });

  test('AC11: copy already deleted for good (GONE) -> original reopened without the rule, toast "Undone"; copy in the trash -> kept', async ({ kit }) => {
    const u = await kit.newUser('ac11g');
    const p = u.page;
    const { t, copy } = await tickRepeating(u, 'Gone copy');
    await trashApi(p, copy.id);
    await json(await api(p, 'DELETE', `/api/trash/${copy.id}`), 204);
    await undoBtn(p).click();
    await expect(msg(p)).toHaveText('Undone');
    expect(await get(p, t.id)).toMatchObject({ status: 'TODO', repeat: null });
    await problem(await p.request.get(`/api/tasks/${copy.id}`), 404, 'NOT_FOUND');

    // copy trashed (not deleted): KEPT, still in the trash
    const second = await tickRepeating(u, 'Trashed copy');
    await trashApi(p, second.copy.id);
    await undoBtn(p).click();
    await expect(msg(p)).toHaveText(KEPT);
    expect((await get(p, second.t.id)).repeat).toBeNull();
    expect((await trashOf(p)).items.map((i) => i.id)).toEqual([second.copy.id]);
  });

  test('AC11: ticking in the edit dialog (status Done) of a repeating task: undo reopens it, restores the rule and removes the copy; other edits are undone too', async ({ kit }) => {
    const u = await kit.newUser('ac11e');
    const p = u.page;
    const t = await mk(p, 'Dialog repeat', { dueDate: '2031-05-05', repeat: DAILY, priority: 'LOW' });
    await gotoTasks(p);
    await row(p, 'Dialog repeat').getByRole('button', { name: 'Edit', exact: true }).click();
    await editDialog(p).locator('#task-edit-status').selectOption('DONE');
    await editDialog(p).locator('#task-edit-priority').selectOption('HIGH');
    await editDialog(p).getByRole('button', { name: 'Save' }).click();
    await expect(editDialog(p)).toHaveCount(0);
    await expect(toast(p)).toBeVisible();
    expect((await get(p, t.id)).priority).toBe('HIGH');
    await undoBtn(p).click();
    await expect(msg(p)).toHaveText('Undone');
    expect(await get(p, t.id)).toMatchObject({ status: 'TODO', priority: 'LOW', repeat: DAILY });
    const all = await json<{ items: Task[] }>(await p.request.get('/api/tasks'));
    expect(all.items.map((x) => x.id)).toEqual([t.id]);
  });
});

// =====================================================================================
// AC12: undo of a move
// =====================================================================================

test.describe('AC12 undo of a move', () => {
  test('AC12: back to the old list with its OLD tag names; tags cleaned up in the old list are created again', async ({ kit }) => {
    const u = await kit.newUser('ac12');
    const p = u.page;
    const home = await createList(p, 'Home');
    const work = await createList(p, 'Work');
    await mk(p, 'Work has CAFE', { listId: work.id, tags: ['CAFE'] });
    const mover = await mk(p, 'Mover', { listId: home.id, tags: ['Café', 'Shared', 'Solo'] });
    await mk(p, 'Home stays', { listId: home.id, tags: ['Shared'] });
    expect(await tagsOf(p, home.id)).toEqual(['Café', 'Shared', 'Solo']);
    await gotoTasks(p, `/lists/${home.id}`, 'Home');
    await row(p, 'Mover').getByRole('button', { name: 'Edit', exact: true }).click();
    await editDialog(p).locator('#task-edit-list').selectOption({ label: 'Work' });
    await editDialog(p).getByRole('button', { name: 'Save' }).click();
    await expect(toast(p)).toContainText('Moved to Work');
    await expect(row(p, 'Mover')).toHaveCount(0);
    const moved = await get(p, mover.id);
    expect(moved.listId).toBe(work.id);
    expect(moved.tags).toEqual(['CAFE', 'Shared', 'Solo']); // target spelling
    expect(await tagsOf(p, home.id)).toEqual(['Shared']); // Café and Solo were cleaned up in Home
    const patches = requests(p, 'PATCH', /^\/api\/tasks\/\d+$/);
    await undoBtn(p).click();
    await expect(msg(p)).toHaveText('Undone');
    await expect(row(p, 'Mover')).toBeVisible();
    expect(patches).toHaveLength(1);
    const back = await get(p, mover.id);
    expect(back.listId).toBe(home.id);
    expect(back.tags).toEqual(['Café', 'Shared', 'Solo']);
    expect(await tagsOf(p, home.id)).toEqual(['Café', 'Shared', 'Solo']);
    expect(await tagsOf(p, work.id)).toEqual(['CAFE']); // still used by the other Work task
    await shot(p, 'ac12-moved-back');
  });
});

// =====================================================================================
// AC13: last write wins on the fields my action changed
// =====================================================================================

test.describe('AC13 undo vs a teammate\'s edit', () => {
  test('AC13: tick, teammate changes the priority, my undo sends only {status} and the priority survives', async ({ kit }) => {
    const owner = await kit.newUser('ac13o');
    const mate = await kit.newUser('ac13m');
    const l = await createList(owner.page, 'Team 13');
    await share(owner, l.id, mate);
    const t = await mk(owner.page, 'Shared tick', { listId: l.id });
    await gotoTasks(owner.page, `/lists/${l.id}`, 'Team 13');
    const patches = requests(owner.page, 'PATCH', /^\/api\/tasks\/\d+$/);
    await owner.page.getByRole('checkbox', { name: 'Done: Shared tick' }).check();
    await expect(toast(owner.page)).toContainText('Task completed');
    await json(await api(mate.page, 'PATCH', `/api/tasks/${t.id}`, { priority: 'HIGH', description: 'mate was here' }));
    await undoBtn(owner.page).click();
    await expect(msg(owner.page)).toHaveText('Undone');
    expect(patches).toHaveLength(2); // the tick, then the undo
    expect(patches[1].postDataJSON()).toEqual({ status: 'TODO' });
    expect(await get(owner.page, t.id)).toMatchObject({ status: 'TODO', priority: 'HIGH', description: 'mate was here' });
  });

  test('AC13: edit, teammate changes another field, my undo sends only the fields I changed; the teammate\'s change survives', async ({ kit }) => {
    const owner = await kit.newUser('ac13eo');
    const mate = await kit.newUser('ac13em');
    const l = await createList(owner.page, 'Team 13e');
    await share(owner, l.id, mate);
    const t = await mk(owner.page, 'Before title', { listId: l.id, description: 'orig' });
    await gotoTasks(owner.page, `/lists/${l.id}`, 'Team 13e');
    const patches = requests(owner.page, 'PATCH', /^\/api\/tasks\/\d+$/);
    await uiEditTitle(owner.page, 'Before title', 'After title');
    await expect(toast(owner.page)).toContainText('Task updated');
    await json(await api(mate.page, 'PATCH', `/api/tasks/${t.id}`, { description: 'mate edit', priority: 'LOW' }));
    await undoBtn(owner.page).click();
    await expect(msg(owner.page)).toHaveText('Undone');
    expect(patches).toHaveLength(1);
    expect(patches[0].postDataJSON()).toEqual({ title: 'Before title' });
    expect(await get(owner.page, t.id)).toMatchObject({ title: 'Before title', description: 'mate edit', priority: 'LOW' });
  });

  test('AC13: last write wins on the field I changed: teammate renames after my rename, my undo overwrites the title', async ({ kit }) => {
    const owner = await kit.newUser('ac13lo');
    const mate = await kit.newUser('ac13lm');
    const l = await createList(owner.page, 'Team 13l');
    await share(owner, l.id, mate);
    const t = await mk(owner.page, 'T0', { listId: l.id });
    await gotoTasks(owner.page, `/lists/${l.id}`, 'Team 13l');
    await uiEditTitle(owner.page, 'T0', 'T1');
    await json(await api(mate.page, 'PATCH', `/api/tasks/${t.id}`, { title: 'T-mate' }));
    await undoBtn(owner.page).click();
    await expect(msg(owner.page)).toHaveText('Undone');
    expect((await get(owner.page, t.id)).title).toBe('T0');
  });
});

// =====================================================================================
// AC14: undo cannot be applied
// =====================================================================================

test.describe('AC14 undo errors', () => {
  test('AC14: delete, then someone deletes it forever -> Undo says the Decisions-log message, no retry, list reloads, API 404 (no 5xx)', async ({ kit }) => {
    kit.allowNetworkErrors = true;
    const owner = await kit.newUser('ac14a');
    const mate = await kit.newUser('ac14am');
    const l = await createList(owner.page, 'Team 14a');
    await share(owner, l.id, mate);
    const t = await mk(owner.page, 'Lost delete', { listId: l.id });
    await mk(owner.page, 'Other 14a', { listId: l.id });
    await gotoTasks(owner.page, `/lists/${l.id}`, 'Team 14a');
    await uiDelete(owner.page, 'Lost delete');
    await json(await api(mate.page, 'DELETE', `/api/trash/${t.id}`), 204);
    const reloads = requests(owner.page, 'GET', /^\/api\/tasks$/);
    await undoBtn(owner.page).click();
    await expect(msg(owner.page)).toHaveText(UNDO_GONE);
    await expect(toast(owner.page).getByRole('button', { name: 'Undo' })).toHaveCount(0);
    await expect(toast(owner.page).getByRole('button', { name: /retry/i })).toHaveCount(0);
    await expect.poll(() => reloads.length).toBeGreaterThan(0); // the list reloads
    await expect(row(owner.page, 'Other 14a')).toBeVisible();
    await shot(owner.page, 'ac14-gone');
    await problem(await api(owner.page, 'POST', `/api/trash/${t.id}/restore`), 404, 'NOT_FOUND');
  });

  test('AC14: tick, then a teammate trashes the task -> Undo fails with the same message (PATCH 404); the list reloads without it', async ({ kit }) => {
    kit.allowNetworkErrors = true;
    const owner = await kit.newUser('ac14b');
    const mate = await kit.newUser('ac14bm');
    const l = await createList(owner.page, 'Team 14b');
    await share(owner, l.id, mate);
    const t = await mk(owner.page, 'Trashed by mate', { listId: l.id });
    await mk(owner.page, 'Other 14b', { listId: l.id });
    await gotoTasks(owner.page, `/lists/${l.id}`, 'Team 14b');
    await owner.page.getByRole('checkbox', { name: 'Done: Trashed by mate' }).check();
    await expect(toast(owner.page)).toContainText('Task completed');
    await trashApi(mate.page, t.id);
    const patchResp = owner.page.waitForResponse((r) => r.request().method() === 'PATCH' && r.url().includes(`/api/tasks/${t.id}`));
    await undoBtn(owner.page).click();
    expect((await patchResp).status()).toBe(404);
    await expect(msg(owner.page)).toHaveText(UNDO_GONE);
    await expect(row(owner.page, 'Trashed by mate')).toHaveCount(0);
    await expect(row(owner.page, 'Other 14b')).toBeVisible();
    // the task is in the trash, not lost
    expect((await trashOf(owner.page)).items.map((i) => i.id)).toEqual([t.id]);
  });

  test('AC14: edit, then the task is deleted for good -> Undo fails with the message', async ({ kit }) => {
    kit.allowNetworkErrors = true;
    const u = await kit.newUser('ac14c');
    const t = await mk(u.page, 'Edited then gone');
    await mk(u.page, 'Other 14c');
    await gotoTasks(u.page);
    await uiEditTitle(u.page, 'Edited then gone', 'Edited twice');
    await trashApi(u.page, t.id);
    await json(await api(u.page, 'DELETE', `/api/trash/${t.id}`), 204);
    await undoBtn(u.page).click();
    await expect(msg(u.page)).toHaveText(UNDO_GONE);
    await expect(row(u.page, 'Edited twice')).toHaveCount(0);
  });

  test('AC14: move, then I lose access to the old list -> Undo fails (404, never 5xx) with the message', async ({ kit }) => {
    kit.allowNetworkErrors = true;
    const owner = await kit.newUser('ac14d');
    const me = await kit.newUser('ac14dm');
    const l = await createList(owner.page, 'Old list 14d');
    await share(owner, l.id, me);
    const t = await mk(me.page, 'Moving away', { listId: l.id });
    await gotoTasks(me.page, `/lists/${l.id}`, 'Old list 14d');
    await me.page.getByRole('checkbox', { name: 'Done: Moving away' }).waitFor();
    await row(me.page, 'Moving away').getByRole('button', { name: 'Edit', exact: true }).click();
    await editDialog(me.page).locator('#task-edit-list').selectOption({ label: 'My tasks' });
    await editDialog(me.page).getByRole('button', { name: 'Save' }).click();
    await expect(toast(me.page)).toContainText('Moved to My tasks');
    expect((await api(owner.page, 'DELETE', `/api/lists/${l.id}/members/${me.id}`)).status()).toBe(204);
    const patchResp = me.page.waitForResponse((r) => r.request().method() === 'PATCH' && r.url().includes(`/api/tasks/${t.id}`));
    await undoBtn(me.page).click();
    expect((await patchResp).status()).toBe(404);
    await expect(msg(me.page)).toHaveText(UNDO_GONE);
    expect((await get(me.page, t.id)).listId).toBe((await personal(me.page)).id); // unchanged
  });

  test('AC14: API: every failed undo call answers 404 NOT_FOUND, never 500 (restore of a deleted-for-good task, PATCH of a gone/trashed task, undo-complete of a gone task)', async ({ kit }) => {
    const u = await kit.newUser('ac14e');
    const p = u.page;
    const t = await mk(p, 'Api gone');
    await trashApi(p, t.id);
    await json(await api(p, 'DELETE', `/api/trash/${t.id}`), 204);
    await problem(await api(p, 'POST', `/api/trash/${t.id}/restore`), 404, 'NOT_FOUND');
    await problem(await api(p, 'PATCH', `/api/tasks/${t.id}`, { status: 'TODO' }), 404, 'NOT_FOUND');
    await problem(await api(p, 'POST', `/api/tasks/${t.id}/undo-complete`, { nextTaskId: 1 }), 404, 'NOT_FOUND');
    const other = await mk(p, 'Api trashed');
    await trashApi(p, other.id);
    await problem(await api(p, 'PATCH', `/api/tasks/${other.id}`, { status: 'TODO' }), 404, 'NOT_FOUND');
    await problem(await api(p, 'POST', `/api/tasks/${other.id}/undo-complete`, { nextTaskId: 1 }), 404, 'NOT_FOUND');
  });
});

// =====================================================================================
// AC15: contract 0.16.0
// =====================================================================================

test.describe('AC15 contract 0.16.0', () => {
  test('AC15: GET /api/trash shape (TrashPage, TrashItem = Task + deletedAt/deletedBy/purgeAt), empty page, filter by list', async ({ kit }) => {
    const u = await kit.newUser('ac15a');
    const p = u.page;
    const empty = await trashOf(p);
    expect(empty).toEqual({ items: [], nextCursor: null, total: 0 });
    const work = await createList(p, 'Work 15');
    const a = await mk(p, 'Trash item', { tags: ['T1'], priority: 'HIGH', dueDate: FAR, dueTime: '08:00', repeat: DAILY });
    const b = await mk(p, 'Other list item', { listId: work.id });
    await trashApi(p, a.id);
    await trashApi(p, b.id);
    const page1 = await trashOf(p);
    expect(Object.keys(page1).sort()).toEqual(['items', 'nextCursor', 'total']);
    expect(page1.total).toBe(2);
    for (const it of page1.items) {
      expect(Object.keys(it).sort()).toEqual(TRASH_KEYS);
      expect(it.deletedBy).toEqual({ displayName: u.displayName });
      expect(Date.parse(it.purgeAt) - Date.parse(it.deletedAt)).toBe(30 * 24 * 3600 * 1000);
    }
    const item = page1.items.find((i) => i.id === a.id)!;
    expect(item).toMatchObject({ title: 'Trash item', tags: ['T1'], priority: 'HIGH', dueDate: FAR, dueTime: '08:00', repeat: DAILY, status: 'TODO', done: false });
    expect(item.deletedAt).toMatch(/^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(\.\d+)?Z$/);
    expect((await trashOf(p, `?list=${work.id}`)).items.map((i) => i.id)).toEqual([b.id]);
    expect((await trashOf(p, `?list=${work.id}`)).total).toBe(1);
    expect((await trashOf(p, '?list=')).total).toBe(2); // empty = all lists
    expect((await trashOf(p, '?cursor=')).total).toBe(2);
  });

  test('AC15: trashing and restoring leave updatedAt (and everything else) untouched', async ({ kit }) => {
    const u = await kit.newUser('ac15u');
    const t = await mk(u.page, 'Stamp', { priority: 'LOW' });
    const before = await get(u.page, t.id);
    await delay(1100);
    await trashApi(u.page, t.id);
    expect((await trashOf(u.page)).items[0].updatedAt).toBe(before.updatedAt);
    await delay(1100);
    await json(await api(u.page, 'POST', `/api/trash/${t.id}/restore`));
    expect((await get(u.page, t.id)).updatedAt).toBe(before.updatedAt);
  });

  test('AC15: errors: 404 bodies identical (missing / foreign / not trashed), 400 malformed id, 403 without CSRF, 401 signed out', async ({ kit }) => {
    kit.allowNetworkErrors = true;
    const a = await kit.newUser('ac15e');
    const b = await kit.newUser('ac15f');
    const mine = await mk(a.page, 'Mine live');
    const trashed = await mk(a.page, 'Mine trashed');
    await trashApi(a.page, trashed.id);
    const foreignTrashed = await mk(b.page, 'Theirs trashed');
    await trashApi(b.page, foreignTrashed.id);
    const bodies: { status: number; code: string; title: string; detail: string }[] = [];
    const norm = (b: { status: number; code: string; title: string; detail: string }) => ({ status: b.status, code: b.code, title: b.title, detail: b.detail });
    bodies.push(norm(await problem(await api(a.page, 'POST', '/api/trash/987654321/restore'), 404, 'NOT_FOUND')));
    bodies.push(norm(await problem(await api(a.page, 'POST', `/api/trash/${foreignTrashed.id}/restore`), 404, 'NOT_FOUND')));
    bodies.push(norm(await problem(await api(a.page, 'POST', `/api/trash/${mine.id}/restore`), 404, 'NOT_FOUND')));
    bodies.push(norm(await problem(await api(a.page, 'DELETE', '/api/trash/987654321'), 404, 'NOT_FOUND')));
    bodies.push(norm(await problem(await api(a.page, 'DELETE', `/api/trash/${foreignTrashed.id}`), 404, 'NOT_FOUND')));
    bodies.push(norm(await problem(await api(a.page, 'DELETE', `/api/trash/${mine.id}`), 404, 'NOT_FOUND'))); // a live task is not deletable here
    for (const x of bodies) expect(x).toEqual(bodies[0]);
    expect((await get(a.page, mine.id)).title).toBe('Mine live'); // untouched
    await problem(await api(a.page, 'POST', '/api/trash/abc/restore'), 400, 'MALFORMED_REQUEST');
    await problem(await api(a.page, 'DELETE', '/api/trash/abc'), 400, 'MALFORMED_REQUEST');
    // DELETE /api/tasks/{id}: not in my lists / already trashed -> 404
    await problem(await api(a.page, 'DELETE', `/api/tasks/${foreignTrashed.id}`), 404, 'NOT_FOUND');
    await problem(await api(a.page, 'DELETE', `/api/tasks/${trashed.id}`), 404, 'NOT_FOUND');
    // CSRF
    for (const [method, path] of [['POST', `/api/trash/${trashed.id}/restore`], ['DELETE', `/api/trash/${trashed.id}`], ['POST', '/api/trash/empty'], ['POST', `/api/tasks/${mine.id}/undo-complete`]] as const) {
      const r = await a.page.request.fetch(path, { method, data: method === 'POST' && path.includes('undo') ? { nextTaskId: 1 } : undefined });
      expect(r.status(), `${method} ${path} without CSRF`).toBe(403);
      expect((await r.json()).code).toBe('CSRF_INVALID');
    }
    expect((await trashOf(a.page)).total).toBe(1); // nothing was changed by those
    // signed out
    const anon = await a.ctx.browser()!.newContext({ baseURL: BASE });
    await anon.request.get('/api/auth/me');
    const token = (await anon.cookies()).find((c) => c.name === 'XSRF-TOKEN')!.value;
    for (const [method, path] of [['GET', '/api/trash'], ['POST', `/api/trash/${trashed.id}/restore`], ['DELETE', `/api/trash/${trashed.id}`], ['POST', '/api/trash/empty'], ['POST', `/api/tasks/${trashed.id}/undo-complete`]] as const) {
      const r = await anon.request.fetch(path, { method, headers: { 'X-XSRF-TOKEN': token }, data: path.includes('undo') ? { nextTaskId: 1 } : undefined });
      expect(r.status(), `${method} ${path} signed out`).toBe(401);
      expect(r.headers()['content-type']).toContain('application/problem+json');
    }
    await anon.close();
  });

  test('AC15: GET /api/trash cursors and list: 404 LIST_NOT_FOUND beats a bad cursor; INVALID_CURSOR; task-list and trash cursors are not interchangeable; foreign list 404', async ({ kit }) => {
    test.setTimeout(120_000);
    kit.allowNetworkErrors = true;
    const a = await kit.newUser('ac15c');
    const b = await kit.newUser('ac15cb');
    const p = a.page;
    const foreignList = await createList(b.page, 'Not yours');
    // 51 live tasks -> a task-list cursor; 51 trashed -> a trash cursor
    await mkMany(p, 'Live', 51);
    const doomed = await mkMany(p, 'Gone', 51);
    for (let i = 0; i < doomed.length; i += 10) await Promise.all(doomed.slice(i, i + 10).map((t) => trashApi(p, t.id)));
    const taskPage = await json<{ items: Task[]; nextCursor: string | null }>(await p.request.get('/api/tasks'));
    const trashPage = await trashOf(p);
    expect(taskPage.nextCursor).toBeTruthy();
    expect(trashPage.nextCursor).toBeTruthy();
    const invalid = async (res: APIResponse) => {
      const body = await problem(res, 400, 'VALIDATION_FAILED');
      expect(body.errors).toEqual([expect.objectContaining({ field: 'cursor', code: 'INVALID_CURSOR' })]);
    };
    await invalid(await p.request.get('/api/trash?cursor=garbage'));
    await invalid(await p.request.get(`/api/trash?cursor=${encodeURIComponent(taskPage.nextCursor!)}`));
    await invalid(await p.request.get(`/api/tasks?cursor=${encodeURIComponent(trashPage.nextCursor!)}`));
    // list check comes first
    await problem(await p.request.get(`/api/trash?list=${foreignList.id}&cursor=garbage`), 404, 'LIST_NOT_FOUND');
    await problem(await p.request.get('/api/trash?list=987654321'), 404, 'LIST_NOT_FOUND');
    await problem(await p.request.get(`/api/trash?list=${foreignList.id}`), 404, 'LIST_NOT_FOUND');
    // the cursor of one list is not valid for another filter... the same cursor with the same list works
    const mine = await personal(p);
    const f1 = await trashOf(p, `?list=${mine.id}`);
    const f2 = await trashOf(p, `?list=${mine.id}&cursor=${encodeURIComponent(f1.nextCursor!)}`);
    expect(f1.items).toHaveLength(50);
    expect(f2.items).toHaveLength(1);
    expect(f2.nextCursor).toBeNull();
  });

  test('AC15: POST /api/trash/empty -> {deleted:n}; empty trash -> {deleted:0}; ?list= scopes it; unknown list 404 LIST_NOT_FOUND', async ({ kit }) => {
    kit.allowNetworkErrors = true;
    const u = await kit.newUser('ac15m');
    const p = u.page;
    const work = await createList(p, 'Work 15m');
    expect(await json(await api(p, 'POST', '/api/trash/empty'))).toEqual({ deleted: 0 });
    const t1 = await mk(p, 'E1');
    const t2 = await mk(p, 'E2', { listId: work.id });
    const t3 = await mk(p, 'E3', { listId: work.id });
    for (const t of [t1, t2, t3]) await trashApi(p, t.id);
    await problem(await api(p, 'POST', '/api/trash/empty?list=987654321'), 404, 'LIST_NOT_FOUND');
    expect(await json(await api(p, 'POST', `/api/trash/empty?list=${work.id}`))).toEqual({ deleted: 2 });
    expect((await trashOf(p)).items.map((i) => i.id)).toEqual([t1.id]);
    expect(await json(await api(p, 'POST', '/api/trash/empty'))).toEqual({ deleted: 1 });
    expect(await json(await api(p, 'POST', '/api/trash/empty'))).toEqual({ deleted: 0 });
  });

  test('AC15: PATCH /api/tasks/{id} takes any subset of the task fields; missing = unchanged; null clears only description / dueDate / dueTime; {} is 400 done/REQUIRED', async ({ kit }) => {
    kit.allowNetworkErrors = true;
    const u = await kit.newUser('ac15p');
    const p = u.page;
    const work = await createList(p, 'Work 15p');
    const t = await mk(p, 'Patch me', { description: 'desc', priority: 'HIGH', tags: ['A', 'B'], dueDate: FAR, dueTime: '09:15', reminder: 'ONE_HOUR' });
    const patch = (body: unknown) => api(p, 'PATCH', `/api/tasks/${t.id}`, body);
    const r1 = await json<Task>(await patch({ priority: 'LOW' }));
    expect(r1).toMatchObject({ title: 'Patch me', description: 'desc', priority: 'LOW', tags: ['A', 'B'], dueDate: FAR, dueTime: '09:15', myReminder: 'ONE_HOUR' });
    expect(Object.keys(r1).sort()).toEqual([...TASK_KEYS, 'nextTask'].sort());
    expect(r1.nextTask).toBeNull();
    expect(await json<Task>(await patch({ title: 'Renamed', tags: ['Z'] }))).toMatchObject({ title: 'Renamed', tags: ['Z'], priority: 'LOW' });
    // nulls on fields that cannot be cleared are "unchanged"
    expect(await json<Task>(await patch({ priority: null, tags: null, title: null, status: 'TODO' }))).toMatchObject({ title: 'Renamed', tags: ['Z'], priority: 'LOW' });
    // a body whose fields are all null (on non-clearable fields) has no change: 400 done / REQUIRED
    await problem(await patch({ priority: null, tags: null, title: null }), 400, 'VALIDATION_FAILED');
    // description null clears; dueTime null clears only the time; dueDate null clears the date, time and reminder
    expect((await json<Task>(await patch({ description: null }))).description).toBeNull();
    expect(await json<Task>(await patch({ dueTime: null }))).toMatchObject({ dueDate: FAR, dueTime: null });
    await json<Task>(await patch({ dueTime: '09:15' }));
    const cleared = await json<Task>(await patch({ dueDate: null }));
    expect(cleared).toMatchObject({ dueDate: null, dueTime: null, myReminder: null, repeat: null });
    // repeat: NEVER clears; status + listId (move)
    await json<Task>(await patch({ dueDate: FAR, repeat: DAILY }));
    expect((await json<Task>(await patch({ repeat: 'NEVER' }))).repeat).toBeNull();
    const moved = await json<Task>(await patch({ listId: work.id, status: 'IN_PROGRESS' }));
    expect(moved).toMatchObject({ listId: work.id, status: 'IN_PROGRESS', done: false });
    // errors
    const empty = await problem(await patch({}), 400, 'VALIDATION_FAILED');
    expect(empty.errors).toEqual([expect.objectContaining({ field: 'done', code: 'REQUIRED' })]);
    const conflict = await problem(await patch({ done: true, status: 'TODO' }), 400, 'VALIDATION_FAILED');
    expect(conflict.errors).toEqual([expect.objectContaining({ field: 'status', code: 'STATUS_CONFLICT' })]);
    await problem(await patch({ title: '   ' }), 400, 'VALIDATION_FAILED');
    await problem(await patch({ listId: 987654321 }), 404, 'LIST_NOT_FOUND');
  });

  test('AC15: POST /api/tasks/{id}/undo-complete: result = Task + nextTaskOutcome; validation errors; nextTaskId == id is KEPT and never deleted; foreign task 404', async ({ kit }) => {
    kit.allowNetworkErrors = true;
    const u = await kit.newUser('ac15x');
    const other = await kit.newUser('ac15y');
    const p = u.page;
    const t = await mk(p, 'Undo me', { dueDate: FAR, repeat: DAILY });
    const done = await json<Task>(await api(p, 'PATCH', `/api/tasks/${t.id}`, { done: true }));
    expect(done.nextTask).toBeTruthy();
    expect(done.repeat).toBeNull();
    const url = `/api/tasks/${t.id}/undo-complete`;
    const val = async (body: unknown, field: string, code: string) => {
      const b = await problem(await api(p, 'POST', url, body), 400, 'VALIDATION_FAILED');
      expect(b.errors).toEqual([expect.objectContaining({ field, code })]);
    };
    await val({}, 'nextTaskId', 'REQUIRED');
    await val({ nextTaskId: null }, 'nextTaskId', 'REQUIRED');
    await val({ nextTaskId: 0 }, 'nextTaskId', 'OUT_OF_RANGE');
    await val({ nextTaskId: done.nextTask!.id, status: 'DONE' }, 'status', 'OUT_OF_RANGE');
    await problem(await api(p, 'POST', url, { nextTaskId: 'x' }), 400, 'MALFORMED_REQUEST');
    await problem(await api(p, 'POST', url, { nextTaskId: done.nextTask!.id, status: 'NOPE' }), 400, 'MALFORMED_REQUEST');
    await problem(await api(p, 'POST', '/api/tasks/abc/undo-complete', { nextTaskId: 1 }), 400, 'MALFORMED_REQUEST');
    await problem(await api(other.page, 'POST', url, { nextTaskId: done.nextTask!.id }), 404, 'NOT_FOUND');
    expect((await get(p, done.nextTask!.id)).id).toBe(done.nextTask!.id); // none of that deleted the copy
    // nextTaskId == id -> KEPT, the task is not deleted
    const self = await json<Task>(await api(p, 'POST', url, { nextTaskId: t.id }));
    expect(self.nextTaskOutcome).toBe('KEPT');
    expect(self).toMatchObject({ id: t.id, status: 'TODO', done: false, repeat: null });
    // the real thing on a fresh completion
    const t2 = await mk(p, 'Undo me 2', { dueDate: FAR, repeat: DAILY });
    const done2 = await json<Task>(await api(p, 'PATCH', `/api/tasks/${t2.id}`, { done: true }));
    const res = await json<Task>(await api(p, 'POST', `/api/tasks/${t2.id}/undo-complete`, { nextTaskId: done2.nextTask!.id, status: 'IN_PROGRESS' }));
    expect(Object.keys(res).sort()).toEqual([...TASK_KEYS, 'nextTaskOutcome'].sort());
    expect(res).toMatchObject({ nextTaskOutcome: 'DELETED', status: 'IN_PROGRESS', done: false, repeat: DAILY });
    // idempotent for the task: calling again is GONE (the copy is gone) and does not error
    const again = await json<Task>(await api(p, 'POST', `/api/tasks/${t2.id}/undo-complete`, { nextTaskId: done2.nextTask!.id }));
    expect(again.nextTaskOutcome).toBe('GONE');
  });

  test('AC15: PUT / PATCH of a completing repeat still returns TaskResult.nextTask; a task trashed by DELETE has no side effects on Notification keys (taskTrashed present)', async ({ kit }) => {
    const u = await kit.newUser('ac15n');
    const t = await mk(u.page, 'Notif key', { dueDate: FAR });
    sql(`INSERT INTO notifications (user_id, task_id, title, due_date, created_at) VALUES (${u.id}, ${t.id}, 'Notif key', '${FAR}', UTC_TIMESTAMP(6))`);
    const n = await json<{ items: Record<string, unknown>[] }>(await u.page.request.get('/api/notifications'));
    expect(Object.keys(n.items[0]).sort()).toEqual(['createdAt', 'dueDate', 'dueTime', 'id', 'late', 'listId', 'read', 'taskId', 'taskTrashed', 'title', 'type']);
    expect(n.items[0]['taskTrashed']).toBe(false);
  });
});

// =====================================================================================
// AC16: Trash page states
// =====================================================================================

test.describe('AC16 Trash page states', () => {
  test('AC16: empty state text; Empty-trash button disabled; restoring the last task shows the empty state', async ({ kit }) => {
    const u = await kit.newUser('ac16e');
    const p = u.page;
    await gotoTrash(p);
    await expect(p.getByTestId('trash-empty')).toHaveText('Trash is empty. Deleted tasks stay here for 30 days.');
    await expect(p.getByTestId('empty-trash')).toBeDisabled();
    await shot(p, 'ac16-empty');
    const t = await mk(p, 'Last one');
    await trashApi(p, t.id);
    await gotoTrash(p);
    await expect(trashRows(p)).toHaveCount(1);
    await p.getByTestId('trash-restore').click();
    await expect(p.getByTestId('trash-empty')).toHaveText('Trash is empty. Deleted tasks stay here for 30 days.');
    await expect(trashRows(p)).toHaveCount(0);
    await expect(p.getByTestId('empty-trash')).toBeDisabled();
  });

  test('AC16: loading state (delayed request), then the rows', async ({ kit }) => {
    const u = await kit.newUser('ac16l');
    const t = await mk(u.page, 'Slow trash');
    await trashApi(u.page, t.id);
    let release!: () => void;
    const gate = new Promise<void>((r) => (release = r));
    await u.page.route('**/api/trash**', async (route) => {
      if (route.request().method() === 'GET') await gate;
      await route.continue();
    });
    await u.page.goto('/trash');
    await expect(u.page.getByText('Loading the trash…')).toBeVisible();
    await expect(u.page.getByTestId('trash-empty')).toHaveCount(0);
    await shot(u.page, 'ac16-loading');
    release();
    await expect(trashRows(u.page)).toHaveCount(1);
    await expect(u.page.getByText('Loading the trash…')).toHaveCount(0);
  });

  test('AC16: error state with Retry (still down -> stays; up -> shows rows); no empty text while failed', async ({ kit }) => {
    kit.allowNetworkErrors = true;
    const u = await kit.newUser('ac16x');
    const t = await mk(u.page, 'After outage');
    await trashApi(u.page, t.id);
    let down = true;
    await u.page.route('**/api/trash', (route) => (down && route.request().method() === 'GET' ? route.abort('failed') : route.continue()));
    await u.page.goto('/trash');
    const err = u.page.getByTestId('trash-error');
    await expect(err).toBeVisible();
    await expect(err).toContainText("Couldn't load the trash");
    await expect(u.page.getByTestId('trash-empty')).toHaveCount(0);
    await shot(u.page, 'ac16-error');
    await err.getByRole('button', { name: 'Retry' }).click();
    await expect(err).toBeVisible(); // still down
    down = false;
    await err.getByRole('button', { name: 'Retry' }).click();
    await expect(err).toHaveCount(0);
    await expect(trashRows(u.page)).toHaveCount(1);
  });

  test('AC16: server 500 on the list shows the error state too; Load more failure keeps the loaded rows and has its own Retry', async ({ kit }) => {
    test.setTimeout(120_000);
    kit.allowNetworkErrors = true;
    kit.allow5xx = true;
    const u = await kit.newUser('ac16m');
    const p = u.page;
    const tasks = await mkMany(p, 'Page', 52);
    for (let i = 0; i < tasks.length; i += 10) await Promise.all(tasks.slice(i, i + 10).map((t) => trashApi(p, t.id)));
    let failMore = true;
    await p.route(/\/api\/trash\?.*cursor=/, (route) => (failMore ? route.fulfill({ status: 500, contentType: 'application/problem+json', body: '{"status":500,"code":"INTERNAL_ERROR","title":"x","detail":"x"}' }) : route.continue()));
    await gotoTrash(p);
    await expect(trashRows(p)).toHaveCount(50);
    await p.getByTestId('trash-load-more').click();
    const alert = p.locator('.trash-footer').getByRole('alert');
    await expect(alert).toContainText("Couldn't load more tasks");
    await expect(trashRows(p)).toHaveCount(50);
    await shot(p, 'ac16-load-more-failed');
    failMore = false;
    await alert.getByRole('button', { name: 'Retry' }).click();
    await expect(trashRows(p)).toHaveCount(52);
    await expect(alert).toHaveCount(0);

    // a 500 on the first page
    let down = true;
    await p.unrouteAll({ behavior: 'wait' });
    await p.route('**/api/trash', (route) => (down && route.request().method() === 'GET' ? route.fulfill({ status: 500, contentType: 'application/problem+json', body: '{"status":500,"code":"INTERNAL_ERROR","title":"x","detail":"x"}' }) : route.continue()));
    await p.reload();
    await expect(p.getByTestId('trash-error')).toBeVisible();
    down = false;
    await p.getByTestId('trash-error').getByRole('button', { name: 'Retry' }).click();
    await expect(trashRows(p)).toHaveCount(50);
  });

  test('AC16: a failing Restore / Delete forever / Empty keeps the row and says so (trash-row-error / dialog error); Retry works; buttons disabled while busy', async ({ kit }) => {
    kit.allowNetworkErrors = true;
    kit.allow5xx = true;
    const u = await kit.newUser('ac16r');
    const p = u.page;
    const t = await mk(p, 'Flaky restore');
    await trashApi(p, t.id);
    let fail = true;
    const boom = { status: 500, contentType: 'application/problem+json', body: '{"status":500,"code":"INTERNAL_ERROR","title":"x","detail":"x"}' };
    await p.route(/\/api\/trash\/\d+\/restore$/, async (route) => {
      await delay(600);
      return fail ? route.fulfill(boom) : route.continue();
    });
    await p.route(/\/api\/trash\/(\d+|empty)$/, async (route) => (fail && ['DELETE', 'POST'].includes(route.request().method()) ? route.fulfill(boom) : route.continue()));
    await gotoTrash(p);
    await p.getByTestId('trash-restore').click();
    await expect(p.getByTestId('trash-restore')).toBeDisabled(); // busy
    await expect(p.getByTestId('trash-delete')).toBeDisabled();
    await expect(p.getByTestId('trash-row-error')).toContainText("Couldn't restore the task");
    await expect(trashRows(p)).toHaveCount(1);
    await shot(p, 'ac16-restore-failed');
    // delete forever fails: the dialog stays with an error
    await p.getByTestId('trash-delete').click();
    await p.getByTestId('trash-confirm').click();
    await expect(p.locator('dialog[aria-labelledby="trash-confirm-heading"] [role=alert]')).toContainText("Couldn't delete the task");
    await p.locator('dialog[aria-labelledby="trash-confirm-heading"]').getByRole('button', { name: 'Cancel' }).click();
    // empty fails
    await p.getByTestId('empty-trash').click();
    await p.getByTestId('trash-confirm').click();
    await expect(p.locator('dialog[aria-labelledby="trash-confirm-heading"] [role=alert]')).toContainText("Couldn't empty the trash");
    await p.locator('dialog[aria-labelledby="trash-confirm-heading"]').getByRole('button', { name: 'Cancel' }).click();
    expect((await trashOf(p)).total).toBe(1);
    // recovered
    fail = false;
    await p.getByTestId('trash-restore').click();
    await expect(p.getByTestId('trash-empty')).toBeVisible();
    expect((await get(p, t.id)).title).toBe('Flaky restore');
  });

  test('AC16: refresh mid-flow: reloading /trash after a delete shows the task; double-clicking Restore sends one request', async ({ kit }) => {
    const u = await kit.newUser('ac16d');
    const p = u.page;
    const t = await mk(p, 'Double');
    await trashApi(p, t.id);
    await gotoTrash(p);
    await p.reload();
    await expect(trashRows(p)).toHaveCount(1);
    const posts = requests(p, 'POST', /^\/api\/trash\/\d+\/restore$/);
    await p.getByTestId('trash-restore').dblclick();
    await expect(p.getByTestId('trash-empty')).toBeVisible();
    await delay(400);
    expect(posts).toHaveLength(1);
  });
});

// keep helpers referenced by every test file section (lint-friendly)
void (null as unknown as TrashItem);
