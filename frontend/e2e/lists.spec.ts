import { APIResponse, Browser, expect, Locator, Page, Request, Response, test as base } from '@playwright/test';
import { execFileSync } from 'node:child_process';

/*
 * E2E for specs/sharing-team-lists.md Part A (T3a: Lists, AC1-AC17), contract 0.12.0.
 * AC13 (concurrency) is in lists-stress.spec.ts, AC16 (10k tasks timing) in lists-perf.spec.ts (E2E_PERF=1).
 * AC15 is also covered by rerunning tasks*.spec.ts (they now run on "My tasks").
 * Needs `docker compose up -d db oidc` (DB checks use `docker compose exec db mysql`; AC1 uses the fake Google).
 * Every test fails on browser console errors, page errors or any 5xx from /api. Tests that deliberately fail
 * requests allow only the browser's own network log lines.
 */

const RUN = `${Date.now()}-${Math.floor(Math.random() * 1e6)}`;
const SHOTS = '../test-results/lists';
const PASSWORD = 'password123';
const BASE = process.env['E2E_BASE_URL'] ?? 'http://localhost:4200';
const OIDC = 'http://localhost:8090/default';
let counter = 0;
function uid(): string {
  counter += 1;
  return `${RUN}-${counter}`;
}
function uniqueEmail(tag: string): string {
  return `qa-t3a-${tag}-${uid()}@example.com`;
}

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
  createdAt: string;
  updatedAt: string;
}
interface TaskPage {
  items: Task[];
  total: number;
  nextCursor: string | null;
}
interface TaskList {
  id: number;
  name: string;
  personal: boolean;
}

// 0.13.0 (T3b): Task gained addedBy, TaskList gained role and memberCount.
const TASK_KEYS = ['addedBy', 'createdAt', 'description', 'done', 'dueDate', 'dueTime', 'id', 'listId', 'myReminder', 'myReminderPassed', 'priority', 'repeat', 'status', 'tags', 'title', 'updatedAt']; // 0.14.0 (T4a): dueTime, myReminder, myReminderPassed; 0.15.0 (T4b): repeat
const LIST_KEYS = ['id', 'memberCount', 'name', 'personal', 'role'];

interface Watch {
  consoleErrors: string[];
  pageErrors: string[];
  serverErrors: string[];
  allowNetworkErrors: boolean;
}

const NETWORK_LOG = [
  /^Failed to load resource: net::ERR_FAILED/,
  /^Failed to load resource: the server responded with a status of (400|401|404|409|500|503)/,
];
/** Expected 4xx answers (404 LIST_NOT_FOUND, 409, 400) are logged by Chrome; they are not app errors. */
const EXPECTED_4XX = /^Failed to load resource: the server responded with a status of (400|401|404|409)/;

function watchPage(page: Page, w: Watch) {
  page.on('console', (msg) => {
    if (msg.type() === 'error') w.consoleErrors.push(msg.text());
  });
  page.on('pageerror', (err) => w.pageErrors.push(err.message));
  page.on('response', (r: Response) => {
    if (r.url().includes('/api/') && r.status() >= 500 && !r.headers()['x-qa-fake']) {
      w.serverErrors.push(`${r.status()} ${r.request().method()} ${r.url()}`);
    }
  });
}

function checkWatch(w: Watch, label = 'page') {
  const errors = w.consoleErrors.filter(
    (e) => !EXPECTED_4XX.test(e) && !(w.allowNetworkErrors && NETWORK_LOG.some((r) => r.test(e))),
  );
  expect(errors, `${label}: browser console errors`).toEqual([]);
  expect(w.pageErrors, `${label}: uncaught page errors`).toEqual([]);
  expect(w.serverErrors, `${label}: 5xx from the API`).toEqual([]);
}

const test = base.extend<{ watch: Watch }>({
  watch: [
    async ({ page }, use) => {
      const w: Watch = { consoleErrors: [], pageErrors: [], serverErrors: [], allowNetworkErrors: false };
      watchPage(page, w);
      await use(w);
      checkWatch(w);
    },
    { auto: true },
  ],
});

// ---------- helpers ----------

function sql(query: string): string {
  return execFileSync(
    'docker',
    ['compose', 'exec', '-T', 'db', 'mysql', '-utaskapp', '-ptaskapp', 'taskapp', '-N', '-B', '-e', query],
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

async function signUp(page: Page, name = 'QA'): Promise<{ email: string; id: number; displayName: string }> {
  const email = uniqueEmail(name.replace(/\W/g, ''));
  const displayName = `${name} ${uid()}`;
  const res = await page.request.post('/api/auth/register', {
    data: { displayName, email, password: PASSWORD },
    headers: { 'X-XSRF-TOKEN': await xsrf(page) },
  });
  expect(res.status(), await res.text()).toBe(201);
  return { email, displayName, id: ((await res.json()) as { id: number }).id };
}

async function lists(page: Page): Promise<TaskList[]> {
  const r = await page.request.get('/api/lists');
  expect(r.status()).toBe(200);
  const body = (await r.json()) as TaskList[];
  for (const l of body) expect(Object.keys(l).sort()).toEqual(LIST_KEYS);
  return body;
}
async function personal(page: Page): Promise<TaskList> {
  return (await lists(page)).find((l) => l.personal)!;
}
async function createList(page: Page, name: string): Promise<TaskList> {
  const r = await api(page, 'POST', '/api/lists', { name });
  expect(r.status(), await r.text()).toBe(201);
  const l = (await r.json()) as TaskList;
  expect(r.headers()['location']).toBe(`/api/lists/${l.id}`);
  return l;
}
async function createTask(page: Page, title: string, listId?: number, tags?: string[]): Promise<Task> {
  const r = await api(page, 'POST', '/api/tasks', listId === undefined ? { title } : { title, listId });
  expect(r.status(), await r.text()).toBe(201);
  let t = (await r.json()) as Task;
  expect(Object.keys(t).sort()).toEqual(TASK_KEYS);
  if (tags) t = await putTask(page, t, { tags });
  return t;
}
async function putTask(page: Page, t: Task, extra: Record<string, unknown>): Promise<Task> {
  const r = await api(page, 'PUT', `/api/tasks/${t.id}`, {
    title: t.title,
    description: t.description,
    dueDate: t.dueDate,
    ...extra,
  });
  expect(r.status(), await r.text()).toBe(200);
  return (await r.json()) as Task;
}
async function getTask(page: Page, id: number): Promise<Task> {
  const r = await page.request.get(`/api/tasks/${id}`);
  expect(r.status()).toBe(200);
  return (await r.json()) as Task;
}
async function tasksPage(page: Page, query: string): Promise<TaskPage> {
  const r = await page.request.get(`/api/tasks${query}`);
  expect(r.status(), await r.text()).toBe(200);
  return (await r.json()) as TaskPage;
}
async function tagNames(page: Page, listId?: number): Promise<string[]> {
  const r = await page.request.get(listId === undefined ? '/api/tags' : `/api/tags?list=${listId}`);
  expect(r.status()).toBe(200);
  return ((await r.json()) as { name: string }[]).map((t) => t.name);
}
async function expectProblem(res: APIResponse, status: number, code: string, detail?: string) {
  expect(res.status()).toBe(status);
  expect(res.headers()['content-type']).toContain('application/problem+json');
  const body = await res.json();
  expect(body.code).toBe(code);
  expect(body.status).toBe(status);
  if (detail) expect(body.detail).toBe(detail);
  return body;
}

function picker(page: Page): Locator {
  return page.getByRole('navigation', { name: 'Lists' });
}
function pickerLinks(page: Page): Locator {
  return picker(page).getByRole('link');
}
function phoneSelect(page: Page): Locator {
  return page.locator('#list-picker-select');
}
function heading(page: Page): Locator {
  return page.locator('h1.list-header__name');
}
function menuItem(page: Page, name: 'Rename' | 'Delete'): Locator {
  return page.locator('.list-menu__items').getByRole('button', { name, exact: true });
}
function menuToggle(page: Page): Locator {
  return page.getByRole('button', { name: 'List actions' });
}
function listTasks(page: Page): Locator {
  return page.getByRole('list', { name: 'Tasks' });
}
function row(page: Page, title: string): Locator {
  return listTasks(page)
    .getByRole('listitem')
    .filter({ has: page.getByRole('checkbox', { name: `Done: ${title}`, exact: true }) });
}
async function titles(page: Page): Promise<string[]> {
  const labels = await listTasks(page)
    .getByRole('checkbox')
    .evaluateAll((els) => els.map((e) => e.getAttribute('aria-label') ?? ''));
  return labels.map((l) => l.replace(/^Done: /, ''));
}
function footer(page: Page): Locator {
  return page.getByTestId('tasks-footer');
}
function editDialog(page: Page): Locator {
  return page.getByRole('dialog', { name: 'Edit task' });
}
function editListSelect(page: Page): Locator {
  return editDialog(page).locator('#task-edit-list');
}
function deleteDialog(page: Page): Locator {
  return page.locator('dialog[aria-labelledby="list-delete-heading"]');
}
async function settled(page: Page) {
  await expect(page.getByText('Loading tasks…')).toHaveCount(0);
  await expect(page.getByText('Loading lists…')).toHaveCount(0);
}
async function gotoList(page: Page, l: TaskList | 'my') {
  await page.goto(l === 'my' ? '/tasks' : `/lists/${l.id}`);
  await expect(heading(page)).toHaveText(l === 'my' ? 'My tasks' : l.name);
  await settled(page);
}
async function shot(page: Page, name: string) {
  await page.screenshot({ path: `${SHOTS}/${name}.png`, fullPage: true });
}
function requests(page: Page, method: string, re: RegExp): Request[] {
  const seen: Request[] = [];
  page.on('request', (r) => {
    const u = new URL(r.url());
    if (r.method() === method && re.test(u.pathname + u.search)) seen.push(r);
  });
  return seen;
}
async function newUser(browser: Browser, name: string) {
  const ctx = await browser.newContext({ baseURL: BASE });
  const p = await ctx.newPage();
  const w: Watch = { consoleErrors: [], pageErrors: [], serverErrors: [], allowNetworkErrors: false };
  watchPage(p, w);
  const u = await signUp(p, name);
  return { ctx, p, w, ...u };
}
async function openEdit(page: Page, title: string) {
  await row(page, title).getByRole('button', { name: 'Edit' }).click();
  await expect(editDialog(page)).toBeVisible();
}

// =====================================================================================
// Lists (AC1-AC4)
// =====================================================================================

test.describe('T3a Lists (AC1-AC4)', () => {
  test('AC1: a new email user has exactly one "My tasks" (personal), heading + picker show it; DB allows only one personal list', async ({
    page,
  }) => {
    // register through the UI
    const email = uniqueEmail('ac1ui');
    await page.goto('/register');
    await page.locator('#register-display-name').fill(`AC1 ${uid()}`);
    await page.locator('#register-email').fill(email);
    await page.locator('#register-password').fill(PASSWORD);
    await page.locator('#register-confirm-password').fill(PASSWORD);
    await page.getByRole('button', { name: 'Create account' }).click();
    await page.waitForURL('**/tasks');
    await expect(heading(page)).toHaveText('My tasks');
    await settled(page);
    await expect(pickerLinks(page)).toHaveText(['My tasks']);
    await expect(pickerLinks(page).first()).toHaveAttribute('href', '/tasks');
    await expect(pickerLinks(page).first()).toHaveAttribute('aria-current', 'page');
    await shot(page, 'ac1-new-user-my-tasks');

    const ls = await lists(page);
    expect(ls).toHaveLength(1);
    expect(ls[0]).toMatchObject({ name: 'My tasks', personal: true });
    const userId = num(`SELECT id FROM users WHERE email = '${email}'`);
    expect(num(`SELECT COUNT(*) FROM lists WHERE owner_id = ${userId} AND personal`)).toBe(1);
    expect(num(`SELECT COUNT(*) FROM lists WHERE owner_id = ${userId}`)).toBe(1);

    // a task added now lands on "My tasks"
    await page.getByLabel('New task').fill('First one');
    await page.getByLabel('New task').press('Enter');
    await expect(row(page, 'First one')).toBeVisible();
    expect((await tasksPage(page, '')).items[0].listId).toBe(ls[0].id);

    // the database refuses a second personal list for the same user
    let failed = false;
    try {
      sql(`INSERT INTO lists (owner_id, name, personal) VALUES (${userId}, 'Second personal', TRUE)`);
    } catch {
      failed = true;
    }
    expect(failed, 'second personal list must be rejected by uk_lists_one_personal_per_owner').toBe(true);
    expect(num(`SELECT COUNT(*) FROM lists WHERE owner_id = ${userId}`)).toBe(1);

    // not deletable (API), no Delete in the menu (UI)
    await expectProblem(await api(page, 'DELETE', `/api/lists/${ls[0].id}`), 409, 'PERSONAL_LIST', "Your personal list can't be deleted");
    await menuToggle(page).click();
    await expect(menuItem(page, 'Rename')).toBeVisible();
    await expect(menuItem(page, 'Delete')).toHaveCount(0);
    await shot(page, 'ac1-my-tasks-menu-no-delete');
  });

  test('AC1: renaming "My tasks" works (stays personal, first, at /tasks, still not deletable)', async ({ page }) => {
    await signUp(page, 'ac1rename');
    await createList(page, 'Alpha');
    await gotoList(page, 'my');
    await menuToggle(page).click();
    await menuItem(page, 'Rename').click();
    const input = page.getByLabel('List name');
    await expect(input).toHaveValue('My tasks');
    await input.fill('  Zz Inbox  ');
    await page.getByRole('button', { name: 'Save' }).click();
    await expect(heading(page)).toHaveText('Zz Inbox');
    await expect(pickerLinks(page)).toHaveText(['Zz Inbox', 'Alpha']); // personal stays first
    await expect(pickerLinks(page).first()).toHaveAttribute('href', '/tasks');
    const p = await personal(page);
    expect(p).toMatchObject({ name: 'Zz Inbox', personal: true });
    await expectProblem(await api(page, 'DELETE', `/api/lists/${p.id}`), 409, 'PERSONAL_LIST');
    await page.reload();
    await expect(heading(page)).toHaveText('Zz Inbox');
    await shot(page, 'ac1-renamed-personal');
  });

  test('AC1: a new Google sign-up gets exactly one "My tasks"', async ({ page }) => {
    const mail = uniqueEmail('ac1g');
    const sub = `g-t3a-${uid()}`;
    await page.goto('/login');
    await page.getByTestId('google-button').click();
    await page.waitForURL((u) => u.href.startsWith(`${OIDC}/authorize`));
    await page.locator('input[name="username"]').fill(sub);
    await page
      .locator('textarea[name="claims"]')
      .fill(JSON.stringify({ email: mail, email_verified: true, name: `G T3a ${uid()}` }));
    await page.locator('input[type="submit"]').click();
    await page.waitForURL((u) => u.origin === new URL(BASE).origin && u.pathname === '/tasks');
    await expect(heading(page)).toHaveText('My tasks');
    await settled(page);
    const ls = await lists(page);
    expect(ls).toEqual([{ id: ls[0].id, name: 'My tasks', personal: true, role: 'OWNER', memberCount: 1 }]);
    const userId = num(`SELECT id FROM users WHERE email = '${mail}'`);
    expect(num(`SELECT COUNT(*) FROM lists WHERE owner_id = ${userId} AND personal`)).toBe(1);
    await shot(page, 'ac1-google-my-tasks');
  });

  test('AC2: "+ New list": trimmed name, 1-100 chars, errors next to the field, opens the new empty list', async ({ page }) => {
    await signUp(page, 'ac2');
    await gotoList(page, 'my');
    const posts = requests(page, 'POST', /^\/api\/lists$/);

    await page.getByRole('button', { name: '+ New list' }).click();
    const input = page.getByLabel('New list', { exact: true });
    await expect(input).toBeFocused();

    // blank -> client error next to the field, no request
    await input.fill('   ');
    await page.getByRole('button', { name: 'Create' }).click();
    await expect(page.locator('#list-create-name-error')).toHaveText('Name is required');
    await expect(input).toHaveAttribute('aria-invalid', 'true');
    // 101 chars -> error, no request
    await input.fill('x'.repeat(101));
    await page.getByRole('button', { name: 'Create' }).click();
    await expect(page.locator('#list-create-name-error')).toHaveText('Name must be 100 characters or fewer');
    await shot(page, 'ac2-name-too-long');
    expect(posts).toHaveLength(0);

    // Escape cancels
    await input.press('Escape');
    await expect(input).toHaveCount(0);
    await expect(page.getByRole('button', { name: '+ New list' })).toBeFocused();

    // valid, trimmed
    await page.getByRole('button', { name: '+ New list' }).click();
    await page.getByLabel('New list', { exact: true }).fill('   Home   ');
    await page.getByRole('button', { name: 'Create' }).click();
    await page.waitForURL(/\/lists\/\d+$/);
    await expect(heading(page)).toHaveText('Home');
    await settled(page);
    expect(posts).toHaveLength(1);
    expect(posts[0].postDataJSON()).toEqual({ name: 'Home' });
    const home = (await lists(page)).find((l) => l.name === 'Home')!;
    expect(home.personal).toBe(false);
    expect(new URL(page.url()).pathname).toBe(`/lists/${home.id}`);
    await expect(page.locator('.tasks-empty')).toBeVisible();
    const details = await (await page.request.get(`/api/lists/${home.id}`)).json();
    expect(details).toEqual({ ...home, taskCount: 0 });
    await expect(pickerLinks(page)).toHaveText(['My tasks', 'Home']);
    await expect(picker(page).getByRole('link', { name: 'Home' })).toHaveAttribute('aria-current', 'page');

    // exactly 100 chars accepted
    const hundred = 'H'.repeat(100);
    await page.getByRole('button', { name: '+ New list' }).click();
    await page.getByLabel('New list', { exact: true }).fill(hundred);
    await page.getByRole('button', { name: 'Create' }).click();
    await expect(heading(page)).toHaveText(hundred);
    // duplicate names allowed
    await createList(page, 'Home');
    expect((await lists(page)).filter((l) => l.name === 'Home')).toHaveLength(2);
    await shot(page, 'ac2-created');
  });

  test('AC2: the 51st list (50 incl. "My tasks") shows "You can have at most 50 lists" and creates nothing; double click = one POST', async ({
    page,
  }) => {
    await signUp(page, 'ac2limit');
    for (let i = 1; i <= 48; i += 1) await createList(page, `L${String(i).padStart(2, '0')}`);
    await gotoList(page, 'my');
    const posts = requests(page, 'POST', /^\/api\/lists$/);

    // 50th via UI, double-clicked: exactly one request, one list
    await page.getByRole('button', { name: '+ New list' }).click();
    await page.getByLabel('New list', { exact: true }).fill('Fiftieth');
    await page.getByRole('button', { name: 'Create' }).dblclick();
    await expect(heading(page)).toHaveText('Fiftieth');
    expect(posts).toHaveLength(1);
    expect(await lists(page)).toHaveLength(50);

    // 51st
    await page.getByRole('button', { name: '+ New list' }).click();
    await page.getByLabel('New list', { exact: true }).fill('One too many');
    await page.getByRole('button', { name: 'Create' }).click();
    await expect(page.locator('#list-create-error')).toContainText('You can have at most 50 lists');
    await expect(page.locator('#list-create-error').getByRole('button', { name: 'Retry' })).toHaveCount(0);
    await shot(page, 'ac2-list-limit');
    expect(await lists(page)).toHaveLength(50);
    expect((await lists(page)).some((l) => l.name === 'One too many')).toBe(false);
    const r = await api(page, 'POST', '/api/lists', { name: 'API too many' });
    const body = await expectProblem(r, 409, 'LIST_LIMIT', 'You can have at most 50 lists');
    expect(body.errors).toBeUndefined();
    // validation still comes before the limit
    await expectProblem(await api(page, 'POST', '/api/lists', { name: '' }), 400, 'VALIDATION_FAILED');
  });

  test('AC3: rename shows in the picker and the header; blank/too long errors next to the field; Escape cancels', async ({
    page,
  }) => {
    await signUp(page, 'ac3');
    const home = await createList(page, 'Home');
    await createList(page, 'Middle');
    await gotoList(page, home);
    const patches = requests(page, 'PATCH', /^\/api\/lists\/\d+$/);

    await menuToggle(page).click();
    await menuItem(page, 'Rename').click();
    const input = page.getByLabel('List name');
    await expect(input).toBeFocused();
    await input.fill('');
    await page.getByRole('button', { name: 'Save' }).click();
    await expect(page.locator('#list-rename-name-error')).toHaveText('Name is required');
    await input.fill('y'.repeat(101));
    await page.getByRole('button', { name: 'Save' }).click();
    await expect(page.locator('#list-rename-name-error')).toHaveText('Name must be 100 characters or fewer');
    expect(patches).toHaveLength(0);
    await input.press('Escape');
    await expect(input).toHaveCount(0);
    await expect(heading(page)).toHaveText('Home');

    await menuToggle(page).click();
    await menuItem(page, 'Rename').click();
    await page.getByLabel('List name').fill('  Zebra stuff ');
    await page.getByRole('button', { name: 'Save' }).click();
    await expect(heading(page)).toHaveText('Zebra stuff');
    await expect(pickerLinks(page)).toHaveText(['My tasks', 'Middle', 'Zebra stuff']); // re-sorted by name
    expect(patches).toHaveLength(1);
    expect(patches[0].postDataJSON()).toEqual({ name: 'Zebra stuff' });
    expect((await lists(page)).find((l) => l.id === home.id)!.name).toBe('Zebra stuff');
    await shot(page, 'ac3-renamed');
    await page.reload();
    await expect(heading(page)).toHaveText('Zebra stuff');

    // API: invalid names
    const blank = await expectProblem(await api(page, 'PATCH', `/api/lists/${home.id}`, { name: ' ' }), 400, 'VALIDATION_FAILED');
    expect(blank.errors).toEqual([{ field: 'name', code: 'REQUIRED', message: 'Name is required' }]);
    const long = await expectProblem(await api(page, 'PATCH', `/api/lists/${home.id}`, { name: 'z'.repeat(101) }), 400, 'VALIDATION_FAILED');
    expect(long.errors).toEqual([{ field: 'name', code: 'TOO_LONG', message: 'Name must be 100 characters or fewer' }]);
    await expectProblem(await api(page, 'PATCH', `/api/lists/${home.id}`, { name: 7 }), 400, 'MALFORMED_REQUEST');
    await expectProblem(await api(page, 'PATCH', '/api/lists/abc', { name: 'x' }), 400, 'MALFORMED_REQUEST');
    await expectProblem(await api(page, 'PATCH', '/api/lists/999999999', { name: 'x' }), 404, 'LIST_NOT_FOUND');
  });

  test('AC4: delete dialog shows the task count (Delete disabled until loaded); Cancel changes nothing; confirm deletes list+tasks+tags and lands on "My tasks"', async ({
    page,
  }) => {
    const me = await signUp(page, 'ac4');
    const home = await createList(page, 'Home');
    for (let i = 1; i <= 12; i += 1) await createTask(page, `Home ${i}`, home.id, i <= 3 ? ['HomeTag', `T${i}`] : undefined);
    // two done tasks still count
    const t = await createTask(page, 'Home done', home.id);
    await putTask(page, t, { status: 'DONE' });
    await createTask(page, 'Mine stays', undefined, ['HomeTag']);
    await gotoList(page, home);

    // hold the count request so we can see the disabled state
    let release!: () => void;
    const gate = new Promise<void>((r) => (release = r));
    await page.route(`**/api/lists/${home.id}`, async (route) => {
      if (route.request().method() === 'GET') await gate;
      await route.continue();
    });
    await menuToggle(page).click();
    await menuItem(page, 'Delete').click();
    const dlg = deleteDialog(page);
    await expect(dlg).toBeVisible();
    await expect(dlg.getByText('Counting tasks…')).toBeVisible();
    await expect(dlg.getByRole('button', { name: 'Delete' })).toBeDisabled();
    await shot(page, 'ac4-counting');
    release();
    await expect(dlg.getByRole('heading')).toHaveText('Delete "Home" and its 13 tasks?');
    await expect(dlg.getByRole('button', { name: 'Delete' })).toBeEnabled();
    await shot(page, 'ac4-dialog');
    await page.unroute(`**/api/lists/${home.id}`);

    // cancel: nothing changes
    await dlg.getByRole('button', { name: 'Cancel' }).click();
    await expect(dlg).toHaveCount(0);
    expect((await (await page.request.get(`/api/lists/${home.id}`)).json()).taskCount).toBe(13);
    // Escape also cancels
    await menuToggle(page).click();
    await menuItem(page, 'Delete').click();
    await expect(deleteDialog(page).getByRole('button', { name: 'Delete' })).toBeEnabled();
    await page.keyboard.press('Escape');
    await expect(deleteDialog(page)).toHaveCount(0);
    expect((await lists(page)).some((l) => l.id === home.id)).toBe(true);

    // confirm
    const deletes = requests(page, 'DELETE', /^\/api\/lists\/\d+$/);
    await menuToggle(page).click();
    await menuItem(page, 'Delete').click();
    await deleteDialog(page).getByRole('button', { name: 'Delete' }).dblclick();
    await page.waitForURL((u) => u.pathname === '/tasks');
    await expect(heading(page)).toHaveText('My tasks');
    await settled(page);
    expect(deletes).toHaveLength(1);
    await expect(pickerLinks(page)).toHaveText(['My tasks']);
    await expect(row(page, 'Mine stays')).toBeVisible();
    await shot(page, 'ac4-after-delete');
    await expectProblem(await page.request.get(`/api/lists/${home.id}`), 404, 'LIST_NOT_FOUND');
    expect(num(`SELECT COUNT(*) FROM tasks WHERE list_id = ${home.id}`)).toBe(0);
    expect(num(`SELECT COUNT(*) FROM tags WHERE list_id = ${home.id}`)).toBe(0);
    expect(num(`SELECT COUNT(*) FROM lists WHERE owner_id = ${me.id}`)).toBe(1);
    // the same tag name in "My tasks" is untouched
    expect(await tagNames(page)).toEqual(['HomeTag']);
    // deleting again -> 404
    await expectProblem(await api(page, 'DELETE', `/api/lists/${home.id}`), 404, 'LIST_NOT_FOUND');
  });

  test('AC4: delete dialog with 0 and 1 task wording', async ({ page }) => {
    await signUp(page, 'ac4w');
    const empty = await createList(page, 'Empty');
    const one = await createList(page, 'One');
    await createTask(page, 'only', one.id);
    await gotoList(page, empty);
    await menuToggle(page).click();
    await menuItem(page, 'Delete').click();
    await expect(deleteDialog(page).getByRole('button', { name: 'Delete' })).toBeEnabled();
    await expect(deleteDialog(page).getByRole('heading')).toHaveText('Delete "Empty"?');
    await deleteDialog(page).getByRole('button', { name: 'Cancel' }).click();
    await gotoList(page, one);
    await menuToggle(page).click();
    await menuItem(page, 'Delete').click();
    await expect(deleteDialog(page).getByRole('heading')).toHaveText('Delete "One" and its 1 task?');
  });
});

// =====================================================================================
// Switching lists (AC5-AC7)
// =====================================================================================

test.describe('T3a Switching lists (AC5-AC7)', () => {
  test('AC5: picker order "My tasks" first then by name (case/accent-insensitive); links /tasks and /lists/<id>; sidebar on desktop', async ({
    page,
  }) => {
    await signUp(page, 'ac5');
    for (const n of ['zeta', 'Émile', 'alpha', 'Beta', 'emma']) await createList(page, n);
    const ls = await lists(page);
    expect(ls.map((l) => l.name)).toEqual(['My tasks', 'alpha', 'Beta', 'Émile', 'emma', 'zeta']);
    await page.setViewportSize({ width: 1280, height: 800 });
    await gotoList(page, 'my');
    await expect(pickerLinks(page)).toHaveText(['My tasks', 'alpha', 'Beta', 'Émile', 'emma', 'zeta']);
    await expect(picker(page)).toBeVisible();
    await expect(phoneSelect(page)).toBeHidden();
    for (const l of ls) {
      await expect(picker(page).getByRole('link', { name: l.name, exact: true })).toHaveAttribute(
        'href',
        l.personal ? '/tasks' : `/lists/${l.id}`,
      );
    }
    await picker(page).getByRole('link', { name: 'Beta', exact: true }).click();
    await expect(page).toHaveURL(new RegExp(`/lists/${ls[2].id}$`));
    await expect(heading(page)).toHaveText('Beta');
    await picker(page).getByRole('link', { name: 'My tasks' }).click();
    await expect(page).toHaveURL(/\/tasks$/);
    await expect(heading(page)).toHaveText('My tasks');
    await shot(page, 'ac5-desktop-sidebar');
  });

  test('AC5: phone width shows a dropdown above the add box (sidebar hidden); choosing opens /lists/<id>', async ({ page }) => {
    await signUp(page, 'ac5phone');
    const work = await createList(page, 'Work');
    await createList(page, 'Home');
    await page.setViewportSize({ width: 375, height: 740 });
    await gotoList(page, 'my');
    await expect(phoneSelect(page)).toBeVisible();
    await expect(picker(page)).toBeHidden();
    await expect(phoneSelect(page).locator('option')).toHaveText(['My tasks', 'Home', 'Work']);
    // dropdown is above the add box
    const sel = (await phoneSelect(page).boundingBox())!;
    const add = (await page.getByLabel('New task').boundingBox())!;
    expect(sel.y).toBeLessThan(add.y);
    // no horizontal scroll
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
    await shot(page, 'ac5-phone-dropdown');
    await phoneSelect(page).selectOption({ label: 'Work' });
    await expect(page).toHaveURL(new RegExp(`/lists/${work.id}$`));
    await expect(heading(page)).toHaveText('Work');
    await expect(phoneSelect(page)).toHaveValue(String(work.id));
    await phoneSelect(page).selectOption({ label: 'My tasks' });
    await expect(page).toHaveURL(/\/tasks$/);
    // 640px boundary: still the phone layout at 640, sidebar at 641+
    await page.setViewportSize({ width: 640, height: 740 });
    await expect(phoneSelect(page)).toBeVisible();
    await page.setViewportSize({ width: 900, height: 740 });
    await expect(picker(page)).toBeVisible();
    await expect(phoneSelect(page)).toBeHidden();
  });

  test('AC5: old /tasks?filter=&tag=&q=&sort= deep links still work on "My tasks" (and nothing leaks from other lists)', async ({
    page,
  }) => {
    await signUp(page, 'ac5deep');
    const other = await createList(page, 'Other');
    await createTask(page, 'b milk', undefined, ['Work']);
    await createTask(page, 'a milk', undefined, ['Work']);
    const done = await createTask(page, 'c milk', undefined, ['Work']);
    await putTask(page, done, { status: 'DONE' });
    await createTask(page, 'd milk');
    await createTask(page, 'e no match', undefined, ['Work']);
    await createTask(page, 'aa milk other list', other.id, ['Work']);
    const gets = requests(page, 'GET', /^\/api\/tasks\?/);
    await page.goto('/tasks?filter=active&tag=Work&q=milk&sort=title');
    await expect(heading(page)).toHaveText('My tasks');
    await settled(page);
    expect(await titles(page)).toEqual(['a milk', 'b milk']);
    await expect(page.getByTestId('tag-filter-bar')).toContainText('Work');
    await expect(page.locator('#task-search')).toHaveValue('milk');
    await expect(page.locator('#task-sort')).toHaveValue('title');
    const u = new URL(gets[gets.length - 1].url());
    expect(u.searchParams.get('list')).toBeNull(); // /tasks sends no list
    await shot(page, 'ac5-deep-link');
  });

  test('AC6: inside a list: quick-add, tabs, tag, search, sort, Load more all scoped to the list; switching resets to defaults and page 1', async ({
    page,
  }) => {
    test.setTimeout(120_000);
    await signUp(page, 'ac6');
    const home = await createList(page, 'Home');
    const work = await createList(page, 'Work');
    for (let i = 1; i <= 55; i += 1) await createTask(page, `Home ${String(i).padStart(2, '0')}`, home.id);
    const hm = await createTask(page, 'Home milk', home.id, ['Errands']);
    await putTask(page, hm, { status: 'IN_PROGRESS', tags: ['Errands'] });
    const hd = await createTask(page, 'Home done milk', home.id, ['Errands']);
    await putTask(page, hd, { status: 'DONE', tags: ['Errands'] });
    await createTask(page, 'Mine milk', undefined, ['Errands']);
    await createTask(page, 'Work milk', work.id, ['Errands']);

    await gotoList(page, home);
    const gets = requests(page, 'GET', /^\/api\/(tasks|tags)\?/);
    await expect(footer(page)).toContainText('Showing 50 of 57');
    await page.getByRole('button', { name: 'Load more' }).click();
    await expect(footer(page)).toContainText('57 tasks');
    const loadMore = gets.find((r) => r.url().includes('cursor='))!;
    expect(new URL(loadMore.url()).searchParams.get('list')).toBe(String(home.id));

    // quick-add goes into Home
    const posts = requests(page, 'POST', /^\/api\/tasks$/);
    await page.getByLabel('New task').fill('Added in Home');
    await page.getByLabel('New task').press('Enter');
    await expect(row(page, 'Added in Home')).toBeVisible();
    expect(posts[0].postDataJSON()).toEqual({ title: 'Added in Home', listId: home.id });
    const added = (await tasksPage(page, `?list=${home.id}&q=Added`)).items[0];
    expect(added.listId).toBe(home.id);
    expect((await tasksPage(page, '?q=Added')).total).toBe(0);

    // search inside Home only
    await page.locator('#task-search').fill('milk');
    await page.locator('#task-search').press('Enter');
    await expect.poll(() => titles(page)).toEqual(['Home milk', 'Home done milk']);
    // tab In progress
    await page.getByRole('link', { name: 'In progress' }).click();
    await expect.poll(() => titles(page)).toEqual(['Home milk']);
    await page.getByRole('link', { name: 'Done' }).click();
    await expect.poll(() => titles(page)).toEqual(['Home done milk']);
    await page.getByRole('link', { name: 'All' }).click();
    // tag filter (click the chip on a row)
    await page.locator('#task-search').fill('');
    await page.locator('#task-search').press('Enter');
    await page.goto(`/lists/${home.id}?tag=Errands&sort=title`);
    await settled(page);
    await expect.poll(() => titles(page)).toEqual(['Home milk', 'Home done milk']);
    await expect(page.locator('#task-sort')).toHaveValue('title');
    for (const r of gets) {
      const u = new URL(r.url());
      expect(u.searchParams.get('list'), r.url()).toBe(String(home.id));
    }
    await page.goto(`/lists/${home.id}?filter=active&tag=Errands&q=milk&sort=title`);
    await settled(page);
    await expect.poll(() => titles(page)).toEqual(['Home milk']);
    await shot(page, 'ac6-home-filtered');

    // switching lists: everything reset, first page, Work only
    const before = gets.length;
    await picker(page).getByRole('link', { name: 'Work' }).click();
    await expect(heading(page)).toHaveText('Work');
    await settled(page);
    expect(new URL(page.url()).search).toBe('');
    await expect(page.locator('#task-search')).toHaveValue('');
    await expect(page.locator('#task-sort')).toHaveValue('newest');
    await expect(page.getByTestId('tag-filter-bar')).toHaveCount(0);
    await expect(page.getByRole('link', { name: 'All' })).toHaveAttribute('aria-current', 'page');
    expect(await titles(page)).toEqual(['Work milk']);
    const after = gets.slice(before);
    const taskReq = after.find((r) => new URL(r.url()).pathname === '/api/tasks')!;
    const sp = new URL(taskReq.url()).searchParams;
    expect(sp.get('list')).toBe(String(work.id));
    expect(sp.get('cursor')).toBeNull();
    expect(sp.get('q')).toBeNull();
    await shot(page, 'ac6-switched-reset');

    // back to My tasks: /tasks, no list param, only mine
    await picker(page).getByRole('link', { name: 'My tasks' }).click();
    await expect(heading(page)).toHaveText('My tasks');
    await settled(page);
    expect(await titles(page)).toEqual(['Mine milk']);
  });

  test('AC6: refresh on /lists/<id> keeps the list; browser back/forward between lists shows the right list', async ({ page }) => {
    await signUp(page, 'ac6nav');
    const a = await createList(page, 'Aaa');
    const b = await createList(page, 'Bbb');
    await createTask(page, 'in A', a.id);
    await createTask(page, 'in B', b.id);
    await gotoList(page, a);
    await page.reload();
    await expect(heading(page)).toHaveText('Aaa');
    await settled(page);
    expect(await titles(page)).toEqual(['in A']);
    await picker(page).getByRole('link', { name: 'Bbb' }).click();
    await expect(heading(page)).toHaveText('Bbb');
    await expect.poll(() => titles(page)).toEqual(['in B']);
    await page.goBack();
    await expect(heading(page)).toHaveText('Aaa');
    await expect.poll(() => titles(page)).toEqual(['in A']);
    await page.goForward();
    await expect(heading(page)).toHaveText('Bbb');
    await expect.poll(() => titles(page)).toEqual(['in B']);
  });

  test('AC7: unknown, other user\'s and malformed list ids show "List not found" + link to "My tasks"; API says 404 never 403', async ({
    page,
    browser,
  }) => {
    await signUp(page, 'ac7');
    const b = await newUser(browser, 'ac7b');
    const bList = await createList(b.p, 'B secret');
    await createTask(b.p, 'B secret task', bList.id);
    const statuses: { url: string; status: number }[] = [];
    page.on('response', (r) => {
      if (r.url().includes('/api/')) statuses.push({ url: r.url(), status: r.status() });
    });

    for (const path of [`/lists/${bList.id}`, '/lists/999999999', '/lists/abc', '/lists/0', '/lists/-5', '/lists/1.5']) {
      await page.goto(path);
      const nf = page.getByTestId('list-not-found');
      await expect(nf, path).toBeVisible();
      await expect(nf.getByRole('heading')).toHaveText('List not found');
      await expect(nf.getByRole('link', { name: 'Go to My tasks' })).toHaveAttribute('href', '/tasks');
      await expect(page.getByText('B secret')).toHaveCount(0);
      await expect(page.getByLabel('New task')).toHaveCount(0);
    }
    await shot(page, 'ac7-list-not-found');
    expect(statuses.filter((s) => s.status === 403)).toEqual([]);
    expect(statuses.some((s) => s.url.includes(`list=${bList.id}`) && s.status === 404)).toBe(true);
    await page.getByTestId('list-not-found').getByRole('link', { name: 'Go to My tasks' }).click();
    await expect(page).toHaveURL(/\/tasks$/);
    await expect(heading(page)).toHaveText('My tasks');

    // API, every way in
    for (const res of [
      await page.request.get(`/api/lists/${bList.id}`),
      await page.request.get(`/api/tasks?list=${bList.id}`),
      await page.request.get(`/api/tags?list=${bList.id}`),
      await api(page, 'PATCH', `/api/lists/${bList.id}`, { name: 'hijack' }),
      await api(page, 'DELETE', `/api/lists/${bList.id}`),
      await api(page, 'POST', '/api/tasks', { title: 'x', listId: bList.id }),
    ]) {
      await expectProblem(res, 404, 'LIST_NOT_FOUND', 'List not found');
    }
    expect((await lists(b.p)).find((l) => l.id === bList.id)!.name).toBe('B secret');
    checkWatch(b.w, 'user B');
    await b.ctx.close();
  });

  test('AC7: a list deleted in another tab -> "List not found" on the next action/navigation (no 500, no console error)', async ({
    page,
    watch,
  }) => {
    await signUp(page, 'ac7tab');
    const home = await createList(page, 'Home');
    await createTask(page, 'h1', home.id);
    await gotoList(page, home);
    // delete it behind the page's back
    expect((await api(page, 'DELETE', `/api/lists/${home.id}`)).status()).toBe(204);
    await page.getByLabel('New task').fill('into a deleted list');
    await page.getByLabel('New task').press('Enter');
    await expect(page.getByTestId('list-not-found')).toBeVisible();
    await expect(pickerLinks(page)).toHaveText(['My tasks']);
    await shot(page, 'ac7-deleted-in-other-tab');
    expect(watch.serverErrors).toEqual([]);
  });
});

// =====================================================================================
// Tags per list (AC8, AC9)
// =====================================================================================

test.describe('T3a Tags belong to the list (AC8, AC9)', () => {
  test('AC8: same tag name in two lists; tags of one list are not suggested/filterable in the other', async ({ page }) => {
    await signUp(page, 'ac8');
    const home = await createList(page, 'Home');
    const work = await createList(page, 'Work');
    await createTask(page, 'home task', home.id, ['Shared', 'OnlyHome']);
    await createTask(page, 'work task', work.id, ['shared']);
    await createTask(page, 'work other', work.id, ['Other']);
    expect(await tagNames(page, home.id)).toEqual(['OnlyHome', 'Shared']);
    expect(await tagNames(page, work.id)).toEqual(['Other', 'shared']); // own spelling, not Home's
    expect(await tagNames(page)).toEqual([]);
    // filter in Work by a Home-only tag -> empty
    expect(await tasksPage(page, `?list=${work.id}&tag=OnlyHome`)).toEqual({ items: [], total: 0, nextCursor: null });
    expect((await tasksPage(page, `?list=${work.id}&tag=Shared`)).items.map((t) => t.title)).toEqual(['work task']);

    // UI: suggestions in Work's edit dialog never offer OnlyHome
    await gotoList(page, work);
    await openEdit(page, 'work task');
    const box = editDialog(page).getByRole('combobox', { name: 'Tags' });
    await box.pressSequentially('o');
    const sugg = page.getByRole('listbox', { name: 'Tag suggestions' });
    await expect(sugg).toBeVisible();
    await expect(sugg.getByRole('option')).toHaveText(['Other']); // Work's own tag only, never Home's OnlyHome
    await shot(page, 'ac8-work-suggestions');
    await editDialog(page).getByRole('button', { name: 'Cancel' }).click();
    // positive control: in Home, 'o' suggests OnlyHome
    await gotoList(page, home);
    await createTask(page, 'home blank', home.id);
    await page.reload();
    await expect(row(page, 'home blank')).toBeVisible();
    await openEdit(page, 'home blank');
    await editDialog(page).getByRole('combobox', { name: 'Tags' }).pressSequentially('o');
    await expect(page.getByRole('listbox', { name: 'Tag suggestions' }).getByRole('option')).toHaveText(['OnlyHome']);
    await editDialog(page).getByRole('button', { name: 'Cancel' }).click();
    // UI: /lists/work?tag=OnlyHome -> empty
    await page.goto(`/lists/${work.id}?tag=OnlyHome`);
    await settled(page);
    await expect(listTasks(page)).toHaveCount(0);
    await expect(page.locator('.tasks-empty')).toBeVisible();
    // DB: two rows with the same name, different lists
    expect(num(`SELECT COUNT(*) FROM tags WHERE list_id IN (${home.id}, ${work.id}) AND name = 'shared'`)).toBe(2);
  });

  test('AC8: the 200-tag limit is per list (Work full; Home still takes new tags)', async ({ page }) => {
    test.setTimeout(120_000);
    await signUp(page, 'ac8lim');
    const work = await createList(page, 'Work');
    const home = await createList(page, 'Home');
    for (let i = 0; i < 20; i += 1) {
      await createTask(page, `w${i}`, work.id, Array.from({ length: 10 }, (_, k) => `W${i * 10 + k}`));
    }
    expect(await tagNames(page, work.id)).toHaveLength(200);
    const t = await createTask(page, 'one more', work.id);
    const r = await api(page, 'PUT', `/api/tasks/${t.id}`, { title: t.title, tags: ['Brand new'] });
    const body = await expectProblem(r, 400, 'VALIDATION_FAILED');
    expect(body.errors).toEqual([{ field: 'tags', code: 'TAG_LIMIT', message: 'A list can have at most 200 tags' }]);
    // reusing an existing tag at the limit is fine (ignoring case)
    expect((await putTask(page, t, { tags: ['w5'] })).tags).toEqual(['W5']);
    // other lists are unaffected
    expect((await createTask(page, 'home', home.id, ['Brand new'])).tags).toEqual(['Brand new']);
    expect((await createTask(page, 'mine', undefined, ['Brand new 2'])).tags).toEqual(['Brand new 2']);
  });

  test('AC9: after V10 every user has one "My tasks" holding all their tasks and tags; nothing on a foreign list, no cross-list links', async () => {
    expect(num(`SELECT COUNT(*) FROM users u WHERE (SELECT COUNT(*) FROM lists l WHERE l.owner_id = u.id AND l.personal) <> 1`)).toBe(0);
    // 0.13.0: tasks.owner_id means "added by" (a member, or NULL for a deleted account); personal lists can't be shared.
    expect(num(`SELECT COUNT(*) FROM tasks t JOIN lists l ON l.id = t.list_id WHERE l.personal AND t.owner_id <> l.owner_id`)).toBe(0);
    expect(num(`SELECT COUNT(*) FROM tags g JOIN lists l ON l.id = g.list_id WHERE l.owner_id <> g.owner_id`)).toBe(0);
    expect(
      num(`SELECT COUNT(*) FROM task_tags tt JOIN tasks t ON t.id = tt.task_id JOIN tags g ON g.id = tt.tag_id WHERE t.list_id <> g.list_id`),
    ).toBe(0);
    expect(num(`SELECT COUNT(*) FROM tasks WHERE list_id IS NULL`)).toBe(0);
    expect(num(`SELECT COUNT(*) FROM tags WHERE list_id IS NULL`)).toBe(0);
    expect(num(`SELECT COUNT(*) FROM flyway_schema_history WHERE version = '10' AND success = 1`)).toBe(1);
  });
});

// =====================================================================================
// Moving tasks (AC10, AC11)
// =====================================================================================

test.describe('T3a Moving tasks (AC10, AC11)', () => {
  test('AC10: edit dialog "List" select moves the task; it leaves the view, total adjusts; tags follow with the target spelling', async ({
    page,
  }) => {
    await signUp(page, 'ac10');
    const home = await createList(page, 'Home');
    const mine = await personal(page);
    await createTask(page, 'mine uses CAFE', undefined, ['CAFE']);
    const mover = await createTask(page, 'mover', home.id, ['Café', 'Shared', 'Solo']);
    await createTask(page, 'stays', home.id, ['Shared']);
    await createTask(page, 'stays too', home.id);
    await gotoList(page, home);
    await expect(footer(page)).toContainText('3 tasks');

    await openEdit(page, 'mover');
    await expect(editListSelect(page)).toHaveValue(String(home.id));
    await expect(editListSelect(page).locator('option')).toHaveText(['My tasks', 'Home']);
    const puts = requests(page, 'PUT', /^\/api\/tasks\/\d+$/);
    await editListSelect(page).selectOption({ label: 'My tasks' });
    await shot(page, 'ac10-edit-dialog-list');
    await editDialog(page).getByRole('button', { name: 'Save' }).click();
    await expect(editDialog(page)).toHaveCount(0);
    await expect(row(page, 'mover')).toHaveCount(0);
    await expect(footer(page)).toContainText('2 tasks');
    expect(puts[0].postDataJSON().listId).toBe(mine.id);
    await shot(page, 'ac10-moved-out');

    const moved = await getTask(page, mover.id);
    expect(moved.listId).toBe(mine.id);
    expect(moved.tags).toEqual(['CAFE', 'Shared', 'Solo']); // Café -> target spelling CAFE
    expect(moved.createdAt).toBe(mover.createdAt);
    expect(await tagNames(page)).toEqual(['CAFE', 'Shared', 'Solo']);
    expect(await tagNames(page, home.id)).toEqual(['Shared']); // Café & Solo were only used by mover
    // shows up in My tasks (/tasks)
    await picker(page).getByRole('link', { name: 'My tasks' }).click();
    await expect(page).toHaveURL(/\/tasks$/);
    await expect(row(page, 'mover')).toBeVisible();
    await expect(row(page, 'mover').locator('[data-testid=task-tag]')).toHaveText(['CAFE', 'Shared', 'Solo']);
    // PUT without listId / same listId is not a move
    const same = await putTask(page, moved, { listId: mine.id });
    expect(same.listId).toBe(mine.id);
    expect((await putTask(page, moved, {})).listId).toBe(mine.id);
  });

  test('AC10: moving with other edits in the same save; after loading 2 pages the total and rows stay consistent', async ({ page }) => {
    test.setTimeout(90_000);
    await signUp(page, 'ac10pg');
    const home = await createList(page, 'Home');
    const work = await createList(page, 'Work');
    for (let i = 1; i <= 60; i += 1) await createTask(page, `H${String(i).padStart(2, '0')}`, home.id);
    await gotoList(page, home);
    await page.getByRole('button', { name: 'Load more' }).click();
    await expect(footer(page)).toContainText('60 tasks');
    await openEdit(page, 'H05');
    await editDialog(page).getByLabel('Title').fill('H05 moved');
    await editListSelect(page).selectOption({ label: 'Work' });
    await editDialog(page).getByRole('button', { name: 'Save' }).click();
    await expect(editDialog(page)).toHaveCount(0);
    await expect(footer(page)).toContainText('59 tasks');
    expect(await titles(page)).not.toContain('H05');
    expect((await tasksPage(page, `?list=${work.id}`)).items.map((t) => t.title)).toEqual(['H05 moved']);
  });

  test('AC11: a move that would put the target over 200 tags is refused with the tag-limit error; nothing changes', async ({ page }) => {
    test.setTimeout(120_000);
    await signUp(page, 'ac11');
    const work = await createList(page, 'Work');
    const home = await createList(page, 'Home');
    for (let i = 0; i < 20; i += 1) {
      await createTask(page, `w${i}`, work.id, Array.from({ length: 10 }, (_, k) => `W${i * 10 + k}`));
    }
    const t = await createTask(page, 'too many', home.id, ['Brand new', 'W1']);
    const before = await getTask(page, t.id);

    // API
    const r = await api(page, 'PUT', `/api/tasks/${t.id}`, { title: 'changed', listId: work.id });
    const body = await expectProblem(r, 400, 'VALIDATION_FAILED');
    expect(body.errors).toEqual([{ field: 'tags', code: 'TAG_LIMIT', message: 'A list can have at most 200 tags' }]);
    expect(await getTask(page, t.id)).toEqual(before);
    expect(await tagNames(page, work.id)).toHaveLength(200);
    expect(await tagNames(page, home.id)).toEqual(['Brand new', 'W1']);

    // UI
    await gotoList(page, home);
    await openEdit(page, 'too many');
    await editDialog(page).getByLabel('Title').fill('changed in UI');
    await editListSelect(page).selectOption({ label: 'Work' });
    await editDialog(page).getByRole('button', { name: 'Save' }).click();
    await expect(editDialog(page).locator('#task-edit-list-error')).toHaveText(
      'Can\'t move to "Work": a list can have at most 200 tags',
    );
    await expect(editDialog(page)).toBeVisible();
    await shot(page, 'ac11-tag-limit-on-move');
    expect(await getTask(page, t.id)).toEqual(before);
    await editDialog(page).getByRole('button', { name: 'Cancel' }).click();
    await expect(row(page, 'too many')).toBeVisible();

    // a move whose tags all exist in the full target is allowed (ignoring case)
    const ok = await createTask(page, 'fits', home.id, ['w7', 'W8']);
    const moved = await putTask(page, ok, { listId: work.id });
    expect(moved).toMatchObject({ listId: work.id, tags: ['W7', 'W8'] });
    expect(await tagNames(page, work.id)).toHaveLength(200);
  });

  test('AC10/AC11 errors: target list deleted while the dialog is open -> "This list no longer exists. Choose another list."; task deleted -> not found; API order of checks', async ({
    page,
  }) => {
    await signUp(page, 'ac10err');
    const home = await createList(page, 'Home');
    const gone = await createList(page, 'Gone soon');
    const t = await createTask(page, 'to move', home.id);
    await gotoList(page, home);
    await openEdit(page, 'to move');
    await editListSelect(page).selectOption({ label: 'Gone soon' });
    expect((await api(page, 'DELETE', `/api/lists/${gone.id}`)).status()).toBe(204);
    await editDialog(page).getByRole('button', { name: 'Save' }).click();
    await expect(editDialog(page).locator('#task-edit-list-error')).toHaveText('This list no longer exists. Choose another list.');
    await expect(editDialog(page)).toBeVisible();
    await expect(editListSelect(page).locator('option')).not.toContainText(['Gone soon']);
    await shot(page, 'ac10-target-gone');
    expect((await getTask(page, t.id)).listId).toBe(home.id);
    // choose another list -> works
    await editListSelect(page).selectOption({ label: 'My tasks' });
    await editDialog(page).getByRole('button', { name: 'Save' }).click();
    await expect(editDialog(page)).toHaveCount(0);
    await expect(row(page, 'to move')).toHaveCount(0);

    // API order: NOT_FOUND (task) before LIST_NOT_FOUND
    await expectProblem(await api(page, 'PUT', '/api/tasks/999999999', { title: 'x', listId: 999999999 }), 404, 'NOT_FOUND', 'Task not found');
    await expectProblem(await api(page, 'PUT', `/api/tasks/${t.id}`, { title: 'x', listId: 999999999 }), 404, 'LIST_NOT_FOUND', 'List not found');
    await expectProblem(await api(page, 'PUT', `/api/tasks/${t.id}`, { title: 'x', listId: 0 }), 404, 'LIST_NOT_FOUND');
    await expectProblem(await api(page, 'PUT', `/api/tasks/${t.id}`, { title: 'x', listId: 'abc' }), 400, 'MALFORMED_REQUEST');
    await expectProblem(await api(page, 'PUT', `/api/tasks/${t.id}`, { title: '', listId: 999999999 }), 400, 'VALIDATION_FAILED');
  });
});

// =====================================================================================
// Account deletion (AC12), privacy (AC14), compatibility (AC15)
// =====================================================================================

test.describe('T3a Account deletion, privacy, compatibility (AC12, AC14, AC15)', () => {
  test('AC12: deleting my account (self, and by an admin) removes all my lists, tasks and tags', async ({ page, browser }) => {
    // self
    const me = await signUp(page, 'ac12');
    const a = await createList(page, 'A');
    const b = await createList(page, 'B');
    await createTask(page, 'a1', a.id, ['x']);
    await createTask(page, 'b1', b.id, ['y']);
    await createTask(page, 'm1', undefined, ['z']);
    expect(num(`SELECT COUNT(*) FROM lists WHERE owner_id = ${me.id}`)).toBe(3);
    const del = await api(page, 'POST', '/api/account/delete', { password: PASSWORD });
    expect(del.status()).toBe(204);
    for (const tbl of ['lists', 'tasks', 'tags']) expect(num(`SELECT COUNT(*) FROM ${tbl} WHERE owner_id = ${me.id}`), tbl).toBe(0);
    expect(num(`SELECT COUNT(*) FROM tasks WHERE list_id IN (${a.id}, ${b.id})`)).toBe(0);

    // by an admin
    const victim = await newUser(browser, 'ac12v');
    const vl = await createList(victim.p, 'V');
    await createTask(victim.p, 'v1', vl.id, ['vt']);
    const admin = await newUser(browser, 'ac12adm');
    sql(`UPDATE users SET role = 'ADMIN' WHERE id = ${admin.id}`);
    // fresh login so the session picks up the role
    const actx = await browser.newContext({ baseURL: BASE });
    const ap = await actx.newPage();
    const login = await ap.request.post('/api/auth/login', {
      data: { email: admin.email, password: PASSWORD },
      headers: { 'X-XSRF-TOKEN': await xsrf(ap) },
    });
    expect(login.status()).toBe(200);
    const r = await api(ap, 'DELETE', `/api/admin/users/${victim.id}`);
    expect(r.status(), await r.text()).toBe(204);
    for (const tbl of ['lists', 'tasks', 'tags']) expect(num(`SELECT COUNT(*) FROM ${tbl} WHERE owner_id = ${victim.id}`), tbl).toBe(0);
    expect((await victim.p.request.get('/api/lists')).status()).toBe(401);
    await Promise.all([victim.ctx.close(), admin.ctx.close(), actx.close()]);
  });

  test('AC14: A never sees B\'s lists, tasks or tags in any response; an admin sees no lists or tasks of others', async ({ page, browser }) => {
    const a = await signUp(page, 'ac14a');
    const aList = await createList(page, 'A private');
    const aTask = await createTask(page, 'A task', aList.id, ['Atag']);
    const aMine = await createTask(page, 'A mine', undefined, ['Amine']);
    const b = await newUser(browser, 'ac14b');
    const bList = await createList(b.p, 'B private');
    const bTask = await createTask(b.p, 'B task', bList.id, ['Btag']);
    const bodies: string[] = [];
    b.p.on('response', async (r) => {
      if (r.url().includes('/api/')) bodies.push(await r.text().catch(() => ''));
    });

    // B's views
    expect((await lists(b.p)).map((l) => l.id)).not.toContain(aList.id);
    for (const res of [
      await b.p.request.get(`/api/tasks?list=${aList.id}`),
      await b.p.request.get(`/api/tags?list=${aList.id}`),
      await b.p.request.get(`/api/lists/${aList.id}`),
      await api(b.p, 'PUT', `/api/tasks/${bTask.id}`, { title: 'x', listId: aList.id }),
      await api(b.p, 'POST', '/api/tasks', { title: 'x', listId: aList.id }),
    ]) {
      await expectProblem(res, 404, 'LIST_NOT_FOUND');
    }
    await expectProblem(await b.p.request.get(`/api/tasks/${aTask.id}`), 404, 'NOT_FOUND');
    await expectProblem(await api(b.p, 'PUT', `/api/tasks/${aTask.id}`, { title: 'x', listId: bList.id }), 404, 'NOT_FOUND');
    await expectProblem(await api(b.p, 'PATCH', `/api/tasks/${aTask.id}`, { done: true }), 404, 'NOT_FOUND');
    await expectProblem(await api(b.p, 'DELETE', `/api/tasks/${aTask.id}`), 404, 'NOT_FOUND');
    // A's personal list id is just as invisible
    const aPersonal = await personal(page);
    await expectProblem(await b.p.request.get(`/api/tasks?list=${aPersonal.id}`), 404, 'LIST_NOT_FOUND');
    await expectProblem(await api(b.p, 'DELETE', `/api/lists/${aPersonal.id}`), 404, 'LIST_NOT_FOUND');
    // UI
    await b.p.goto(`/lists/${aList.id}`);
    await expect(b.p.getByTestId('list-not-found')).toBeVisible();
    await b.p.goto('/tasks');
    await expect(heading(b.p)).toHaveText('My tasks');
    await expect(b.p.getByText('Loading tasks…')).toHaveCount(0);
    await expect(picker(b.p).getByRole('link')).toHaveText(['My tasks', 'B private']);
    for (const s of ['A private', 'A task', 'Atag', 'Amine', 'A mine', a.email]) {
      expect(bodies.some((x) => x.includes(s)), s).toBe(false);
    }
    // nothing of A's changed
    expect((await getTask(page, aTask.id)).title).toBe('A task');
    expect((await getTask(page, aMine.id)).tags).toEqual(['Amine']);

    // admin: only own lists; others' lists/tasks are 404; admin user listing carries no list/task data
    const admin = await newUser(browser, 'ac14adm');
    sql(`UPDATE users SET role = 'ADMIN' WHERE id = ${admin.id}`);
    const actx = await browser.newContext({ baseURL: BASE });
    const ap = await actx.newPage();
    expect(
      (
        await ap.request.post('/api/auth/login', {
          data: { email: admin.email, password: PASSWORD },
          headers: { 'X-XSRF-TOKEN': await xsrf(ap) },
        })
      ).status(),
    ).toBe(200);
    const al = await lists(ap);
    expect(al).toHaveLength(1);
    expect(al[0].personal).toBe(true);
    await expectProblem(await ap.request.get(`/api/lists/${aList.id}`), 404, 'LIST_NOT_FOUND');
    await expectProblem(await ap.request.get(`/api/tasks?list=${bList.id}`), 404, 'LIST_NOT_FOUND');
    await expectProblem(await ap.request.get(`/api/tasks/${aTask.id}`), 404, 'NOT_FOUND');
    const users = await ap.request.get(`/api/admin/users?q=${encodeURIComponent(a.email)}`);
    expect(users.status()).toBe(200);
    const ub = await users.json();
    const txt = JSON.stringify(ub);
    expect(txt).not.toContain('A private');
    expect(txt).not.toContain('A task');
    for (const u of ub.items ?? []) expect(Object.keys(u).sort()).toEqual(['createdAt', 'disabled', 'displayName', 'email', 'emailVerified', 'googleConnected', 'id', 'role']);
    checkWatch(b.w, 'user B');
    await Promise.all([b.ctx.close(), admin.ctx.close(), actx.close()]);
  });

  test('AC15: no list parameter = "My tasks"; a cursor from one list is rejected in another (INVALID_CURSOR); cursor with ?list=<My tasks id> = no list', async ({
    page,
  }) => {
    test.setTimeout(90_000);
    await signUp(page, 'ac15');
    const mine = await personal(page);
    const home = await createList(page, 'Home');
    for (let i = 0; i < 52; i += 1) await createTask(page, `m${i}`);
    for (let i = 0; i < 52; i += 1) await createTask(page, `h${i}`, home.id);
    const p1 = await tasksPage(page, '');
    expect(p1.total).toBe(52);
    expect(p1.items.every((t) => t.listId === mine.id)).toBe(true);
    expect(p1.nextCursor).not.toBeNull();
    // same as ?list=<id of My tasks> and ?list=
    expect((await tasksPage(page, `?list=${mine.id}&cursor=${p1.nextCursor}`)).items).toHaveLength(2);
    expect((await tasksPage(page, `?list=&cursor=${p1.nextCursor}`)).items).toHaveLength(2);
    // cross-list cursor
    const bad = await page.request.get(`/api/tasks?list=${home.id}&cursor=${p1.nextCursor}`);
    const body = await expectProblem(bad, 400, 'VALIDATION_FAILED');
    expect(body.errors).toEqual([{ field: 'cursor', code: 'INVALID_CURSOR', message: 'The list changed, reload from the first page' }]);
    const h1 = await tasksPage(page, `?list=${home.id}`);
    await expectProblem(await page.request.get(`/api/tasks?cursor=${h1.nextCursor}`), 400, 'VALIDATION_FAILED');
    // list is checked before q and cursor
    await expectProblem(await page.request.get(`/api/tasks?list=999999999&q=${'x'.repeat(101)}&cursor=zzz`), 404, 'LIST_NOT_FOUND');
    // tags without list = My tasks
    await putTask(page, p1.items[0], { tags: ['MineTag'] });
    await putTask(page, h1.items[0], { tags: ['HomeTag'] });
    expect(await tagNames(page)).toEqual(['MineTag']);
    expect(await tagNames(page, mine.id)).toEqual(['MineTag']);
    // ?list= parsing per contract
    for (const v of ['%20', '0', '-1', 'abc', '1.5', `%20${home.id}`, '99999999999999999999']) {
      await expectProblem(await page.request.get(`/api/tasks?list=${v}`), 404, 'LIST_NOT_FOUND');
      await expectProblem(await page.request.get(`/api/tags?list=${v}`), 404, 'LIST_NOT_FOUND');
    }
    // repeated list: only the first is used
    expect((await tasksPage(page, `?list=${home.id}&list=${mine.id}`)).items[0].listId).toBe(home.id);
  });
});

// =====================================================================================
// States and errors (AC17)
// =====================================================================================

test.describe('T3a States and errors (AC17)', () => {
  test('AC17: picker loading state, load failure with Retry', async ({ page, watch }) => {
    watch.allowNetworkErrors = true;
    await signUp(page, 'ac17pick');
    await createList(page, 'Home');
    let fail = true;
    let release!: () => void;
    const gate = new Promise<void>((r) => (release = r));
    await page.route('**/api/lists', async (route) => {
      if (route.request().method() !== 'GET') return route.continue();
      await gate;
      if (fail) return route.fulfill({ status: 500, contentType: 'application/problem+json', headers: { 'x-qa-fake': '1' }, body: '{"status":500}' });
      return route.continue();
    });
    await page.goto('/tasks');
    await expect(page.getByText('Loading lists…')).toBeVisible();
    await shot(page, 'ac17-lists-loading');
    release();
    const alert = page.getByTestId('list-picker').getByRole('alert');
    await expect(alert).toContainText("Couldn't load your lists.");
    await shot(page, 'ac17-lists-load-failed');
    // the tasks of "My tasks" still load
    await expect(heading(page)).toHaveText('My tasks');
    fail = false;
    await alert.getByRole('button', { name: 'Retry' }).click();
    await expect(pickerLinks(page)).toHaveText(['My tasks', 'Home']);
    await expect(alert).toHaveCount(0);
  });

  test('AC17: create/rename/delete server errors show messages with Retry, and Retry succeeds', async ({ page, watch }) => {
    watch.allowNetworkErrors = true;
    await signUp(page, 'ac17cud');
    const home = await createList(page, 'Home');
    await gotoList(page, home);

    // create: one failure, then Retry
    let createFails = 1;
    await page.route('**/api/lists', async (route) => {
      if (route.request().method() === 'POST' && createFails > 0) {
        createFails -= 1;
        return route.abort('failed');
      }
      return route.continue();
    });
    await page.getByRole('button', { name: '+ New list' }).click();
    await page.getByLabel('New list', { exact: true }).fill('Retried');
    await page.getByRole('button', { name: 'Create' }).click();
    await expect(page.locator('#list-create-error')).toContainText("Couldn't create the list. Please try again.");
    await shot(page, 'ac17-create-failed');
    await page.locator('#list-create-error').getByRole('button', { name: 'Retry' }).click();
    await expect(heading(page)).toHaveText('Retried');
    expect((await lists(page)).filter((l) => l.name === 'Retried')).toHaveLength(1);

    // rename: 500 then Retry
    await gotoList(page, home);
    let renameFails = 1;
    await page.route(`**/api/lists/${home.id}`, async (route) => {
      const m = route.request().method();
      if (m === 'PATCH' && renameFails > 0) {
        renameFails -= 1;
        return route.fulfill({ status: 500, headers: { 'x-qa-fake': '1' }, contentType: 'application/problem+json', body: '{"status":500}' });
      }
      return route.continue();
    });
    await menuToggle(page).click();
    await menuItem(page, 'Rename').click();
    await page.getByLabel('List name').fill('Home 2');
    await page.getByRole('button', { name: 'Save' }).click();
    await expect(page.locator('.list-header__rename').getByRole('alert')).toContainText("Couldn't rename the list. Please try again.");
    await shot(page, 'ac17-rename-failed');
    await page.locator('.list-header__rename').getByRole('button', { name: 'Retry' }).click();
    await expect(heading(page)).toHaveText('Home 2');
    await page.unroute(`**/api/lists/${home.id}`);

    // delete: count fails then Retry; delete fails then Retry
    let countFails = 1;
    let deleteFails = 1;
    await page.route(`**/api/lists/${home.id}`, async (route) => {
      const m = route.request().method();
      if (m === 'GET' && countFails > 0) {
        countFails -= 1;
        return route.abort('failed');
      }
      if (m === 'DELETE' && deleteFails > 0) {
        deleteFails -= 1;
        return route.fulfill({ status: 500, headers: { 'x-qa-fake': '1' }, contentType: 'application/problem+json', body: '{"status":500}' });
      }
      return route.continue();
    });
    await menuToggle(page).click();
    await menuItem(page, 'Delete').click();
    const dlg = deleteDialog(page);
    await expect(dlg.getByRole('alert')).toContainText("Couldn't load the list. Please try again.");
    await expect(dlg.getByRole('button', { name: 'Delete' })).toBeDisabled();
    await shot(page, 'ac17-delete-count-failed');
    await dlg.getByRole('alert').getByRole('button', { name: 'Retry' }).click();
    await expect(dlg.getByRole('heading')).toHaveText('Delete "Home 2"?');
    await dlg.getByRole('button', { name: 'Delete' }).click();
    await expect(dlg.getByRole('alert')).toContainText("Couldn't delete the list. Please try again.");
    await shot(page, 'ac17-delete-failed');
    expect((await lists(page)).some((l) => l.id === home.id)).toBe(true);
    await dlg.getByRole('alert').getByRole('button', { name: 'Retry' }).click();
    await page.waitForURL((u) => u.pathname === '/tasks');
    expect((await lists(page)).some((l) => l.id === home.id)).toBe(false);
  });

  test('AC17: create shows a busy state while pending; move failure (network) keeps the dialog with an error', async ({ page, watch }) => {
    watch.allowNetworkErrors = true;
    await signUp(page, 'ac17busy');
    const home = await createList(page, 'Home');
    await createTask(page, 'mv', home.id);
    await gotoList(page, home);
    let release!: () => void;
    const gate = new Promise<void>((r) => (release = r));
    await page.route('**/api/lists', async (route) => {
      if (route.request().method() === 'POST') await gate;
      return route.continue();
    });
    await page.getByRole('button', { name: '+ New list' }).click();
    await page.getByLabel('New list', { exact: true }).fill('Slow');
    await page.getByRole('button', { name: 'Create' }).click();
    await expect(page.getByRole('button', { name: 'Create' })).toBeDisabled();
    await expect(page.getByRole('button', { name: 'Create' })).toHaveAttribute('aria-busy', 'true');
    await shot(page, 'ac17-create-pending');
    release();
    await expect(heading(page)).toHaveText('Slow');

    await gotoList(page, home);
    await page.route('**/api/tasks/*', (route) => (route.request().method() === 'PUT' ? route.abort('failed') : route.continue()));
    await openEdit(page, 'mv');
    await editListSelect(page).selectOption({ label: 'My tasks' });
    await editDialog(page).getByRole('button', { name: 'Save' }).click();
    await expect(editDialog(page).getByRole('alert')).toBeVisible();
    await expect(editDialog(page)).toBeVisible();
    await shot(page, 'ac17-move-failed');
    expect((await tasksPage(page, `?list=${home.id}`)).items.map((t) => t.title)).toEqual(['mv']);
  });

  test('AC17: edit dialog when lists failed to load: List select disabled with a note; other edits still save', async ({ page, watch }) => {
    watch.allowNetworkErrors = true;
    await signUp(page, 'ac17nolists');
    await createTask(page, 'plain');
    await page.route('**/api/lists', (route) =>
      route.request().method() === 'GET' ? route.abort('failed') : route.continue(),
    );
    await page.goto('/tasks');
    await expect(row(page, 'plain')).toBeVisible();
    await openEdit(page, 'plain');
    await expect(editListSelect(page)).toBeDisabled();
    await expect(editDialog(page).locator('#task-edit-list-note')).toBeVisible();
    await shot(page, 'ac17-edit-no-lists');
    await editDialog(page).getByLabel('Title').fill('plain edited');
    await editDialog(page).getByRole('button', { name: 'Save' }).click();
    await expect(editDialog(page)).toHaveCount(0);
    await expect(row(page, 'plain edited')).toBeVisible();
  });
});
