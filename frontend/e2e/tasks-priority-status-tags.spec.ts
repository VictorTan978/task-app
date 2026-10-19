import { APIResponse, Browser, expect, Locator, Page, Request, test as base } from '@playwright/test';
import { execFileSync } from 'node:child_process';

/*
 * E2E for specs/tasks-priority-status-tags.md (T1: AC1-AC17 + Decisions log), contract 0.10.0.
 * AC18 = the updated specs/tasks.md suite (e2e/tasks.spec.ts).
 * Needs `docker compose up -d db` (AC14 admin + AC15 checks use `docker compose exec db mysql`).
 * Every test fails on browser console errors / page errors. Tests that deliberately abort or fake
 * failed requests allow only the browser's own network log lines.
 */

const RUN = `${Date.now()}-${Math.floor(Math.random() * 1e6)}`;
const SHOTS = '../test-results/tasks-priority-status-tags';
const PASSWORD = 'password123';
let counter = 0;
function uniqueEmail(tag: string): string {
  counter += 1;
  return `qa-t1-${RUN}-${tag}-${counter}@example.com`;
}

type Priority = 'NONE' | 'LOW' | 'MEDIUM' | 'HIGH';
type Status = 'TODO' | 'IN_PROGRESS' | 'DONE';
interface Task {
  id: number;
  title: string;
  description: string | null;
  priority: Priority;
  status: Status;
  tags: string[];
  done: boolean;
  dueDate: string | null;
  createdAt: string;
  updatedAt: string;
}

// 0.12.0 (T3a): Task gained the required listId.
const TASK_KEYS = ['createdAt', 'description', 'done', 'dueDate', 'id', 'listId', 'priority', 'status', 'tags', 'title', 'updatedAt'];
const INSTANT = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{1,6})?Z$/;

/** Checks a Task against contract 0.10.0 (required keys, enums, done mirror, tags shape). */
function expectTaskShape(t: Task) {
  expect(Object.keys(t).sort()).toEqual(TASK_KEYS);
  expect(typeof t.id).toBe('number');
  expect(['NONE', 'LOW', 'MEDIUM', 'HIGH']).toContain(t.priority);
  expect(['TODO', 'IN_PROGRESS', 'DONE']).toContain(t.status);
  expect(t.done).toBe(t.status === 'DONE');
  expect(Array.isArray(t.tags)).toBe(true);
  expect(t.tags.length).toBeLessThanOrEqual(10);
  for (const g of t.tags) {
    expect(typeof g).toBe('string');
    expect(g.length).toBeGreaterThanOrEqual(1);
    expect(g.length).toBeLessThanOrEqual(30);
  }
  expect(t.createdAt).toMatch(INSTANT);
  expect(t.updatedAt).toMatch(INSTANT);
}

interface Watch {
  consoleErrors: string[];
  pageErrors: string[];
  allowNetworkErrors: boolean;
}

const NETWORK_LOG = [
  /^Failed to load resource: net::ERR_FAILED/,
  /^Failed to load resource: the server responded with a status of (400|401|404|500|503)/,
];

const test = base.extend<{ watch: Watch }>({
  watch: [
    async ({ page }, use) => {
      const w: Watch = { consoleErrors: [], pageErrors: [], allowNetworkErrors: false };
      page.on('console', (msg) => {
        if (msg.type() === 'error') w.consoleErrors.push(msg.text());
      });
      page.on('pageerror', (err) => w.pageErrors.push(err.message));
      await use(w);
      const errors = w.allowNetworkErrors
        ? w.consoleErrors.filter((e) => !NETWORK_LOG.some((r) => r.test(e)))
        : w.consoleErrors;
      expect(errors, 'browser console errors').toEqual([]);
      expect(w.pageErrors, 'uncaught page errors').toEqual([]);
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

async function xsrf(page: Page): Promise<string> {
  let c = (await page.context().cookies()).find((x) => x.name === 'XSRF-TOKEN');
  if (!c) {
    await page.request.get('/api/auth/me');
    c = (await page.context().cookies()).find((x) => x.name === 'XSRF-TOKEN');
  }
  return c!.value;
}

/** Registers via the API inside the page's context, so the page is signed in. */
async function signUp(page: Page, name = 'QA'): Promise<{ email: string; id: number }> {
  const email = uniqueEmail(name);
  const me = await page.request.get('/api/auth/me');
  expect(me.status()).toBe(401);
  const res = await page.request.post('/api/auth/register', {
    data: { displayName: `${name} ${RUN}-${counter}`, email, password: PASSWORD },
    headers: { 'X-XSRF-TOKEN': await xsrf(page) },
  });
  expect(res.status()).toBe(201);
  return { email, id: ((await res.json()) as { id: number }).id };
}

async function api(page: Page, method: string, path: string, data?: unknown): Promise<APIResponse> {
  return page.request.fetch(path, { method, data, headers: { 'X-XSRF-TOKEN': await xsrf(page) } });
}

async function createTask(page: Page, body: Record<string, unknown>): Promise<Task> {
  const res = await api(page, 'POST', '/api/tasks', body);
  expect(res.status()).toBe(201);
  const t = (await res.json()) as Task;
  expectTaskShape(t);
  return t;
}

async function putTask(page: Page, id: number, body: Record<string, unknown>): Promise<Task> {
  const res = await api(page, 'PUT', `/api/tasks/${id}`, body);
  expect(res.status(), await res.text()).toBe(200);
  const t = (await res.json()) as Task;
  expectTaskShape(t);
  return t;
}

/** Creates a task and sets the T1 fields via PUT. */
async function seed(
  page: Page,
  title: string,
  extra: { priority?: Priority; status?: Status; tags?: string[]; dueDate?: string } = {},
): Promise<Task> {
  const t = await createTask(page, { title, dueDate: extra.dueDate ?? null });
  if (extra.priority || extra.status || extra.tags) {
    return putTask(page, t.id, { title, description: null, dueDate: extra.dueDate ?? null, ...extra });
  }
  return t;
}

async function getTask(page: Page, id: number): Promise<Task> {
  const res = await api(page, 'GET', `/api/tasks/${id}`);
  expect(res.status()).toBe(200);
  const t = (await res.json()) as Task;
  expectTaskShape(t);
  return t;
}

/** All matching tasks, in list order: GET /api/tasks is paged since contract 0.11.0. */
async function listTasks(page: Page, query = ''): Promise<Task[]> {
  const ts: Task[] = [];
  let cursor: string | null = null;
  do {
    const sep = query ? '&' : '?';
    const res = await api(page, 'GET', `/api/tasks${query}${cursor ? `${sep}cursor=${cursor}` : ''}`);
    expect(res.status()).toBe(200);
    const body = (await res.json()) as { items: Task[]; total: number; nextCursor: string | null };
    expect(Object.keys(body).sort()).toEqual(['items', 'nextCursor', 'total']);
    ts.push(...body.items);
    cursor = body.nextCursor;
  } while (cursor);
  ts.forEach(expectTaskShape);
  return ts;
}

async function listTags(page: Page): Promise<string[]> {
  const res = await api(page, 'GET', '/api/tags');
  expect(res.status()).toBe(200);
  const body = (await res.json()) as { name: string }[];
  expect(Array.isArray(body)).toBe(true);
  for (const t of body) expect(Object.keys(t)).toEqual(['name']);
  return body.map((t) => t.name);
}

async function expectProblem(res: APIResponse, status: number, code: string) {
  expect(res.status(), await res.text()).toBe(status);
  expect(res.headers()['content-type']).toContain('application/problem+json');
  const b = (await res.json()) as { code: string; errors?: { field: string; code: string; message: string }[] };
  expect(b.code).toBe(code);
  return b;
}

function localDate(offsetDays: number): string {
  const d = new Date();
  d.setDate(d.getDate() + offsetDays);
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
}

function list(page: Page): Locator {
  return page.getByRole('list', { name: 'Tasks' });
}
function rows(page: Page): Locator {
  return list(page).getByRole('listitem');
}
function row(page: Page, title: string): Locator {
  return rows(page).filter({ has: page.getByRole('checkbox', { name: `Done: ${title}`, exact: true }) });
}
function cb(page: Page, title: string): Locator {
  return page.getByRole('checkbox', { name: `Done: ${title}`, exact: true });
}
async function order(page: Page): Promise<string[]> {
  const labels = await list(page).getByRole('checkbox').evaluateAll((els) =>
    els.map((e) => e.getAttribute('aria-label') ?? ''),
  );
  return labels.map((l) => l.replace(/^Done: /, ''));
}
async function gotoTasks(page: Page, query = '') {
  await page.goto(`/tasks${query}`);
  await expect(page.getByRole('heading', { name: 'My tasks' })).toBeVisible();
  await expect(page.getByRole('status')).toHaveCount(0);
}
function nav(page: Page): Locator {
  return page.locator('nav[aria-label="Filter tasks"]');
}
function editDialog(page: Page) {
  return page.getByRole('dialog', { name: 'Edit task' });
}
function tagsBox(page: Page) {
  return editDialog(page).getByRole('combobox', { name: 'Tags' });
}
function chips(page: Page) {
  return editDialog(page).locator('.tag-chip__name');
}
function suggestions(page: Page) {
  return page.getByRole('listbox', { name: 'Tag suggestions' });
}
async function openEdit(page: Page, title: string) {
  await row(page, title).getByRole('button', { name: 'Edit' }).click();
  await expect(editDialog(page)).toBeVisible();
}
async function save(page: Page) {
  await editDialog(page).getByRole('button', { name: 'Save' }).click();
  await expect(editDialog(page)).toHaveCount(0);
}
function countRequests(page: Page, method: string, re: RegExp) {
  const seen: Request[] = [];
  page.on('request', (r) => {
    if (r.method() === method && re.test(new URL(r.url()).pathname)) seen.push(r);
  });
  return seen;
}
async function titleStruck(page: Page, title: string): Promise<boolean> {
  const btn = row(page, title).getByRole('button', { name: title, exact: true });
  return btn.evaluate((el) => {
    let n: Element | null = el;
    while (n) {
      if (getComputedStyle(n).textDecorationLine.includes('line-through')) return true;
      n = n.parentElement;
    }
    return false;
  });
}
function rowTags(page: Page, title: string) {
  return row(page, title).locator('[data-testid=task-tag]');
}
function params(page: Page): Record<string, string> {
  const u = new URL(page.url());
  expect(u.pathname).toBe('/tasks');
  return Object.fromEntries(u.searchParams.entries());
}
function rgb(c: string): [number, number, number] {
  const [r, g, b] = c.match(/\d+(\.\d+)?/g)!.map(Number);
  return [r, g, b];
}
async function colors(l: Locator) {
  return l.evaluate((el) => {
    const cs = getComputedStyle(el);
    return { color: cs.color, bg: cs.backgroundColor };
  });
}
async function shot(page: Page, name: string) {
  await page.screenshot({ path: `${SHOTS}/${name}.png`, fullPage: true });
}
function delay(ms: number) {
  return new Promise((r) => setTimeout(r, ms));
}
async function newUserPage(browser: Browser, name: string) {
  const ctx = await browser.newContext({ baseURL: process.env['E2E_BASE_URL'] ?? 'http://localhost:4200' });
  const p = await ctx.newPage();
  const errors: string[] = [];
  p.on('console', (m) => m.type() === 'error' && errors.push(m.text()));
  p.on('pageerror', (e) => errors.push(e.message));
  const u = await signUp(p, name);
  return { ctx, p, errors, ...u };
}

// ---------- AC1 Create ----------

test.describe('T1 Create', () => {
  test('AC1: quick-add creates NONE / TODO / no tags; quick-add box unchanged; POST ignores new fields', async ({
    page,
  }) => {
    await signUp(page, 'ac1');
    await gotoTasks(page);
    const posts = countRequests(page, 'POST', /^\/api\/tasks$/);
    await page.getByLabel('New task').fill('Fresh one');
    await page.getByLabel('New task').press('Enter');
    await expect(row(page, 'Fresh one')).toBeVisible();
    await expect(cb(page, 'Fresh one')).not.toBeChecked();
    await expect(row(page, 'Fresh one').getByTestId('task-priority')).toHaveCount(0);
    await expect(row(page, 'Fresh one').getByTestId('task-in-progress')).toHaveCount(0);
    await expect(rowTags(page, 'Fresh one')).toHaveCount(0);
    // quick-add form: just the title box and Add button
    const form = page.getByLabel('New task').locator('xpath=ancestor::form');
    await expect(form.locator('input, select, textarea')).toHaveCount(1);
    expect(posts.length).toBe(1);
    expect(Object.keys(posts[0].postDataJSON()).sort()).toEqual(['title']);
    await shot(page, 'ac1-quick-add');

    const [t] = await listTasks(page);
    expect(t).toMatchObject({ title: 'Fresh one', priority: 'NONE', status: 'TODO', tags: [], done: false });

    // contract: POST ignores priority/status/tags/done
    const ignored = await createTask(page, {
      title: 'Ignore extras',
      priority: 'HIGH',
      status: 'IN_PROGRESS',
      tags: ['Work'],
      done: true,
    });
    expect(ignored).toMatchObject({ priority: 'NONE', status: 'TODO', tags: [], done: false });
    expect(await listTags(page)).toEqual([]);
  });
});

// ---------- AC2 Priority ----------

test.describe('T1 Priority', () => {
  test('AC2/Q14: High red, Medium amber, Low grey, None removes; persists; faded on Done', async ({ page }) => {
    await signUp(page, 'ac2');
    const t = await seed(page, 'Prio task');
    await seed(page, 'Late one', { dueDate: '2020-01-01' });
    await gotoTasks(page);
    const badge = row(page, 'Prio task').getByTestId('task-priority');
    await expect(badge).toHaveCount(0);

    await openEdit(page, 'Prio task');
    const prio = editDialog(page).getByLabel('Priority');
    await expect(prio).toHaveValue('NONE');
    expect(await prio.locator('option').allInnerTexts()).toEqual(['None', 'Low', 'Medium', 'High']);
    await prio.selectOption({ label: 'High' });
    await save(page);
    await expect(badge).toHaveText('High');
    await expect(badge).toHaveClass(/task-row__priority--high/);
    const high = await colors(badge);
    const overdue = await colors(row(page, 'Late one').getByText('Overdue', { exact: true }));
    console.log('AC2 High', high, 'Overdue', overdue);
    const [hr, hg, hb] = rgb(high.color);
    expect(hr, `High text ${high.color} reddish`).toBeGreaterThan(hg + 80);
    expect(hr).toBeGreaterThan(hb + 60);
    expect(high.bg, 'High differs from Overdue').not.toBe(overdue.bg);
    expect((await getTask(page, t.id)).priority).toBe('HIGH');
    await shot(page, 'ac2-high');
    await page.reload();
    await expect(badge).toHaveText('High');

    await openEdit(page, 'Prio task');
    await expect(prio).toHaveValue('HIGH');
    await prio.selectOption({ label: 'Medium' });
    await save(page);
    await expect(badge).toHaveText('Medium');
    const med = await colors(badge);
    const [mr, mg, mb] = rgb(med.bg);
    console.log('AC2 Medium', med);
    expect(mr, `Medium bg ${med.bg} amber`).toBeGreaterThan(200);
    expect(mg).toBeGreaterThan(150);
    expect(mr - mb, 'amber = little blue').toBeGreaterThan(30);
    expect((await getTask(page, t.id)).priority).toBe('MEDIUM');

    await openEdit(page, 'Prio task');
    await prio.selectOption({ label: 'Low' });
    await save(page);
    await expect(badge).toHaveText('Low');
    const low = await colors(badge);
    console.log('AC2 Low', low);
    for (const c of [low.color, low.bg]) {
      const [r, g, b] = rgb(c);
      expect(Math.max(r, g, b) - Math.min(r, g, b), `Low ${c} grey`).toBeLessThan(25);
    }
    await shot(page, 'ac2-low');

    // Q14: priority badge kept (faded) on a done task
    await cb(page, 'Prio task').check();
    await expect.poll(() => titleStruck(page, 'Prio task')).toBe(true);
    await expect(badge).toHaveText('Low');
    const opacity = await badge.evaluate((el) => {
      let o = 1;
      for (let n: Element | null = el; n; n = n.parentElement) o *= Number(getComputedStyle(n).opacity);
      return o;
    });
    console.log('Q14 done-row priority opacity', opacity);
    expect(opacity, 'faded on done').toBeLessThan(1);
    await shot(page, 'q14-done-faded');
    await cb(page, 'Prio task').uncheck();
    await expect.poll(() => titleStruck(page, 'Prio task')).toBe(false);

    await openEdit(page, 'Prio task');
    await prio.selectOption({ label: 'None' });
    await save(page);
    await expect(badge).toHaveCount(0);
    await page.reload();
    await expect(row(page, 'Prio task')).toBeVisible();
    await expect(badge).toHaveCount(0);
    expect((await getTask(page, t.id)).priority).toBe('NONE');
  });
});

// ---------- AC3-AC6 Status ----------

test.describe('T1 Status', () => {
  test('AC3: To do -> In progress via dialog shows badge, checkbox unticked, persists', async ({ page }) => {
    await signUp(page, 'ac3');
    const t = await seed(page, 'Start me');
    await gotoTasks(page);
    await openEdit(page, 'Start me');
    const status = editDialog(page).getByLabel('Status');
    await expect(status).toHaveValue('TODO');
    expect(await status.locator('option').allInnerTexts()).toEqual(['To do', 'In progress', 'Done']);
    // the old "Done" checkbox is gone from the dialog
    await expect(editDialog(page).getByRole('checkbox')).toHaveCount(0);
    const puts = countRequests(page, 'PUT', /^\/api\/tasks\/\d+$/);
    await status.selectOption({ label: 'In progress' });
    await save(page);
    const body = puts[0].postDataJSON() as Record<string, unknown>;
    expect(body.status).toBe('IN_PROGRESS');
    expect('done' in body, 'frontend never sends done in PUT').toBe(false);

    await expect(row(page, 'Start me').getByTestId('task-in-progress')).toHaveText('In progress');
    await expect(cb(page, 'Start me')).not.toBeChecked();
    expect(await titleStruck(page, 'Start me')).toBe(false);
    await shot(page, 'ac3-in-progress');
    await page.reload();
    await expect(row(page, 'Start me').getByTestId('task-in-progress')).toHaveText('In progress');
    await expect(cb(page, 'Start me')).not.toBeChecked();
    expect(await getTask(page, t.id)).toMatchObject({ status: 'IN_PROGRESS', done: false });
    await openEdit(page, 'Start me');
    await expect(editDialog(page).getByLabel('Status')).toHaveValue('IN_PROGRESS');
  });

  test('AC4: ticking To do / In progress -> Done; unticking Done -> To do', async ({ page }) => {
    await signUp(page, 'ac4');
    const old = await seed(page, 'Old done');
    await api(page, 'PATCH', `/api/tasks/${old.id}`, { status: 'DONE' });
    const todo = await seed(page, 'Todo one');
    const ip = await seed(page, 'Working on', { status: 'IN_PROGRESS' });
    await gotoTasks(page);
    await expect.poll(() => order(page)).toEqual(['Working on', 'Todo one', 'Old done']);
    const patches = countRequests(page, 'PATCH', /^\/api\/tasks\/\d+$/);

    await cb(page, 'Working on').check();
    await expect(cb(page, 'Working on')).toBeChecked();
    await expect.poll(() => titleStruck(page, 'Working on')).toBe(true);
    await expect(row(page, 'Working on').getByTestId('task-in-progress')).toHaveCount(0);
    await expect.poll(() => order(page)).toEqual(['Todo one', 'Working on', 'Old done']);
    expect(patches[0].postDataJSON()).toEqual({ status: 'DONE' });
    expect(await getTask(page, ip.id)).toMatchObject({ status: 'DONE', done: true });
    await shot(page, 'ac4-in-progress-ticked');

    await cb(page, 'Todo one').check();
    await expect.poll(() => order(page)).toEqual(['Working on', 'Todo one', 'Old done']);
    expect(await getTask(page, todo.id)).toMatchObject({ status: 'DONE', done: true });

    // untick the formerly in-progress task -> To do (not back to In progress)
    await cb(page, 'Working on').uncheck();
    await expect.poll(() => order(page)).toEqual(['Working on', 'Todo one', 'Old done']);
    await expect.poll(() => titleStruck(page, 'Working on')).toBe(false);
    await expect(row(page, 'Working on').getByTestId('task-in-progress')).toHaveCount(0);
    expect(patches[2].postDataJSON()).toEqual({ status: 'TODO' });
    expect(await getTask(page, ip.id)).toMatchObject({ status: 'TODO', done: false });
    await page.reload();
    await expect.poll(() => order(page)).toEqual(['Working on', 'Todo one', 'Old done']);
    await expect(cb(page, 'Working on')).not.toBeChecked();
    await expect(cb(page, 'Todo one')).toBeChecked();
  });

  test('AC5: Done -> To do / In progress via dialog un-strikes and moves up; dialog Done = ticking', async ({
    page,
  }) => {
    await signUp(page, 'ac5');
    const a = await seed(page, 'Alpha', { status: 'DONE' });
    const b = await seed(page, 'Beta', { status: 'DONE' });
    await seed(page, 'Gamma');
    await gotoTasks(page);
    await expect.poll(() => order(page)).toEqual(['Gamma', 'Beta', 'Alpha']);
    expect(await titleStruck(page, 'Alpha')).toBe(true);

    await openEdit(page, 'Alpha');
    await expect(editDialog(page).getByLabel('Status')).toHaveValue('DONE');
    await editDialog(page).getByLabel('Status').selectOption({ label: 'To do' });
    await save(page);
    await expect.poll(() => order(page)).toEqual(['Gamma', 'Alpha', 'Beta']);
    expect(await titleStruck(page, 'Alpha')).toBe(false);
    await expect(cb(page, 'Alpha')).not.toBeChecked();
    expect(await getTask(page, a.id)).toMatchObject({ status: 'TODO', done: false });

    await openEdit(page, 'Beta');
    await editDialog(page).getByLabel('Status').selectOption({ label: 'In progress' });
    await save(page);
    await expect.poll(() => order(page)).toEqual(['Gamma', 'Beta', 'Alpha']);
    expect(await titleStruck(page, 'Beta')).toBe(false);
    await expect(row(page, 'Beta').getByTestId('task-in-progress')).toBeVisible();
    expect(await getTask(page, b.id)).toMatchObject({ status: 'IN_PROGRESS', done: false });
    await shot(page, 'ac5-reopened');

    // Status Done in the dialog = ticking
    await openEdit(page, 'Beta');
    await editDialog(page).getByLabel('Status').selectOption({ label: 'Done' });
    await save(page);
    await expect.poll(() => order(page)).toEqual(['Gamma', 'Alpha', 'Beta']);
    await expect(cb(page, 'Beta')).toBeChecked();
    expect(await titleStruck(page, 'Beta')).toBe(true);
    await expect(row(page, 'Beta').getByTestId('task-in-progress')).toHaveCount(0);
    expect(await getTask(page, b.id)).toMatchObject({ status: 'DONE', done: true });
  });

  test('AC6: In progress past due shows Overdue; Done never does', async ({ page }) => {
    await signUp(page, 'ac6');
    await seed(page, 'IP late', { status: 'IN_PROGRESS', dueDate: localDate(-1) });
    await seed(page, 'IP today', { status: 'IN_PROGRESS', dueDate: localDate(0) });
    await seed(page, 'Done late', { status: 'DONE', dueDate: '2020-01-01', priority: 'HIGH' });
    await gotoTasks(page);
    await expect(row(page, 'IP late').getByText('Overdue', { exact: true })).toBeVisible();
    await expect(row(page, 'IP late').getByTestId('task-in-progress')).toBeVisible();
    await expect(row(page, 'IP today').getByText('Overdue', { exact: true })).toHaveCount(0);
    await expect(row(page, 'Done late').getByText('Overdue', { exact: true })).toHaveCount(0);
    // badge order (UI notes): priority, In progress, due/Overdue, tags
    await shot(page, 'ac6-overdue');
    await cb(page, 'IP late').check();
    await expect(row(page, 'IP late').getByText('Overdue', { exact: true })).toHaveCount(0);
    await cb(page, 'IP late').uncheck();
    await expect(row(page, 'IP late').getByText('Overdue', { exact: true })).toBeVisible();
  });
});

// ---------- AC7-AC8 Filters ----------

test.describe('T1 Filters', () => {
  test('AC7: All / Active / In progress / Done tabs, URL, refresh, empty texts', async ({ page }) => {
    await signUp(page, 'ac7');
    await gotoTasks(page, '?filter=in_progress');
    await expect(page.getByText('Nothing in progress')).toBeVisible();
    await expect(nav(page).getByRole('link', { name: 'In progress' })).toHaveAttribute('aria-current', 'page');
    await shot(page, 'ac7-in-progress-empty');

    await seed(page, 'T todo');
    await seed(page, 'T ip', { status: 'IN_PROGRESS' });
    await seed(page, 'T done', { status: 'DONE' });
    await gotoTasks(page);
    expect(await nav(page).getByRole('link').allInnerTexts()).toEqual(['All', 'Active', 'In progress', 'Done']);
    await expect.poll(() => order(page)).toEqual(['T ip', 'T todo', 'T done']);

    const cases: [string, string | undefined, string[]][] = [
      ['Active', 'active', ['T ip', 'T todo']],
      ['In progress', 'in_progress', ['T ip']],
      ['Done', 'done', ['T done']],
      ['All', undefined, ['T ip', 'T todo', 'T done']],
    ];
    for (const [label, param, expected] of cases) {
      await nav(page).getByRole('link', { name: label, exact: true }).click();
      await expect.poll(() => new URL(page.url()).searchParams.get('filter')).toBe(param ?? null);
      expect(params(page)).toEqual(param ? { filter: param } : {});
      await expect.poll(() => order(page)).toEqual(expected);
      await page.reload();
      await expect(nav(page).getByRole('link', { name: label, exact: true })).toHaveAttribute('aria-current', 'page');
      await expect.poll(() => order(page)).toEqual(expected);
      expect(params(page)).toEqual(param ? { filter: param } : {});
      if (label === 'In progress') await shot(page, 'ac7-in-progress');
    }
    // API side of the filters
    expect((await listTasks(page, '?filter=in_progress')).map((t) => t.title)).toEqual(['T ip']);
    expect((await listTasks(page, '?filter=active')).map((t) => t.title)).toEqual(['T ip', 'T todo']);
    expect((await listTasks(page, '?filter=IN_PROGRESS')).length).toBe(3); // unknown -> all

    // ticking the only in-progress task in the In progress tab empties it
    await gotoTasks(page, '?filter=in_progress');
    await cb(page, 'T ip').check();
    await expect(page.getByText('Nothing in progress')).toBeVisible();
    // existing empty texts still there
    await nav(page).getByRole('link', { name: 'Active' }).click();
    await expect.poll(() => order(page)).toEqual(['T todo']);
    await cb(page, 'T todo').check();
    await expect(page.getByText('Nothing left to do')).toBeVisible();
  });

  test('AC8: order not-done (To do and In progress mixed) then done, newest first; priority ignored', async ({
    page,
  }) => {
    await signUp(page, 'ac8');
    await seed(page, 'O1', { priority: 'HIGH' });
    await seed(page, 'O2', { status: 'IN_PROGRESS', priority: 'LOW' });
    await seed(page, 'O3', { status: 'DONE', priority: 'HIGH' });
    await seed(page, 'O4');
    await seed(page, 'O5', { status: 'IN_PROGRESS', priority: 'MEDIUM' });
    await seed(page, 'O6', { status: 'DONE' });
    await gotoTasks(page);
    await expect.poll(() => order(page)).toEqual(['O5', 'O4', 'O2', 'O1', 'O6', 'O3']);
    await nav(page).getByRole('link', { name: 'Active' }).click();
    await expect.poll(() => order(page)).toEqual(['O5', 'O4', 'O2', 'O1']);
    expect((await listTasks(page)).map((t) => t.title)).toEqual(['O5', 'O4', 'O2', 'O1', 'O6', 'O3']);
    await shot(page, 'ac8-order');
  });
});

// ---------- AC9-AC13 Tags ----------

test.describe('T1 Tags', () => {
  test('AC9: Enter / comma add chips, × and Backspace remove, saved tags shown on row and persist', async ({
    page,
  }) => {
    await signUp(page, 'ac9');
    const t = await seed(page, 'Tag me');
    await gotoTasks(page);
    await openEdit(page, 'Tag me');
    await expect(editDialog(page).getByText('Press Enter or comma to add a tag.')).toBeVisible();
    const box = tagsBox(page);
    await box.fill('Home');
    await box.press('Enter');
    await expect(chips(page)).toHaveText(['Home']);
    await expect(box).toHaveValue('');
    await expect(editDialog(page)).toBeVisible(); // Enter in the tag box doesn't submit
    await box.pressSequentially('Errands,');
    await expect(chips(page)).toHaveText(['Home', 'Errands']);
    await expect(box).toHaveValue('');
    await box.pressSequentially('Temp');
    await box.press('Enter');
    await box.pressSequentially('  Two   words  ');
    await box.press('Enter');
    await expect(chips(page)).toHaveText(['Home', 'Errands', 'Temp', 'Two words']);
    await shot(page, 'ac9-chips');
    await editDialog(page).getByRole('button', { name: 'Remove tag Temp' }).click();
    await expect(chips(page)).toHaveText(['Home', 'Errands', 'Two words']);
    // Backspace on an empty input removes the last chip
    await box.press('Backspace');
    await expect(chips(page)).toHaveText(['Home', 'Errands']);
    // pasted "a,b" splits into chips
    await box.fill('Paste1,Paste2');
    await expect(chips(page)).toHaveText(['Home', 'Errands', 'Paste1']);
    await expect(box).toHaveValue('Paste2');
    // text still typed counts on Save
    await save(page);

    const saved = await getTask(page, t.id);
    expect(saved.tags).toEqual(['Errands', 'Home', 'Paste1', 'Paste2']);
    await expect(rowTags(page, 'Tag me')).toHaveText(['Errands', 'Home', 'Paste1', 'Paste2']);
    await shot(page, 'ac9-row-tags');
    await page.reload();
    await expect(rowTags(page, 'Tag me')).toHaveText(['Errands', 'Home', 'Paste1', 'Paste2']);
    await openEdit(page, 'Tag me');
    await expect(chips(page)).toHaveText(['Errands', 'Home', 'Paste1', 'Paste2']);
    // removing all + save clears tags
    for (const n of ['Errands', 'Home', 'Paste1', 'Paste2'])
      await editDialog(page).getByRole('button', { name: `Remove tag ${n}` }).click();
    await save(page);
    await expect(rowTags(page, 'Tag me')).toHaveCount(0);
    expect((await getTask(page, t.id)).tags).toEqual([]);
    expect(await listTags(page)).toEqual([]);
  });

  test('AC10: "wo" suggests Work; pick by click and by keyboard; "work"/"wörk" reuse Work', async ({ page }) => {
    await signUp(page, 'ac10');
    await seed(page, 'Has work', { tags: ['Work', 'Home'] });
    await seed(page, 'Target 1');
    await seed(page, 'Target 2');
    await seed(page, 'Target 3');
    await gotoTasks(page);

    // click a suggestion
    await openEdit(page, 'Target 1');
    await tagsBox(page).pressSequentially('wo');
    await expect(suggestions(page)).toBeVisible();
    await expect(suggestions(page).getByRole('option')).toHaveText(['Work']);
    await expect(tagsBox(page)).toHaveAttribute('aria-expanded', 'true');
    await shot(page, 'ac10-suggest');
    await suggestions(page).getByRole('option', { name: 'Work' }).click();
    await expect(chips(page)).toHaveText(['Work']);
    await expect(tagsBox(page)).toHaveValue('');
    await expect(tagsBox(page)).toBeFocused();
    // already-chosen tag isn't suggested again
    await tagsBox(page).pressSequentially('wo');
    await expect(suggestions(page).getByRole('option')).toHaveCount(0);
    await tagsBox(page).fill('');
    await save(page);
    await expect(rowTags(page, 'Target 1')).toHaveText(['Work']);

    // keyboard: ArrowDown + Enter
    await openEdit(page, 'Target 2');
    await tagsBox(page).pressSequentially('o');
    await expect(suggestions(page).getByRole('option')).toHaveText(['Home', 'Work']);
    await tagsBox(page).press('ArrowDown');
    await tagsBox(page).press('ArrowDown');
    await expect(suggestions(page).getByRole('option', { name: 'Work' })).toHaveAttribute('aria-selected', 'true');
    await tagsBox(page).press('Enter');
    await expect(chips(page)).toHaveText(['Work']);
    // Escape closes the list but not the dialog
    await tagsBox(page).pressSequentially('h');
    await expect(suggestions(page)).toBeVisible();
    await tagsBox(page).press('Escape');
    await expect(suggestions(page)).toBeHidden();
    await expect(editDialog(page)).toBeVisible();
    await tagsBox(page).fill('');
    await save(page);

    // typed variants reuse the stored spelling
    await openEdit(page, 'Target 3');
    await tagsBox(page).fill('work');
    await tagsBox(page).press('Enter');
    await expect(chips(page)).toHaveText(['Work']);
    await editDialog(page).getByRole('button', { name: 'Remove tag Work' }).click();
    await tagsBox(page).fill('wörk');
    await tagsBox(page).press('Enter');
    await expect(chips(page)).toHaveText(['Work']);
    await tagsBox(page).fill('HOME');
    await tagsBox(page).press('Enter');
    await expect(chips(page)).toHaveText(['Work', 'Home']);
    await save(page);
    await expect(rowTags(page, 'Target 3')).toHaveText(['Home', 'Work']);
    expect(await listTags(page)).toEqual(['Home', 'Work']);

    // server side: a variant sent directly also keeps the stored spelling
    const t3 = (await listTasks(page)).find((t) => t.title === 'Target 3')!;
    const viaApi = await putTask(page, t3.id, { title: 'Target 3', tags: ['WÖRK', 'hOme'] });
    expect(viaApi.tags).toEqual(['Home', 'Work']);
    expect(await listTags(page)).toEqual(['Home', 'Work']);
  });

  test('AC11: duplicates ignored, 11th tag rejected, >30 chars rejected, blank ignored; server limits', async ({
    page,
  }) => {
    await signUp(page, 'ac11');
    const t = await seed(page, 'Limits');
    await gotoTasks(page);
    await openEdit(page, 'Limits');
    const box = tagsBox(page);
    const err = editDialog(page).locator('#task-edit-tags-error');
    await box.fill('Café');
    await box.press('Enter');
    for (const v of ['café', 'CAFE', ' cafe ']) {
      await box.fill(v);
      await box.press('Enter');
    }
    await expect(chips(page)).toHaveText(['Café']);
    // blank ignored
    await box.fill('    ');
    await box.press('Enter');
    await expect(chips(page)).toHaveText(['Café']);
    await expect(err).toHaveCount(0);
    for (let i = 2; i <= 10; i++) {
      await box.fill(`T${i}`);
      await box.press('Enter');
    }
    await expect(chips(page)).toHaveCount(10);
    await box.fill('Eleven');
    await box.press('Enter');
    await expect(err).toHaveText('A task can have at most 10 tags');
    await expect(chips(page)).toHaveCount(10);
    await expect(box).toHaveAttribute('aria-invalid', 'true');
    await shot(page, 'ac11-too-many');
    // a duplicate of an existing chip at 10 is still fine (no error, no chip)
    await box.fill('t5');
    await box.press('Enter');
    await expect(chips(page)).toHaveCount(10);
    await editDialog(page).getByRole('button', { name: 'Remove tag T10' }).click();
    await expect(err).toHaveCount(0);
    const long = 'x'.repeat(31);
    await box.fill(long);
    await box.press('Enter');
    await expect(err).toHaveText('Tags must be 30 characters or fewer');
    await expect(chips(page)).toHaveCount(9);
    await shot(page, 'ac11-too-long');
    // Save with the bad text still typed is blocked locally
    const puts = countRequests(page, 'PUT', /^\/api\/tasks\/\d+$/);
    await editDialog(page).getByRole('button', { name: 'Save' }).click();
    await expect(editDialog(page)).toBeVisible();
    await expect(err).toHaveText('Tags must be 30 characters or fewer');
    expect(puts.length).toBe(0);
    await box.fill('y'.repeat(30));
    await box.press('Enter');
    await expect(chips(page)).toHaveCount(10);
    await expect(err).toHaveCount(0);
    await save(page);
    const saved = await getTask(page, t.id);
    expect(saved.tags.length).toBe(10);
    expect(saved.tags).toContain('y'.repeat(30));
    expect(saved.tags).toContain('Café');

    // API-level per-field codes (contract: field `tags`, one entry)
    const cases: [unknown, string, string][] = [
      [['  '], 'REQUIRED', "Tags can't be blank"],
      [['a,b'], 'INVALID_CHARACTERS', "Tags can't contain commas or control characters"],
      [['a\nb'], 'INVALID_CHARACTERS', "Tags can't contain commas or control characters"],
      [['z'.repeat(31)], 'TOO_LONG', 'Tags must be 30 characters or fewer'],
      [Array.from({ length: 11 }, (_, i) => `n${i}`), 'TOO_MANY', 'A task can have at most 10 tags'],
    ];
    for (const [tags, code, message] of cases) {
      const b = await expectProblem(await api(page, 'PUT', `/api/tasks/${t.id}`, { title: 'Limits', tags }), 400, 'VALIDATION_FAILED');
      expect(b.errors).toEqual([{ field: 'tags', code, message }]);
    }
    // 11 entries that are 10 after removing duplicates are fine; 30 chars after normalising is fine
    const ok = await putTask(page, t.id, {
      title: 'Limits',
      tags: [...Array.from({ length: 10 }, (_, i) => `d${i}`), 'D0'],
    });
    expect(ok.tags.length).toBe(10);
    const norm = await putTask(page, t.id, { title: 'Limits', tags: [`  ${'a'.repeat(14)}   ${'b'.repeat(15)} `] });
    expect(norm.tags).toEqual([`${'a'.repeat(14)} ${'b'.repeat(15)}`]);
    // wrong types -> MALFORMED_REQUEST, no errors array
    for (const bad of [{ tags: 'Work' }, { tags: [1] }, { priority: 'URGENT' }, { priority: 'high' }, { status: 'done' }]) {
      const b = await expectProblem(await api(page, 'PUT', `/api/tasks/${t.id}`, { title: 'Limits', ...bad }), 400, 'MALFORMED_REQUEST');
      expect(b.errors).toBeUndefined();
    }
    expect((await getTask(page, t.id)).tags).toEqual(norm.tags);
  });

  test('AC11/Q13: 200-tag cap per user -> TAG_LIMIT alone, nothing saved; UI shows it under Tags (AC17)', async ({
    page,
    watch,
  }) => {
    watch.allowNetworkErrors = true;
    await signUp(page, 'cap');
    for (let i = 0; i < 20; i++) {
      const t = await createTask(page, { title: `Cap ${i}` });
      await putTask(page, t.id, { title: `Cap ${i}`, tags: Array.from({ length: 10 }, (_, j) => `c${i}-${j}`) });
    }
    expect((await listTags(page)).length).toBe(200);
    const extra = await createTask(page, { title: 'Over cap', description: 'keep' });
    const res = await api(page, 'PUT', `/api/tasks/${extra.id}`, { title: 'Changed', description: 'keep', tags: ['brand new'] });
    const b = await expectProblem(res, 400, 'VALIDATION_FAILED');
    expect(b.errors).toEqual([{ field: 'tags', code: 'TAG_LIMIT', message: 'A list can have at most 200 tags' }]);
    expect(await getTask(page, extra.id)).toEqual(extra); // nothing saved (title unchanged)
    // reusing existing tags is fine at the cap
    expect((await putTask(page, extra.id, { title: 'Over cap', tags: ['C0-0', 'c1-1'] })).tags).toEqual(['c0-0', 'c1-1']);
    // swapping: freeing a tag in the same request lets a new one in (counted after cleanup)
    const t0 = (await listTasks(page)).find((t) => t.title === 'Cap 0')!;
    const swapped = await putTask(page, t0.id, {
      title: 'Cap 0',
      tags: [...Array.from({ length: 9 }, (_, j) => `c0-${j}`), 'swap-in'],
    });
    expect(swapped.tags).toContain('swap-in');
    expect((await listTags(page)).length).toBe(200);
    // with too many tags AND a bad title, TAG_LIMIT is not reported (only checked when all else valid)
    const both = await expectProblem(
      await api(page, 'PUT', `/api/tasks/${extra.id}`, { title: '', tags: ['another new'] }),
      400,
      'VALIDATION_FAILED',
    );
    expect(both.errors).toEqual([{ field: 'title', code: 'REQUIRED', message: 'Title is required' }]);

    // UI: server field error shown under Tags, dialog stays open, input kept
    await gotoTasks(page);
    await openEdit(page, 'Over cap');
    await tagsBox(page).fill('Totally new');
    await tagsBox(page).press('Enter');
    await editDialog(page).getByRole('button', { name: 'Save' }).click();
    await expect(editDialog(page).locator('#task-edit-tags-error')).toHaveText('A list can have at most 200 tags');
    await expect(editDialog(page)).toBeVisible();
    await expect(chips(page)).toHaveText(['c0-0', 'c1-1', 'Totally new']);
    await expect(editDialog(page).getByRole('alert')).toHaveCount(0);
    await shot(page, 'ac17-tag-limit');
    // removing the new tag lets the save go through
    await editDialog(page).getByRole('button', { name: 'Remove tag Totally new' }).click();
    await save(page);
  });

  test('AC12: click a row tag filters within the tab, URL ?tag=, bar with ×, refresh, empty text', async ({
    page,
  }) => {
    await signUp(page, 'ac12');
    await seed(page, 'W todo', { tags: ['Work'] });
    await seed(page, 'W done', { tags: ['Work', 'Home'], status: 'DONE' });
    await seed(page, 'H only', { tags: ['Home'] });
    await seed(page, 'Plain');
    await gotoTasks(page, '?filter=active');
    await expect.poll(() => order(page)).toEqual(['Plain', 'H only', 'W todo']);

    await row(page, 'W todo').getByRole('link', { name: 'Work', exact: true }).click();
    await expect.poll(() => params(page)).toEqual({ filter: 'active', tag: 'Work' });
    await expect.poll(() => order(page)).toEqual(['W todo']);
    const bar = page.getByTestId('tag-filter-bar');
    await expect(bar).toContainText('Tag: Work');
    await expect(nav(page).getByRole('link', { name: 'Active' })).toHaveAttribute('aria-current', 'page');
    await shot(page, 'ac12-tag-active');

    // switching tab keeps the tag
    await nav(page).getByRole('link', { name: 'All' }).click();
    await expect.poll(() => order(page)).toEqual(['W todo', 'W done']);
    expect(params(page).tag).toBe('Work');
    await nav(page).getByRole('link', { name: 'Done' }).click();
    await expect.poll(() => params(page)).toEqual({ filter: 'done', tag: 'Work' });
    await expect.poll(() => order(page)).toEqual(['W done']);
    await page.reload();
    await expect(bar).toContainText('Tag: Work');
    await expect.poll(() => order(page)).toEqual(['W done']);

    // In progress + Work: none
    await nav(page).getByRole('link', { name: 'In progress' }).click();
    await expect(page.getByText('No tasks with this tag')).toBeVisible();
    await expect(bar).toContainText('Tag: Work');
    await shot(page, 'ac12-tag-empty');

    // clear
    await page.getByRole('button', { name: 'Clear tag filter' }).click();
    await expect.poll(() => params(page)).toEqual({ filter: 'in_progress' });
    await expect(bar).toHaveCount(0);
    await expect(page.getByText('Nothing in progress')).toBeVisible();

    // click a different tag replaces it
    await gotoTasks(page);
    await row(page, 'W done').getByRole('link', { name: 'Home', exact: true }).click();
    await expect.poll(() => order(page)).toEqual(['H only', 'W done']);
    expect(params(page)).toEqual({ tag: 'Home' });

    // case/accent-insensitive param, bar shows stored spelling
    await gotoTasks(page, '?tag=wörk');
    await expect.poll(() => order(page)).toEqual(['W todo', 'W done']);
    await expect(bar).toContainText('Tag: Work');
    // unknown / invalid tag -> empty, never an error
    for (const q of ['?tag=Nope', '?tag=a%2Cb', `?tag=${'x'.repeat(40)}`]) {
      await gotoTasks(page, q);
      await expect(page.getByText('No tasks with this tag')).toBeVisible();
      await expect(page.getByRole('alert')).toHaveCount(0);
    }
    expect(await listTasks(page, '?tag=Nope')).toEqual([]);
    expect((await listTasks(page, '?tag=%20%20')).length).toBe(4); // blank = no filter
    expect((await listTasks(page, '?tag=work&tag=home')).map((t) => t.title)).toEqual(['W todo', 'W done']);
  });

  test('AC13: a tag used by one task disappears when removed from it or the task is deleted', async ({ page }) => {
    await signUp(page, 'ac13');
    await seed(page, 'Has old', { tags: ['Old', 'Keep'] });
    await seed(page, 'Has old 2', { tags: ['Gone'] });
    await seed(page, 'Other', { tags: ['Keep'] });
    await gotoTasks(page);
    expect(await listTags(page)).toEqual(['Gone', 'Keep', 'Old']);

    await openEdit(page, 'Has old');
    await editDialog(page).getByRole('button', { name: 'Remove tag Old' }).click();
    await save(page);
    expect(await listTags(page)).toEqual(['Gone', 'Keep']);

    await row(page, 'Has old 2').getByRole('button', { name: 'Delete' }).click();
    await page.getByRole('dialog', { name: "Delete 'Has old 2'?" }).getByRole('button', { name: 'Delete' }).click();
    await expect(row(page, 'Has old 2')).toHaveCount(0);
    expect(await listTags(page)).toEqual(['Keep']);

    await openEdit(page, 'Other');
    for (const typed of ['ol', 'go']) {
      await tagsBox(page).fill('');
      await tagsBox(page).pressSequentially(typed);
      await expect(suggestions(page).getByRole('option')).toHaveCount(0);
    }
    await tagsBox(page).fill('');
    await tagsBox(page).press('ArrowDown'); // opens with all suggestions (empty text matches all)
    await expect(suggestions(page).getByRole('option')).toHaveCount(0); // Keep already on the task
    await editDialog(page).getByRole('button', { name: 'Cancel' }).click();
    // re-adding later creates it with the new spelling
    const t = (await listTasks(page)).find((x) => x.title === 'Other')!;
    expect((await putTask(page, t.id, { title: 'Other', tags: ['Keep', 'OLD'] })).tags).toEqual(['Keep', 'OLD']);
  });
});

// ---------- AC14 Ownership ----------

test.describe('T1 Ownership', () => {
  test('AC14: A and B each see only their own tags, suggestions, filters; A cannot attach B\'s tags', async ({
    page,
    browser,
  }) => {
    await signUp(page, 'ac14a');
    const aTask = await seed(page, 'A work', { tags: ['Work', 'A secret'] });
    const B = await newUserPage(browser, 'ac14b');
    const bTask = await seed(B.p, 'B work', { tags: ['WORK', 'B private'] });

    expect(await listTags(page)).toEqual(['A secret', 'Work']);
    expect(await listTags(B.p)).toEqual(['B private', 'WORK']);
    expect((await listTasks(page, '?tag=Work')).map((t) => t.id)).toEqual([aTask.id]);
    expect((await listTasks(B.p, '?tag=work')).map((t) => t.id)).toEqual([bTask.id]);
    expect(await listTasks(page, '?tag=B%20private')).toEqual([]);

    // A "attaches" B's tag name -> becomes A's own new tag; B unaffected
    const a2 = await putTask(page, aTask.id, { title: 'A work', tags: ['Work', 'B private'] });
    expect(a2.tags).toEqual(['B private', 'Work']);
    expect(await listTags(B.p)).toEqual(['B private', 'WORK']);
    expect((await listTasks(B.p, '?tag=B%20private')).map((t) => t.id)).toEqual([bTask.id]);
    // A removing it deletes only A's tag
    await putTask(page, aTask.id, { title: 'A work', tags: ['Work'] });
    expect(await listTags(B.p)).toEqual(['B private', 'WORK']);
    // A can't read or change B's task (404 everywhere)
    for (const [m, body] of [
      ['GET', undefined],
      ['PUT', { title: 'x', tags: ['pwn'] }],
      ['PATCH', { status: 'DONE' }],
    ] as [string, unknown][]) {
      await expectProblem(await api(page, m, `/api/tasks/${bTask.id}`, body), 404, 'NOT_FOUND');
    }
    expect(await getTask(B.p, bTask.id)).toEqual(bTask);

    // UI: suggestions for A never include B's tags
    await gotoTasks(page);
    await openEdit(page, 'A work');
    await tagsBox(page).pressSequentially('b');
    await expect(suggestions(page).getByRole('option')).toHaveCount(0);
    await tagsBox(page).fill('');
    await tagsBox(page).pressSequentially('w');
    await expect(suggestions(page).getByRole('option')).toHaveCount(0); // own Work already chosen
    await editDialog(page).getByRole('button', { name: 'Cancel' }).click();
    await gotoTasks(page, '?tag=B%20private');
    await expect(page.getByText('No tasks with this tag')).toBeVisible();
    await shot(page, 'ac14-a-cannot-filter-b');
    await gotoTasks(B.p, '?tag=Work');
    await expect.poll(() => order(B.p)).toEqual(['B work']);
    await expect(B.p.getByTestId('tag-filter-bar')).toContainText('Tag: WORK');

    // Admin can't see anyone's tasks or tags (A5 AC10)
    const Adm = await newUserPage(browser, 'ac14adm');
    sql(`UPDATE users SET role = 'SUPER_ADMIN' WHERE id = ${Adm.id}`);
    expect(await listTags(Adm.p)).toEqual([]);
    expect(await listTasks(Adm.p, '?tag=Work')).toEqual([]);
    await expectProblem(await api(Adm.p, 'GET', `/api/tasks/${bTask.id}`), 404, 'NOT_FOUND');
    const admList = await api(Adm.p, 'GET', `/api/admin/users?q=${encodeURIComponent(`ac14b ${RUN}`)}`);
    expect(admList.status()).toBe(200);
    expect(await admList.text()).not.toMatch(/B private|WORK|tags|priority/);

    expect(B.errors, 'B console').toEqual([]);
    expect(Adm.errors, 'admin console').toEqual([]);
    await B.ctx.close();
    await Adm.ctx.close();
  });
});

// ---------- AC15-AC16 Compatibility ----------

test.describe('T1 Compatibility', () => {
  test('AC15: no task in the DB has status/done disagreeing; pre-V9 rows are NONE with no tags', async () => {
    // Full backfill proof is backend V9MigrationTest; this checks the real local DB after V9 ran.
    expect(sql(`SELECT COUNT(*) FROM tasks WHERE (status = 'DONE') <> done`)).toBe('0');
    const installed = sql(`SELECT installed_on FROM flyway_schema_history WHERE version = '9'`);
    expect(installed).not.toBe('');
    const old = sql(
      `SELECT COUNT(*) FROM tasks t WHERE t.created_at < '${installed}' AND (t.priority <> 'NONE' OR t.status = 'IN_PROGRESS'
         OR EXISTS (SELECT 1 FROM task_tags tt WHERE tt.task_id = t.id)) AND t.updated_at < '${installed}'`,
    );
    expect(old).toBe('0');
  });

  test('AC16: PUT without new fields leaves them; PATCH {done} true/false; status/done combos', async ({ page }) => {
    await signUp(page, 'ac16');
    const t = await seed(page, 'Compat', { priority: 'HIGH', status: 'IN_PROGRESS', tags: ['Keep', 'Me'] });

    // old-client PUT (0.9.0 shape, with done:false) -> priority, tags, IN_PROGRESS kept
    const r1 = await putTask(page, t.id, { title: 'Compat 2', description: 'd', dueDate: null, done: false });
    expect(r1).toMatchObject({ title: 'Compat 2', priority: 'HIGH', status: 'IN_PROGRESS', tags: ['Keep', 'Me'], done: false });
    // no done at all
    const r2 = await putTask(page, t.id, { title: 'Compat 3', description: null, dueDate: null });
    expect(r2).toMatchObject({ priority: 'HIGH', status: 'IN_PROGRESS', tags: ['Keep', 'Me'] });
    // explicit nulls = unchanged
    const r3 = await putTask(page, t.id, { title: 'Compat 3', priority: null, status: null, tags: null, done: null });
    expect(r3).toMatchObject({ priority: 'HIGH', status: 'IN_PROGRESS', tags: ['Keep', 'Me'] });
    // old-client PUT done:true -> DONE
    expect(await putTask(page, t.id, { title: 'Compat 3', done: true })).toMatchObject({ status: 'DONE', done: true });
    // done:false on DONE -> TODO
    expect(await putTask(page, t.id, { title: 'Compat 3', done: false })).toMatchObject({ status: 'TODO', done: false });
    // agreeing both ok, disagreeing conflict
    expect(await putTask(page, t.id, { title: 'Compat 3', status: 'DONE', done: true })).toMatchObject({ status: 'DONE' });
    const c = await expectProblem(
      await api(page, 'PUT', `/api/tasks/${t.id}`, { title: 'Compat 3', status: 'IN_PROGRESS', done: true }),
      400,
      'VALIDATION_FAILED',
    );
    expect(c.errors).toEqual([{ field: 'status', code: 'STATUS_CONFLICT', message: "Status and done don't match" }]);
    // tags: [] clears
    expect((await putTask(page, t.id, { title: 'Compat 3', tags: [] })).tags).toEqual([]);

    // PATCH
    const patch = async (body: unknown) => {
      const res = await api(page, 'PATCH', `/api/tasks/${t.id}`, body);
      expect(res.status(), await res.text()).toBe(200);
      const j = (await res.json()) as Task;
      expectTaskShape(j);
      return j;
    };
    expect(await patch({ done: false })).toMatchObject({ status: 'TODO', done: false });
    expect(await patch({ done: true })).toMatchObject({ status: 'DONE', done: true });
    expect(await patch({ done: false })).toMatchObject({ status: 'TODO', done: false });
    expect(await patch({ status: 'IN_PROGRESS' })).toMatchObject({ status: 'IN_PROGRESS', done: false });
    expect(await patch({ done: false })).toMatchObject({ status: 'IN_PROGRESS', done: false });
    expect(await patch({ done: true, status: 'DONE' })).toMatchObject({ status: 'DONE', done: true });
    expect(await patch({ status: 'TODO', priority: 'LOW', tags: ['x'] })).toMatchObject({ status: 'TODO', priority: 'HIGH', tags: [] });
    const pc = await expectProblem(await api(page, 'PATCH', `/api/tasks/${t.id}`, { done: true, status: 'TODO' }), 400, 'VALIDATION_FAILED');
    expect(pc.errors).toEqual([{ field: 'status', code: 'STATUS_CONFLICT', message: "Status and done don't match" }]);
    const pr = await expectProblem(await api(page, 'PATCH', `/api/tasks/${t.id}`, {}), 400, 'VALIDATION_FAILED');
    expect(pr.errors).toEqual([{ field: 'done', code: 'REQUIRED', message: 'Done or status is required' }]);
    const pn = await expectProblem(await api(page, 'PATCH', `/api/tasks/${t.id}`, { done: null, status: null }), 400, 'VALIDATION_FAILED');
    expect(pn.errors).toEqual([{ field: 'done', code: 'REQUIRED', message: 'Done or status is required' }]);
    await expectProblem(await api(page, 'PATCH', `/api/tasks/${t.id}`, { status: 'in_progress' }), 400, 'MALFORMED_REQUEST');
    // GET /api/tags unauthenticated -> 401 problem
    const anon = await browserlessGet(page, '/api/tags');
    expect(anon).toBe(401);
  });
});

async function browserlessGet(page: Page, path: string): Promise<number> {
  const ctx = await page.context().browser()!.newContext({ baseURL: process.env['E2E_BASE_URL'] ?? 'http://localhost:4200' });
  const r = await ctx.request.get(path);
  const s = r.status();
  expect(r.headers()['content-type']).toContain('application/problem+json');
  await ctx.close();
  return s;
}

// ---------- AC17 States + edges ----------

test.describe('T1 States', () => {
  test('AC17: controls disabled while saving; failure keeps dialog + input with Retry; Retry saves', async ({
    page,
    watch,
  }) => {
    watch.allowNetworkErrors = true;
    await signUp(page, 'ac17');
    await seed(page, 'Known', { tags: ['Work'] });
    const t = await seed(page, 'Busy');
    await gotoTasks(page);

    let mode: 'slow' | 'fail' | 'pass' = 'slow';
    await page.route(/\/api\/tasks\/\d+$/, async (route) => {
      if (route.request().method() !== 'PUT') return route.continue();
      if (mode === 'fail') return route.abort('failed');
      if (mode === 'slow') await delay(1500);
      return route.continue();
    });

    await openEdit(page, 'Busy');
    const dlg = editDialog(page);
    await dlg.getByLabel('Priority').selectOption('HIGH');
    await dlg.getByLabel('Status').selectOption('IN_PROGRESS');
    await tagsBox(page).fill('Work');
    await tagsBox(page).press('Enter');
    await tagsBox(page).fill('Fresh');
    await tagsBox(page).press('Enter');
    await dlg.getByRole('button', { name: 'Save' }).click();
    for (const l of [dlg.getByLabel('Priority'), dlg.getByLabel('Status'), tagsBox(page), dlg.getByLabel('Title'),
      dlg.getByRole('button', { name: 'Save' }), dlg.getByRole('button', { name: 'Cancel' }),
      dlg.getByRole('button', { name: 'Remove tag Work' })]) {
      await expect(l).toBeDisabled();
    }
    await shot(page, 'ac17-saving');
    await expect(dlg).toHaveCount(0);
    await expect(row(page, 'Busy').getByTestId('task-priority')).toHaveText('High');

    // failure
    mode = 'fail';
    await openEdit(page, 'Busy');
    await dlg.getByLabel('Priority').selectOption('LOW');
    await dlg.getByLabel('Status').selectOption('DONE');
    await editDialog(page).getByRole('button', { name: 'Remove tag Fresh' }).click();
    await tagsBox(page).fill('Another');
    await tagsBox(page).press('Enter');
    await dlg.getByRole('button', { name: 'Save' }).click();
    const alert = dlg.getByRole('alert');
    await expect(alert).toBeVisible();
    await expect(alert.getByRole('button', { name: 'Retry' })).toBeVisible();
    console.log('AC17 save banner:', await alert.textContent());
    await expect(dlg.getByLabel('Priority')).toHaveValue('LOW');
    await expect(dlg.getByLabel('Status')).toHaveValue('DONE');
    await expect(chips(page)).toHaveText(['Work', 'Another']);
    await expect(dlg.getByLabel('Priority')).toBeEnabled();
    await expect(tagsBox(page)).toBeEnabled();
    await shot(page, 'ac17-save-failed');
    expect(await getTask(page, t.id)).toMatchObject({ priority: 'HIGH', status: 'IN_PROGRESS', tags: ['Fresh', 'Work'] });
    mode = 'pass';
    await alert.getByRole('button', { name: 'Retry' }).click();
    await expect(dlg).toHaveCount(0);
    expect(await getTask(page, t.id)).toMatchObject({ priority: 'LOW', status: 'DONE', tags: ['Another', 'Work'] });
    await expect(cb(page, 'Busy')).toBeChecked();
    expect(await listTags(page)).toEqual(['Another', 'Work']); // Fresh cleaned up
  });

  test('AC17: server field errors land under Status and Tags (faked 400s)', async ({ page, watch }) => {
    watch.allowNetworkErrors = true;
    await signUp(page, 'ac17f');
    await seed(page, 'Fields');
    await gotoTasks(page);
    let errors = [{ field: 'status', code: 'STATUS_CONFLICT', message: "Status and done don't match" }];
    await page.route(/\/api\/tasks\/\d+$/, (route) =>
      route.request().method() === 'PUT'
        ? route.fulfill({
            status: 400,
            contentType: 'application/problem+json',
            body: JSON.stringify({ type: 'about:blank', title: 'Bad Request', status: 400, detail: 'Validation failed', code: 'VALIDATION_FAILED', errors }),
          })
        : route.continue(),
    );
    await openEdit(page, 'Fields');
    await editDialog(page).getByLabel('Status').selectOption('IN_PROGRESS');
    await editDialog(page).getByRole('button', { name: 'Save' }).click();
    await expect(editDialog(page).locator('#task-edit-status-error')).toHaveText("Status and done don't match");
    await expect(editDialog(page).getByLabel('Status')).toHaveAttribute('aria-describedby', 'task-edit-status-error');
    await expect(editDialog(page).getByLabel('Status')).toHaveValue('IN_PROGRESS');
    await shot(page, 'ac17-status-error');
    // a server error stays until the field changes: Save again sends nothing
    const puts = countRequests(page, 'PUT', /^\/api\/tasks\/\d+$/);
    await editDialog(page).getByRole('button', { name: 'Save' }).click();
    await page.waitForTimeout(500);
    console.log('AC17 PUTs on re-save with server status error still shown:', puts.length);
    await editDialog(page).getByLabel('Status').selectOption('TODO');
    await expect(editDialog(page).locator('#task-edit-status-error')).toHaveCount(0);
    errors = [{ field: 'tags', code: 'TOO_MANY', message: 'A task can have at most 10 tags' }];
    await tagsBox(page).fill('One');
    await tagsBox(page).press('Enter');
    await editDialog(page).getByRole('button', { name: 'Save' }).click();
    await expect(editDialog(page).locator('#task-edit-tags-error')).toHaveText('A task can have at most 10 tags');
    await expect(tagsBox(page)).toHaveAttribute('aria-invalid', 'true');
    await expect(chips(page)).toHaveText(['One']);
    await expect(editDialog(page)).toBeVisible();
  });

  test('Edge: concurrent tag saves by different users all succeed (no 500)', async ({ browser }) => {
    const users = await Promise.all([0, 1, 2, 3].map((i) => newUserPage(browser, `conc${i}`)));
    const results: number[] = [];
    for (let round = 0; round < 5; round++) {
      const tasks = await Promise.all(users.map((u) => createTask(u.p, { title: `Conc ${round}` })));
      const res = await Promise.all(
        users.map(async (u, i) =>
          (await api(u.p, 'PUT', `/api/tasks/${tasks[i].id}`, { title: `Conc ${round}`, tags: ['Work', 'Home'] })).status(),
        ),
      );
      results.push(...res);
    }
    console.log('Concurrent PUT statuses:', JSON.stringify(results));
    expect(results.filter((s) => s !== 200), 'non-200 responses for concurrent tag saves').toEqual([]);
    for (const u of users) await u.ctx.close();
  });

  test('Edge: double-click Save sends one PUT; tags load failure still lets you edit; refresh mid-edit saves nothing', async ({
    page,
    watch,
  }) => {
    watch.allowNetworkErrors = true;
    await signUp(page, 'edge');
    await seed(page, 'Dbl', { tags: ['Work'] });
    await gotoTasks(page);
    const puts = countRequests(page, 'PUT', /^\/api\/tasks\/\d+$/);
    await openEdit(page, 'Dbl');
    await editDialog(page).getByLabel('Priority').selectOption('MEDIUM');
    await editDialog(page).getByRole('button', { name: 'Save' }).dblclick();
    await expect(editDialog(page)).toHaveCount(0);
    expect(puts.length).toBe(1);

    // GET /api/tags fails -> dialog still works, no suggestions, no banner
    await page.route('**/api/tags', (r) => r.abort('failed'));
    await openEdit(page, 'Dbl');
    await tagsBox(page).pressSequentially('wo');
    await expect(suggestions(page).getByRole('option')).toHaveCount(0);
    await tagsBox(page).fill('work');
    await tagsBox(page).press('Enter');
    await expect(chips(page)).toHaveText(['Work']); // already on task, de-duplicated
    await expect(editDialog(page).getByRole('alert')).toHaveCount(0);
    await tagsBox(page).fill('New');
    await tagsBox(page).press('Enter');
    await save(page);
    await expect(rowTags(page, 'Dbl')).toHaveText(['New', 'Work']);

    // refresh mid-flow (dialog open with unsaved chips) -> nothing saved
    await openEdit(page, 'Dbl');
    await tagsBox(page).fill('Unsaved');
    await tagsBox(page).press('Enter');
    await page.reload();
    await expect(editDialog(page)).toHaveCount(0);
    await expect(rowTags(page, 'Dbl')).toHaveText(['New', 'Work']);
  });

  test('Phone width 375px + 320px: badges/chips wrap under the title, no horizontal scroll', async ({ page }) => {
    await page.setViewportSize({ width: 375, height: 800 });
    await signUp(page, 'phone');
    await seed(page, 'A rather long task title that needs wrapping on small screens', {
      priority: 'HIGH',
      status: 'IN_PROGRESS',
      dueDate: '2020-02-02',
      tags: ['Work', 'Home', 'Errands', 'A tag of thirty characters xx', 'Ünïcödé 😀'],
    });
    await seed(page, 'Short', { priority: 'LOW', tags: ['Work'] });
    await gotoTasks(page);
    const title = 'A rather long task title that needs wrapping on small screens';
    const r = row(page, title);
    await expect(r.getByTestId('task-priority')).toBeVisible();
    const sw = await page.evaluate(() => document.documentElement.scrollWidth);
    const cw = await page.evaluate(() => document.documentElement.clientWidth);
    expect(sw, 'no horizontal scroll').toBeLessThanOrEqual(cw);
    const titleBox = (await r.getByRole('button', { name: title, exact: true }).boundingBox())!;
    const badgeBox = (await r.getByTestId('task-priority').boundingBox())!;
    expect(badgeBox.y, 'badges below the title').toBeGreaterThanOrEqual(titleBox.y + titleBox.height - 2);
    // nothing in the row sticks out of the viewport
    const overflow = await r.evaluate((el) =>
      [...el.querySelectorAll('*')].filter((n) => n.getBoundingClientRect().right > window.innerWidth + 0.5).length,
    );
    expect(overflow).toBe(0);
    await shot(page, 'phone-row');
    await openEdit(page, title);
    // Save/Cancel reachable on a phone
    await editDialog(page).getByRole('button', { name: 'Save' }).scrollIntoViewIfNeeded();
    await expect(editDialog(page).getByRole('button', { name: 'Save' })).toBeInViewport();
    const dsw = await page.evaluate(() => document.documentElement.scrollWidth);
    expect(dsw).toBeLessThanOrEqual(cw);
    const dlgBox = (await editDialog(page).boundingBox())!;
    expect(dlgBox.x + dlgBox.width).toBeLessThanOrEqual(375.5);
    await shot(page, 'phone-dialog');
    await gotoTasks(page, '?tag=Work');
    await expect(page.getByTestId('tag-filter-bar')).toBeVisible();
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(cw);
    await shot(page, 'phone-tag-filter');

    // UI notes: only badges/chips wrap under the title; the checkbox should stay on the title's line
    await gotoTasks(page);
    for (const t of ['Short', title]) {
      const c = (await cb(page, t).boundingBox())!;
      const tb = (await row(page, t).getByRole('button', { name: t, exact: true }).boundingBox())!;
      console.log(`phone ${t.slice(0, 10)}: checkbox y=${c.y.toFixed(0)}-${(c.y + c.height).toFixed(0)}, title y=${tb.y.toFixed(0)}-${(tb.y + tb.height).toFixed(0)}`);
      expect(c.y + c.height, 'checkbox on the same line as the title (not above it)').toBeGreaterThan(tb.y);
      expect(c.x + c.width, 'checkbox left of the title').toBeLessThanOrEqual(tb.x + 0.5);
      expect(c.y, 'checkbox top not below the title first line').toBeLessThan(tb.y + 24);
    }

    // 320px (smallest common phone): still no horizontal scroll, checkbox inline with the title
    await page.setViewportSize({ width: 320, height: 800 });
    await gotoTasks(page);
    await expect(row(page, title).getByTestId('task-priority')).toBeVisible();
    const sw320 = await page.evaluate(() => document.documentElement.scrollWidth);
    const cw320 = await page.evaluate(() => document.documentElement.clientWidth);
    expect(sw320, 'no horizontal scroll at 320px').toBeLessThanOrEqual(cw320);
    for (const t of ['Short', title]) {
      const over = await row(page, t).evaluate((el) =>
        [...el.querySelectorAll('*')].filter((n) => n.getBoundingClientRect().right > window.innerWidth + 0.5).length,
      );
      expect(over, `nothing in row "${t.slice(0, 10)}" sticks out at 320px`).toBe(0);
      const c = (await cb(page, t).boundingBox())!;
      const tb = (await row(page, t).getByRole('button', { name: t, exact: true }).boundingBox())!;
      console.log(`phone320 ${t.slice(0, 10)}: checkbox x=${c.x.toFixed(0)} y=${c.y.toFixed(0)}-${(c.y + c.height).toFixed(0)}, title x=${tb.x.toFixed(0)} y=${tb.y.toFixed(0)}-${(tb.y + tb.height).toFixed(0)}`);
      expect(c.y + c.height, '320: checkbox not above the title').toBeGreaterThan(tb.y);
      expect(c.x + c.width, '320: checkbox left of the title').toBeLessThanOrEqual(tb.x + 0.5);
      expect(c.y, '320: checkbox top on the title first line').toBeLessThan(tb.y + 24);
    }
    await shot(page, 'phone-row-320');
  });
});
