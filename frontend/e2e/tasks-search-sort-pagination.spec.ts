import { APIResponse, Browser, expect, Locator, Page, Request, test as base } from '@playwright/test';
import { execFileSync } from 'node:child_process';

/*
 * E2E for specs/tasks-search-sort-pagination.md (T2: AC1-AC14 + Decisions log), contract 0.11.0.
 * AC15 = the updated tasks.spec.ts + tasks-priority-status-tags.spec.ts suites; AC16 is timed by hand.
 * Needs `docker compose up -d db` (AC14 admin check uses `docker compose exec db mysql`).
 * Every test fails on browser console errors / page errors. Tests that deliberately abort or fake
 * failed requests allow only the browser's own network log lines.
 */

const RUN = `${Date.now()}-${Math.floor(Math.random() * 1e6)}`;
const SHOTS = '../test-results/tasks-search-sort-pagination';
const PASSWORD = 'password123';
const BASE = process.env['E2E_BASE_URL'] ?? 'http://localhost:4200';
let counter = 0;
function uniqueEmail(tag: string): string {
  counter += 1;
  return `qa-t2-${RUN}-${tag}-${counter}@example.com`;
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
interface TaskPage {
  items: Task[];
  total: number;
  nextCursor: string | null;
}

// 0.12.0 (T3a): Task gained the required listId.
// 0.13.0 (T3b): Task gained addedBy.
const TASK_KEYS = ['addedBy', 'createdAt', 'description', 'done', 'dueDate', 'id', 'listId', 'priority', 'status', 'tags', 'title', 'updatedAt'];

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

interface Seed {
  title: string;
  description?: string | null;
  dueDate?: string | null;
  priority?: Priority;
  status?: Status;
  tags?: string[];
}

/** Creates tasks one by one (so createdAt follows the array order), then PUTs the T1 fields. */
async function seed(page: Page, seeds: Seed[]): Promise<Task[]> {
  const out: Task[] = [];
  for (const s of seeds) {
    const res = await api(page, 'POST', '/api/tasks', {
      title: s.title,
      description: s.description ?? null,
      dueDate: s.dueDate ?? null,
    });
    expect(res.status()).toBe(201);
    let t = (await res.json()) as Task;
    if (s.priority || s.status || s.tags) {
      const put = await api(page, 'PUT', `/api/tasks/${t.id}`, {
        title: t.title,
        description: t.description,
        dueDate: t.dueDate,
        priority: s.priority,
        status: s.status,
        tags: s.tags,
      });
      expect(put.status()).toBe(200);
      t = (await put.json()) as Task;
    }
    out.push(t);
  }
  return out;
}

function numbered(n: number, prefix = 'P'): Seed[] {
  return Array.from({ length: n }, (_, i) => ({ title: `${prefix}${String(i + 1).padStart(3, '0')}` }));
}

async function getPage(page: Page, query: string): Promise<{ res: APIResponse; body: TaskPage }> {
  const res = await api(page, 'GET', `/api/tasks${query}`);
  const body = res.status() === 200 ? ((await res.json()) as TaskPage) : (null as unknown as TaskPage);
  return { res, body };
}

/** Pages through the whole list for `params` and checks the shape of every page. */
async function listAll(page: Page, params: Record<string, string> = {}): Promise<{ items: Task[]; pages: TaskPage[] }> {
  const items: Task[] = [];
  const pages: TaskPage[] = [];
  let cursor: string | null = null;
  do {
    const p = new URLSearchParams(params);
    if (cursor) p.set('cursor', cursor);
    const qs = p.toString();
    const { res, body } = await getPage(page, qs ? `?${qs}` : '');
    expect(res.status(), await res.text()).toBe(200);
    expect(Object.keys(body).sort()).toEqual(['items', 'nextCursor', 'total']);
    expect(body.items.length).toBeLessThanOrEqual(50);
    body.items.forEach((t) => expect(Object.keys(t).sort()).toEqual(TASK_KEYS));
    if (body.nextCursor !== null) {
      expect(body.nextCursor).toMatch(/^[A-Za-z0-9_-]+$/);
      expect(body.nextCursor.length).toBeLessThanOrEqual(1024);
      expect(body.items.length).toBe(50);
    }
    pages.push(body);
    items.push(...body.items);
    cursor = body.nextCursor;
  } while (cursor);
  return { items, pages };
}

async function expectProblem(res: APIResponse, status: number, code: string, field?: string, fieldCode?: string) {
  expect(res.status(), await res.text()).toBe(status);
  expect(res.headers()['content-type']).toContain('application/problem+json');
  const b = (await res.json()) as { code: string; errors?: { field: string; code: string; message: string }[] };
  expect(b.code).toBe(code);
  if (field) {
    expect(b.errors).toEqual([expect.objectContaining({ field, code: fieldCode })]);
  }
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
  if ((await list(page).count()) === 0) return [];
  const labels = await list(page).getByRole('checkbox').evaluateAll((els) =>
    els.map((e) => e.getAttribute('aria-label') ?? ''),
  );
  return labels.map((l) => l.replace(/^Done: /, ''));
}
async function gotoTasks(page: Page, query = '') {
  await page.goto(`/tasks${query}`);
  await expect(page.getByRole('heading', { name: 'My tasks' })).toBeVisible();
  await expect(page.getByText('Loading tasks…')).toHaveCount(0);
}
function search(page: Page): Locator {
  return page.getByRole('searchbox', { name: 'Search tasks' });
}
function sortSelect(page: Page): Locator {
  return page.getByLabel('Sort', { exact: true });
}
function footer(page: Page): Locator {
  return page.getByTestId('tasks-footer');
}
function loadMoreBtn(page: Page): Locator {
  return page.locator('.tasks-footer__more');
}
function tab(page: Page, name: string): Locator {
  return page.locator('nav[aria-label="Filter tasks"]').getByRole('link', { name, exact: true });
}
function editDialog(page: Page) {
  return page.getByRole('dialog', { name: 'Edit task' });
}
function params(page: Page): Record<string, string> {
  const u = new URL(page.url());
  expect(u.pathname).toBe('/tasks');
  return Object.fromEntries(u.searchParams.entries());
}
async function shot(page: Page, name: string) {
  await page.screenshot({ path: `${SHOTS}/${name}.png`, fullPage: true });
}
function listRequests(page: Page): Request[] {
  const seen: Request[] = [];
  page.on('request', (r) => {
    if (r.method() === 'GET' && new URL(r.url()).pathname === '/api/tasks') seen.push(r);
  });
  return seen;
}
const IS_MORE = (u: URL) => u.pathname === '/api/tasks' && u.searchParams.has('cursor');
const IS_LIST = (u: URL) => u.pathname === '/api/tasks';
function qp(r: Request): Record<string, string> {
  return Object.fromEntries(new URL(r.url()).searchParams.entries());
}
async function loadMore(page: Page) {
  const resp = page.waitForResponse((r) => new URL(r.url()).pathname === '/api/tasks' && new URL(r.url()).searchParams.has('cursor'));
  await loadMoreBtn(page).click();
  await resp;
  await expect(loadMoreBtn(page).or(footer(page).getByText(/tasks?$/))).toBeVisible();
  await expect(page.getByText('Loading more…')).toHaveCount(0);
}
async function newUserPage(browser: Browser, name: string) {
  const ctx = await browser.newContext({ baseURL: BASE });
  const p = await ctx.newPage();
  const errors: string[] = [];
  p.on('console', (m) => m.type() === 'error' && errors.push(m.text()));
  p.on('pageerror', (e) => errors.push(e.message));
  const u = await signUp(p, name);
  return { ctx, p, errors, ...u };
}

/** Fixed data set for the sort tests (created in this order). */
function sortSeeds(): Seed[] {
  return [
    { title: 'Delta', dueDate: localDate(5), priority: 'LOW' },
    { title: 'alpha', priority: 'HIGH', status: 'IN_PROGRESS' },
    { title: 'Écho', dueDate: localDate(1) },
    { title: 'bravo', dueDate: localDate(5), priority: 'MEDIUM' },
    { title: 'Charlie', priority: 'HIGH', status: 'DONE' },
    { title: 'Foxtrot', dueDate: localDate(-2), priority: 'LOW', status: 'DONE' },
    { title: 'echo', dueDate: localDate(3), priority: 'MEDIUM' },
  ];
}
const SORT_EXPECTED: Record<string, string[]> = {
  newest: ['echo', 'bravo', 'Écho', 'alpha', 'Delta', 'Foxtrot', 'Charlie'],
  oldest: ['Delta', 'alpha', 'Écho', 'bravo', 'echo', 'Charlie', 'Foxtrot'],
  due: ['Écho', 'echo', 'bravo', 'Delta', 'alpha', 'Foxtrot', 'Charlie'],
  priority: ['alpha', 'echo', 'bravo', 'Delta', 'Écho', 'Charlie', 'Foxtrot'],
  title: ['alpha', 'bravo', 'Delta', 'echo', 'Écho', 'Charlie', 'Foxtrot'],
};

// =====================================================================================
// API (contract 0.11.0)
// =====================================================================================

test.describe('T2 API', () => {
  test('page shape, 50/50/20 paging, every sort pages without duplicates or gaps', async ({ page }) => {
    await signUp(page, 'apipg');
    const seeds: Seed[] = numbered(120).map((s, i) => ({
      ...s,
      priority: (['NONE', 'LOW', 'MEDIUM', 'HIGH'] as Priority[])[i % 4],
      dueDate: i % 3 === 0 ? null : localDate((i * 7) % 11),
      status: i % 5 === 0 ? 'DONE' : i % 7 === 0 ? 'IN_PROGRESS' : undefined,
    }));
    const created = await seed(page, seeds);
    const ids = new Set(created.map((t) => t.id));

    const first = await getPage(page, '');
    expect(first.res.status()).toBe(200);
    expect(first.body.items.length).toBe(50);
    expect(first.body.total).toBe(120);
    expect(typeof first.body.nextCursor).toBe('string');

    for (const sort of ['newest', 'oldest', 'due', 'priority', 'title']) {
      for (const filter of ['all', 'active', 'done', 'in_progress']) {
        const { items, pages } = await listAll(page, { sort, filter });
        const expectedCount = created.filter((t) =>
          filter === 'all' ? true : filter === 'done' ? t.status === 'DONE' : filter === 'active' ? t.status !== 'DONE' : t.status === 'IN_PROGRESS',
        ).length;
        expect(items.length, `${sort}/${filter}`).toBe(expectedCount);
        expect(new Set(items.map((t) => t.id)).size, `${sort}/${filter} no dup`).toBe(expectedCount);
        items.forEach((t) => expect(ids.has(t.id)).toBe(true));
        pages.forEach((p) => expect(p.total).toBe(expectedCount));
        expect(pages[pages.length - 1].nextCursor).toBeNull();
        // order check against the contract rules
        const prank: Record<Priority, number> = { HIGH: 0, MEDIUM: 1, LOW: 2, NONE: 3 };
        for (let i = 1; i < items.length; i++) {
          const a = items[i - 1];
          const b = items[i];
          if (a.done !== b.done) {
            expect(a.done, `${sort}/${filter} done group last`).toBe(false);
            continue;
          }
          const newest = a.createdAt > b.createdAt || (a.createdAt === b.createdAt && a.id > b.id);
          if (sort === 'newest') expect(newest).toBe(true);
          if (sort === 'oldest') expect(newest).toBe(false);
          if (sort === 'due') {
            const ka = a.dueDate ?? '9999-99-99';
            const kb = b.dueDate ?? '9999-99-99';
            expect(ka <= kb).toBe(true);
            if (ka === kb) expect(newest).toBe(true);
          }
          if (sort === 'priority') {
            expect(prank[a.priority] <= prank[b.priority]).toBe(true);
            if (prank[a.priority] === prank[b.priority]) expect(newest).toBe(true);
          }
          if (sort === 'title') {
            expect(a.title.localeCompare(b.title, 'en', { sensitivity: 'base' }) <= 0).toBe(true);
          }
        }
      }
    }
    const newest = await listAll(page);
    expect(newest.pages.map((p) => p.items.length)).toEqual([50, 50, 20]);
  });

  test('search: case, accents, literal % _ \\, blank q, q length 100/101, unknown sort', async ({ page }) => {
    await signUp(page, 'apiq');
    await seed(page, [
      { title: 'Buy milk' },
      { title: 'Call Bob' },
      { title: 'Notes', description: 'get MILK too' },
      { title: 'Café meeting' },
      { title: '50% off' },
      { title: '50 percent' },
      { title: 'a_b thing' },
      { title: 'axb thing' },
      { title: 'back\\slash' },
      { title: 'backslash' },
    ]);
    const titles = async (q: string) => (await listAll(page, { q })).items.map((t) => t.title);
    expect(await titles('MILK')).toEqual(['Notes', 'Buy milk']);
    expect(await titles('cafe')).toEqual(['Café meeting']);
    expect(await titles('CAFÉ')).toEqual(['Café meeting']);
    expect(await titles('50%')).toEqual(['50% off']);
    expect(await titles('a_b')).toEqual(['a_b thing']);
    expect(await titles('k\\s')).toEqual(['back\\slash']);
    expect(await titles('%')).toEqual(['50% off']);
    expect(await titles('  milk  ')).toEqual(['Notes', 'Buy milk']);
    expect((await listAll(page, { q: '   ' })).items.length).toBe(10);
    expect((await listAll(page, { q: '' })).items.length).toBe(10);
    // q length: 100 ok, 101 -> 400 q TOO_LONG; 100 + surrounding spaces ok (trimmed first)
    expect((await getPage(page, `?q=${'a'.repeat(100)}`)).res.status()).toBe(200);
    expect((await getPage(page, `?q=%20%20${'a'.repeat(100)}%20%20`)).res.status()).toBe(200);
    await expectProblem((await getPage(page, `?q=${'a'.repeat(101)}`)).res, 400, 'VALIDATION_FAILED', 'q', 'TOO_LONG');
    // UTF-16 code units: 50 emoji = 100 units ok, 51 = 102 -> 400
    expect((await getPage(page, `?q=${encodeURIComponent('😀'.repeat(50))}`)).res.status()).toBe(200);
    await expectProblem((await getPage(page, `?q=${encodeURIComponent('😀'.repeat(51))}`)).res, 400, 'VALIDATION_FAILED', 'q', 'TOO_LONG');
    // unknown / wrong-case sort and filter -> defaults, never 400
    const def = (await getPage(page, '')).body.items.map((t) => t.id);
    for (const s of ['foo', 'Due', 'NEWEST', '']) {
      const r = await getPage(page, `?sort=${s}`);
      expect(r.res.status()).toBe(200);
      expect(r.body.items.map((t) => t.id)).toEqual(def);
    }
    // repeated q: first used
    expect((await getPage(page, '?q=milk&q=bob')).body.items.map((t) => t.title)).toEqual(['Notes', 'Buy milk']);
    // unknown tag -> empty page shape
    expect((await getPage(page, '?tag=nope')).body).toEqual({ items: [], total: 0, nextCursor: null });
  });

  test('cursor: invalid, tampered, mismatched with filter/tag/q/sort -> 400 INVALID_CURSOR; normalised equal -> ok; other user', async ({ page, browser }) => {
    await signUp(page, 'apicur');
    await seed(page, numbered(60).map((s, i) => ({ ...s, tags: i % 2 ? ['Odd'] : ['Even'] })));
    const first = (await getPage(page, '')).body;
    const c = first.nextCursor!;
    expect((await getPage(page, `?cursor=${c}`)).res.status()).toBe(200);
    expect((await getPage(page, `?cursor=${c}&filter=foo`)).res.status()).toBe(200); // filter=foo == all
    expect((await getPage(page, `?cursor=${c}&sort=bogus`)).res.status()).toBe(200); // == newest
    expect((await getPage(page, `?cursor=${c}&q=%20`)).res.status()).toBe(200); // blank == none
    for (const bad of [
      `?cursor=garbage`,
      `?cursor=${c.slice(0, -3)}`,
      `?cursor=${c}xx`,
      `?cursor=${'A'.repeat(1025)}`,
      `?cursor=abc%2B%2F%3D`,
      `?cursor=${c}&sort=oldest`,
      `?cursor=${c}&filter=active`,
      `?cursor=${c}&q=P0`,
      `?cursor=${c}&tag=Odd`,
    ]) {
      await expectProblem((await getPage(page, bad)).res, 400, 'VALIDATION_FAILED', 'cursor', 'INVALID_CURSOR');
    }
    // tampered: flip a character in the middle
    const mid = Math.floor(c.length / 2);
    const flipped = c.slice(0, mid) + (c[mid] === 'A' ? 'B' : 'A') + c.slice(mid + 1);
    const tr = (await getPage(page, `?cursor=${flipped}`)).res;
    expect([200, 400]).toContain(tr.status()); // either rejected or a valid position of this user's own list
    if (tr.status() === 400) await expectProblem(tr, 400, 'VALIDATION_FAILED', 'cursor', 'INVALID_CURSOR');

    // Another user replaying A's cursor only ever sees their own tasks
    const B = await newUserPage(browser, 'apicurb');
    const bTasks = await seed(B.p, numbered(3, 'B'));
    const r = await getPage(B.p, `?cursor=${c}`);
    if (r.res.status() === 200) {
      r.body.items.forEach((t) => expect(bTasks.map((x) => x.id)).toContain(t.id));
      expect(r.body.total).toBe(3);
    } else {
      await expectProblem(r.res, 400, 'VALIDATION_FAILED', 'cursor', 'INVALID_CURSOR');
    }
    await B.ctx.close();
  });
});

// =====================================================================================
// UI
// =====================================================================================

test.describe('T2 Search (AC1-AC4)', () => {
  test('AC1: MILK finds title+description after ~300 ms debounce, Enter is immediate, URL q, refresh keeps it, typing replaces the history entry', async ({ page }) => {
    await signUp(page, 'ac1');
    await seed(page, [{ title: 'Buy milk' }, { title: 'Call Bob' }, { title: 'Notes', description: 'get milk too' }]);
    await gotoTasks(page);
    await expect.poll(() => order(page)).toEqual(['Notes', 'Call Bob', 'Buy milk']);
    await expect(search(page)).toHaveAttribute('placeholder', 'Search tasks');
    await expect(search(page)).toHaveAttribute('maxlength', '100');
    await expect(page.getByRole('button', { name: 'Clear search' })).toHaveCount(0);

    // History: /tasks -> Active (push) -> typing (replace)
    await tab(page, 'Active').click();
    await expect.poll(() => params(page)).toEqual({ filter: 'active' });
    await gotoTasksSettled(page);

    const reqs = listRequests(page);
    await search(page).click();
    await search(page).pressSequentially('MILK', { delay: 60 });
    const typed = Date.now();
    await expect(page.getByRole('button', { name: 'Clear search' })).toBeVisible();
    const req = await page.waitForRequest((r) => new URL(r.url()).pathname === '/api/tasks' && new URL(r.url()).searchParams.get('q') === 'MILK');
    const waited = Date.now() - typed;
    expect(waited, 'debounce ~300ms').toBeGreaterThanOrEqual(200);
    expect(waited).toBeLessThan(1500);
    await expect.poll(() => order(page)).toEqual(['Notes', 'Buy milk']);
    expect(reqs.map((r) => qp(r).q ?? '')).toEqual(['MILK']); // one request, no per-keystroke search
    expect(qp(req)).toEqual({ filter: 'active', q: 'MILK' });
    expect(params(page)).toEqual({ filter: 'active', q: 'MILK' });
    await shot(page, 'ac1-search-milk');

    // Refresh keeps search + text in the box
    await page.reload();
    await gotoTasksSettled(page);
    await expect(search(page)).toHaveValue('MILK');
    await expect.poll(() => order(page)).toEqual(['Notes', 'Buy milk']);

    // Typing replaced the Active entry: Back goes to plain /tasks, Forward restores the search
    await page.goBack();
    await expect.poll(() => params(page)).toEqual({});
    await expect(search(page)).toHaveValue('');
    await expect.poll(() => order(page)).toEqual(['Notes', 'Call Bob', 'Buy milk']);
    await page.goForward();
    await expect.poll(() => params(page)).toEqual({ filter: 'active', q: 'MILK' });
    await expect(search(page)).toHaveValue('MILK');
    await expect.poll(() => order(page)).toEqual(['Notes', 'Buy milk']);

    // Enter searches at once
    await search(page).fill('bob');
    const t0 = Date.now();
    const enterReq = page.waitForRequest((r) => new URL(r.url()).pathname === '/api/tasks' && new URL(r.url()).searchParams.get('q') === 'bob');
    await search(page).press('Enter');
    await enterReq;
    expect(Date.now() - t0, 'Enter does not wait for the debounce').toBeLessThan(250);
    await expect.poll(() => order(page)).toEqual(['Call Bob']);
    expect(params(page)).toEqual({ filter: 'active', q: 'bob' });
  });

  test('AC2: accents/case ignored ("cafe" finds "Café meeting"), "50%" and "a_b" literal', async ({ page }) => {
    await signUp(page, 'ac2');
    await seed(page, [
      { title: 'Café meeting' },
      { title: 'Cafeteria plan' },
      { title: '50% off' },
      { title: '50 percent' },
      { title: '500 boxes' },
      { title: 'a_b thing' },
      { title: 'axb thing' },
      { title: 'ab thing' },
    ]);
    await gotoTasks(page);
    await search(page).fill('cafe');
    await search(page).press('Enter');
    await expect.poll(() => order(page)).toEqual(['Cafeteria plan', 'Café meeting']);
    await search(page).fill('CAFÉ M');
    await search(page).press('Enter');
    await expect.poll(() => order(page)).toEqual(['Café meeting']);
    await search(page).fill('50%');
    await search(page).press('Enter');
    await expect.poll(() => order(page)).toEqual(['50% off']);
    expect(page.url()).toContain('q=50%25');
    await search(page).fill('a_b');
    await search(page).press('Enter');
    await expect.poll(() => order(page)).toEqual(['a_b thing']);
    await shot(page, 'ac2-literal-underscore');
    // refresh with an encoded % keeps working
    await page.reload();
    await gotoTasksSettled(page);
    await expect(search(page)).toHaveValue('a_b');
    await expect.poll(() => order(page)).toEqual(['a_b thing']);
  });

  test('AC3: search combines with tab and tag (?filter&tag&q); × and emptying the box clear q', async ({ page }) => {
    await signUp(page, 'ac3');
    await seed(page, [
      { title: 'Report draft', tags: ['Work'] },
      { title: 'Report final', tags: ['Work'], status: 'DONE' },
      { title: 'Report home', tags: ['Home'], status: 'IN_PROGRESS' },
      { title: 'Groceries', tags: ['Work'] },
    ]);
    await gotoTasks(page);
    await search(page).fill('report');
    await search(page).press('Enter');
    await expect.poll(() => order(page)).toEqual(['Report home', 'Report draft', 'Report final']);

    await tab(page, 'Active').click();
    await expect.poll(() => params(page)).toEqual({ filter: 'active', q: 'report' });
    await expect.poll(() => order(page)).toEqual(['Report home', 'Report draft']);
    await expect(search(page)).toHaveValue('report');

    await tab(page, 'In progress').click();
    await expect.poll(() => params(page)).toEqual({ filter: 'in_progress', q: 'report' });
    await expect.poll(() => order(page)).toEqual(['Report home']);

    await tab(page, 'All').click();
    await expect.poll(() => params(page)).toEqual({ q: 'report' });
    await row(page, 'Report draft').locator('[data-testid=task-tag]', { hasText: 'Work' }).click();
    await expect.poll(() => params(page)).toEqual({ q: 'report', tag: 'Work' });
    await expect.poll(() => order(page)).toEqual(['Report draft', 'Report final']);
    await tab(page, 'Done').click();
    await expect.poll(() => params(page)).toEqual({ q: 'report', tag: 'Work', filter: 'done' });
    await expect.poll(() => order(page)).toEqual(['Report final']);
    await shot(page, 'ac3-search-tag-done');

    // × clears: q leaves the URL, the tab+tag list comes back, focus stays in the box
    await page.getByRole('button', { name: 'Clear search' }).click();
    await expect.poll(() => params(page)).toEqual({ tag: 'Work', filter: 'done' });
    await expect(search(page)).toHaveValue('');
    await expect(search(page)).toBeFocused();
    await tab(page, 'All').click();
    await expect.poll(() => order(page)).toEqual(['Groceries', 'Report draft', 'Report final']);

    // emptying the box (select all + delete) clears too
    await search(page).fill('groc');
    await search(page).press('Enter');
    await expect.poll(() => order(page)).toEqual(['Groceries']);
    await search(page).fill('');
    await expect.poll(() => params(page)).toEqual({ tag: 'Work' });
    await expect.poll(() => order(page)).toEqual(['Groceries', 'Report draft', 'Report final']);
  });

  test('AC4: no match message (also with a tag), spaces only = no search, 100-char cap, text shown literally', async ({ page }) => {
    await signUp(page, 'ac4');
    await seed(page, [{ title: 'One', tags: ['Work'] }, { title: 'Two' }]);
    await gotoTasks(page);
    await search(page).fill('zzz');
    await search(page).press('Enter');
    await expect(page.getByText('No tasks match "zzz"', { exact: true })).toBeVisible();
    await expect(page.getByText('No tasks yet')).toHaveCount(0);
    await shot(page, 'ac4-no-match');
    // tab with search: same message, not the tab's
    await tab(page, 'Done').click();
    await expect(page.getByText('No tasks match "zzz"', { exact: true })).toBeVisible();
    await expect(page.getByText('Nothing done yet')).toHaveCount(0);
    // tag + search with no result
    await gotoTasks(page, '?tag=Work&q=zzz');
    await expect(page.getByText('No tasks match "zzz"', { exact: true })).toBeVisible();
    await expect(page.getByText('No tasks with this tag')).toHaveCount(0);
    // HTML/quotes are shown as text
    await gotoTasks(page, `?q=${encodeURIComponent('<b>"x"</b>')}`);
    await expect(page.getByText('No tasks match "<b>"x"</b>"', { exact: true })).toBeVisible();
    // spaces only = no search
    await gotoTasks(page);
    await search(page).fill('    ');
    await search(page).press('Enter');
    await page.waitForTimeout(500);
    expect(params(page)).toEqual({});
    await expect.poll(() => order(page)).toEqual(['Two', 'One']);
    // ?q=%20%20 in the URL = no search
    await gotoTasks(page, '?q=%20%20');
    await expect.poll(() => order(page)).toEqual(['Two', 'One']);
    // 100-char cap in the box
    await gotoTasks(page);
    await search(page).fill('');
    await search(page).pressSequentially('x'.repeat(105));
    await expect(search(page)).toHaveValue('x'.repeat(100));
    await search(page).press('Enter');
    await expect(page.getByText(`No tasks match "${'x'.repeat(100)}"`)).toBeVisible();
    // A hand-edited URL with 101 chars must not break the page
    await gotoTasks(page, `?q=${'y'.repeat(101)}`);
    await expect(page.getByRole('alert')).toHaveCount(0);
    await expect(page.getByText(/No tasks match "y{100}"/)).toBeVisible();
    await expect(search(page)).toHaveValue('y'.repeat(100));
  });
});

/** Waits until the first page has rendered (list, footer or empty text). */
async function gotoTasksSettled(page: Page) {
  await expect(page.getByRole('heading', { name: 'My tasks' })).toBeVisible();
  await expect(page.getByText('Loading tasks…')).toHaveCount(0);
  await expect(footer(page).or(page.locator('.tasks-empty'))).toBeVisible();
}

test.describe('T2 Sort (AC5-AC8)', () => {
  test('AC5: Sort select options, URL ?sort, Newest has no param, refresh keeps it, unknown -> Newest', async ({ page }) => {
    await signUp(page, 'ac5');
    await seed(page, sortSeeds());
    await gotoTasks(page);
    await expect(sortSelect(page)).toHaveValue('newest');
    expect((await sortSelect(page).locator('option').allInnerTexts()).map((x) => x.trim())).toEqual(['Newest', 'Oldest', 'Due date', 'Priority', 'Title A–Z']);
    for (const [label, value] of [['Oldest', 'oldest'], ['Due date', 'due'], ['Priority', 'priority'], ['Title A–Z', 'title']]) {
      await sortSelect(page).selectOption({ label });
      await expect.poll(() => params(page)).toEqual({ sort: value });
      await expect.poll(() => order(page)).toEqual(SORT_EXPECTED[value]);
    }
    await page.reload();
    await gotoTasksSettled(page);
    await expect(sortSelect(page)).toHaveValue('title');
    await expect.poll(() => order(page)).toEqual(SORT_EXPECTED['title']);
    await sortSelect(page).selectOption({ label: 'Newest' });
    await expect.poll(() => params(page)).toEqual({});
    await expect.poll(() => order(page)).toEqual(SORT_EXPECTED['newest']);
    for (const bad of ['foo', 'Due', 'TITLE', '']) {
      await gotoTasks(page, `?sort=${bad}`);
      await expect(sortSelect(page)).toHaveValue('newest');
      await expect.poll(() => order(page)).toEqual(SORT_EXPECTED['newest']);
    }
    // Back steps through sort changes (each adds an entry)
    await gotoTasks(page);
    await sortSelect(page).selectOption('due');
    await expect.poll(() => params(page)).toEqual({ sort: 'due' });
    await sortSelect(page).selectOption('priority');
    await expect.poll(() => params(page)).toEqual({ sort: 'priority' });
    await page.goBack();
    await expect.poll(() => params(page)).toEqual({ sort: 'due' });
    await expect(sortSelect(page)).toHaveValue('due');
    await expect.poll(() => order(page)).toEqual(SORT_EXPECTED['due']);
  });

  test('AC6+AC7: due (no date last), priority H>M>L>None, title ignores case/accents, ties newest first, done group last in All', async ({ page }) => {
    await signUp(page, 'ac6');
    await seed(page, sortSeeds());
    for (const s of ['newest', 'oldest', 'due', 'priority', 'title']) {
      await gotoTasks(page, s === 'newest' ? '' : `?sort=${s}`);
      await expect.poll(() => order(page), s).toEqual(SORT_EXPECTED[s]);
      await shot(page, `ac6-sort-${s}`);
    }
    // other tabs: one group, same order
    await gotoTasks(page, '?sort=due&filter=active');
    await expect.poll(() => order(page)).toEqual(['Écho', 'echo', 'bravo', 'Delta', 'alpha']);
    await gotoTasks(page, '?sort=due&filter=done');
    await expect.poll(() => order(page)).toEqual(['Foxtrot', 'Charlie']);
    await gotoTasks(page, '?sort=priority&filter=in_progress');
    await expect.poll(() => order(page)).toEqual(['alpha']);
  });

  test('AC8: sort + search + tag + tab all apply together, survive refresh, Back and leaving the page', async ({ page }) => {
    await signUp(page, 'ac8');
    await seed(page, [
      { title: 'Plan B', tags: ['Work'], dueDate: localDate(9) },
      { title: 'Plan A', tags: ['Work'], dueDate: localDate(2) },
      { title: 'Plan C', tags: ['Work'], status: 'DONE' },
      { title: 'Plan D', tags: ['Home'], dueDate: localDate(1) },
      { title: 'Other', tags: ['Work'], dueDate: localDate(0) },
    ]);
    await gotoTasks(page);
    await sortSelect(page).selectOption('due');
    await expect.poll(() => params(page)).toEqual({ sort: 'due' });
    await tab(page, 'Active').click();
    await expect.poll(() => params(page)).toEqual({ sort: 'due', filter: 'active' });
    await row(page, 'Plan A').locator('[data-testid=task-tag]').click();
    await expect.poll(() => params(page)).toEqual({ sort: 'due', filter: 'active', tag: 'Work' });
    await search(page).fill('plan');
    await search(page).press('Enter');
    await expect.poll(() => params(page)).toEqual({ sort: 'due', filter: 'active', tag: 'Work', q: 'plan' });
    const expected = ['Plan A', 'Plan B'];
    await expect.poll(() => order(page)).toEqual(expected);
    await shot(page, 'ac8-all-combined');

    await page.reload();
    await gotoTasksSettled(page);
    await expect.poll(() => order(page)).toEqual(expected);
    await expect(sortSelect(page)).toHaveValue('due');
    await expect(search(page)).toHaveValue('plan');
    await expect(page.getByTestId('tag-filter-bar')).toContainText('Work');
    await expect(tab(page, 'Active')).toHaveAttribute('aria-current', 'page');

    // leave the page and come back with Back
    await page.goto('/account');
    await expect(page).toHaveURL(/\/account/);
    await page.goBack();
    await gotoTasksSettled(page);
    expect(params(page)).toEqual({ sort: 'due', filter: 'active', tag: 'Work', q: 'plan' });
    await expect.poll(() => order(page)).toEqual(expected);
    await expect(search(page)).toHaveValue('plan');
    await expect(sortSelect(page)).toHaveValue('due');

    // Back through the history: the search replaced the tag entry, so Back drops tag+q together
    await page.goBack();
    await expect.poll(() => params(page)).toEqual({ sort: 'due', filter: 'active' });
    await expect(search(page)).toHaveValue('');
    await expect.poll(() => order(page)).toEqual(['Other', 'Plan D', 'Plan A', 'Plan B']);
    await page.goBack();
    await expect.poll(() => params(page)).toEqual({ sort: 'due' });
    await expect(tab(page, 'All')).toHaveAttribute('aria-current', 'page');
    await expect.poll(() => order(page)).toEqual(['Other', 'Plan D', 'Plan A', 'Plan B', 'Plan C']);
    await page.goForward();
    await page.goForward();
    await expect.poll(() => params(page)).toEqual({ sort: 'due', filter: 'active', tag: 'Work', q: 'plan' });
    await expect(search(page)).toHaveValue('plan');
    await expect.poll(() => order(page)).toEqual(expected);
  });
});

test.describe('T2 Pagination (AC9-AC12)', () => {
  test('AC9: 120 tasks -> 50 / Showing 50 of 120 / Load more -> 100 -> 120 tasks; <=50 -> "N tasks"; 1 -> "1 task"', async ({ page }) => {
    await signUp(page, 'ac9');
    await seed(page, numbered(120));
    await gotoTasks(page);
    await expect(rows(page)).toHaveCount(50);
    await expect(footer(page)).toContainText('Showing 50 of 120');
    await expect(loadMoreBtn(page)).toHaveText('Load more');
    expect((await order(page))[0]).toBe('P120');
    await shot(page, 'ac9-first-page');
    await loadMore(page);
    await expect(rows(page)).toHaveCount(100);
    await expect(footer(page)).toContainText('Showing 100 of 120');
    await loadMore(page);
    await expect(rows(page)).toHaveCount(120);
    await expect(footer(page)).toHaveText('120 tasks');
    await expect(loadMoreBtn(page)).toHaveCount(0);
    const titles = await order(page);
    expect(titles).toEqual(numbered(120).map((s) => s.title).reverse());
    await shot(page, 'ac9-all-loaded');

    // Search narrowing to <= 50: no button, "N tasks"
    await search(page).fill('P0');
    await search(page).press('Enter');
    await expect(footer(page)).toContainText('Showing 50 of 99');
    await expect(rows(page)).toHaveCount(50);
    await search(page).fill('P1');
    await search(page).press('Enter');
    await expect(footer(page)).toHaveText('21 tasks');
    await expect(loadMoreBtn(page)).toHaveCount(0);
  });

  test('AC9: exactly 50 tasks -> no button "50 tasks"; 1 task -> "1 task"', async ({ page, browser }) => {
    await signUp(page, 'ac9b');
    await seed(page, numbered(50));
    await gotoTasks(page);
    await expect(rows(page)).toHaveCount(50);
    await expect(footer(page)).toHaveText('50 tasks');
    await expect(loadMoreBtn(page)).toHaveCount(0);
    const B = await newUserPage(browser, 'ac9one');
    await seed(B.p, [{ title: 'Solo' }]);
    await gotoTasks(B.p);
    await expect(footer(B.p)).toHaveText('1 task');
    // 51 -> one more page
    await seed(page, [{ title: 'P051' }]);
    await gotoTasks(page);
    await expect(footer(page)).toContainText('Showing 50 of 51');
    await loadMore(page);
    await expect(footer(page)).toHaveText('51 tasks');
    expect(B.errors).toEqual([]);
    await B.ctx.close();
  });

  test('AC10: after two pages, tick/edit/delete update in place (no reload, scroll kept), total adjusts, Load more has no dup/skip', async ({ page }) => {
    await signUp(page, 'ac10');
    await seed(page, numbered(120));
    await gotoTasks(page);
    await loadMore(page);
    await expect(rows(page)).toHaveCount(100);
    const reqs = listRequests(page);

    // tick P060 (page 2) while scrolled: moves to the not-yet-loaded done group -> leaves the list
    await row(page, 'P060').scrollIntoViewIfNeeded();
    const y0 = await page.evaluate(() => window.scrollY);
    expect(y0).toBeGreaterThan(500);
    await cb(page, 'P060').check();
    await expect(row(page, 'P060')).toHaveCount(0);
    await expect(footer(page)).toContainText('Showing 99 of 120');
    const y1 = await page.evaluate(() => window.scrollY);
    expect(Math.abs(y1 - y0), 'scroll position kept').toBeLessThan(150);

    // edit P110 (page 1): stays in place with the new title
    await row(page, 'P110').getByRole('button', { name: 'Edit' }).click();
    await expect(editDialog(page)).toBeVisible();
    await editDialog(page).getByLabel('Title').fill('P110 edited');
    await editDialog(page).getByRole('button', { name: 'Save' }).click();
    await expect(editDialog(page)).toHaveCount(0);
    const o1 = await order(page);
    expect(o1.indexOf('P110 edited')).toBe(10);

    // delete P030 (page 2): total -1
    await row(page, 'P030').getByRole('button', { name: 'Delete' }).click();
    await page.getByRole('dialog', { name: "Delete 'P030'?" }).getByRole('button', { name: 'Delete' }).click();
    await expect(row(page, 'P030')).toHaveCount(0);
    await expect(footer(page)).toContainText('Showing 98 of 119');
    expect(reqs, 'no list reload').toEqual([]);

    await loadMore(page);
    const final = await order(page);
    expect(new Set(final).size, 'no duplicates').toBe(final.length);
    const server = (await listAll(page)).items.map((t) => t.title);
    expect(final).toEqual(server);
    expect(final.length).toBe(119);
    expect(final[final.length - 1]).toBe('P060');
    await expect(footer(page)).toHaveText('119 tasks');
    await shot(page, 'ac10-after-load-more');
  });

  test('AC10: Active tab + search: ticked/edited-out tasks disappear and "of N" drops; title sort move past boundary', async ({ page }) => {
    await signUp(page, 'ac10b');
    await seed(page, numbered(120));
    await gotoTasks(page, '?filter=active&q=P');
    await loadMore(page);
    await expect(footer(page)).toContainText('Showing 100 of 120');
    await cb(page, 'P100').check();
    await expect(row(page, 'P100')).toHaveCount(0);
    await expect(footer(page)).toContainText('Showing 99 of 119');
    // edit so it no longer matches the search
    await row(page, 'P099').getByRole('button', { name: 'Edit' }).click();
    await editDialog(page).getByLabel('Title').fill('Renamed');
    await editDialog(page).getByRole('button', { name: 'Save' }).click();
    await expect(editDialog(page)).toHaveCount(0);
    await expect(row(page, 'Renamed')).toHaveCount(0);
    await expect(footer(page)).toContainText('Showing 98 of 118');
    await loadMore(page);
    const final = await order(page);
    expect(final).toEqual((await listAll(page, { filter: 'active', q: 'P' })).items.map((t) => t.title));
    expect(final.length).toBe(118);

    // title sort: first page P001..P050; retitle P002 to "ZZZ" -> after boundary -> gone, comes back at the end
    await gotoTasks(page, '?sort=title');
    await expect(rows(page)).toHaveCount(50);
    const before = await order(page);
    await row(page, before[1]).getByRole('button', { name: 'Edit' }).click();
    await editDialog(page).getByLabel('Title').fill('ZZZ moved');
    await editDialog(page).getByRole('button', { name: 'Save' }).click();
    await expect(editDialog(page)).toHaveCount(0);
    await expect(row(page, 'ZZZ moved')).toHaveCount(0);
    // retitle another to sort first -> moves to top
    await row(page, before[10]).getByRole('button', { name: 'Edit' }).click();
    await editDialog(page).getByLabel('Title').fill('AAA moved');
    await editDialog(page).getByRole('button', { name: 'Save' }).click();
    await expect(editDialog(page)).toHaveCount(0);
    expect((await order(page))[0]).toBe('AAA moved');
    await loadMore(page);
    await loadMore(page);
    const all = await order(page);
    expect(new Set(all).size).toBe(all.length);
    expect(all).toEqual((await listAll(page, { sort: 'title' })).items.map((t) => t.title));
  });

  test('AC11: quick-add on Newest appears at top (+1); other sort/search/tag/Done -> created, total adjusts, "Task added" when hidden', async ({ page }) => {
    await signUp(page, 'ac11');
    await seed(page, numbered(60).map((s) => ({ ...s, tags: ['Work'] })));
    await gotoTasks(page);
    await expect(footer(page)).toContainText('Showing 50 of 60');
    await page.getByLabel('New task').fill('Fresh one');
    await page.getByRole('button', { name: 'Add', exact: true }).click();
    await expect.poll(async () => (await order(page))[0]).toBe('Fresh one');
    await expect(footer(page)).toContainText('Showing 51 of 61');
    await expect(page.getByTestId('task-added')).toHaveCount(0);

    // Title sort: "zzz" sorts after the loaded part -> hidden, "Task added", total +1
    await gotoTasks(page, '?sort=title');
    await expect(footer(page)).toContainText('Showing 50 of 61');
    await page.getByLabel('New task').fill('zzz last');
    await page.getByRole('button', { name: 'Add', exact: true }).click();
    await expect(page.getByTestId('task-added')).toHaveText('Task added');
    await expect(row(page, 'zzz last')).toHaveCount(0);
    await expect(footer(page)).toContainText('Showing 50 of 62');
    await shot(page, 'ac11-task-added');
    await expect(page.getByTestId('task-added')).toHaveCount(0, { timeout: 7000 }); // ~4 s
    // "AAA" sorts inside -> shown in place, no message
    await page.getByLabel('New task').fill('AAA first');
    await page.getByRole('button', { name: 'Add', exact: true }).click();
    await expect.poll(async () => (await order(page))[0]).toBe('AAA first');
    await expect(footer(page)).toContainText('Showing 51 of 63');
    await expect(page.getByTestId('task-added')).toHaveCount(0);

    // search active, new task doesn't match -> "Task added", total unchanged
    await gotoTasks(page, '?q=P05');
    await expect(footer(page)).toHaveText('10 tasks');
    await page.getByLabel('New task').fill('Unrelated');
    await page.getByRole('button', { name: 'Add', exact: true }).click();
    await expect(page.getByTestId('task-added')).toBeVisible();
    await expect(footer(page)).toHaveText('10 tasks');
    // matches the search and everything is loaded -> shown
    await page.getByLabel('New task').fill('P05 new');
    await page.getByRole('button', { name: 'Add', exact: true }).click();
    await expect.poll(async () => (await order(page))[0]).toBe('P05 new');
    await expect(footer(page)).toHaveText('11 tasks');

    // tag filter: new task has no tag -> hidden
    await gotoTasks(page, '?tag=Work');
    await page.getByLabel('New task').fill('No tag');
    await page.getByRole('button', { name: 'Add', exact: true }).click();
    await expect(page.getByTestId('task-added')).toBeVisible();
    await expect(row(page, 'No tag')).toHaveCount(0);

    // Done tab: hidden
    await gotoTasks(page, '?filter=done');
    await page.getByLabel('New task').fill('Not done');
    await page.getByRole('button', { name: 'Add', exact: true }).click();
    await expect(page.getByTestId('task-added')).toBeVisible();
    await expect(page.getByText('Nothing done yet')).toBeVisible();

    // all of them exist on the server, exactly once
    const titles = (await listAll(page)).items.map((t) => t.title);
    for (const t of ['Fresh one', 'zzz last', 'AAA first', 'Unrelated', 'P05 new', 'No tag', 'Not done']) {
      expect(titles.filter((x) => x === t).length, t).toBe(1);
    }
  });

  test('AC12: changing tab, tag, search or sort restarts from the first page (no cursor)', async ({ page }) => {
    await signUp(page, 'ac12');
    await seed(page, numbered(120).map((s) => ({ ...s, tags: ['Work'] })));
    const reqs = listRequests(page);
    const check = async (label: string) => {
      await expect(rows(page), label).toHaveCount(50);
      await expect(footer(page)).toContainText('Showing 50 of 120');
      const last = reqs[reqs.length - 1];
      expect(qp(last).cursor, label).toBeUndefined();
    };
    await gotoTasks(page);
    await loadMore(page);
    await sortSelect(page).selectOption('oldest');
    await check('sort');
    await loadMore(page);
    await tab(page, 'Active').click();
    await check('tab');
    await loadMore(page);
    await row(page, 'P001').locator('[data-testid=task-tag]').click();
    await check('tag');
    await loadMore(page);
    await search(page).fill('P');
    await search(page).press('Enter');
    await check('search');
    expect(params(page)).toEqual({ sort: 'oldest', filter: 'active', tag: 'Work', q: 'P' });
    // refresh mid-flow (after Load more) also starts from page 1 with the same query
    await loadMore(page);
    await page.reload();
    await gotoTasksSettled(page);
    await check('refresh');
    expect((await order(page))[0]).toBe('P001');
  });
});

test.describe('T2 States and errors (AC13)', () => {
  test('AC13: first-page loading state; Load more disabled + "Loading more…"; double click = one request', async ({ page }) => {
    await signUp(page, 'ac13a');
    await seed(page, numbered(120));
    let release!: () => void;
    const gate = new Promise<void>((r) => (release = r));
    await page.route(IS_LIST, async (route) => {
      await gate;
      await route.continue();
    });
    await page.goto('/tasks');
    await expect(page.getByRole('status').filter({ hasText: 'Loading tasks…' })).toBeVisible();
    await shot(page, 'ac13-first-page-loading');
    release();
    await expect(rows(page)).toHaveCount(50);
    await page.unroute(IS_LIST);

    let release2!: () => void;
    const gate2 = new Promise<void>((r) => (release2 = r));
    const reqs = listRequests(page);
    await page.route(IS_MORE, async (route) => {
      await gate2;
      await route.continue();
    });
    await loadMoreBtn(page).click();
    await expect(loadMoreBtn(page)).toHaveAttribute('aria-disabled', 'true');
    await expect(loadMoreBtn(page)).toHaveText('Loading more…');
    await expect(rows(page)).toHaveCount(50);
    await shot(page, 'ac13-loading-more');
    await loadMoreBtn(page).click({ force: true });
    await loadMoreBtn(page).click({ force: true });
    release2();
    await expect(rows(page)).toHaveCount(100);
    await expect(loadMoreBtn(page)).toHaveText('Load more');
    await expect(loadMoreBtn(page)).not.toHaveAttribute('aria-disabled', 'true');
    expect(reqs.length, 'double click sends one request').toBe(1);
    const titles = await order(page);
    expect(new Set(titles).size).toBe(100);
  });

  test('AC13: Load more failure -> error + Retry, loaded items stay; Retry works; first-page failure -> Retry', async ({ page, watch }) => {
    watch.allowNetworkErrors = true;
    await signUp(page, 'ac13b');
    await seed(page, numbered(120));
    await gotoTasks(page);
    await page.route(IS_MORE, (r) => r.abort());
    await loadMoreBtn(page).click();
    const alert = page.getByRole('alert').filter({ hasText: "Couldn't load more tasks. Please try again." });
    await expect(alert).toBeVisible();
    await expect(rows(page)).toHaveCount(50);
    await expect(footer(page)).toContainText('Showing 50 of 120');
    await shot(page, 'ac13-load-more-failed');
    await page.unroute(IS_MORE);
    await alert.getByRole('button', { name: 'Retry' }).click();
    await expect(rows(page)).toHaveCount(100);
    await expect(alert).toHaveCount(0);
    // 500 from the server behaves the same
    await page.route(IS_MORE, (r) =>
      r.fulfill({ status: 500, contentType: 'application/problem+json', body: JSON.stringify({ status: 500, code: 'INTERNAL_ERROR' }) }),
    );
    await loadMoreBtn(page).click();
    await expect(alert).toBeVisible();
    await expect(rows(page)).toHaveCount(100);
    await page.unroute(IS_MORE);

    // first page failure
    await page.route(IS_LIST, (r) => r.abort());
    await page.goto('/tasks?sort=oldest');
    const banner = page.getByRole('alert').filter({ hasText: "Couldn't load your tasks. Please try again." });
    await expect(banner).toBeVisible();
    await shot(page, 'ac13-first-page-failed');
    await page.unroute(IS_LIST);
    await banner.getByRole('button', { name: 'Retry' }).click();
    await expect(rows(page)).toHaveCount(50);
    expect((await order(page))[0]).toBe('P001');
  });

  test('AC13/Decision: INVALID_CURSOR from the server -> list restarts from page 1 without an error', async ({ page, watch }) => {
    watch.allowNetworkErrors = true;
    await signUp(page, 'ac13c');
    await seed(page, numbered(120));
    await gotoTasks(page);
    await loadMore(page);
    await expect(rows(page)).toHaveCount(100);
    // corrupt the cursor on its way to the real backend -> real 400 INVALID_CURSOR
    await page.route(IS_MORE, async (route) => {
      const u = new URL(route.request().url());
      u.searchParams.set('cursor', 'tampered');
      await route.continue({ url: u.toString() });
    });
    const bad = page.waitForResponse((r) => r.url().includes('cursor=') && r.status() === 400);
    const reqs = listRequests(page);
    await loadMoreBtn(page).click();
    await bad;
    await expect(rows(page)).toHaveCount(50);
    await expect(footer(page)).toContainText('Showing 50 of 120');
    await expect(page.getByRole('alert')).toHaveCount(0);
    expect(reqs.length).toBe(2); // the failed one + one restart, no loop
    expect(qp(reqs[1]).cursor).toBeUndefined();
    await page.unroute(IS_MORE);
    await loadMore(page);
    await expect(rows(page)).toHaveCount(100);
  });
});

test.describe('T2 Privacy, layout (AC14 + UI notes)', () => {
  test('AC14: users A and B only see their own tasks in search/sort/pages; admin sees none', async ({ page, browser }) => {
    const A = await signUp(page, 'ac14a');
    await seed(page, numbered(60, 'Shared '));
    const B = await newUserPage(browser, 'ac14b');
    await seed(B.p, numbered(55, 'Shared '));
    const aIds = new Set((await listAll(page)).items.map((t) => t.id));
    const bIds = new Set((await listAll(B.p)).items.map((t) => t.id));
    expect(aIds.size).toBe(60);
    expect(bIds.size).toBe(55);
    for (const sort of ['newest', 'oldest', 'due', 'priority', 'title']) {
      const a = await listAll(page, { sort, q: 'shared' });
      const b = await listAll(B.p, { sort, q: 'shared' });
      a.items.forEach((t) => expect(aIds.has(t.id)).toBe(true));
      b.items.forEach((t) => expect(bIds.has(t.id)).toBe(true));
      expect(a.items.length).toBe(60);
      expect(b.items.length).toBe(55);
    }
    // UI for B
    await gotoTasks(B.p, '?q=shared&sort=title');
    await expect(footer(B.p)).toContainText('Showing 50 of 55');
    await loadMore(B.p);
    await expect(footer(B.p)).toHaveText('55 tasks');
    // admin: role via SQL, sees only own (none)
    const Adm = await newUserPage(browser, 'ac14adm');
    sql(`UPDATE users SET role = 'SUPER_ADMIN' WHERE id = ${Adm.id}`);
    for (const sort of ['newest', 'title']) {
      const r = await getPage(Adm.p, `?q=shared&sort=${sort}`);
      expect(r.res.status()).toBe(200);
      expect(r.body).toEqual({ items: [], total: 0, nextCursor: null });
    }
    await gotoTasks(Adm.p, '?q=shared');
    await expect(Adm.p.getByText('No tasks match "shared"')).toBeVisible();
    expect(A.id).toBeGreaterThan(0);
    expect(B.errors).toEqual([]);
    expect(Adm.errors).toEqual([]);
    await B.ctx.close();
    await Adm.ctx.close();
  });

  test('phone width 320px: search takes the row, Sort under it, no horizontal scroll', async ({ page }) => {
    await page.setViewportSize({ width: 320, height: 720 });
    await signUp(page, 'mobile');
    await seed(page, [...numbered(55), { title: 'A really long task title that should wrap nicely on a small phone screen without overflow', tags: ['Work'] }]);
    await gotoTasks(page);
    await search(page).fill('P0');
    await search(page).press('Enter');
    await expect(footer(page)).toContainText('Showing 50 of 55');
    const sb = (await search(page).boundingBox())!;
    const so = (await sortSelect(page).boundingBox())!;
    expect(so.y, 'sort under the search box').toBeGreaterThanOrEqual(sb.y + sb.height - 1);
    expect(sb.width).toBeGreaterThan(200);
    const sw = await page.evaluate(() => document.documentElement.scrollWidth);
    expect(sw, 'no horizontal scroll').toBeLessThanOrEqual(320);
    const lm = (await loadMoreBtn(page).boundingBox())!;
    expect(Math.abs(lm.x + lm.width / 2 - 160)).toBeLessThan(30); // centred
    await shot(page, 'mobile-320-search');
    await gotoTasks(page);
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(320);
    await shot(page, 'mobile-320-list');
    // desktop: toolbar on one row
    await page.setViewportSize({ width: 1280, height: 800 });
    const sb2 = (await search(page).boundingBox())!;
    const so2 = (await sortSelect(page).boundingBox())!;
    expect(Math.abs(so2.y - sb2.y)).toBeLessThan(20);
    expect(so2.x).toBeGreaterThan(sb2.x + sb2.width - 1);
    await shot(page, 'desktop-toolbar');
  });
});
