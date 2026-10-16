import { Browser, expect, Locator, Page, Request, test as base } from '@playwright/test';

/*
 * E2E for specs/tasks.md (AC1-AC15 + Decisions log Q1-Q7 and PM rows).
 * Every test fails on browser console errors / page errors. Tests that deliberately abort
 * requests (AC15) allow only the browser's own network log lines for those requests.
 */

const RUN = `${Date.now()}-${Math.floor(Math.random() * 1e6)}`;
const SHOTS = '../test-results/tasks';
const PASSWORD = 'password123';
let counter = 0;
function uniqueEmail(tag: string): string {
  counter += 1;
  return `qa-tasks-${RUN}-${tag}-${counter}@example.com`;
}

interface Task {
  id: number;
  title: string;
  description: string | null;
  done: boolean;
  dueDate: string | null;
  createdAt: string;
  updatedAt: string;
}

interface Watch {
  consoleErrors: string[];
  pageErrors: string[];
  allowNetworkErrors: boolean;
}

const NETWORK_LOG = [
  /^Failed to load resource: net::ERR_FAILED/,
  /^Failed to load resource: the server responded with a status of (401|404|500|503)/,
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

async function xsrf(page: Page): Promise<string> {
  const c = (await page.context().cookies()).find((x) => x.name === 'XSRF-TOKEN');
  return c!.value;
}

/** Registers via the API inside the page's context, so the page is signed in. */
async function signUp(page: Page, name = 'QA'): Promise<string> {
  const email = uniqueEmail(name);
  const me = await page.request.get('/api/auth/me');
  expect(me.status()).toBe(401);
  const res = await page.request.post('/api/auth/register', {
    // Display names are unique (ignoring case/accents) and the DB keeps data, so suffix per run.
    data: { displayName: `${name} ${RUN}-${counter}`, email, password: PASSWORD },
    headers: { 'X-XSRF-TOKEN': await xsrf(page) },
  });
  expect(res.status()).toBe(201);
  return email;
}

async function api(page: Page, method: string, path: string, data?: unknown) {
  return page.request.fetch(path, {
    method,
    data,
    headers: { 'X-XSRF-TOKEN': await xsrf(page) },
  });
}

async function createTask(page: Page, body: Record<string, unknown>): Promise<Task> {
  const res = await api(page, 'POST', '/api/tasks', body);
  expect(res.status()).toBe(201);
  return (await res.json()) as Task;
}

async function getTask(page: Page, id: number): Promise<Task> {
  const res = await api(page, 'GET', `/api/tasks/${id}`);
  expect(res.status()).toBe(200);
  return (await res.json()) as Task;
}

/** All of the user's tasks, in list order: GET /api/tasks is paged since contract 0.11.0. */
async function listTasks(page: Page): Promise<Task[]> {
  const all: Task[] = [];
  let cursor: string | null = null;
  do {
    const res = await api(page, 'GET', cursor ? `/api/tasks?cursor=${cursor}` : '/api/tasks');
    expect(res.status()).toBe(200);
    const body = (await res.json()) as { items: Task[]; total: number; nextCursor: string | null };
    all.push(...body.items);
    cursor = body.nextCursor;
  } while (cursor);
  return all;
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
async function order(page: Page): Promise<string[]> {
  const labels = await list(page).getByRole('checkbox').evaluateAll((els) =>
    els.map((e) => e.getAttribute('aria-label') ?? ''),
  );
  return labels.map((l) => l.replace(/^Done: /, ''));
}
async function gotoTasks(page: Page, query = '') {
  await page.goto(`/tasks${query}`);
  await expect(page.getByRole('heading', { name: 'Your tasks' })).toBeVisible();
  await expect(page.getByRole('status')).toHaveCount(0);
}
function addBox(page: Page) {
  return page.getByLabel('New task');
}
function addBtn(page: Page) {
  return page.getByRole('button', { name: 'Add', exact: true });
}
function editDialog(page: Page) {
  return page.getByRole('dialog', { name: 'Edit task' });
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

// ---------- Create ----------

test.describe('Create', () => {
  test('AC1: add via Enter and via Add button; appears not done; box clears', async ({ page }) => {
    await signUp(page, 'ac1');
    await gotoTasks(page);
    await addBox(page).fill('Buy milk');
    await addBox(page).press('Enter');
    await expect(row(page, 'Buy milk')).toBeVisible();
    await expect(page.getByRole('checkbox', { name: 'Done: Buy milk' })).not.toBeChecked();
    await expect(addBox(page)).toHaveValue('');

    await addBox(page).fill('  Walk dog  ');
    await addBtn(page).click();
    await expect(row(page, 'Walk dog')).toBeVisible();
    await expect(page.getByRole('checkbox', { name: 'Done: Walk dog' })).not.toBeChecked();
    await expect(addBox(page)).toHaveValue('');
    await expect.poll(() => order(page)).toEqual(['Walk dog', 'Buy milk']);
    await page.screenshot({ path: `${SHOTS}/ac1-added.png`, fullPage: true });

    const server = await listTasks(page);
    expect(server.map((t) => [t.title, t.done])).toEqual([
      ['Walk dog', false],
      ['Buy milk', false],
    ]);
  });

  test('AC2/Q1: blank title creates nothing; >200 chars shows error; length checked after trim', async ({
    page,
  }) => {
    await signUp(page, 'ac2');
    await gotoTasks(page);
    const posts = countRequests(page, 'POST', /^\/api\/tasks$/);

    await expect(addBtn(page)).toBeDisabled();
    await addBox(page).fill('     ');
    await expect(addBtn(page)).toBeDisabled();
    await addBox(page).press('Enter');
    await expect(page.locator('#task-add-title-error')).toHaveCount(0);

    await addBox(page).fill('x'.repeat(201));
    await expect(addBtn(page)).toBeEnabled();
    await addBtn(page).click();
    await expect(page.getByText('Title must be 200 characters or fewer')).toBeVisible();
    await addBox(page).press('Enter');
    await page.screenshot({ path: `${SHOTS}/ac2-too-long.png`, fullPage: true });
    expect(posts.length).toBe(0);
    await expect(page.getByText('No tasks yet — add your first one above')).toBeVisible();

    // exactly 200 after trimming is accepted (Q1: length checked after trimming)
    const t200 = 'y'.repeat(200);
    await addBox(page).fill(`   ${t200}   `);
    await addBox(page).press('Enter');
    await expect(row(page, t200)).toBeVisible();
    await expect(page.getByText('Title must be 200 characters or fewer')).toHaveCount(0);
    expect(posts.length).toBe(1);
    expect((await listTasks(page)).length).toBe(1);
  });
});

// ---------- List and filter ----------

test.describe('List and filter', () => {
  test('AC3: empty state on /tasks', async ({ page }) => {
    await signUp(page, 'ac3');
    await gotoTasks(page);
    await expect(page.getByText('No tasks yet — add your first one above')).toBeVisible();
    await expect(list(page)).toHaveCount(0);
    await page.screenshot({ path: `${SHOTS}/ac3-empty.png`, fullPage: true });
  });

  test('AC4/Q2: not-done first then done, each newest first', async ({ page }) => {
    await signUp(page, 'ac4');
    const t1 = await createTask(page, { title: 'T1 oldest' });
    await createTask(page, { title: 'T2' });
    const t3 = await createTask(page, { title: 'T3' });
    await createTask(page, { title: 'T4 newest' });
    // mark done in "wrong" order, to prove done-time doesn't affect order (Q2)
    expect((await api(page, 'PATCH', `/api/tasks/${t3.id}`, { done: true })).status()).toBe(200);
    expect((await api(page, 'PATCH', `/api/tasks/${t1.id}`, { done: true })).status()).toBe(200);
    await gotoTasks(page);
    await expect.poll(() => order(page)).toEqual(['T4 newest', 'T2', 'T3', 'T1 oldest']);
    await expect(page.locator('nav[aria-label="Filter tasks"] a[aria-current="page"]')).toHaveText('All');
    await page.screenshot({ path: `${SHOTS}/ac4-order.png`, fullPage: true });
  });

  test('AC5/Q3: Active/Done filters, URL, refresh keeps filter, empty texts, invalid filter', async ({
    page,
  }) => {
    await signUp(page, 'ac5');
    await createTask(page, { title: 'Open A' });
    await createTask(page, { title: 'Open B' });
    await gotoTasks(page);
    const nav = page.locator('nav[aria-label="Filter tasks"]');

    // Done has no tasks
    await nav.getByRole('link', { name: 'Done' }).click();
    await expect(page).toHaveURL(/\/tasks\?filter=done$/);
    await expect(page.getByText('Nothing done yet')).toBeVisible();
    await expect(nav.getByRole('link', { name: 'Done' })).toHaveAttribute('aria-current', 'page');
    await page.screenshot({ path: `${SHOTS}/ac5-done-empty.png`, fullPage: true });

    const b = (await listTasks(page)).find((t) => t.title === 'Open B')!;
    await api(page, 'PATCH', `/api/tasks/${b.id}`, { done: true });

    await page.reload();
    await expect(page).toHaveURL(/\/tasks\?filter=done$/);
    await expect(nav.getByRole('link', { name: 'Done' })).toHaveAttribute('aria-current', 'page');
    await expect.poll(() => order(page)).toEqual(['Open B']);

    await nav.getByRole('link', { name: 'Active' }).click();
    await expect(page).toHaveURL(/\/tasks\?filter=active$/);
    await expect(rows(page)).toHaveCount(1);
    await expect.poll(() => order(page)).toEqual(['Open A']);
    await page.reload();
    await expect(page).toHaveURL(/\/tasks\?filter=active$/);
    await expect(nav.getByRole('link', { name: 'Active' })).toHaveAttribute('aria-current', 'page');
    await expect.poll(() => order(page)).toEqual(['Open A']);
    await page.screenshot({ path: `${SHOTS}/ac5-active.png`, fullPage: true });

    // Q3: ticking in Active removes it after server confirms; empty Active text
    await page.getByRole('checkbox', { name: 'Done: Open A' }).check();
    await expect(page.getByText('Nothing left to do')).toBeVisible();
    await expect(list(page)).toHaveCount(0);

    await nav.getByRole('link', { name: 'All' }).click();
    await expect(page).toHaveURL(/\/tasks$/);
    await expect(nav.getByRole('link', { name: 'All' })).toHaveAttribute('aria-current', 'page');
    await expect(rows(page)).toHaveCount(2);

    // Q3: invalid filter treated as All
    await gotoTasks(page, '?filter=Done');
    await expect(nav.getByRole('link', { name: 'All' })).toHaveAttribute('aria-current', 'page');
    await expect(rows(page)).toHaveCount(2);
    await gotoTasks(page, '?filter=bogus');
    await expect(rows(page)).toHaveCount(2);
  });

  test('AC6: due date shown; red Overdue only for not-done past dates', async ({ page }) => {
    await signUp(page, 'ac6');
    const yesterday = localDate(-1);
    const today = localDate(0);
    const tomorrow = localDate(1);
    await createTask(page, { title: 'No date' });
    await createTask(page, { title: 'Due tomorrow', dueDate: tomorrow });
    await createTask(page, { title: 'Due today', dueDate: today });
    await createTask(page, { title: 'Late', dueDate: yesterday });
    const doneLate = await createTask(page, { title: 'Done late', dueDate: '2020-01-01' });
    await api(page, 'PATCH', `/api/tasks/${doneLate.id}`, { done: true });
    await gotoTasks(page);

    await expect(row(page, 'Due tomorrow')).toContainText(tomorrow);
    await expect(row(page, 'Due today')).toContainText(today);
    await expect(row(page, 'Late')).toContainText(yesterday);
    await expect(row(page, 'Done late')).toContainText('2020-01-01');

    const overdue = row(page, 'Late').getByText('Overdue', { exact: true });
    await expect(overdue).toBeVisible();
    // red pill (white text on red background) or red text both count as a red label
    const color = await overdue.evaluate((el) => {
      const cs = getComputedStyle(el);
      return cs.backgroundColor !== 'rgba(0, 0, 0, 0)' ? cs.backgroundColor : cs.color;
    });
    console.log('AC6 Overdue red color:', color);
    const [r, g, bl] = color.match(/\d+/g)!.map(Number);
    expect(r, `Overdue color ${color} should be red`).toBeGreaterThan(150);
    expect(g).toBeLessThan(100);
    expect(bl).toBeLessThan(100);

    for (const t of ['No date', 'Due tomorrow', 'Due today', 'Done late']) {
      await expect(row(page, t).getByText('Overdue', { exact: true })).toHaveCount(0);
    }
    await expect(row(page, 'No date')).not.toContainText(/\d{4}-\d{2}-\d{2}/);
    await page.screenshot({ path: `${SHOTS}/ac6-due-overdue.png`, fullPage: true });
  });
});

// ---------- Complete ----------

test.describe('Complete', () => {
  test('AC7: tick marks done, strikes through, moves to done group; untick reverses; persists', async ({
    page,
  }) => {
    await signUp(page, 'ac7');
    await createTask(page, { title: 'Old done' });
    await createTask(page, { title: 'Alpha', dueDate: '2020-05-05' });
    await createTask(page, { title: 'Beta' });
    const od = (await listTasks(page)).find((t) => t.title === 'Old done')!;
    await api(page, 'PATCH', `/api/tasks/${od.id}`, { done: true });
    await gotoTasks(page);
    await expect.poll(() => order(page)).toEqual(['Beta', 'Alpha', 'Old done']);
    await expect(row(page, 'Alpha').getByText('Overdue')).toBeVisible();
    expect(await titleStruck(page, 'Alpha')).toBe(false);

    const opens = page.getByRole('dialog');
    await page.getByRole('checkbox', { name: 'Done: Alpha' }).check();
    await expect(page.getByRole('checkbox', { name: 'Done: Alpha' })).toBeChecked();
    await expect.poll(() => order(page)).toEqual(['Beta', 'Alpha', 'Old done']);
    // Alpha is newer than Old done, so it heads the done group
    await expect.poll(() => titleStruck(page, 'Alpha')).toBe(true);
    await expect(row(page, 'Alpha').getByText('Overdue')).toHaveCount(0);
    await expect(opens).toHaveCount(0); // Q7: checkbox doesn't open the dialog
    await page.screenshot({ path: `${SHOTS}/ac7-ticked.png`, fullPage: true });

    // tick Beta too -> moves below? Beta newest -> top of done group
    await page.getByRole('checkbox', { name: 'Done: Beta' }).check();
    await expect.poll(() => order(page)).toEqual(['Beta', 'Alpha', 'Old done']);
    await page.getByRole('checkbox', { name: 'Done: Alpha' }).uncheck();
    await expect.poll(() => order(page)).toEqual(['Alpha', 'Beta', 'Old done']);

    await page.reload();
    await expect(rows(page)).toHaveCount(3);
    await expect.poll(() => order(page)).toEqual(['Alpha', 'Beta', 'Old done']);
    await expect(page.getByRole('checkbox', { name: 'Done: Alpha' })).not.toBeChecked();
    await expect(page.getByRole('checkbox', { name: 'Done: Beta' })).toBeChecked();
    expect(await titleStruck(page, 'Beta')).toBe(true);
    expect(await titleStruck(page, 'Alpha')).toBe(false);
    await expect(row(page, 'Alpha').getByText('Overdue')).toBeVisible();
  });
});

// ---------- Edit ----------

test.describe('Edit', () => {
  test('AC8/Q7: title click and Edit button open dialog; save closes, shows and persists', async ({
    page,
  }) => {
    await signUp(page, 'ac8');
    await createTask(page, { title: 'Original', description: 'Old desc', dueDate: '2030-01-02' });
    await gotoTasks(page);

    await row(page, 'Original').getByRole('button', { name: 'Original', exact: true }).click();
    const dlg = editDialog(page);
    await expect(dlg).toBeVisible();
    await expect(dlg.getByLabel('Title')).toHaveValue('Original');
    await expect(dlg.getByLabel('Description')).toHaveValue('Old desc');
    await expect(dlg.getByLabel('Due date')).toHaveValue('2030-01-02');
    await expect(dlg.getByLabel('Status')).toHaveValue('TODO'); // T1 AC18: Status replaces the Done checkbox
    await page.screenshot({ path: `${SHOTS}/ac8-dialog.png`, fullPage: true });
    await dlg.getByRole('button', { name: 'Cancel' }).click();
    await expect(dlg).toHaveCount(0);

    await row(page, 'Original').getByRole('button', { name: 'Edit' }).click();
    await expect(dlg).toBeVisible();
    await dlg.getByLabel('Title').fill('  Renamed  ');
    await dlg.getByLabel('Description').fill('New desc');
    await dlg.getByLabel('Due date').fill('2031-03-04');
    await dlg.getByLabel('Status').selectOption('DONE'); // T1 AC18
    await dlg.getByRole('button', { name: 'Save' }).click();
    await expect(dlg).toHaveCount(0);
    await expect(row(page, 'Renamed')).toContainText('2031-03-04');
    await expect(page.getByRole('checkbox', { name: 'Done: Renamed' })).toBeChecked();
    expect(await titleStruck(page, 'Renamed')).toBe(true);
    await page.screenshot({ path: `${SHOTS}/ac8-saved.png`, fullPage: true });

    await page.reload();
    await expect(row(page, 'Renamed')).toContainText('2031-03-04');
    await expect(page.getByRole('checkbox', { name: 'Done: Renamed' })).toBeChecked();
    const [t] = await listTasks(page);
    expect(t).toMatchObject({ title: 'Renamed', description: 'New desc', dueDate: '2031-03-04', done: true });
    await row(page, 'Renamed').getByRole('button', { name: 'Renamed', exact: true }).click();
    await expect(dlg.getByLabel('Description')).toHaveValue('New desc');
  });

  test('AC9: invalid fields rejected with error under field; Cancel/Escape change nothing', async ({
    page,
  }) => {
    await signUp(page, 'ac9');
    const created = await createTask(page, { title: 'Keep me', description: 'Keep desc' });
    await gotoTasks(page);
    const puts = countRequests(page, 'PUT', /^\/api\/tasks\/\d+$/);
    const dlg = editDialog(page);

    await row(page, 'Keep me').getByRole('button', { name: 'Edit' }).click();
    await dlg.getByLabel('Title').fill('   ');
    await dlg.getByRole('button', { name: 'Save' }).click();
    await expect(dlg).toBeVisible();
    await expect(dlg.locator('#task-edit-title-error')).toHaveText('Title is required');
    await expect(dlg.getByLabel('Title')).toHaveAttribute('aria-describedby', 'task-edit-title-error');

    await dlg.getByLabel('Title').fill('t'.repeat(201));
    await dlg.getByRole('button', { name: 'Save' }).click();
    await expect(dlg.locator('#task-edit-title-error')).toHaveText('Title must be 200 characters or fewer');

    await dlg.getByLabel('Title').fill('Fine');
    await dlg.getByLabel('Description').fill('d'.repeat(2001));
    await dlg.getByRole('button', { name: 'Save' }).click();
    await expect(dlg).toBeVisible();
    await expect(dlg.locator('#task-edit-title-error')).toHaveCount(0);
    await expect(dlg.locator('#task-edit-description-error')).toHaveText(
      'Description must be 2,000 characters or fewer',
    );
    await page.screenshot({ path: `${SHOTS}/ac9-errors.png`, fullPage: true });
    expect(puts.length).toBe(0);

    await dlg.getByRole('button', { name: 'Cancel' }).click();
    await expect(dlg).toHaveCount(0);
    await expect(row(page, 'Keep me')).toBeVisible();
    expect(puts.length).toBe(0);

    // Escape also cancels; reopening shows original values (edits discarded)
    await row(page, 'Keep me').getByRole('button', { name: 'Edit' }).click();
    await expect(dlg.getByLabel('Title')).toHaveValue('Keep me');
    await expect(dlg.getByLabel('Description')).toHaveValue('Keep desc');
    await dlg.getByLabel('Title').fill('Changed but cancelled');
    await page.keyboard.press('Escape');
    await expect(dlg).toHaveCount(0);
    await expect(row(page, 'Keep me')).toBeVisible();
    expect(puts.length).toBe(0);
    const after = await getTask(page, created.id);
    expect(after).toEqual(created);

    // boundary: 2000-char description after trimming is accepted (Q6)
    await row(page, 'Keep me').getByRole('button', { name: 'Edit' }).click();
    await dlg.getByLabel('Description').fill(`  ${'e'.repeat(2000)}  `);
    await dlg.getByRole('button', { name: 'Save' }).click();
    await expect(dlg).toHaveCount(0);
    expect((await getTask(page, created.id)).description).toBe('e'.repeat(2000));
  });

  test('AC10/Q6: due date optional; past date accepted; can clear; blank description stored as null', async ({
    page,
  }) => {
    await signUp(page, 'ac10');
    const t = await createTask(page, { title: 'Dated', description: 'something', dueDate: '2030-06-01' });
    await gotoTasks(page);
    const dlg = editDialog(page);

    await row(page, 'Dated').getByRole('button', { name: 'Edit' }).click();
    await dlg.getByLabel('Due date').fill('2019-02-03');
    await dlg.getByRole('button', { name: 'Save' }).click();
    await expect(dlg).toHaveCount(0);
    await expect(row(page, 'Dated')).toContainText('2019-02-03');
    await expect(row(page, 'Dated').getByText('Overdue')).toBeVisible();
    expect((await getTask(page, t.id)).dueDate).toBe('2019-02-03');

    await row(page, 'Dated').getByRole('button', { name: 'Edit' }).click();
    await dlg.getByRole('button', { name: 'Clear date' }).click();
    await expect(dlg.getByLabel('Due date')).toHaveValue('');
    await expect(dlg.getByRole('button', { name: 'Clear date' })).toHaveCount(0);
    await dlg.getByLabel('Description').fill('    ');
    await dlg.getByRole('button', { name: 'Save' }).click();
    await expect(dlg).toHaveCount(0);
    await expect(row(page, 'Dated')).not.toContainText(/\d{4}-\d{2}-\d{2}/);
    await expect(row(page, 'Dated').getByText('Overdue')).toHaveCount(0);
    await page.screenshot({ path: `${SHOTS}/ac10-cleared.png`, fullPage: true });
    const after = await getTask(page, t.id);
    expect(after.dueDate).toBeNull();
    expect(after.description).toBeNull();

    await page.reload();
    await expect(row(page, 'Dated')).not.toContainText(/\d{4}-\d{2}-\d{2}/);
  });
});

// ---------- Delete ----------

test.describe('Delete', () => {
  test('AC11: confirm dialog; Cancel keeps; Delete removes permanently', async ({ page }) => {
    await signUp(page, 'ac11');
    await createTask(page, { title: 'Doomed' });
    await createTask(page, { title: 'Survivor' });
    await gotoTasks(page);
    const dels = countRequests(page, 'DELETE', /^\/api\/tasks\/\d+$/);

    await row(page, 'Doomed').getByRole('button', { name: 'Delete' }).click();
    const dlg = page.getByRole('dialog', { name: "Delete 'Doomed'?" });
    await expect(dlg).toBeVisible();
    await expect(dlg.getByRole('heading', { name: "Delete 'Doomed'?" })).toBeVisible();
    await page.screenshot({ path: `${SHOTS}/ac11-confirm.png`, fullPage: true });
    await dlg.getByRole('button', { name: 'Cancel' }).click();
    await expect(dlg).toHaveCount(0);
    await expect(rows(page)).toHaveCount(2);
    expect(dels.length).toBe(0);

    await row(page, 'Doomed').getByRole('button', { name: 'Delete' }).click();
    await dlg.getByRole('button', { name: 'Delete' }).click();
    await expect(dlg).toHaveCount(0);
    await expect(row(page, 'Doomed')).toHaveCount(0);
    await expect.poll(() => order(page)).toEqual(['Survivor']);
    expect(dels.length).toBe(1);

    await page.reload();
    await expect(rows(page)).toHaveCount(1);
    await expect.poll(() => order(page)).toEqual(['Survivor']);
    expect((await listTasks(page)).map((t) => t.title)).toEqual(['Survivor']);
  });
});

// ---------- Ownership ----------

async function newUserPage(browser: Browser, name: string) {
  const ctx = await browser.newContext({ baseURL: process.env['E2E_BASE_URL'] ?? 'http://localhost:4200' });
  const p = await ctx.newPage();
  await signUp(p, name);
  return { ctx, p };
}

test.describe('Ownership', () => {
  test('AC12: A sees only own tasks', async ({ page, browser }) => {
    await signUp(page, 'ac12a');
    await createTask(page, { title: 'A task 1' });
    await createTask(page, { title: 'A task 2' });
    const { ctx, p: pageB } = await newUserPage(browser, 'ac12b');
    await createTask(pageB, { title: 'B private' });

    await gotoTasks(page);
    await expect.poll(() => order(page)).toEqual(['A task 2', 'A task 1']);
    await expect(page.getByText('B private')).toHaveCount(0);

    await gotoTasks(pageB);
    await expect.poll(() => order(pageB)).toEqual(['B private']);
    await pageB.screenshot({ path: `${SHOTS}/ac12-user-b.png`, fullPage: true });
    await ctx.close();
  });

  test('AC13: A gets 404 on GET/PUT/PATCH/DELETE of B task; B task unchanged', async ({ page, browser }) => {
    await signUp(page, 'ac13a');
    const { ctx, p: pageB } = await newUserPage(browser, 'ac13b');
    const bTask = await createTask(pageB, { title: 'B owns', description: 'secret', dueDate: '2030-01-01' });
    const id = bTask.id;

    const calls: [string, unknown][] = [
      ['GET', undefined],
      ['PUT', { title: 'pwned', description: null, dueDate: null, done: true }],
      ['PATCH', { done: true }],
      ['DELETE', undefined],
    ];
    for (const [m, body] of calls) {
      const res = await api(page, m, `/api/tasks/${id}`, body);
      expect(res.status(), `${m} as A`).toBe(404);
      expect(res.headers()['content-type']).toContain('application/problem+json');
      expect(await res.json()).toMatchObject({ status: 404, code: 'NOT_FOUND', title: 'Not Found' });
    }
    // identical body to a non-existent id
    const missing = await api(page, 'GET', `/api/tasks/987654321`);
    expect(missing.status()).toBe(404);
    expect((await missing.json()).detail).toBe('Task not found');

    expect(await getTask(pageB, id)).toEqual(bTask);
    await ctx.close();
  });
});

// ---------- States ----------

function delay(ms: number) {
  return new Promise((r) => setTimeout(r, ms));
}

test.describe('States', () => {
  test('AC14: loading indicator; controls disabled while create/toggle/edit/delete run', async ({ page }) => {
    await signUp(page, 'ac14');
    await createTask(page, { title: 'Row one' });
    await createTask(page, { title: 'Row two' });

    // list loading
    let release!: () => void;
    const gate = new Promise<void>((r) => (release = r));
    await page.route('**/api/tasks', async (route) => {
      if (route.request().method() === 'GET') await gate;
      await route.continue();
    });
    await page.goto('/tasks');
    await expect(page.getByRole('status')).toHaveText(/Loading tasks…/);
    await page.screenshot({ path: `${SHOTS}/ac14-loading.png`, fullPage: true });
    release();
    await expect(page.getByRole('status')).toHaveCount(0);
    await expect(rows(page)).toHaveCount(2);
    await page.unrouteAll({ behavior: 'wait' });

    // slow every mutation
    await page.route(/\/api\/tasks(\/\d+)?$/, async (route) => {
      if (route.request().method() !== 'GET') await delay(1200);
      await route.continue();
    });

    // create: Add disabled, double submit creates one task
    const posts = countRequests(page, 'POST', /^\/api\/tasks$/);
    await addBox(page).fill('Slow add');
    await addBtn(page).click();
    await expect(addBtn(page)).toBeDisabled();
    await addBox(page).press('Enter');
    await page.screenshot({ path: `${SHOTS}/ac14-adding.png`, fullPage: true });
    await expect(row(page, 'Slow add')).toBeVisible();
    expect(posts.length).toBe(1);

    // toggle: only the busy row disabled (PM decision)
    const cb = page.getByRole('checkbox', { name: 'Done: Row one' });
    await cb.click();
    await expect(cb).toBeDisabled();
    await expect(row(page, 'Row one').getByRole('button', { name: 'Edit' })).toBeDisabled();
    await expect(row(page, 'Row one').getByRole('button', { name: 'Delete' })).toBeDisabled();
    await expect(page.getByRole('checkbox', { name: 'Done: Row two' })).toBeEnabled();
    await expect(row(page, 'Row two').getByRole('button', { name: 'Edit' })).toBeEnabled();
    await page.screenshot({ path: `${SHOTS}/ac14-toggling.png`, fullPage: true });
    await expect(cb).toBeEnabled();
    await expect(cb).toBeChecked();

    // edit: Save disabled while saving
    await row(page, 'Row two').getByRole('button', { name: 'Edit' }).click();
    const dlg = editDialog(page);
    await dlg.getByLabel('Title').fill('Row two edited');
    await dlg.getByRole('button', { name: 'Save' }).click();
    await expect(dlg.getByRole('button', { name: 'Save' })).toBeDisabled();
    await expect(dlg.getByRole('button', { name: 'Cancel' })).toBeDisabled();
    await expect(dlg).toHaveCount(0);
    await expect(row(page, 'Row two edited')).toBeVisible();

    // delete: Delete disabled while deleting
    await row(page, 'Slow add').getByRole('button', { name: 'Delete' }).click();
    const del = page.getByRole('dialog', { name: "Delete 'Slow add'?" });
    await del.getByRole('button', { name: 'Delete' }).click();
    await expect(del.getByRole('button', { name: 'Delete' })).toBeDisabled();
    await expect(del).toHaveCount(0);
    await expect(row(page, 'Slow add')).toHaveCount(0);
  });

  test('AC15: list load failure shows banner with Retry; Retry reloads', async ({ page, watch }) => {
    watch.allowNetworkErrors = true;
    await signUp(page, 'ac15a');
    await createTask(page, { title: 'Survives outage' });
    let fail = true;
    await page.route('**/api/tasks', (route) =>
      fail && route.request().method() === 'GET' ? route.abort('failed') : route.continue(),
    );
    await page.goto('/tasks');
    const alert = page.getByRole('alert');
    await expect(alert).toBeVisible();
    await expect(alert.getByRole('button', { name: 'Retry' })).toBeVisible();
    await expect(page.getByRole('status')).toHaveCount(0);
    await page.screenshot({ path: `${SHOTS}/ac15-load-failed.png`, fullPage: true });
    console.log('AC15 load banner:', await alert.textContent());

    await alert.getByRole('button', { name: 'Retry' }).click();
    await expect(alert).toBeVisible(); // still down
    fail = false;
    await alert.getByRole('button', { name: 'Retry' }).click();
    await expect(alert).toHaveCount(0);
    await expect(row(page, 'Survives outage')).toBeVisible();
  });

  test('AC15/Q5: failed create/toggle keep list as it was; Retry re-runs the request', async ({
    page,
    watch,
  }) => {
    watch.allowNetworkErrors = true;
    await signUp(page, 'ac15b');
    await createTask(page, { title: 'Stable' });
    await gotoTasks(page);
    let down = true;
    await page.route(/\/api\/tasks(\/\d+)?$/, (route) =>
      down && route.request().method() !== 'GET' ? route.abort('failed') : route.continue(),
    );

    // toggle fails: checkbox reverts, list unchanged
    const cb = page.getByRole('checkbox', { name: 'Done: Stable' });
    await cb.click();
    const alert = page.getByRole('alert');
    await expect(alert).toBeVisible();
    await expect(cb).not.toBeChecked();
    expect(await titleStruck(page, 'Stable')).toBe(false);
    await expect.poll(() => order(page)).toEqual(['Stable']);
    await page.screenshot({ path: `${SHOTS}/ac15-toggle-failed.png`, fullPage: true });
    console.log('AC15 toggle banner:', await alert.textContent());
    down = false;
    await alert.getByRole('button', { name: 'Retry' }).click();
    await expect(alert).toHaveCount(0);
    await expect(cb).toBeChecked();
    expect((await listTasks(page))[0].done).toBe(true);

    // create fails: nothing added
    down = true;
    await addBox(page).fill('Offline add');
    await addBtn(page).click();
    await expect(alert).toBeVisible();
    await expect(row(page, 'Offline add')).toHaveCount(0);
    await expect.poll(() => order(page)).toEqual(['Stable']);
    console.log('AC15 add banner:', await alert.textContent());
    await page.screenshot({ path: `${SHOTS}/ac15-add-failed.png`, fullPage: true });
    down = false;
    await alert.getByRole('button', { name: 'Retry' }).click();
    await expect(alert).toHaveCount(0);
    await expect(row(page, 'Offline add')).toBeVisible();
    expect((await listTasks(page)).filter((t) => t.title === 'Offline add').length).toBe(1);
  });

  test('AC15/PM: failed save/delete keep dialog open with Retry and keep input', async ({ page, watch }) => {
    watch.allowNetworkErrors = true;
    await signUp(page, 'ac15c');
    const t = await createTask(page, { title: 'Dialog task' });
    await gotoTasks(page);
    let down = true;
    await page.route(/\/api\/tasks\/\d+$/, (route) =>
      down && route.request().method() !== 'GET' ? route.abort('failed') : route.continue(),
    );

    await row(page, 'Dialog task').getByRole('button', { name: 'Edit' }).click();
    const dlg = editDialog(page);
    await dlg.getByLabel('Title').fill('Dialog task v2');
    await dlg.getByRole('button', { name: 'Save' }).click();
    const dAlert = dlg.getByRole('alert');
    await expect(dAlert).toBeVisible();
    await expect(dlg).toBeVisible();
    await expect(dlg.getByLabel('Title')).toHaveValue('Dialog task v2');
    await page.screenshot({ path: `${SHOTS}/ac15-save-failed.png`, fullPage: true });
    expect((await getTask(page, t.id)).title).toBe('Dialog task');
    down = false;
    await dAlert.getByRole('button', { name: 'Retry' }).click();
    await expect(dlg).toHaveCount(0);
    await expect(row(page, 'Dialog task v2')).toBeVisible();

    down = true;
    await row(page, 'Dialog task v2').getByRole('button', { name: 'Delete' }).click();
    const del = page.getByRole('dialog', { name: "Delete 'Dialog task v2'?" });
    await del.getByRole('button', { name: 'Delete' }).click();
    await expect(del.getByRole('alert')).toBeVisible();
    await expect(del).toBeVisible();
    await page.screenshot({ path: `${SHOTS}/ac15-delete-failed.png`, fullPage: true });
    down = false;
    await del.getByRole('alert').getByRole('button', { name: 'Retry' }).click();
    await expect(del).toHaveCount(0);
    await expect(list(page)).toHaveCount(0);
    expect(await listTasks(page)).toEqual([]);
  });

  test('PM 404 rule: task deleted elsewhere -> "This task no longer exists", no Retry, list reloads', async ({
    page,
    watch,
  }) => {
    watch.allowNetworkErrors = true;
    await signUp(page, 'nf');
    const gone = await createTask(page, { title: 'Ghost' });
    await createTask(page, { title: 'Real' });
    await gotoTasks(page);
    await api(page, 'DELETE', `/api/tasks/${gone.id}`);

    await page.getByRole('checkbox', { name: 'Done: Ghost' }).click();
    const alert = page.getByRole('alert');
    await expect(alert).toContainText('This task no longer exists');
    await expect(alert.getByRole('button', { name: 'Retry' })).toHaveCount(0);
    await expect(row(page, 'Ghost')).toHaveCount(0);
    await expect(row(page, 'Real')).toBeVisible();
    await page.screenshot({ path: `${SHOTS}/pm-404.png`, fullPage: true });

    // 404 inside edit dialog closes it
    const real = (await listTasks(page))[0];
    await row(page, 'Real').getByRole('button', { name: 'Edit' }).click();
    await api(page, 'DELETE', `/api/tasks/${real.id}`);
    await editDialog(page).getByRole('button', { name: 'Save' }).click();
    await expect(editDialog(page)).toHaveCount(0);
    await expect(alert).toContainText('This task no longer exists');
    await expect(page.getByText('No tasks yet — add your first one above')).toBeVisible();
  });

  test('Edge: double-click on confirm Delete and on Save sends one request each', async ({ page }) => {
    await signUp(page, 'dbl');
    await createTask(page, { title: 'Twice' });
    await gotoTasks(page);
    const puts = countRequests(page, 'PUT', /^\/api\/tasks\/\d+$/);
    const dels = countRequests(page, 'DELETE', /^\/api\/tasks\/\d+$/);
    await row(page, 'Twice').getByRole('button', { name: 'Edit' }).click();
    await editDialog(page).getByLabel('Title').fill('Twice edited');
    await editDialog(page).getByRole('button', { name: 'Save' }).dblclick();
    await expect(editDialog(page)).toHaveCount(0);
    await row(page, 'Twice edited').getByRole('button', { name: 'Delete' }).click();
    await page.getByRole('dialog', { name: "Delete 'Twice edited'?" }).getByRole('button', { name: 'Delete' }).dblclick();
    await expect(page.getByText('No tasks yet — add your first one above')).toBeVisible();
    await expect(page.getByRole('alert')).toHaveCount(0);
    expect(puts.length).toBe(1);
    expect(dels.length).toBe(1);
  });

  test('401 on tasks page (session gone) does not show task error banner', async ({ page, watch }) => {
    watch.allowNetworkErrors = true;
    await signUp(page, 'unauth');
    await gotoTasks(page);
    await page.context().clearCookies({ name: 'JSESSIONID' });
    await page.reload();
    await expect(page).toHaveURL(/\/login/);
  });
});

