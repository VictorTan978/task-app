import { APIResponse, Browser, BrowserContext, expect, Locator, Page, Request, request as pwRequest, test as base } from '@playwright/test';
import { execFileSync } from 'node:child_process';

/*
 * E2E for specs/undo-trash-dnd-bulk.md Part B (T5b: bulk actions, AC18-AC22), contract 0.17.0.
 * The Decisions log of 2026-10-21 overrides the AC wording:
 *  - selection mode is entered with the "Select" button only;
 *  - the response is BulkResult {count, tasks: TaskResult[]} (each nextTask paired with its task);
 *  - undo endpoints: bulk-patch, bulk-undo-complete (no nextTaskId = plain reopen, outcome NONE), trash/restore;
 *  - errors carry failedIds; MIXED_LISTS / DUPLICATE are new field codes;
 *  - Trash-page selection and bulk delete-forever are OUT of scope.
 * Needs `docker compose up -d db mail oidc` (DB via `docker compose exec db mysql`).
 * Every test fails on browser console errors (other than the browser's own 4xx lines when a test allows them),
 * page errors, or 5xx responses.
 */

const RUN = `${Date.now()}-${Math.floor(Math.random() * 1e6)}`;
const SHOTS = '../test-results/bulk-actions';
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
  u.email = `qa-t5b-${tag}-${uid()}@example.com`;
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

// UI helpers
function tasksList(p: Page): Locator {
  return p.getByRole('list', { name: 'Tasks' });
}
function pickBox(p: Page, title: string): Locator {
  return p.getByRole('checkbox', { name: `Select ${title}`, exact: true });
}
function doneBox(p: Page, title: string): Locator {
  return p.getByRole('checkbox', { name: `Done: ${title}`, exact: true });
}
function srow(p: Page, title: string): Locator {
  return tasksList(p)
    .getByRole('listitem')
    .filter({ has: p.getByRole('checkbox', { name: new RegExp(`^(Select|Done): ${title}$`) }) });
}
function toastRegion(p: Page): Locator {
  return p.locator('app-toast-host [role=status]');
}
function toast(p: Page): Locator {
  return toastRegion(p).locator('.toast');
}
function msg(p: Page): Locator {
  return toast(p).locator('span').first();
}
function undoBtn(p: Page): Locator {
  return toast(p).getByRole('button', { name: 'Undo' });
}
function bar(p: Page): Locator {
  return p.getByTestId('bulk-bar');
}
function count(p: Page): Locator {
  return p.getByTestId('bulk-count');
}
function banner(p: Page): Locator {
  return p.getByRole('alert');
}
async function gotoTasks(p: Page, path = '/tasks', headingText = 'My tasks') {
  await p.goto(path);
  await expect(p.getByRole('heading', { name: headingText, exact: true })).toBeVisible();
  await expect(p.getByText('Loading tasks…')).toHaveCount(0);
  await expect(p.getByText('Loading lists…')).toHaveCount(0);
}
async function enterSelect(p: Page) {
  await p.getByTestId('select-mode').click();
  await expect(bar(p)).toBeVisible(); // the bar loads via @defer
}
async function pick(p: Page, ...titles: string[]) {
  for (const t of titles) await pickBox(p, t).click();
}
/** Select mode with the given tasks ticked. */
async function selectTasks(p: Page, ...titles: string[]) {
  await enterSelect(p);
  await pick(p, ...titles);
  await expect(count(p)).toHaveText(`${titles.length} selected`);
}
/** Every request that changes data (method + path), in order. */
function mutations(p: Page): string[] {
  const seen: string[] = [];
  p.on('request', (r) => {
    if (['POST', 'PUT', 'PATCH', 'DELETE'].includes(r.method()) && new URL(r.url()).pathname.startsWith('/api/') && !/\/api\/(auth|account|notifications)/.test(new URL(r.url()).pathname)) {
      seen.push(`${r.method()} ${new URL(r.url()).pathname}`);
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
async function shot(p: Page, name: string) {
  await p.screenshot({ path: `${SHOTS}/${name}.png`, fullPage: true });
}
function expectTaskResult(t: Task) {
  expect(Object.keys(t).sort()).toEqual(TASK_RESULT_KEYS);
}
function expectBulkResult(r: BulkResult, n: number) {
  expect(Object.keys(r).sort()).toEqual(['count', 'tasks']);
  expect(r.count).toBe(n);
  r.tasks.forEach(expectTaskResult);
}

// =====================================================================================
// AC18: selection mode
// =====================================================================================

test.describe('AC18 selection mode', () => {
  test('AC18: Select shows a checkbox on every row, the header shows "N selected", Select all loaded and Cancel', async ({ kit }) => {
    const u = await kit.newUser('ac18a');
    const p = u.page;
    await mkMany(p, 'Row ', 3);
    await gotoTasks(p);
    await expect(bar(p)).toHaveCount(0);
    await expect(doneBox(p, 'Row 000')).toBeVisible(); // normal mode: the "Done" checkbox
    await enterSelect(p);
    await expect(count(p)).toHaveText('0 selected');
    await expect(p.getByTestId('bulk-select-all')).toHaveText('Select all loaded');
    await expect(p.getByTestId('bulk-cancel')).toHaveText('Cancel');
    await expect(p.getByTestId('task-select')).toHaveCount(3);
    for (const t of ['Row 000', 'Row 001', 'Row 002']) await expect(pickBox(p, t)).toBeVisible();
    await expect(doneBox(p, 'Row 000')).toHaveCount(0);
    await expect(p.getByTestId('select-mode')).toHaveCount(0); // the entry button is gone while selecting
    // no per-row Edit / Delete while selecting
    await expect(tasksList(p).getByRole('button', { name: 'Edit', exact: true })).toHaveCount(0);
    await expect(tasksList(p).getByRole('button', { name: 'Delete', exact: true })).toHaveCount(0);
    // nothing selected: the actions are disabled
    for (const id of ['bulk-complete', 'bulk-reopen', 'bulk-status', 'bulk-priority', 'bulk-add-tag', 'bulk-remove-tag', 'bulk-move', 'bulk-delete']) {
      await expect(p.getByTestId(id), id).toBeDisabled();
    }
    await shot(p, 'ac18-select-mode');
    await pick(p, 'Row 001');
    await expect(count(p)).toHaveText('1 selected');
    await expect(p.getByTestId('bulk-complete')).toBeEnabled();
    await pick(p, 'Row 002');
    await expect(count(p)).toHaveText('2 selected');
    await pickBox(p, 'Row 002').click(); // untick
    await expect(count(p)).toHaveText('1 selected');
    await expect(pickBox(p, 'Row 001')).toBeChecked();
    await expect(pickBox(p, 'Row 002')).not.toBeChecked();
    await shot(p, 'ac18-one-selected');
  });

  test('AC18: the Select button is not offered on an empty list; clicking a title in selection mode toggles it (no editor)', async ({ kit }) => {
    const u = await kit.newUser('ac18b');
    const p = u.page;
    await gotoTasks(p);
    await expect(p.getByTestId('select-mode')).toHaveCount(0);
    await mk(p, 'Solo');
    await gotoTasks(p);
    await enterSelect(p);
    await tasksList(p).getByRole('button', { name: 'Solo', exact: true }).click();
    await expect(count(p)).toHaveText('1 selected');
    await expect(pickBox(p, 'Solo')).toBeChecked();
    await expect(p.getByRole('dialog')).toHaveCount(0);
    await tasksList(p).getByRole('button', { name: 'Solo', exact: true }).click();
    await expect(count(p)).toHaveText('0 selected');
  });

  test('AC18: "Select all loaded" selects only the loaded page, never unloaded pages; Load more then adds the rest', async ({ kit }) => {
    const u = await kit.newUser('ac18c');
    const p = u.page;
    await mkMany(p, 'Page ', 60);
    await gotoTasks(p);
    await expect(p.getByTestId('tasks-footer')).toContainText('Showing 50 of 60');
    await enterSelect(p);
    await p.getByTestId('bulk-select-all').click();
    await expect(count(p)).toHaveText('50 selected');
    await expect(p.getByTestId('bulk-limit')).toHaveCount(0);
    await shot(p, 'ac18-select-all-50');
    // loading more keeps the selection and the mode
    await p.getByRole('button', { name: 'Load more' }).click();
    await expect(p.getByTestId('tasks-footer')).toContainText('60 tasks');
    await expect(count(p)).toHaveText('50 selected');
    await p.getByTestId('bulk-select-all').click();
    await expect(count(p)).toHaveText('60 selected');
  });

  test('AC18: Shift+click selects a range (both directions), a plain click moves the anchor', async ({ kit }) => {
    const u = await kit.newUser('ac18d');
    const p = u.page;
    for (const t of ['E', 'D', 'C', 'B', 'A']) await mk(p, `Rng ${t}`); // newest first: A B C D E
    await gotoTasks(p);
    await enterSelect(p);
    const order = await tasksList(p).getByRole('checkbox').evaluateAll((els) => els.map((e) => e.getAttribute('aria-label')));
    expect(order).toEqual(['Select Rng A', 'Select Rng B', 'Select Rng C', 'Select Rng D', 'Select Rng E']);
    await pickBox(p, 'Rng B').click();
    await pickBox(p, 'Rng D').click({ modifiers: ['Shift'] });
    await expect(count(p)).toHaveText('3 selected');
    for (const t of ['B', 'C', 'D']) await expect(pickBox(p, `Rng ${t}`)).toBeChecked();
    await expect(pickBox(p, 'Rng A')).not.toBeChecked();
    await expect(pickBox(p, 'Rng E')).not.toBeChecked();
    // plain click on E moves the anchor, shift+click on C extends upward from E
    await pickBox(p, 'Rng E').click();
    await pickBox(p, 'Rng C').click({ modifiers: ['Shift'] });
    await expect(count(p)).toHaveText('4 selected'); // B C D E
    await expect(pickBox(p, 'Rng A')).not.toBeChecked();
    await shot(p, 'ac18-shift-range');
  });

  test('AC18: Esc and Cancel leave selection mode and forget the selection', async ({ kit }) => {
    const u = await kit.newUser('ac18e');
    const p = u.page;
    await mkMany(p, 'Esc ', 3);
    await gotoTasks(p);
    const muts = mutations(p);
    await selectTasks(p, 'Esc 000', 'Esc 001');
    await p.keyboard.press('Escape');
    await expect(bar(p)).toHaveCount(0);
    await expect(p.getByTestId('task-select')).toHaveCount(0);
    await expect(doneBox(p, 'Esc 000')).toBeVisible();
    // back in: nothing is selected any more
    await enterSelect(p);
    await expect(count(p)).toHaveText('0 selected');
    await pick(p, 'Esc 002');
    await p.getByTestId('bulk-cancel').click();
    await expect(bar(p)).toHaveCount(0);
    await expect(doneBox(p, 'Esc 002')).toBeVisible();
    await expect(p.getByTestId('select-mode')).toBeVisible();
    expect(muts).toEqual([]);
  });

  test('AC18: the selection mode ends when the list, filter, sort or search changes', async ({ kit }) => {
    const u = await kit.newUser('ac18f');
    const p = u.page;
    const home = await createList(p, 'Home');
    await mk(p, 'Alpha');
    await mk(p, 'Beta');
    await mk(p, 'Gamma', { status: 'DONE' });
    await mk(p, 'Home one', { listId: home.id });
    await gotoTasks(p);
    const tabs = p.getByRole('navigation', { name: 'Filter tasks' });
    // filter tab
    await selectTasks(p, 'Alpha');
    await tabs.getByRole('link', { name: 'Active' }).click();
    await expect(bar(p)).toHaveCount(0);
    await expect(p.getByTestId('task-select')).toHaveCount(0);
    await tabs.getByRole('link', { name: 'All' }).click();
    await expect(doneBox(p, 'Gamma')).toBeVisible();
    // sort
    await selectTasks(p, 'Alpha');
    await p.getByLabel('Sort').selectOption('title');
    await expect(bar(p)).toHaveCount(0);
    await expect(p.getByTestId('task-select')).toHaveCount(0);
    // search
    await expect(doneBox(p, 'Alpha')).toBeVisible();
    await selectTasks(p, 'Alpha');
    await p.getByRole('searchbox', { name: 'Search tasks' }).fill('Beta');
    await expect(bar(p)).toHaveCount(0);
    await expect(doneBox(p, 'Beta')).toBeVisible();
    await p.getByRole('button', { name: 'Clear search' }).click();
    await expect(doneBox(p, 'Alpha')).toBeVisible();
    // another list via the sidebar
    await selectTasks(p, 'Alpha');
    await p.getByRole('complementary', { name: 'Your lists' }).getByRole('link', { name: /^Home/ }).click();
    await expect(p.getByRole('heading', { name: 'Home', exact: true })).toBeVisible();
    await expect(bar(p)).toHaveCount(0);
    await expect(doneBox(p, 'Home one')).toBeVisible();
    // refreshing mid-selection also ends it
    await selectTasks(p, 'Home one');
    await p.reload();
    await expect(doneBox(p, 'Home one')).toBeVisible();
    await expect(bar(p)).toHaveCount(0);
  });

  test('AC18: at most 200 can be selected (cap message), and a bulk action on 200 is one request', async ({ kit }) => {
    test.setTimeout(240_000);
    const u = await kit.newUser('ac18g');
    const p = u.page;
    await mkMany(p, 'Cap ', 205);
    await gotoTasks(p);
    for (let i = 0; i < 4; i++) {
      await p.getByRole('button', { name: 'Load more' }).click();
      await expect(p.getByTestId('tasks-footer')).toContainText(i < 3 ? `Showing ${100 + i * 50} of 205` : '205 tasks');
    }
    await enterSelect(p);
    await p.getByTestId('bulk-select-all').click();
    await expect(count(p)).toHaveText('200 selected');
    await expect(p.getByTestId('bulk-limit')).toHaveText('At most 200 tasks can be selected.');
    // the 201st-205th rows cannot be added
    const unchecked = tasksList(p).getByRole('checkbox', { checked: false });
    await expect(unchecked).toHaveCount(5);
    await unchecked.first().click();
    await expect(count(p)).toHaveText('200 selected');
    await shot(p, 'ac18-cap-200');
    // one request for all 200
    const bulkReqs = requests(p, 'POST', /^\/api\/tasks\/bulk$/);
    const muts = mutations(p);
    await p.getByTestId('bulk-priority').click();
    await p.getByTestId('bulk-priority-HIGH').click();
    await expect(msg(p)).toHaveText('Priority set on 200 tasks');
    expect(bulkReqs).toHaveLength(1);
    expect(muts).toEqual(['POST /api/tasks/bulk']);
    expect((bulkReqs[0].postDataJSON() as { ids: number[] }).ids).toHaveLength(200);
    const all = await allTasks(p);
    expect(all.filter((t) => t.priority === 'HIGH')).toHaveLength(200);
    expect(all.filter((t) => t.priority === 'NONE')).toHaveLength(5);
    // and undo is one request that restores all 200
    const patchReqs = requests(p, 'POST', /^\/api\/tasks\/bulk-patch$/);
    await undoBtn(p).click();
    await expect(msg(p)).toHaveText('Undone');
    expect(patchReqs).toHaveLength(1);
    expect((await allTasks(p)).filter((t) => t.priority === 'NONE')).toHaveLength(205);
  });
});

// =====================================================================================
// AC19: every action through the UI, one request, with batch undo
// =====================================================================================

test.describe('AC19 bulk actions with undo', () => {
  test('AC19: Complete (mixed TODO / IN_PROGRESS): one request, "3 tasks completed", Undo restores each own status in one request', async ({ kit }) => {
    const u = await kit.newUser('ac19c');
    const p = u.page;
    const a = await mk(p, 'C todo');
    const b = await mk(p, 'C prog', { status: 'IN_PROGRESS' });
    const c = await mk(p, 'C todo2');
    await gotoTasks(p);
    const muts = mutations(p);
    await selectTasks(p, 'C todo', 'C prog', 'C todo2');
    await p.getByTestId('bulk-complete').click();
    await expect(msg(p)).toHaveText('3 tasks completed');
    await expect(bar(p)).toHaveCount(0); // success leaves selection mode
    expect(muts).toEqual(['POST /api/tasks/bulk']);
    for (const t of [a, b, c]) expect((await get(p, t.id)).status).toBe('DONE');
    await expect(doneBox(p, 'C prog')).toBeChecked();
    await shot(p, 'ac19-complete-toast');
    muts.length = 0;
    const reqs = requests(p, 'POST', /^\/api\/tasks\/bulk-undo-complete$/);
    await undoBtn(p).click();
    await expect(msg(p)).toHaveText('Undone');
    expect(muts).toEqual(['POST /api/tasks/bulk-undo-complete']);
    const sent = reqs[0].postDataJSON() as { items: { id: number; nextTaskId?: number; status: string }[] };
    expect(sent.items).toEqual([
      { id: a.id, status: 'TODO' },
      { id: b.id, status: 'IN_PROGRESS' },
      { id: c.id, status: 'TODO' },
    ].sort((x, y) => sent.items.findIndex((i) => i.id === x.id) - sent.items.findIndex((i) => i.id === y.id)));
    expect((await get(p, a.id)).status).toBe('TODO');
    expect((await get(p, b.id)).status).toBe('IN_PROGRESS');
    expect((await get(p, c.id)).status).toBe('TODO');
    await expect(doneBox(p, 'C prog')).not.toBeChecked();
    await expect(p.getByTestId('task-in-progress')).toHaveCount(1);
  });

  test('AC19: Complete skips tasks already DONE; undo leaves them DONE', async ({ kit }) => {
    const u = await kit.newUser('ac19c2');
    const p = u.page;
    const open = await mk(p, 'Open one');
    const done = await mk(p, 'Done one', { status: 'DONE' });
    await gotoTasks(p);
    await selectTasks(p, 'Open one', 'Done one');
    const before = (await get(p, done.id)).updatedAt;
    await p.getByTestId('bulk-complete').click();
    await expect(msg(p)).toHaveText('2 tasks completed');
    expect((await get(p, done.id)).updatedAt).toBe(before); // untouched
    await undoBtn(p).click();
    await expect(msg(p)).toHaveText('Undone');
    expect((await get(p, open.id)).status).toBe('TODO');
    expect((await get(p, done.id)).status).toBe('DONE');
  });

  test('AC19: Reopen: DONE tasks go back to TODO; Undo makes them DONE again in one request', async ({ kit }) => {
    const u = await kit.newUser('ac19r');
    const p = u.page;
    const a = await mk(p, 'R one', { status: 'DONE' });
    const b = await mk(p, 'R two', { status: 'DONE' });
    await gotoTasks(p);
    const muts = mutations(p);
    await selectTasks(p, 'R one', 'R two');
    await p.getByTestId('bulk-reopen').click();
    await expect(msg(p)).toHaveText('2 tasks reopened');
    expect(muts).toEqual(['POST /api/tasks/bulk']);
    expect((await get(p, a.id)).status).toBe('TODO');
    muts.length = 0;
    await undoBtn(p).click();
    await expect(msg(p)).toHaveText('Undone');
    expect(muts).toHaveLength(1);
    expect((await get(p, a.id)).status).toBe('DONE');
    expect((await get(p, b.id)).status).toBe('DONE');
  });

  test('AC19: Set status: each option, one request; Undo gives each task its own old status', async ({ kit }) => {
    const u = await kit.newUser('ac19s');
    const p = u.page;
    const a = await mk(p, 'S a'); // TODO
    const b = await mk(p, 'S b', { status: 'IN_PROGRESS' });
    const c = await mk(p, 'S c', { status: 'DONE' });
    await gotoTasks(p);
    const muts = mutations(p);
    await selectTasks(p, 'S a', 'S b', 'S c');
    await p.getByTestId('bulk-status').click();
    for (const s of ['TODO', 'IN_PROGRESS', 'DONE']) await expect(p.getByTestId(`bulk-status-${s}`)).toBeVisible();
    await shot(p, 'ac19-status-menu');
    await p.getByTestId('bulk-status-IN_PROGRESS').click();
    await expect(msg(p)).toHaveText('Status set on 3 tasks');
    expect(muts).toEqual(['POST /api/tasks/bulk']);
    for (const t of [a, b, c]) expect((await get(p, t.id)).status).toBe('IN_PROGRESS');
    muts.length = 0;
    await undoBtn(p).click();
    await expect(msg(p)).toHaveText('Undone');
    expect(muts).toEqual(['POST /api/tasks/bulk-patch']);
    expect((await get(p, a.id)).status).toBe('TODO');
    expect((await get(p, b.id)).status).toBe('IN_PROGRESS');
    expect((await get(p, c.id)).status).toBe('DONE');
  });

  test('AC19: Set status DONE on repeating + plain tasks: copies created, Undo is one bulk-undo-complete', async ({ kit }) => {
    const u = await kit.newUser('ac19sd');
    const p = u.page;
    const rep = await mk(p, 'SD rep', { dueDate: FAR, repeat: DAILY });
    const plain = await mk(p, 'SD plain', { status: 'IN_PROGRESS' });
    await gotoTasks(p);
    const muts = mutations(p);
    await selectTasks(p, 'SD rep', 'SD plain');
    await p.getByTestId('bulk-status').click();
    await p.getByTestId('bulk-status-DONE').click();
    await expect(msg(p)).toHaveText('Status set on 2 tasks');
    expect(muts).toEqual(['POST /api/tasks/bulk']);
    const now = await allTasks(p);
    expect(now.filter((t) => t.title === 'SD rep')).toHaveLength(2); // original + copy
    muts.length = 0;
    const reqs = requests(p, 'POST', /^\/api\/tasks\/bulk-undo-complete$/);
    await undoBtn(p).click();
    await expect(msg(p)).toHaveText('Undone');
    expect(muts).toEqual(['POST /api/tasks/bulk-undo-complete']);
    const sent = reqs[0].postDataJSON() as { items: { id: number; nextTaskId?: number; status?: string }[] };
    expect(sent.items.find((i) => i.id === rep.id)?.nextTaskId).toBeGreaterThan(0);
    expect(sent.items.find((i) => i.id === plain.id)).toEqual({ id: plain.id, status: 'IN_PROGRESS' });
    const after = await allTasks(p);
    expect(after.filter((t) => t.title === 'SD rep')).toHaveLength(1);
    expect((await get(p, rep.id)).repeat).toEqual(DAILY);
    expect((await get(p, plain.id)).status).toBe('IN_PROGRESS');
  });

  test('AC19: Set priority: each option, one request; Undo gives each task its own old priority', async ({ kit }) => {
    const u = await kit.newUser('ac19p');
    const p = u.page;
    const a = await mk(p, 'P a'); // NONE
    const b = await mk(p, 'P b', { priority: 'LOW' });
    const c = await mk(p, 'P c', { priority: 'MEDIUM' });
    await gotoTasks(p);
    const muts = mutations(p);
    await selectTasks(p, 'P a', 'P b', 'P c');
    await p.getByTestId('bulk-priority').click();
    for (const s of ['NONE', 'LOW', 'MEDIUM', 'HIGH']) await expect(p.getByTestId(`bulk-priority-${s}`)).toBeVisible();
    await p.getByTestId('bulk-priority-HIGH').click();
    await expect(msg(p)).toHaveText('Priority set on 3 tasks');
    expect(muts).toEqual(['POST /api/tasks/bulk']);
    for (const t of [a, b, c]) expect((await get(p, t.id)).priority).toBe('HIGH');
    await expect(p.getByTestId('task-priority')).toHaveCount(3);
    muts.length = 0;
    const reqs = requests(p, 'POST', /^\/api\/tasks\/bulk-patch$/);
    await undoBtn(p).click();
    await expect(msg(p)).toHaveText('Undone');
    expect(muts).toEqual(['POST /api/tasks/bulk-patch']);
    const sent = (reqs[0].postDataJSON() as { items: { id: number; patch: { priority: string } }[] }).items;
    expect(Object.fromEntries(sent.map((i) => [i.id, i.patch.priority]))).toEqual({ [a.id]: 'NONE', [b.id]: 'LOW', [c.id]: 'MEDIUM' });
    expect((await get(p, a.id)).priority).toBe('NONE');
    expect((await get(p, b.id)).priority).toBe('LOW');
    expect((await get(p, c.id)).priority).toBe('MEDIUM');
  });

  test('AC19: Add tag keeps other tags; Undo removes it only where it was added and keeps a teammate\'s later tags', async ({ kit }) => {
    const owner = await kit.newUser('ac19ta-o');
    const mate = await kit.newUser('ac19ta-m');
    const team = await createList(owner.page, 'Team');
    await share(owner, team.id, mate);
    const a = await mk(owner.page, 'T a', { listId: team.id, tags: ['Keep'] });
    const b = await mk(owner.page, 'T b', { listId: team.id, tags: ['Errands', 'Keep'] }); // already has it
    const c = await mk(owner.page, 'T c', { listId: team.id });
    await gotoTasks(owner.page, `/lists/${team.id}`, 'Team');
    const p = owner.page;
    const muts = mutations(p);
    await selectTasks(p, 'T a', 'T b', 'T c');
    await p.getByTestId('bulk-add-tag').click();
    await expect(p.getByTestId('bulk-tag-apply')).toBeDisabled(); // empty name
    await p.getByTestId('bulk-tag-input').fill('Errands');
    await p.getByTestId('bulk-tag-apply').click();
    await expect(msg(p)).toHaveText("Tag 'Errands' added to 3 tasks");
    expect(muts).toEqual(['POST /api/tasks/bulk']);
    expect((await get(p, a.id)).tags).toEqual(['Errands', 'Keep']);
    expect((await get(p, b.id)).tags).toEqual(['Errands', 'Keep']);
    expect((await get(p, c.id)).tags).toEqual(['Errands']);
    await shot(p, 'ac19-add-tag');
    // the teammate then adds a tag to task a and c
    await json(await api(mate.page, 'PATCH', `/api/tasks/${a.id}`, { tags: ['Errands', 'Keep', 'Mate'] }));
    await json(await api(mate.page, 'PATCH', `/api/tasks/${c.id}`, { tags: ['Errands', 'Mate'] }));
    muts.length = 0;
    const reqs = requests(p, 'POST', /^\/api\/tasks\/bulk$/);
    await undoBtn(p).click();
    await expect(msg(p)).toHaveText('Undone');
    expect(muts).toEqual(['POST /api/tasks/bulk']);
    // the undo is REMOVE_TAG on a and c only (b already had the tag)
    const sent = reqs[0].postDataJSON() as { ids: number[]; action: string; value: string };
    expect(sent.action).toBe('REMOVE_TAG');
    expect(sent.value).toBe('Errands');
    expect([...sent.ids].sort()).toEqual([a.id, c.id].sort());
    expect((await get(p, a.id)).tags).toEqual(['Keep', 'Mate']);
    expect((await get(p, b.id)).tags).toEqual(['Errands', 'Keep']);
    expect((await get(p, c.id)).tags).toEqual(['Mate']);
  });

  test('AC19: Remove tag lists the selected tasks\' tags; Undo adds it back only where it was, keeping a teammate\'s tags', async ({ kit }) => {
    const owner = await kit.newUser('ac19tr-o');
    const mate = await kit.newUser('ac19tr-m');
    const team = await createList(owner.page, 'Team');
    await share(owner, team.id, mate);
    const a = await mk(owner.page, 'U a', { listId: team.id, tags: ['Work', 'Solo'] });
    const b = await mk(owner.page, 'U b', { listId: team.id, tags: ['Work'] });
    const c = await mk(owner.page, 'U c', { listId: team.id, tags: ['Other'] }); // does not have Work
    await gotoTasks(owner.page, `/lists/${team.id}`, 'Team');
    const p = owner.page;
    const muts = mutations(p);
    await selectTasks(p, 'U a', 'U b', 'U c');
    await p.getByTestId('bulk-remove-tag').click();
    await expect(p.getByTestId('bulk-remove-tag-choice')).toHaveText(['Other', 'Solo', 'Work']);
    await p.getByTestId('bulk-remove-tag-choice').filter({ hasText: 'Work' }).click();
    await expect(msg(p)).toHaveText("Tag 'Work' removed from 3 tasks");
    expect(muts).toEqual(['POST /api/tasks/bulk']);
    expect((await get(p, a.id)).tags).toEqual(['Solo']);
    expect((await get(p, b.id)).tags).toEqual([]);
    expect((await get(p, c.id)).tags).toEqual(['Other']);
    // teammate tags a and b afterwards
    await json(await api(mate.page, 'PATCH', `/api/tasks/${a.id}`, { tags: ['Solo', 'Mate'] }));
    await json(await api(mate.page, 'PATCH', `/api/tasks/${b.id}`, { tags: ['Mate'] }));
    muts.length = 0;
    const reqs = requests(p, 'POST', /^\/api\/tasks\/bulk$/);
    await undoBtn(p).click();
    await expect(msg(p)).toHaveText('Undone');
    expect(muts).toEqual(['POST /api/tasks/bulk']);
    const sent = reqs[0].postDataJSON() as { ids: number[]; action: string; value: string };
    expect(sent.action).toBe('ADD_TAG');
    expect([...sent.ids].sort()).toEqual([a.id, b.id].sort());
    expect((await get(p, a.id)).tags).toEqual(['Mate', 'Solo', 'Work']);
    expect((await get(p, b.id)).tags).toEqual(['Mate', 'Work']);
    expect((await get(p, c.id)).tags).toEqual(['Other']);
  });

  test('AC19: Remove tag with no tags shows an empty hint', async ({ kit }) => {
    const u = await kit.newUser('ac19te');
    await mk(u.page, 'No tags');
    await gotoTasks(u.page);
    await selectTasks(u.page, 'No tags');
    await u.page.getByTestId('bulk-remove-tag').click();
    await expect(u.page.getByText('The selected tasks have no tags')).toBeVisible();
    await expect(u.page.getByTestId('bulk-remove-tag-choice')).toHaveCount(0);
  });

  test('AC19: Move to list: tags go along, one request; Undo moves each back with its own tag names', async ({ kit }) => {
    const u = await kit.newUser('ac19m');
    const p = u.page;
    const home = await createList(p, 'Home');
    const mine = await personal(p);
    const a = await mk(p, 'M a', { tags: ['Alpha', 'Beta'] });
    const b = await mk(p, 'M b');
    await gotoTasks(p);
    const muts = mutations(p);
    await selectTasks(p, 'M a', 'M b');
    await p.getByTestId('bulk-move').click();
    await expect(p.getByTestId('bulk-move-choice').filter({ hasText: 'Home' })).toBeVisible();
    await shot(p, 'ac19-move-menu');
    await p.getByTestId('bulk-move-choice').filter({ hasText: 'Home' }).click();
    await expect(msg(p)).toHaveText('2 tasks moved to Home');
    expect(muts).toEqual(['POST /api/tasks/bulk']);
    await expect(tasksList(p)).toHaveCount(0); // both left this list
    expect((await get(p, a.id)).listId).toBe(home.id);
    expect((await get(p, a.id)).tags).toEqual(['Alpha', 'Beta']);
    muts.length = 0;
    const reqs = requests(p, 'POST', /^\/api\/tasks\/bulk-patch$/);
    await undoBtn(p).click();
    await expect(msg(p)).toHaveText('Undone');
    expect(muts).toEqual(['POST /api/tasks/bulk-patch']);
    const sent = (reqs[0].postDataJSON() as { items: { id: number; patch: { listId: number; tags: string[] } }[] }).items;
    expect(sent.find((i) => i.id === a.id)!.patch).toEqual({ listId: mine.id, tags: ['Alpha', 'Beta'] });
    expect(sent.find((i) => i.id === b.id)!.patch).toEqual({ listId: mine.id, tags: [] });
    expect((await get(p, a.id)).listId).toBe(mine.id);
    expect((await get(p, a.id)).tags).toEqual(['Alpha', 'Beta']);
    expect((await get(p, b.id)).tags).toEqual([]);
    await expect(doneBox(p, 'M a')).toBeVisible();
    await expect(doneBox(p, 'M b')).toBeVisible();
  });

  test('AC19: Delete: the confirm names the count, Cancel changes nothing, confirm trashes all in one request, Undo restores in one request', async ({ kit }) => {
    const u = await kit.newUser('ac19d');
    const p = u.page;
    const a = await mk(p, 'D a');
    const b = await mk(p, 'D b');
    await mk(p, 'D keep');
    await gotoTasks(p);
    const muts = mutations(p);
    await selectTasks(p, 'D a', 'D b');
    await p.getByTestId('bulk-delete').click();
    const dialog = p.getByTestId('bulk-delete-dialog');
    await expect(dialog).toBeVisible();
    await expect(dialog.getByRole('heading')).toContainText('2 tasks');
    await shot(p, 'ac19-delete-confirm');
    await dialog.getByRole('button', { name: 'Cancel' }).click();
    await expect(dialog).toHaveCount(0);
    expect(muts).toEqual([]);
    await expect(count(p)).toHaveText('2 selected'); // cancel keeps the selection
    await p.getByTestId('bulk-delete').click();
    await p.getByTestId('bulk-delete-confirm').click();
    await expect(msg(p)).toHaveText('2 tasks moved to trash');
    expect(muts).toEqual(['POST /api/tasks/bulk']);
    await expect(srow(p, 'D a')).toHaveCount(0);
    await expect(srow(p, 'D keep')).toHaveCount(1);
    expect((await trashedIds(p)).sort()).toEqual([a.id, b.id].sort());
    muts.length = 0;
    await undoBtn(p).click();
    await expect(msg(p)).toHaveText('Undone');
    expect(muts).toEqual(['POST /api/trash/restore']);
    expect(await trashedIds(p)).toEqual([]);
    await expect(srow(p, 'D a')).toBeVisible();
    await expect(srow(p, 'D b')).toBeVisible();
  });

  test('AC19: the confirm of one task says "1 task"; Esc closes the confirm first, then selection mode', async ({ kit }) => {
    const u = await kit.newUser('ac19d1');
    const p = u.page;
    await mk(p, 'Only');
    await gotoTasks(p);
    await selectTasks(p, 'Only');
    await p.getByTestId('bulk-delete').click();
    await expect(p.getByTestId('bulk-delete-dialog').getByRole('heading')).toContainText('1 task');
    await p.keyboard.press('Escape');
    await expect(p.getByTestId('bulk-delete-dialog')).toHaveCount(0);
    await expect(bar(p)).toBeVisible();
    await expect(count(p)).toHaveText('1 selected');
    await p.keyboard.press('Escape');
    await expect(bar(p)).toHaveCount(0);
  });

  test('AC19: the toast closes with its Close button and "1 task" wording is singular', async ({ kit }) => {
    const u = await kit.newUser('ac19one');
    const p = u.page;
    const t = await mk(p, 'Single');
    await gotoTasks(p);
    await selectTasks(p, 'Single');
    await p.getByTestId('bulk-complete').click();
    await expect(msg(p)).toHaveText('1 task completed');
    await toast(p).getByRole('button', { name: 'Close' }).click();
    await expect(toast(p)).toHaveCount(0);
    expect((await get(p, t.id)).status).toBe('DONE');
  });

  test('AC19: a double-click on an action sends one request; the bar shows "Working…" and ignores Esc while busy', async ({ kit }) => {
    const u = await kit.newUser('ac19busy');
    const p = u.page;
    const a = await mk(p, 'B a');
    await mk(p, 'B b');
    await gotoTasks(p);
    await p.route('**/api/tasks/bulk', async (route) => {
      await new Promise((r) => setTimeout(r, 1200));
      await route.continue();
    });
    const reqs = requests(p, 'POST', /^\/api\/tasks\/bulk$/);
    await selectTasks(p, 'B a', 'B b');
    await p.getByTestId('bulk-complete').dblclick();
    await expect(p.getByTestId('bulk-busy')).toBeVisible();
    await expect(p.getByTestId('bulk-complete')).toBeDisabled();
    await expect(p.getByTestId('bulk-select-all')).toBeDisabled();
    await expect(p.getByTestId('bulk-cancel')).toBeDisabled();
    await shot(p, 'ac19-busy');
    await p.keyboard.press('Escape');
    await expect(bar(p)).toBeVisible();
    await expect(msg(p)).toHaveText('2 tasks completed');
    expect(reqs).toHaveLength(1);
    expect((await get(p, a.id)).status).toBe('DONE');
  });
});

// =====================================================================================
// AC20: all or nothing
// =====================================================================================

test.describe('AC20 all-or-nothing', () => {
  test('AC20: a teammate trashed a task: "N tasks are no longer available", nothing changes, the selection is kept, Reload recovers', async ({ kit }) => {
    kit.allowNetworkErrors = true;
    const owner = await kit.newUser('ac20a-o');
    const mate = await kit.newUser('ac20a-m');
    const team = await createList(owner.page, 'Team');
    await share(owner, team.id, mate);
    const ts = await mkMany(owner.page, 'N ', 4, { listId: team.id });
    const p = owner.page;
    await gotoTasks(p, `/lists/${team.id}`, 'Team');
    await selectTasks(p, 'N 000', 'N 001', 'N 002', 'N 003');
    // the teammate trashes two of them
    expect((await api(mate.page, 'DELETE', `/api/tasks/${ts[1].id}`)).status()).toBe(204);
    expect((await api(mate.page, 'DELETE', `/api/tasks/${ts[2].id}`)).status()).toBe(204);
    await p.getByTestId('bulk-complete').click();
    await expect(banner(p)).toContainText('2 tasks are no longer available. Reload and try again.');
    await expect(banner(p).getByRole('button', { name: 'Reload' })).toBeVisible();
    await shot(p, 'ac20-not-available');
    // nothing changed
    expect((await get(p, ts[0].id)).status).toBe('TODO');
    expect((await get(p, ts[3].id)).status).toBe('TODO');
    // the selection and the mode are kept
    await expect(bar(p)).toBeVisible();
    await expect(count(p)).toHaveText('4 selected');
    await expect(toast(p)).toHaveCount(0);
    // Reload shows the current list
    await banner(p).getByRole('button', { name: 'Reload' }).click();
    await expect(srow(p, 'N 001')).toHaveCount(0);
    await expect(srow(p, 'N 000')).toHaveCount(1);
  });

  test('AC20: one task gone gives the singular "1 task is no longer available"; a delete confirm closes on the error', async ({ kit }) => {
    kit.allowNetworkErrors = true;
    const owner = await kit.newUser('ac20b-o');
    const mate = await kit.newUser('ac20b-m');
    const team = await createList(owner.page, 'Team');
    await share(owner, team.id, mate);
    const ts = await mkMany(owner.page, 'G ', 2, { listId: team.id });
    const p = owner.page;
    await gotoTasks(p, `/lists/${team.id}`, 'Team');
    await selectTasks(p, 'G 000', 'G 001');
    expect((await api(mate.page, 'DELETE', `/api/tasks/${ts[0].id}`)).status()).toBe(204);
    await p.getByTestId('bulk-delete').click();
    await p.getByTestId('bulk-delete-confirm').click();
    await expect(banner(p)).toContainText('1 task is no longer available. Reload and try again.');
    await expect(p.getByTestId('bulk-delete-dialog')).toHaveCount(0);
    expect(await trashedIds(p)).toEqual([ts[0].id]); // still only the teammate's
    await expect(count(p)).toHaveText('2 selected');
  });

  test('AC20: I lost access to the list (removed by the owner): the bulk call fails and nothing changes', async ({ kit }) => {
    kit.allowNetworkErrors = true;
    const owner = await kit.newUser('ac20c-o');
    const mate = await kit.newUser('ac20c-m');
    const team = await createList(owner.page, 'Team');
    await share(owner, team.id, mate);
    const ts = await mkMany(owner.page, 'L ', 2, { listId: team.id });
    const p = mate.page;
    await gotoTasks(p, `/lists/${team.id}`, 'Team');
    await selectTasks(p, 'L 000', 'L 001');
    expect((await api(owner.page, 'DELETE', `/api/lists/${team.id}/members/${mate.id}`)).status()).toBe(204);
    await p.getByTestId('bulk-priority').click();
    await p.getByTestId('bulk-priority-HIGH').click();
    await expect(banner(p)).toContainText('2 tasks are no longer available. Reload and try again.');
    expect((await get(owner.page, ts[0].id)).priority).toBe('NONE');
    expect((await get(owner.page, ts[1].id)).priority).toBe('NONE');
    await expect(count(p)).toHaveText('2 selected');
  });

  test('AC20: TOO_MANY: a task already has 10 tags, Add tag changes nothing and says so; the selection is kept', async ({ kit }) => {
    kit.allowNetworkErrors = true;
    const u = await kit.newUser('ac20tm');
    const p = u.page;
    const ten = Array.from({ length: 10 }, (_, i) => `Tag${i}`);
    const full = await mk(p, 'Full', { tags: ten });
    const roomy = await mk(p, 'Roomy', { tags: ['One'] });
    await gotoTasks(p);
    await selectTasks(p, 'Full', 'Roomy');
    await p.getByTestId('bulk-add-tag').click();
    await p.getByTestId('bulk-tag-input').fill('Extra');
    await p.getByTestId('bulk-tag-apply').click();
    await expect(banner(p)).toContainText('1 task is already at the limit of 10 tags, so nothing was changed.');
    await shot(p, 'ac20-too-many');
    expect((await get(p, full.id)).tags).toHaveLength(10);
    expect((await get(p, roomy.id)).tags).toEqual(['One']);
    await expect(count(p)).toHaveText('2 selected');
    await expect(toast(p)).toHaveCount(0);
  });

  test('AC20: TAG_LIMIT: moving into a list that would pass 200 tags changes nothing', async ({ kit }) => {
    kit.allowNetworkErrors = true;
    test.setTimeout(120_000);
    const u = await kit.newUser('ac20tl');
    const p = u.page;
    const full = await createList(p, 'Full');
    for (let i = 0; i < 20; i++) {
      await mk(p, `Filler ${i}`, { listId: full.id, tags: Array.from({ length: 10 }, (_, j) => `f${i}-${j}`) });
    }
    const mover = await mk(p, 'Mover', { tags: ['brand-new-tag'] });
    await gotoTasks(p);
    await selectTasks(p, 'Mover');
    await p.getByTestId('bulk-move').click();
    await p.getByTestId('bulk-move-choice').filter({ hasText: 'Full' }).click();
    await expect(banner(p)).toContainText('A list can have at most 200 tags, so nothing was changed.');
    await shot(p, 'ac20-tag-limit');
    const after = await get(p, mover.id);
    expect(after.listId).toBe(mover.listId);
    expect(after.tags).toEqual(['brand-new-tag']);
    await expect(count(p)).toHaveText('1 selected');
    await expect(srow(p, 'Mover')).toHaveCount(1);
  });

  test('AC20: moving to a list I lost (removed from it) says the list is no longer available and changes nothing', async ({ kit }) => {
    kit.allowNetworkErrors = true;
    const owner = await kit.newUser('ac20ml-o');
    const me = await kit.newUser('ac20ml-m');
    const shared = await createList(owner.page, 'Shared');
    await share(owner, shared.id, me);
    const t = await mk(me.page, 'Wander');
    const p = me.page;
    await gotoTasks(p);
    await selectTasks(p, 'Wander');
    await p.getByTestId('bulk-move').click();
    await expect(p.getByTestId('bulk-move-choice').filter({ hasText: 'Shared' })).toBeVisible();
    expect((await api(owner.page, 'DELETE', `/api/lists/${shared.id}/members/${me.id}`)).status()).toBe(204);
    await p.getByTestId('bulk-move-choice').filter({ hasText: 'Shared' }).click();
    await expect(banner(p)).toContainText('The list you picked is no longer available. Pick another one.');
    expect((await get(p, t.id)).listId).toBe(t.listId);
    await expect(count(p)).toHaveText('1 selected');
    await expect(bar(p)).toBeVisible();
  });

  test('AC20: moving to a list that was deleted gives the same message', async ({ kit }) => {
    kit.allowNetworkErrors = true;
    const u = await kit.newUser('ac20md');
    const p = u.page;
    const doomed = await createList(p, 'Doomed');
    const t = await mk(p, 'Stay');
    await gotoTasks(p);
    await selectTasks(p, 'Stay');
    await p.getByTestId('bulk-move').click();
    expect((await api(p, 'DELETE', `/api/lists/${doomed.id}`)).status()).toBe(204);
    await p.getByTestId('bulk-move-choice').filter({ hasText: 'Doomed' }).click();
    await expect(banner(p)).toContainText('The list you picked is no longer available. Pick another one.');
    expect((await get(p, t.id)).listId).toBe(t.listId);
    await expect(count(p)).toHaveText('1 selected');
  });

  test('AC20: a network failure keeps the selection and offers Retry, which then works', async ({ kit }) => {
    kit.allowNetworkErrors = true;
    const u = await kit.newUser('ac20net');
    const p = u.page;
    const a = await mk(p, 'Net a');
    await mk(p, 'Net b');
    await gotoTasks(p);
    await selectTasks(p, 'Net a', 'Net b');
    await p.route('**/api/tasks/bulk', (route) => route.abort('failed'));
    await p.getByTestId('bulk-complete').click();
    await expect(banner(p)).toContainText("Couldn't update the tasks. Please try again.");
    await expect(count(p)).toHaveText('2 selected');
    expect((await get(p, a.id)).status).toBe('TODO');
    await p.unroute('**/api/tasks/bulk');
    await banner(p).getByRole('button', { name: 'Retry' }).click();
    await expect(msg(p)).toHaveText('2 tasks completed');
    expect((await get(p, a.id)).status).toBe('DONE');
  });

  test('AC20: undo after a teammate trashed one of the tasks fails as a whole with the "deleted or no access" toast', async ({ kit }) => {
    kit.allowNetworkErrors = true;
    const owner = await kit.newUser('ac20u-o');
    const mate = await kit.newUser('ac20u-m');
    const team = await createList(owner.page, 'Team');
    await share(owner, team.id, mate);
    const ts = await mkMany(owner.page, 'V ', 2, { listId: team.id });
    const p = owner.page;
    await gotoTasks(p, `/lists/${team.id}`, 'Team');
    await selectTasks(p, 'V 000', 'V 001');
    await p.getByTestId('bulk-priority').click();
    await p.getByTestId('bulk-priority-HIGH').click();
    await expect(msg(p)).toHaveText('Priority set on 2 tasks');
    expect((await api(mate.page, 'DELETE', `/api/tasks/${ts[0].id}`)).status()).toBe(204);
    await undoBtn(p).click();
    await expect(msg(p)).toHaveText("Couldn't undo: the task was deleted or you no longer have access");
    expect((await get(p, ts[1].id)).priority).toBe('HIGH'); // nothing was undone
  });
});

// =====================================================================================
// AC21: repeating tasks
// =====================================================================================

test.describe('AC21 bulk-completing repeating tasks', () => {
  test('AC21: one copy per repeating task (rule moves to the copy), plain tasks get none; Undo deletes untouched copies and restores the rules', async ({ kit }) => {
    const u = await kit.newUser('ac21a');
    const p = u.page;
    const r1 = await mk(p, 'Rep one', { dueDate: FAR, repeat: DAILY });
    const r2 = await mk(p, 'Rep two', { dueDate: FAR, repeat: { frequency: 'WEEKLY', weekdays: ['MON'] } });
    const plain = await mk(p, 'Plain');
    await gotoTasks(p);
    const muts = mutations(p);
    await selectTasks(p, 'Rep one', 'Rep two', 'Plain');
    await p.getByTestId('bulk-complete').click();
    await expect(msg(p)).toHaveText('3 tasks completed');
    expect(muts).toEqual(['POST /api/tasks/bulk']);
    await expect(p.getByTestId('task-next-scheduled')).toBeVisible();
    const now = await allTasks(p);
    expect(now).toHaveLength(5);
    const copy1 = now.find((t) => t.title === 'Rep one' && t.id !== r1.id)!;
    const copy2 = now.find((t) => t.title === 'Rep two' && t.id !== r2.id)!;
    expect(copy1.status).toBe('TODO');
    expect(copy1.repeat).toEqual(DAILY);
    expect(copy1.dueDate).toBe('2031-05-06');
    expect(copy2.repeat).toEqual({ frequency: 'WEEKLY', weekdays: ['MON'] });
    expect((await get(p, r1.id)).repeat).toBeNull(); // the rule moved to the copy
    expect(now.filter((t) => t.title === 'Plain')).toHaveLength(1);
    await expect(srow(p, 'Rep one')).toHaveCount(2); // original + copy on screen
    await shot(p, 'ac21-copies');
    muts.length = 0;
    const reqs = requests(p, 'POST', /^\/api\/tasks\/bulk-undo-complete$/);
    await undoBtn(p).click();
    await expect(msg(p)).toHaveText('Undone');
    expect(muts).toEqual(['POST /api/tasks/bulk-undo-complete']);
    const sent = (reqs[0].postDataJSON() as { items: { id: number; nextTaskId?: number }[] }).items;
    expect(sent.find((i) => i.id === r1.id)!.nextTaskId).toBe(copy1.id);
    expect(sent.find((i) => i.id === r2.id)!.nextTaskId).toBe(copy2.id);
    expect(sent.find((i) => i.id === plain.id)!.nextTaskId).toBeUndefined(); // a plain reopen
    expect(sent).toHaveLength(3);
    const after = await allTasks(p);
    expect(after).toHaveLength(3); // copies deleted
    expect((await get(p, r1.id)).repeat).toEqual(DAILY); // rule back
    expect((await get(p, r2.id)).repeat).toEqual({ frequency: 'WEEKLY', weekdays: ['MON'] });
    expect((await get(p, plain.id)).status).toBe('TODO');
    await expect(srow(p, 'Rep one')).toHaveCount(1);
    expect(num(`SELECT COUNT(*) FROM tasks WHERE id IN (${copy1.id}, ${copy2.id})`)).toBe(0);
  });

  test('AC21: Undo keeps a copy that was edited meanwhile ("KEPT" wording) and deletes the untouched one', async ({ kit }) => {
    const u = await kit.newUser('ac21b');
    const p = u.page;
    const r1 = await mk(p, 'Keep one', { dueDate: FAR, repeat: DAILY });
    const r2 = await mk(p, 'Keep two', { dueDate: FAR, repeat: DAILY });
    await gotoTasks(p);
    await selectTasks(p, 'Keep one', 'Keep two');
    await p.getByTestId('bulk-complete').click();
    await expect(msg(p)).toHaveText('2 tasks completed');
    const now = await allTasks(p);
    const copy1 = now.find((t) => t.title === 'Keep one' && t.id !== r1.id)!;
    const copy2 = now.find((t) => t.title === 'Keep two' && t.id !== r2.id)!;
    await json(await api(p, 'PATCH', `/api/tasks/${copy1.id}`, { description: 'edited meanwhile' }));
    await undoBtn(p).click();
    await expect(msg(p)).toHaveText(KEPT);
    expect(num(`SELECT COUNT(*) FROM tasks WHERE id = ${copy1.id}`)).toBe(1);
    expect(num(`SELECT COUNT(*) FROM tasks WHERE id = ${copy2.id}`)).toBe(0);
    expect((await get(p, r1.id)).status).toBe('TODO');
    expect((await get(p, r1.id)).repeat).toBeNull(); // kept copy keeps the rule
    expect((await get(p, r2.id)).repeat).toEqual(DAILY);
    await expect(srow(p, 'Keep one')).toHaveCount(2); // original + kept copy
    await expect(srow(p, 'Keep two')).toHaveCount(1);
    await shot(p, 'ac21-kept');
  });

  test('AC21: a repeating task that is already DONE gets no copy', async ({ kit }) => {
    const u = await kit.newUser('ac21c');
    const p = u.page;
    const done = await mk(p, 'Already', { dueDate: FAR });
    await json(await api(p, 'PATCH', `/api/tasks/${done.id}`, { status: 'DONE' }));
    await mk(p, 'Open rep', { dueDate: FAR, repeat: DAILY });
    await gotoTasks(p);
    await selectTasks(p, 'Already', 'Open rep');
    await p.getByTestId('bulk-complete').click();
    await expect(msg(p)).toHaveText('2 tasks completed');
    const now = await allTasks(p);
    expect(now.filter((t) => t.title === 'Already')).toHaveLength(1);
    expect(now.filter((t) => t.title === 'Open rep')).toHaveLength(2);
  });
});

// =====================================================================================
// Phone width and accessibility
// =====================================================================================

test.describe('phone width and accessibility', () => {
  test('phone: the actions collapse into a "More" menu', async ({ kit }) => {
    const u = await kit.newUser('phone');
    const p = u.page;
    await p.setViewportSize({ width: 390, height: 800 });
    const a = await mk(p, 'Ph a');
    await mk(p, 'Ph b');
    await gotoTasks(p);
    await enterSelect(p);
    await pick(p, 'Ph a', 'Ph b');
    await expect(p.getByTestId('bulk-more')).toBeVisible();
    await expect(p.getByTestId('bulk-complete')).toBeHidden();
    await expect(p.getByTestId('bulk-delete')).toBeHidden();
    await shot(p, 'phone-closed');
    await p.getByTestId('bulk-more').click();
    await expect(p.getByTestId('bulk-more')).toHaveAttribute('aria-expanded', 'true');
    for (const id of ['bulk-complete', 'bulk-reopen', 'bulk-status', 'bulk-priority', 'bulk-add-tag', 'bulk-remove-tag', 'bulk-move', 'bulk-delete']) {
      await expect(p.getByTestId(id), id).toBeVisible();
    }
    await shot(p, 'phone-more-open');
    await p.getByTestId('bulk-complete').click();
    await expect(msg(p)).toHaveText('2 tasks completed');
    expect((await get(p, a.id)).status).toBe('DONE');
    await shot(p, 'phone-after');
  });

  test('desktop: no "More" button, all actions visible in the bar', async ({ kit }) => {
    const u = await kit.newUser('desk');
    const p = u.page;
    await p.setViewportSize({ width: 1280, height: 800 });
    await mk(p, 'Dk a');
    await gotoTasks(p);
    await selectTasks(p, 'Dk a');
    await expect(p.getByTestId('bulk-more')).toBeHidden();
    for (const id of ['bulk-complete', 'bulk-reopen', 'bulk-status', 'bulk-priority', 'bulk-add-tag', 'bulk-remove-tag', 'bulk-move', 'bulk-delete']) {
      await expect(p.getByTestId(id), id).toBeVisible();
    }
    await shot(p, 'desktop-bar');
  });

  test('a11y: checkboxes are named "Select <title>", the count is a live status region, and the keyboard works', async ({ kit }) => {
    const u = await kit.newUser('a11y');
    const p = u.page;
    await mk(p, 'Access one');
    await mk(p, 'Access two');
    await gotoTasks(p);
    await p.getByTestId('select-mode').focus();
    await p.keyboard.press('Enter');
    await expect(bar(p)).toBeVisible();
    await expect(bar(p)).toHaveAttribute('role', 'region');
    await expect(bar(p)).toHaveAttribute('aria-label', 'Bulk actions');
    const counter = p.getByRole('status').filter({ hasText: /selected$/ });
    await expect(counter).toHaveCount(1);
    await expect(counter).toHaveText('0 selected');
    await expect(counter).toHaveAttribute('aria-live', 'polite');
    await expect(p.getByRole('checkbox', { name: 'Select Access one' })).toHaveCount(1);
    await pickBox(p, 'Access two').focus();
    await p.keyboard.press('Space');
    await expect(pickBox(p, 'Access two')).toBeChecked();
    await expect(counter).toHaveText('1 selected');
    // the busy line is announced too
    await p.route('**/api/tasks/bulk', async (route) => {
      await new Promise((r) => setTimeout(r, 600));
      await route.continue();
    });
    await p.getByTestId('bulk-complete').click();
    await expect(p.getByRole('status').filter({ hasText: 'Working…' })).toBeVisible();
    await expect(msg(p)).toHaveText('1 task completed');
    // the toast region is announced
    await expect(toastRegion(p)).toBeVisible();
  });
});

// =====================================================================================
// AC22: API contract, 0.17.0
// =====================================================================================

test.describe('AC22 API: POST /api/tasks/bulk', () => {
  test('AC22: success shape BulkResult, request order, count; every action', async ({ kit }) => {
    const u = await kit.newUser('api1');
    const p = u.page;
    const home = await createList(p, 'Home');
    const a = await mk(p, 'a', { tags: ['Old'] });
    const b = await mk(p, 'b', { status: 'IN_PROGRESS', priority: 'LOW' });
    const c = await mk(p, 'c', { status: 'DONE' });
    const ids = [c.id, a.id, b.id]; // an order that is not the creation order
    let r = await json<BulkResult>(await bulk(p, { ids, action: 'SET_PRIORITY', value: 'HIGH' }));
    expectBulkResult(r, 3);
    expect(r.tasks.map((t) => t.id)).toEqual(ids);
    expect(r.tasks.every((t) => t.priority === 'HIGH' && t.nextTask === null)).toBe(true);
    r = await json<BulkResult>(await bulk(p, { ids, action: 'SET_STATUS', value: 'IN_PROGRESS' }));
    expect(r.tasks.every((t) => t.status === 'IN_PROGRESS')).toBe(true);
    r = await json<BulkResult>(await bulk(p, { ids, action: 'COMPLETE' }));
    expectBulkResult(r, 3);
    expect(r.tasks.every((t) => t.status === 'DONE' && t.done)).toBe(true);
    r = await json<BulkResult>(await bulk(p, { ids, action: 'REOPEN' }));
    expect(r.tasks.every((t) => t.status === 'TODO' && !t.done)).toBe(true);
    r = await json<BulkResult>(await bulk(p, { ids, action: 'ADD_TAG', value: 'New' }));
    expect(r.tasks.find((t) => t.id === a.id)!.tags).toEqual(['New', 'Old']);
    expect(r.tasks.find((t) => t.id === b.id)!.tags).toEqual(['New']);
    r = await json<BulkResult>(await bulk(p, { ids, action: 'REMOVE_TAG', value: 'new' })); // case-insensitive
    expect(r.tasks.every((t) => !t.tags.includes('New'))).toBe(true);
    expect(r.tasks.find((t) => t.id === a.id)!.tags).toEqual(['Old']);
    r = await json<BulkResult>(await bulk(p, { ids, action: 'MOVE', value: home.id }));
    expect(r.tasks.every((t) => t.listId === home.id)).toBe(true);
    expect(r.tasks.find((t) => t.id === a.id)!.tags).toEqual(['Old']);
    r = await json<BulkResult>(await bulk(p, { ids, action: 'DELETE' }));
    expect(Object.keys(r).sort()).toEqual(['count', 'tasks']);
    expect(r.count).toBe(3);
    expect(r.tasks).toEqual([]); // DELETE: tasks is []
    expect((await trashedIds(p)).sort()).toEqual([...ids].sort());
  });

  test('AC22: tasks already in the wanted state are returned unchanged (no updatedAt change, no copy)', async ({ kit }) => {
    const u = await kit.newUser('api2');
    const p = u.page;
    const a = await mk(p, 'a', { priority: 'HIGH' });
    const d = await mk(p, 'd', { status: 'DONE', dueDate: FAR });
    const before = [await get(p, a.id), await get(p, d.id)];
    const r1 = await json<BulkResult>(await bulk(p, { ids: [a.id], action: 'SET_PRIORITY', value: 'HIGH' }));
    expect(r1.tasks[0].updatedAt).toBe(before[0].updatedAt);
    const r2 = await json<BulkResult>(await bulk(p, { ids: [d.id], action: 'COMPLETE' }));
    expect(r2.tasks[0].updatedAt).toBe(before[1].updatedAt);
    expect(r2.tasks[0].nextTask).toBeNull();
    const r3 = await json<BulkResult>(await bulk(p, { ids: [a.id], action: 'REOPEN' }));
    expect(r3.tasks[0].updatedAt).toBe(before[0].updatedAt);
    expect(await allTasks(p)).toHaveLength(2);
  });

  test('AC22: ADD_TAG is a no-op where the tag exists (any case); repeating COMPLETE returns nextTask paired with its task', async ({ kit }) => {
    const u = await kit.newUser('api3');
    const p = u.page;
    const a = await mk(p, 'a', { tags: ['Work'] });
    const rep = await mk(p, 'rep', { dueDate: FAR, repeat: DAILY });
    const r = await json<BulkResult>(await bulk(p, { ids: [a.id], action: 'ADD_TAG', value: 'work' }));
    expect(r.tasks[0].tags).toEqual(['Work']);
    const c = await json<BulkResult>(await bulk(p, { ids: [a.id, rep.id], action: 'COMPLETE' }));
    expectBulkResult(c, 2);
    expect(c.tasks[0].nextTask).toBeNull();
    expect(c.tasks[1].id).toBe(rep.id);
    expect(c.tasks[1].nextTask).not.toBeNull();
    expect(c.tasks[1].nextTask!.title).toBe('rep');
    expect(c.tasks[1].nextTask!.status).toBe('TODO');
    expect(c.tasks[1].nextTask!.repeat).toEqual(DAILY);
    expect(Object.keys(c.tasks[1].nextTask!).sort()).toEqual([...TASK_KEYS].sort());
  });

  test('AC22: 201 ids gives 400 (ids / OUT_OF_RANGE); 0 ids too; 200 ids is accepted', async ({ kit }) => {
    kit.allowNetworkErrors = true;
    const u = await kit.newUser('api4');
    const p = u.page;
    const t = await mk(p, 'real');
    const many = Array.from({ length: 201 }, (_, i) => 900_000_000 + i);
    let b = await problem(await bulk(p, { ids: many, action: 'COMPLETE' }), 400, 'VALIDATION_FAILED');
    expect(b.errors).toEqual([expect.objectContaining({ field: 'ids', code: 'OUT_OF_RANGE' })]);
    b = await problem(await bulk(p, { ids: [], action: 'COMPLETE' }), 400, 'VALIDATION_FAILED');
    expect(b.errors).toEqual([expect.objectContaining({ field: 'ids', code: 'OUT_OF_RANGE' })]);
    b = await problem(await bulk(p, { action: 'COMPLETE' }), 400, 'VALIDATION_FAILED');
    expect(b.errors).toEqual([expect.objectContaining({ field: 'ids', code: 'REQUIRED' })]);
    // 200 ids (non-existent) is past validation: 404 with all 200 in failedIds
    const ok200 = Array.from({ length: 200 }, (_, i) => 900_000_000 + i);
    const nf = await problem(await bulk(p, { ids: ok200, action: 'COMPLETE' }), 404, 'NOT_FOUND');
    expect(nf.failedIds).toEqual(ok200);
    expect((await get(p, t.id)).status).toBe('TODO');
  });

  test('AC22: duplicate ids give DUPLICATE; an unknown action / bad value type is MALFORMED_REQUEST; missing or bad values are field errors', async ({ kit }) => {
    kit.allowNetworkErrors = true;
    const u = await kit.newUser('api5');
    const p = u.page;
    const a = await mk(p, 'a');
    let b = await problem(await bulk(p, { ids: [a.id, a.id], action: 'COMPLETE' }), 400, 'VALIDATION_FAILED');
    expect(b.errors).toEqual([expect.objectContaining({ field: 'ids', code: 'DUPLICATE' })]);
    await problem(await bulk(p, { ids: [a.id], action: 'NUKE' }), 400, 'MALFORMED_REQUEST');
    await problem(await bulk(p, { ids: [a.id] }), 400, 'MALFORMED_REQUEST');
    await problem(await bulk(p, { ids: [a.id], action: 'SET_STATUS', value: 'BOGUS' }), 400, 'MALFORMED_REQUEST');
    await problem(await bulk(p, { ids: [a.id], action: 'MOVE', value: 'abc' }), 400, 'MALFORMED_REQUEST');
    await problem(await bulk(p, { ids: 'x', action: 'COMPLETE' }), 400, 'MALFORMED_REQUEST');
    b = await problem(await bulk(p, { ids: [a.id], action: 'SET_STATUS' }), 400, 'VALIDATION_FAILED');
    expect(b.errors).toEqual([expect.objectContaining({ field: 'value', code: 'REQUIRED' })]);
    b = await problem(await bulk(p, { ids: [a.id], action: 'ADD_TAG', value: '   ' }), 400, 'VALIDATION_FAILED');
    expect(b.errors?.[0]).toEqual(expect.objectContaining({ field: 'value' }));
    b = await problem(await bulk(p, { ids: [a.id], action: 'ADD_TAG', value: 'x'.repeat(31) }), 400, 'VALIDATION_FAILED');
    expect(b.errors).toEqual([expect.objectContaining({ field: 'value', code: 'TOO_LONG' })]);
    b = await problem(await bulk(p, { ids: [a.id], action: 'ADD_TAG', value: 'bad<tag>' }), 400, 'VALIDATION_FAILED');
    expect(b.errors).toEqual([expect.objectContaining({ field: 'value', code: 'INVALID_CHARACTERS' })]);
    // a value on an action that takes none is ignored
    expect((await bulk(p, { ids: [a.id], action: 'COMPLETE', value: 'whatever' })).status()).toBe(200);
  });

  test('AC22: ids from different lists give 400 ids / MIXED_LISTS and nothing changes', async ({ kit }) => {
    kit.allowNetworkErrors = true;
    const u = await kit.newUser('api6');
    const p = u.page;
    const home = await createList(p, 'Home');
    const a = await mk(p, 'a');
    const b = await mk(p, 'b', { listId: home.id });
    const r = await problem(await bulk(p, { ids: [a.id, b.id], action: 'COMPLETE' }), 400, 'VALIDATION_FAILED');
    expect(r.errors).toEqual([expect.objectContaining({ field: 'ids', code: 'MIXED_LISTS' })]);
    expect(r.failedIds).toBeUndefined();
    expect((await get(p, a.id)).status).toBe('TODO');
    expect((await get(p, b.id)).status).toBe('TODO');
  });

  test('AC22: an id from another user (or missing, or trashed) is 404 NOT_FOUND with every failed id, ascending, never 403; the body does not tell them apart', async ({ kit }) => {
    kit.allowNetworkErrors = true;
    const me = await kit.newUser('api7-me');
    const other = await kit.newUser('api7-other');
    const mine = await mk(me.page, 'mine');
    const theirs = await mk(other.page, 'theirs');
    const gone = await mk(me.page, 'gone');
    expect((await api(me.page, 'DELETE', `/api/tasks/${gone.id}`)).status()).toBe(204);
    const missing = 999_999_999;
    const res = await bulk(me.page, { ids: [missing, mine.id, theirs.id, gone.id], action: 'COMPLETE' });
    expect(res.status()).toBe(404);
    const body = await problem(res, 404, 'NOT_FOUND');
    expect(body.failedIds).toEqual([theirs.id, gone.id, missing].sort((x, y) => x - y));
    // a single foreign id and a single missing id give the same body shape
    const f = await problem(await bulk(me.page, { ids: [theirs.id], action: 'COMPLETE' }), 404, 'NOT_FOUND');
    const m = await problem(await bulk(me.page, { ids: [missing], action: 'COMPLETE' }), 404, 'NOT_FOUND');
    expect({ ...f, failedIds: 0, instance: 0 }).toEqual({ ...m, failedIds: 0, instance: 0 });
    expect(f.failedIds).toEqual([theirs.id]);
    expect(m.failedIds).toEqual([missing]);
    // nothing changed, the foreign task is untouched
    expect((await get(me.page, mine.id)).status).toBe('TODO');
    expect((await get(other.page, theirs.id)).status).toBe('TODO');
    // 404 is checked before MIXED_LISTS (never reveals anything about tasks I cannot see)
    const home = await createList(me.page, 'Home');
    const inHome = await mk(me.page, 'inHome', { listId: home.id });
    await problem(await bulk(me.page, { ids: [mine.id, inHome.id, theirs.id], action: 'COMPLETE' }), 404, 'NOT_FOUND');
  });

  test('AC22: MOVE to a list that is not mine is 404 LIST_NOT_FOUND; TOO_MANY carries failedIds; TAG_LIMIT has none', async ({ kit }) => {
    kit.allowNetworkErrors = true;
    test.setTimeout(120_000);
    const me = await kit.newUser('api8-me');
    const other = await kit.newUser('api8-other');
    const theirs = await personal(other.page);
    const a = await mk(me.page, 'a');
    await problem(await bulk(me.page, { ids: [a.id], action: 'MOVE', value: theirs.id }), 404, 'LIST_NOT_FOUND');
    await problem(await bulk(me.page, { ids: [a.id], action: 'MOVE', value: 999_999_999 }), 404, 'LIST_NOT_FOUND');
    // TOO_MANY
    const full = await mk(me.page, 'full', { tags: Array.from({ length: 10 }, (_, i) => `T${i}`) });
    const full2 = await mk(me.page, 'full2', { tags: Array.from({ length: 10 }, (_, i) => `U${i}`) });
    const tm = await problem(await bulk(me.page, { ids: [full2.id, a.id, full.id], action: 'ADD_TAG', value: 'extra' }), 400, 'VALIDATION_FAILED');
    expect(tm.errors).toEqual([expect.objectContaining({ field: 'tags', code: 'TOO_MANY' })]);
    expect(tm.failedIds).toEqual([full.id, full2.id].sort((x, y) => x - y));
    expect((await get(me.page, a.id)).tags).toEqual([]);
    // an existing tag on a full task is a no-op, not an error
    expect((await bulk(me.page, { ids: [full.id], action: 'ADD_TAG', value: 'T3' })).status()).toBe(200);
    // TAG_LIMIT
    const big = await createList(me.page, 'Big');
    for (let i = 0; i < 20; i++) await mk(me.page, `f${i}`, { listId: big.id, tags: Array.from({ length: 10 }, (_, j) => `g${i}-${j}`) });
    const mover = await mk(me.page, 'mover', { tags: ['zzz'] });
    const tl = await problem(await bulk(me.page, { ids: [mover.id], action: 'MOVE', value: big.id }), 400, 'VALIDATION_FAILED');
    expect(tl.errors).toEqual([expect.objectContaining({ field: 'tags', code: 'TAG_LIMIT' })]);
    expect(tl.failedIds).toBeUndefined();
    expect((await get(me.page, mover.id)).listId).toBe(mover.listId);
    // ADD_TAG that would pass 200 tags in a list
    const tl2 = await problem(await bulk(me.page, { ids: [(await mk(me.page, 'g', { listId: big.id })).id], action: 'ADD_TAG', value: 'one-too-many' }), 400, 'VALIDATION_FAILED');
    expect(tl2.errors).toEqual([expect.objectContaining({ field: 'tags', code: 'TAG_LIMIT' })]);
  });

  test('AC22: CSRF 403 (CSRF_INVALID) and 401 without a session, on all four new endpoints', async ({ kit }) => {
    kit.allowNetworkErrors = true;
    const u = await kit.newUser('api9');
    const p = u.page;
    const a = await mk(p, 'a');
    const bodies: [string, unknown][] = [
      ['/api/tasks/bulk', { ids: [a.id], action: 'COMPLETE' }],
      ['/api/tasks/bulk-patch', { items: [{ id: a.id, patch: { priority: 'LOW' } }] }],
      ['/api/tasks/bulk-undo-complete', { items: [{ id: a.id }] }],
      ['/api/trash/restore', { ids: [a.id] }],
    ];
    for (const [path, data] of bodies) {
      await problem(await p.request.post(path, { data }), 403, 'CSRF_INVALID'); // session, no CSRF header
      expect((await p.request.post(path, { data, headers: { 'X-XSRF-TOKEN': 'wrong' } })).status(), `${path} wrong token`).toBe(403);
      const anon = await pwRequest.newContext({ baseURL: BASE });
      const first = await anon.get('/api/auth/me'); // gets an XSRF cookie, no session
      expect(first.status()).toBe(401);
      const token = (await anon.storageState()).cookies.find((c) => c.name === 'XSRF-TOKEN')?.value;
      const res = await anon.post(path, { data, headers: token ? { 'X-XSRF-TOKEN': token } : {} });
      await problem(res, 401, 'UNAUTHENTICATED');
      const noToken = await anon.post(path, { data });
      expect([401, 403], `${path} anonymous, no token`).toContain(noToken.status());
      await anon.dispose();
    }
    expect((await get(p, a.id)).status).toBe('TODO');
  });

  test('AC22: a task id belonging to another user never gives 403 on any of the four endpoints', async ({ kit }) => {
    kit.allowNetworkErrors = true;
    const me = await kit.newUser('api10-me');
    const other = await kit.newUser('api10-o');
    const theirs = await mk(other.page, 'theirs');
    const p = me.page;
    for (const [path, data] of [
      ['/api/tasks/bulk', { ids: [theirs.id], action: 'COMPLETE' }],
      ['/api/tasks/bulk-patch', { items: [{ id: theirs.id, patch: { priority: 'LOW' } }] }],
      ['/api/tasks/bulk-undo-complete', { items: [{ id: theirs.id }] }],
      ['/api/trash/restore', { ids: [theirs.id] }],
    ] as [string, unknown][]) {
      const b = await problem(await api(p, 'POST', path, data), 404, 'NOT_FOUND');
      expect(b.failedIds, path).toEqual([theirs.id]);
    }
    expect((await get(other.page, theirs.id)).priority).toBe('NONE');
  });
});

test.describe('AC22 API: bulk-patch, bulk-undo-complete, trash/restore', () => {
  test('bulk-patch: per-task patches, BulkResult shape in request order; mixed lists, duplicates, empty and 201 items are refused', async ({ kit }) => {
    kit.allowNetworkErrors = true;
    const u = await kit.newUser('bp1');
    const p = u.page;
    const home = await createList(p, 'Home');
    const mine = await personal(p);
    const a = await mk(p, 'a', { tags: ['X'] });
    const b = await mk(p, 'b');
    const h = await mk(p, 'h', { listId: home.id });
    const r = await json<BulkResult>(await api(p, 'POST', '/api/tasks/bulk-patch', { items: [{ id: b.id, patch: { priority: 'HIGH' } }, { id: a.id, patch: { priority: 'LOW', tags: ['Y'] } }] }));
    expectBulkResult(r, 2);
    expect(r.tasks.map((t) => t.id)).toEqual([b.id, a.id]);
    expect(r.tasks[0].priority).toBe('HIGH');
    expect(r.tasks[1].tags).toEqual(['Y']);
    // move + tags
    const mv = await json<BulkResult>(await api(p, 'POST', '/api/tasks/bulk-patch', { items: [{ id: a.id, patch: { listId: home.id, tags: ['Y'] } }] }));
    expect(mv.tasks[0].listId).toBe(home.id);
    // MIXED_LISTS: a is in Home now, b in personal
    const mixed = await problem(await api(p, 'POST', '/api/tasks/bulk-patch', { items: [{ id: a.id, patch: { priority: 'NONE' } }, { id: b.id, patch: { priority: 'NONE' } }] }), 400, 'VALIDATION_FAILED');
    expect(mixed.errors).toEqual([expect.objectContaining({ field: 'items', code: 'MIXED_LISTS' })]);
    expect((await get(p, b.id)).priority).toBe('HIGH');
    const dup = await problem(await api(p, 'POST', '/api/tasks/bulk-patch', { items: [{ id: a.id, patch: { priority: 'NONE' } }, { id: a.id, patch: { priority: 'LOW' } }] }), 400, 'VALIDATION_FAILED');
    expect(dup.errors).toEqual([expect.objectContaining({ field: 'items', code: 'DUPLICATE' })]);
    const empty = await problem(await api(p, 'POST', '/api/tasks/bulk-patch', { items: [] }), 400, 'VALIDATION_FAILED');
    expect(empty.errors).toEqual([expect.objectContaining({ field: 'items', code: 'OUT_OF_RANGE' })]);
    const big = Array.from({ length: 201 }, (_, i) => ({ id: 800_000_000 + i, patch: { priority: 'LOW' } }));
    const tooBig = await problem(await api(p, 'POST', '/api/tasks/bulk-patch', { items: big }), 400, 'VALIDATION_FAILED');
    expect(tooBig.errors).toEqual([expect.objectContaining({ field: 'items', code: 'OUT_OF_RANGE' })]);
    await problem(await api(p, 'POST', '/api/tasks/bulk-patch', { items: [{ id: h.id, patch: { listId: 999_999_999 } }] }), 404, 'LIST_NOT_FOUND');
    const nf = await problem(await api(p, 'POST', '/api/tasks/bulk-patch', { items: [{ id: h.id, patch: { priority: 'LOW' } }, { id: 999_999_998, patch: { priority: 'LOW' } }] }), 404, 'NOT_FOUND');
    expect(nf.failedIds).toEqual([999_999_998]);
    expect((await get(p, h.id)).priority).toBe('NONE');
    expect(mine.id).not.toBe(home.id);
  });

  test('bulk-patch: an item with an empty patch or an invalid patch value is a 400 and nothing changes', async ({ kit }) => {
    kit.allowNetworkErrors = true;
    const u = await kit.newUser('bp2');
    const p = u.page;
    const a = await mk(p, 'a');
    const b = await mk(p, 'b');
    const res = await api(p, 'POST', '/api/tasks/bulk-patch', { items: [{ id: a.id, patch: { priority: 'LOW' } }, { id: b.id, patch: { title: '' } }] });
    expect(res.status(), await res.text()).toBe(400);
    expect((await get(p, a.id)).priority).toBe('NONE');
    const none = await api(p, 'POST', '/api/tasks/bulk-patch', { items: [{ id: a.id, patch: {} }] });
    expect(none.status(), await none.text()).toBe(400);
    await problem(await api(p, 'POST', '/api/tasks/bulk-patch', { items: [{ id: a.id }] }), 400, 'VALIDATION_FAILED').catch(async () => {
      // an item without a patch may also be a MALFORMED_REQUEST; either is a 400 problem
      const r = await api(p, 'POST', '/api/tasks/bulk-patch', { items: [{ id: a.id }] });
      expect(r.status()).toBe(400);
    });
  });

  test('bulk-undo-complete: results in request order with outcomes DELETED / KEPT / GONE / NONE; tasks may span lists', async ({ kit }) => {
    kit.allowNetworkErrors = true;
    const u = await kit.newUser('buc');
    const p = u.page;
    const home = await createList(p, 'Home');
    const r1 = await mk(p, 'r1', { dueDate: FAR, repeat: DAILY });
    const r2 = await mk(p, 'r2', { dueDate: FAR, repeat: DAILY });
    const r3 = await mk(p, 'r3', { dueDate: FAR, repeat: DAILY });
    const plain = await mk(p, 'plain', { listId: home.id, status: 'IN_PROGRESS' });
    const done = await json<BulkResult>(await bulk(p, { ids: [r1.id, r2.id, r3.id], action: 'COMPLETE' }));
    const [n1, n2, n3] = done.tasks.map((t) => t.nextTask!);
    await json(await bulk(p, { ids: [plain.id], action: 'COMPLETE' }));
    await json(await api(p, 'PATCH', `/api/tasks/${n2.id}`, { description: 'changed' })); // -> KEPT
    expect((await api(p, 'DELETE', `/api/tasks/${n3.id}`)).status()).toBe(204);
    expect((await api(p, 'DELETE', `/api/trash/${n3.id}`)).status()).toBe(204); // deleted for good -> GONE
    const res = await json<{ results: Task[] }>(
      await api(p, 'POST', '/api/tasks/bulk-undo-complete', {
        items: [{ id: plain.id, status: 'IN_PROGRESS' }, { id: r1.id, nextTaskId: n1.id }, { id: r2.id, nextTaskId: n2.id }, { id: r3.id, nextTaskId: n3.id }],
      }),
    );
    expect(Object.keys(res)).toEqual(['results']);
    expect(res.results.map((r) => r.id)).toEqual([plain.id, r1.id, r2.id, r3.id]);
    expect(res.results.map((r) => r.nextTaskOutcome)).toEqual(['NONE', 'DELETED', 'KEPT', 'GONE']);
    for (const r of res.results) expect(Object.keys(r).sort()).toEqual([...TASK_KEYS, 'nextTaskOutcome'].sort());
    expect(res.results[0].status).toBe('IN_PROGRESS');
    expect(res.results[1].repeat).toEqual(DAILY);
    expect(res.results[2].repeat).toBeNull();
    // errors
    const dup = await problem(await api(p, 'POST', '/api/tasks/bulk-undo-complete', { items: [{ id: r1.id }, { id: r1.id }] }), 400, 'VALIDATION_FAILED');
    expect(dup.errors).toEqual([expect.objectContaining({ field: 'items', code: 'DUPLICATE' })]);
    const nf = await problem(await api(p, 'POST', '/api/tasks/bulk-undo-complete', { items: [{ id: r1.id }, { id: 999_999_997 }] }), 404, 'NOT_FOUND');
    expect(nf.failedIds).toEqual([999_999_997]);
    await problem(await api(p, 'POST', '/api/tasks/bulk-undo-complete', { items: [] }), 400, 'VALIDATION_FAILED');
    const bad = await api(p, 'POST', '/api/tasks/bulk-undo-complete', { items: [{ id: r1.id, status: 'DONE' }] });
    expect(bad.status(), await bad.text()).toBe(400);
  });

  test('trash/restore: restores many (across lists) in request order as Task[]; duplicates, empty, 201 refused; a task that is not trashed is 404 with failedIds', async ({ kit }) => {
    kit.allowNetworkErrors = true;
    const u = await kit.newUser('rs');
    const p = u.page;
    const home = await createList(p, 'Home');
    const a = await mk(p, 'a', { tags: ['Keep'] });
    const b = await mk(p, 'b', { listId: home.id });
    const live = await mk(p, 'live');
    expect((await api(p, 'DELETE', `/api/tasks/${a.id}`)).status()).toBe(204);
    expect((await api(p, 'DELETE', `/api/tasks/${b.id}`)).status()).toBe(204);
    // 404 first: nothing is restored when one is not trashed
    const nf = await problem(await api(p, 'POST', '/api/trash/restore', { ids: [a.id, live.id, b.id] }), 404, 'NOT_FOUND');
    expect(nf.failedIds).toEqual([live.id]);
    expect((await trashedIds(p)).sort()).toEqual([a.id, b.id].sort());
    const dup = await problem(await api(p, 'POST', '/api/trash/restore', { ids: [a.id, a.id] }), 400, 'VALIDATION_FAILED');
    expect(dup.errors).toEqual([expect.objectContaining({ field: 'ids', code: 'DUPLICATE' })]);
    const empty = await problem(await api(p, 'POST', '/api/trash/restore', { ids: [] }), 400, 'VALIDATION_FAILED');
    expect(empty.errors).toEqual([expect.objectContaining({ field: 'ids', code: 'OUT_OF_RANGE' })]);
    const big = await problem(await api(p, 'POST', '/api/trash/restore', { ids: Array.from({ length: 201 }, (_, i) => 700_000_000 + i) }), 400, 'VALIDATION_FAILED');
    expect(big.errors).toEqual([expect.objectContaining({ field: 'ids', code: 'OUT_OF_RANGE' })]);
    const res = await json<{ tasks: Task[] }>(await api(p, 'POST', '/api/trash/restore', { ids: [b.id, a.id] }));
    expect(Object.keys(res)).toEqual(['tasks']);
    expect(res.tasks.map((t) => t.id)).toEqual([b.id, a.id]);
    for (const t of res.tasks) expect(Object.keys(t).sort()).toEqual([...TASK_KEYS].sort());
    expect(res.tasks[1].tags).toEqual(['Keep']);
    expect(await trashedIds(p)).toEqual([]);
    // a second restore is a 404 (not trashed any more)
    const again = await problem(await api(p, 'POST', '/api/trash/restore', { ids: [a.id] }), 404, 'NOT_FOUND');
    expect(again.failedIds).toEqual([a.id]);
  });
});
