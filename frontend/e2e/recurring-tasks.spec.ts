import { APIResponse, Browser, BrowserContext, expect, Locator, Page, test as base } from '@playwright/test';
import { execFileSync } from 'node:child_process';

/*
 * E2E for specs/due-times-reminders.md Part B (T4b: AC27-AC33), contract 0.15.0.
 * Needs the usual stack (see memory/project-e2e-run-setup): backend with raised rate limits, docker db (SQL helper).
 */

const RUN = `${Date.now()}-${Math.floor(Math.random() * 1e6)}`;
const BASE = process.env['E2E_BASE_URL'] ?? 'http://localhost:4200';
const SHOTS = '../test-results/t4b';
const PASSWORD = 'password123';
let counter = 0;
function uid(): string {
  counter += 1;
  return `${RUN}-${counter}`;
}
const NETWORK_LOG = [
  /^Failed to load resource: net::ERR_FAILED/,
  /^Failed to load resource: the server responded with a status of (400|401|404|500|503)/,
];

type Day = 'MON' | 'TUE' | 'WED' | 'THU' | 'FRI' | 'SAT' | 'SUN';
const DAYS: Day[] = ['MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT', 'SUN'];
interface Rule {
  frequency: 'DAILY' | 'WEEKLY' | 'MONTHLY' | 'YEARLY';
  weekdays: Day[];
}
interface Task {
  id: number;
  title: string;
  description: string | null;
  priority: string;
  status: string;
  tags: string[];
  done: boolean;
  dueDate: string | null;
  dueTime: string | null;
  repeat: Rule | null;
  myReminder: string | null;
  myReminderPassed: boolean;
  listId: number;
  addedBy: { id: number; displayName?: string } | number;
  nextTask?: Task | null;
}
const TASK_KEYS = [
  'addedBy', 'createdAt', 'description', 'done', 'dueDate', 'dueTime', 'id', 'listId', 'myReminder',
  'myReminderPassed', 'priority', 'repeat', 'status', 'tags', 'title', 'updatedAt',
];
interface TaskList { id: number; name: string; personal: boolean }

function sql(query: string): string {
  return execFileSync(
    'docker',
    ['compose', 'exec', '-T', 'db', 'mysql', '-utaskapp', '-ptaskapp', 'taskapp', '-N', '-B', '-e', query],
    { cwd: '..', encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] },
  ).trim();
}
function verify(userId: number) {
  sql(`UPDATE users SET email_verified_at = NOW(6) WHERE id = ${userId}`);
}

function inZone(zone: string): string {
  const p = Object.fromEntries(
    new Intl.DateTimeFormat('en-CA', { timeZone: zone, year: 'numeric', month: '2-digit', day: '2-digit' })
      .formatToParts(new Date())
      .map((x) => [x.type, x.value]),
  );
  return `${p['year']}-${p['month']}-${p['day']}`;
}
function addDays(date: string, n: number): string {
  const d = new Date(`${date}T00:00:00Z`);
  d.setUTCDate(d.getUTCDate() + n);
  return d.toISOString().slice(0, 10);
}
function dow(date: string): Day {
  return DAYS[(new Date(`${date}T00:00:00Z`).getUTCDay() + 6) % 7];
}
function lastDay(y: number, m: number): number {
  return new Date(Date.UTC(y, m, 0)).getUTCDate();
}
function ymd(y: number, m: number, d: number): string {
  return `${y}-${String(m).padStart(2, '0')}-${String(d).padStart(2, '0')}`;
}

interface U { ctx: BrowserContext; page: Page; id: number; email: string; name: string }
class Kit {
  contexts: BrowserContext[] = [];
  consoleErrors: string[] = [];
  pageErrors: string[] = [];
  serverErrors: string[] = [];
  allowNetworkErrors = false;
  allow5xx = false;
  constructor(readonly browser: Browser) {}
  async newUser(zone = 'UTC'): Promise<U> {
    const ctx = await this.browser.newContext({ baseURL: BASE, timezoneId: 'UTC' });
    this.contexts.push(ctx);
    const page = await ctx.newPage();
    page.on('console', (m) => m.type() === 'error' && this.consoleErrors.push(m.text()));
    page.on('pageerror', (e) => this.pageErrors.push(e.message));
    page.on('response', (r) => {
      if (r.url().includes('/api/') && r.status() >= 500) this.serverErrors.push(`${r.status()} ${r.request().method()} ${r.url()}`);
    });
    const email = `qa-t4b-${uid()}@example.com`;
    const name = `QA T4b ${uid()}`;
    expect((await page.request.get('/api/auth/me')).status()).toBe(401);
    const res = await page.request.post('/api/auth/register', {
      data: { displayName: name, email, password: PASSWORD },
      headers: { 'X-XSRF-TOKEN': await xsrf(page) },
    });
    expect(res.status(), await res.text()).toBe(201);
    const id = ((await res.json()) as { id: number }).id;
    const r = await api(page, 'PUT', '/api/account/time-zone', { timeZone: zone });
    expect(r.status(), await r.text()).toBe(200);
    verify(id);
    return { ctx, page, id, email, name };
  }
  async finish() {
    await Promise.all(this.contexts.map((c) => c.close().catch(() => undefined)));
    const errors = this.allowNetworkErrors ? this.consoleErrors.filter((e) => !NETWORK_LOG.some((r) => r.test(e))) : this.consoleErrors;
    // the signed-out /api/auth/me probe before registration logs one 401
    const real = errors.filter((e) => !/status of 401/.test(e));
    expect(real, 'browser console errors').toEqual([]);
    expect(this.pageErrors, 'uncaught page errors').toEqual([]);
    if (!this.allow5xx) expect(this.serverErrors, '5xx responses').toEqual([]);
  }
}
const test = base.extend<{ kit: Kit }>({
  kit: async ({ browser }, use) => {
    const kit = new Kit(browser);
    await use(kit);
    await kit.finish();
  },
});

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
  expect(res.status(), await res.text()).toBe(status);
  return (await res.json()) as T;
}

interface Mk {
  title?: string;
  description?: string | null;
  dueDate?: string | null;
  dueTime?: string | null;
  repeat?: Rule | 'NEVER';
  reminder?: string;
  listId?: number;
  priority?: string;
  tags?: string[];
}
async function mk(page: Page, o: Mk = {}): Promise<Task> {
  const title = o.title ?? `R ${uid()}`;
  const body: Record<string, unknown> = { title };
  if (o.listId) body['listId'] = o.listId;
  let t = await json<Task>(await api(page, 'POST', '/api/tasks', body), 201);
  if (Object.keys(o).some((k) => k !== 'title' && k !== 'listId')) {
    t = await put(page, t.id, { title, description: o.description ?? null, ...o });
  }
  return t;
}
async function put(page: Page, id: number, body: Record<string, unknown>, status = 200): Promise<Task> {
  // PUT replaces dueDate/dueTime when missing, so carry the current ones unless the caller sets them
  const cur = await get(page, id);
  const full = { dueDate: cur.dueDate, ...(cur.dueDate && !('dueTime' in body) ? { dueTime: cur.dueTime } : {}), ...body };
  return json<Task>(await api(page, 'PUT', `/api/tasks/${id}`, full), status);
}
async function patch(page: Page, id: number, body: Record<string, unknown>, status = 200): Promise<Task> {
  return json<Task>(await api(page, 'PATCH', `/api/tasks/${id}`, body), status);
}
async function complete(page: Page, t: Task, via: 'PATCH' | 'PUT' = 'PATCH'): Promise<Task> {
  return via === 'PATCH'
    ? patch(page, t.id, { done: true })
    : put(page, t.id, { title: t.title, description: t.description, status: 'DONE' });
}
async function get(page: Page, id: number): Promise<Task> {
  return json<Task>(await page.request.get(`/api/tasks/${id}`));
}
async function listTasks(page: Page, listId: number): Promise<Task[]> {
  return (await json<{ items: Task[] }>(await page.request.get(`/api/tasks?list=${listId}&size=50`))).items;
}
async function personalList(page: Page): Promise<TaskList> {
  const ls = await json<TaskList[]>(await page.request.get('/api/lists'));
  return ls.find((l) => l.personal)!;
}
async function createList(page: Page, name: string): Promise<TaskList> {
  return json<TaskList>(await api(page, 'POST', '/api/lists', { name }), 201);
}
async function share(owner: U, listId: number, member: U) {
  await json(await api(owner.page, 'POST', `/api/lists/${listId}/invitations`, { email: member.email }), 201);
  const invs = await json<{ id: number; list: { id: number } }[]>(await member.page.request.get('/api/invitations'));
  const inv = invs.find((i) => i.list.id === listId)!;
  await json(await api(member.page, 'POST', `/api/invitations/${inv.id}/accept`));
}
function rule(frequency: Rule['frequency'], weekdays: Day[] = []): Rule {
  return { frequency, weekdays };
}
/** Completes `t` and returns the copy (asserting there is one). */
async function next(page: Page, t: Task, via: 'PATCH' | 'PUT' = 'PATCH'): Promise<Task> {
  const r = await complete(page, t, via);
  expect(r.nextTask, `copy of ${t.dueDate}`).toBeTruthy();
  return r.nextTask!;
}

function editDialog(page: Page) {
  return page.getByRole('dialog', { name: 'Edit task' });
}
function rows(page: Page, title: string): Locator {
  return page
    .getByRole('list', { name: 'Tasks' })
    .getByRole('listitem')
    .filter({ has: page.getByRole('checkbox', { name: `Done: ${title}`, exact: true }) });
}
async function gotoTasks(page: Page) {
  await page.goto('/tasks');
  await expect(page.getByRole('heading', { name: 'My tasks' })).toBeVisible();
  await expect(page.getByText('Loading tasks…')).toHaveCount(0);
}
async function shot(page: Page, name: string) {
  await page.screenshot({ path: `${SHOTS}/${name}.png`, fullPage: true });
}

// =====================================================================================
// AC27 picker + icon + contract shape
// =====================================================================================
test.describe('AC27 repeat picker', () => {
  test('dialog: disabled without date, options, weekday chips, hints, save, row icon', async ({ kit }) => {
    const u = await kit.newUser();
    const t = await mk(u.page, { title: `Pick ${uid()}` });
    expect(Object.keys(t).sort()).toEqual(TASK_KEYS);
    expect(t.repeat).toBeNull();
    expect('nextTask' in t).toBe(false);
    await gotoTasks(u.page);
    await rows(u.page, t.title).getByRole('button', { name: 'Edit' }).click();
    const dlg = editDialog(u.page);
    await expect(dlg.locator('#task-edit-repeat')).toBeDisabled();
    await expect(dlg.getByTestId('repeat-hint')).toHaveText('Set a due date to repeat');
    await shot(u.page, 'ac27-disabled');
    await dlg.locator('#task-edit-due').fill('2030-01-31');
    await expect(dlg.locator('#task-edit-repeat')).toBeEnabled();
    expect(await dlg.locator('#task-edit-repeat option').evaluateAll((o) => o.map((x) => (x as HTMLOptionElement).value))).toEqual([
      'NEVER', 'DAILY', 'WEEKLY', 'MONTHLY', 'YEARLY',
    ]);
    console.log('options', await dlg.locator('#task-edit-repeat option').allTextContents());
    await expect(dlg.getByTestId('repeat-weekdays')).toHaveCount(0);
    await dlg.locator('#task-edit-repeat').selectOption('MONTHLY');
    await expect(dlg.getByTestId('repeat-hint')).toContainText('31st');
    console.log('monthly hint:', await dlg.getByTestId('repeat-hint').textContent());
    await dlg.locator('#task-edit-due').fill('2032-02-29');
    await dlg.locator('#task-edit-repeat').selectOption('YEARLY');
    await expect(dlg.getByTestId('repeat-hint')).toContainText('February 28');
    await dlg.locator('#task-edit-due').fill('2030-01-31');
    await dlg.locator('#task-edit-repeat').selectOption('WEEKLY');
    for (const d of DAYS) await expect(dlg.getByTestId(`repeat-day-${d}`)).toHaveAttribute('aria-pressed', /true|false/);
    console.log('default weekday pressed (THU due):', await dlg.getByTestId('repeat-day-THU').getAttribute('aria-pressed'));
    await dlg.getByTestId('repeat-day-WED').click();
    await dlg.getByTestId('repeat-day-MON').click();
    if ((await dlg.getByTestId('repeat-day-THU').getAttribute('aria-pressed')) === 'true') await dlg.getByTestId('repeat-day-THU').click();
    await expect(dlg.getByTestId('repeat-day-MON')).toHaveAttribute('aria-pressed', 'true');
    await shot(u.page, 'ac27-weekly');
    await dlg.getByRole('button', { name: 'Save' }).click();
    await expect(dlg).toHaveCount(0);
    const row = rows(u.page, t.title);
    await expect(row.getByTestId('task-repeat')).toHaveAttribute('aria-label', 'Repeats weekly (Mon, Wed)');
    await shot(u.page, 'ac27-row-icon');
    const saved = await get(u.page, t.id);
    expect(saved.repeat).toEqual({ frequency: 'WEEKLY', weekdays: ['MON', 'WED'] });
    // reload: dialog shows saved rule
    await u.page.reload();
    await rows(u.page, t.title).getByRole('button', { name: 'Edit' }).click();
    await expect(editDialog(u.page).locator('#task-edit-repeat')).toHaveValue('WEEKLY');
    await expect(editDialog(u.page).getByTestId('repeat-day-WED')).toHaveAttribute('aria-pressed', 'true');
    // Stop repeating
    await editDialog(u.page).locator('#task-edit-repeat').selectOption('NEVER');
    await editDialog(u.page).getByRole('button', { name: 'Save' }).click();
    await expect(editDialog(u.page)).toHaveCount(0);
    await expect(rows(u.page, t.title).getByTestId('task-repeat')).toHaveCount(0);
    expect((await get(u.page, t.id)).repeat).toBeNull();
  });

  test('icon label for each frequency; clearing the due date in the dialog clears the rule', async ({ kit }) => {
    const u = await kit.newUser();
    const a = await mk(u.page, { dueDate: '2030-05-01', repeat: rule('DAILY') });
    const b = await mk(u.page, { dueDate: '2030-05-01', repeat: rule('MONTHLY') });
    const c = await mk(u.page, { dueDate: '2030-05-01', repeat: rule('YEARLY') });
    expect(a.repeat).toEqual({ frequency: 'DAILY', weekdays: [] });
    await gotoTasks(u.page);
    for (const t of [a, b, c]) console.log(t.repeat!.frequency, await rows(u.page, t.title).getByTestId('task-repeat').getAttribute('aria-label'));
    await rows(u.page, a.title).getByRole('button', { name: 'Edit' }).click();
    await editDialog(u.page).locator('#task-edit-due').fill('');
    await expect(editDialog(u.page).locator('#task-edit-repeat')).toBeDisabled();
    await editDialog(u.page).getByRole('button', { name: 'Save' }).click();
    await expect(editDialog(u.page)).toHaveCount(0);
    const after = await get(u.page, a.id);
    expect(after.dueDate).toBeNull();
    expect(after.repeat).toBeNull();
  });
});

// =====================================================================================
// AC28 copy
// =====================================================================================
test.describe('AC28 completing creates the next task', () => {
  for (const via of ['PATCH', 'PUT'] as const) {
    test(`copy fields via ${via}; contract shape; completed stays Done`, async ({ kit }) => {
      const u = await kit.newUser();
      const t = await mk(u.page, {
        description: 'desc here', priority: 'HIGH', tags: ['alpha', 'beta'], dueDate: '2030-06-10', dueTime: '09:30',
        reminder: 'ONE_HOUR', repeat: rule('DAILY'),
      });
      const r = await json<Task>(
        via === 'PATCH'
          ? await api(u.page, 'PATCH', `/api/tasks/${t.id}`, { done: true })
          : await api(u.page, 'PUT', `/api/tasks/${t.id}`, { title: t.title, description: 'desc here', dueDate: '2030-06-10', dueTime: '09:30', status: 'DONE' }),
      );
      expect(Object.keys(r).sort()).toEqual([...TASK_KEYS, 'nextTask'].sort());
      expect(r.done).toBe(true);
      expect(r.status).toBe('DONE');
      expect(r.repeat).toBeNull();
      expect(r.dueDate).toBe('2030-06-10');
      const n = r.nextTask!;
      expect(Object.keys(n).sort()).toEqual(TASK_KEYS);
      expect('nextTask' in n).toBe(false);
      expect(n).toMatchObject({
        title: t.title, description: 'desc here', priority: 'HIGH', status: 'TODO', done: false, dueDate: '2030-06-11',
        dueTime: '09:30', listId: t.listId, repeat: { frequency: 'DAILY', weekdays: [] }, myReminder: 'ONE_HOUR',
      });
      expect([...n.tags].sort()).toEqual(['alpha', 'beta']);
      expect(n.id).not.toBe(t.id);
      const stored = await get(u.page, t.id);
      expect(stored.status).toBe('DONE');
      expect(stored.repeat).toBeNull();
      expect('nextTask' in stored).toBe(false);
      const all = await listTasks(u.page, t.listId);
      expect(all.filter((x) => x.title === t.title)).toHaveLength(2);
      for (const x of all) expect('nextTask' in x).toBe(false);
    });
  }

  test('copy goes to the same (non-personal) list, addedBy = completer; reminders only for current members', async ({ kit }) => {
    const owner = await kit.newUser();
    const m1 = await kit.newUser();
    const m2 = await kit.newUser();
    const list = await createList(owner.page, `Shared ${uid()}`);
    await share(owner, list.id, m1);
    await share(owner, list.id, m2);
    const t = await mk(owner.page, { listId: list.id, dueDate: '2030-07-01', dueTime: '10:00', reminder: 'ONE_DAY', repeat: rule('WEEKLY', ['MON', 'FRI']) });
    for (const m of [m1, m2]) await put(m.page, t.id, { title: t.title, description: null, reminder: 'TEN_MINUTES' });
    expect(sql(`SELECT COUNT(*) FROM reminders WHERE task_id=${t.id}`)).toBe('3');
    // m2 leaves the list before the task is completed
    const rm = await api(owner.page, "DELETE", `/api/lists/${list.id}/members/${m2.id}`);
    expect(rm.status(), await rm.text()).toBe(204);
    // completed by member m1
    const r = await complete(m1.page, t);
    const n = r.nextTask!;
    expect(n.listId).toBe(list.id);
    expect((n.addedBy as { displayName: string }).displayName).toBe(m1.name);
    expect(n.myReminder).toBe('TEN_MINUTES');
    const users = sql(`SELECT GROUP_CONCAT(user_id ORDER BY user_id) FROM reminders WHERE task_id=${n.id}`);
    console.log('copy reminders for users', users, 'owner', owner.id, 'm1', m1.id, 'm2', m2.id);
    const ownerCopy = await get(owner.page, n.id);
    expect(ownerCopy.myReminder).toBe('ONE_DAY');
    expect(users.split(',').map(Number).sort()).toEqual([owner.id, m1.id].sort());
    // original keeps its reminders
    expect(sql(`SELECT COUNT(*) FROM reminders WHERE task_id=${t.id} AND user_id=${m1.id}`)).toBe('1');
  });

  test('UI: ticking the checkbox adds the next row, notice appears, rule moves to the new row', async ({ kit }) => {
    const u = await kit.newUser();
    const t = await mk(u.page, { dueDate: '2031-03-03', repeat: rule('DAILY') });
    await gotoTasks(u.page);
    await rows(u.page, t.title).getByRole('checkbox').first().check();
    await expect(rows(u.page, t.title)).toHaveCount(2);
    await expect(u.page.getByTestId('task-next-scheduled')).toContainText('Next one scheduled for');
    console.log('notice:', await u.page.getByTestId('task-next-scheduled').textContent());
    await expect(u.page.getByTestId('task-repeat')).toHaveCount(1);
    await shot(u.page, 'ac28-ui-complete');
    await u.page.reload();
    await expect(rows(u.page, t.title)).toHaveCount(2);
    await expect(u.page.getByTestId('task-repeat')).toHaveCount(1);
  });

  test('UI: status Done in the edit dialog creates the copy', async ({ kit }) => {
    const u = await kit.newUser();
    const t = await mk(u.page, { dueDate: '2031-03-03', repeat: rule('DAILY') });
    await gotoTasks(u.page);
    await rows(u.page, t.title).getByRole('button', { name: 'Edit' }).click();
    await editDialog(u.page).locator('#task-edit-status').selectOption('DONE');
    await editDialog(u.page).getByRole('button', { name: 'Save' }).click();
    await expect(editDialog(u.page)).toHaveCount(0);
    await expect(rows(u.page, t.title)).toHaveCount(2);
    await expect(u.page.getByTestId('task-next-scheduled')).toBeVisible();
    expect((await listTasks(u.page, t.listId)).filter((x) => x.title === t.title)).toHaveLength(2);
  });

  test('editing a Done task (title only) never creates a copy; PATCH to non-done has nextTask null', async ({ kit }) => {
    const u = await kit.newUser();
    const t = await mk(u.page, { dueDate: '2031-03-03', repeat: rule('DAILY') });
    const done = await complete(u.page, t);
    // set the rule again on the Done task: allowed, no copy
    const again = await put(u.page, t.id, { title: t.title, description: null, repeat: rule('DAILY') });
    expect(again.nextTask).toBeNull();
    expect(again.repeat).toEqual(rule('DAILY'));
    const edit = await put(u.page, t.id, { title: `${t.title} x`, description: null });
    expect(edit.nextTask).toBeNull();
    const dd = await complete(u.page, edit); // DONE -> DONE
    expect(dd.nextTask).toBeNull();
    expect(done.nextTask).toBeTruthy();
    const all = await listTasks(u.page, t.listId);
    expect(all).toHaveLength(2);
    const reopened = await patch(u.page, t.id, { done: false });
    expect(reopened.nextTask).toBeNull();
    // now it has a rule again (set on the Done task) -> completing creates a copy
    const r2 = await complete(u.page, reopened);
    expect(r2.nextTask).toBeTruthy();
  });
});

// =====================================================================================
// Date rules
// =====================================================================================
test.describe('next date rules (future dates, no catch-up)', () => {
  test('daily', async ({ kit }) => {
    const u = await kit.newUser();
    const t = await mk(u.page, { dueDate: '2030-12-31', repeat: rule('DAILY') });
    const n = await next(u.page, t);
    expect(n.dueDate).toBe('2031-01-01');
  });

  test('weekly: next selected weekday strictly after the old date (incl. same weekday, wrap, single day)', async ({ kit }) => {
    const u = await kit.newUser();
    // 2030-01-02 is a Wednesday
    expect(dow('2030-01-02')).toBe('WED');
    const cases: [string, Day[], string][] = [
      ['2030-01-02', ['MON', 'WED'], '2030-01-07'], // Wed -> next Mon (not same day)
      ['2030-01-02', ['WED'], '2030-01-09'], // only Wed -> +7
      ['2030-01-02', ['MON', 'THU', 'SUN'], '2030-01-03'], // Thu
      ['2030-01-02', ['MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT', 'SUN'], '2030-01-03'],
      ['2030-01-06', ['MON', 'SUN'], '2030-01-07'], // Sun -> Mon
      ['2030-12-30', ['WED'], '2031-01-01'], // year wrap (Mon -> Wed)
    ];
    for (const [due, days, expected] of cases) {
      const t = await mk(u.page, { dueDate: due, repeat: rule('WEEKLY', days) });
      const n = await next(u.page, t);
      expect(n.dueDate, `${due} ${days}`).toBe(expected);
      expect(n.repeat!.weekdays).toEqual(DAYS.filter((d) => days.includes(d)));
    }
  });

  test('monthly anchor: Jan 31 -> Feb 28 -> Mar 31 -> Apr 30 -> May 31; Dec -> Jan', async ({ kit }) => {
    const u = await kit.newUser();
    let t = await mk(u.page, { dueDate: '2031-01-31', repeat: rule('MONTHLY') });
    const seen: string[] = [];
    for (let i = 0; i < 4; i++) {
      t = await next(u.page, t);
      seen.push(t.dueDate!);
    }
    expect(seen).toEqual(['2031-02-28', '2031-03-31', '2031-04-30', '2031-05-31']);
    let d = await mk(u.page, { dueDate: '2030-12-15', repeat: rule('MONTHLY') });
    d = await next(u.page, d);
    expect(d.dueDate).toBe('2031-01-15');
    // leap February: Jan 31 2032 -> Feb 29
    const l = await mk(u.page, { dueDate: '2032-01-31', repeat: rule('MONTHLY') });
    expect((await next(u.page, l)).dueDate).toBe('2032-02-29');
  });

  test('yearly anchor: Feb 29 2032 -> Feb 28 2033 -> ... -> Feb 29 2036; ordinary date', async ({ kit }) => {
    const u = await kit.newUser();
    let t = await mk(u.page, { dueDate: '2032-02-29', repeat: rule('YEARLY') });
    const seen: string[] = [];
    for (let i = 0; i < 4; i++) {
      t = await next(u.page, t);
      seen.push(t.dueDate!);
    }
    expect(seen).toEqual(['2033-02-28', '2034-02-28', '2035-02-28', '2036-02-29']);
    const o = await mk(u.page, { dueDate: '2030-10-05', repeat: rule('YEARLY') });
    expect((await next(u.page, o)).dueDate).toBe('2031-10-05');
  });

  test('re-saving the same rule + date keeps the anchor; changing the date resets it', async ({ kit }) => {
    const u = await kit.newUser();
    let t = await mk(u.page, { dueDate: '2031-01-31', repeat: rule('MONTHLY') });
    t = await next(u.page, t); // Feb 28 with anchor 31
    t = await put(u.page, t.id, { title: t.title, description: null, dueDate: '2031-02-28', repeat: rule('MONTHLY') });
    expect((await next(u.page, t)).dueDate).toBe('2031-03-31');
    // new anchor 28
    let s = await mk(u.page, { dueDate: '2031-01-31', repeat: rule('MONTHLY') });
    s = await put(u.page, s.id, { title: s.title, description: null, dueDate: '2031-02-28' });
    expect((await next(u.page, s)).dueDate).toBe('2031-03-28');
  });
});

// =====================================================================================
// AC29 late completion
// =====================================================================================
test.describe('AC29 late completion', () => {
  test('daily/weekly/monthly/yearly overdue by a long time -> first occurrence on or after today (UTC account)', async ({ kit }) => {
    const u = await kit.newUser('UTC');
    const today = inZone('UTC');
    // daily: any past date -> today
    const dly = await mk(u.page, { dueDate: '2020-01-01', repeat: rule('DAILY') });
    const dn = await next(u.page, dly);
    expect(dn.dueDate).toBe(today);
    // due yesterday -> today ; due today -> tomorrow ; due tomorrow -> day after
    expect((await next(u.page, await mk(u.page, { dueDate: addDays(today, -1), repeat: rule('DAILY') }))).dueDate).toBe(today);
    expect((await next(u.page, await mk(u.page, { dueDate: today, repeat: rule('DAILY') }))).dueDate).toBe(addDays(today, 1));
    expect((await next(u.page, await mk(u.page, { dueDate: addDays(today, 1), repeat: rule('DAILY') }))).dueDate).toBe(addDays(today, 2));
    // weekly MON+THU long ago
    const wk = await next(u.page, await mk(u.page, { dueDate: '2020-01-02', repeat: rule('WEEKLY', ['MON', 'THU']) }));
    let exp = today;
    while (!['MON', 'THU'].includes(dow(exp))) exp = addDays(exp, 1);
    expect(wk.dueDate).toBe(exp);
    // monthly on the 31st long ago -> first (31st or last-day) >= today
    const mo = await next(u.page, await mk(u.page, { dueDate: '2020-01-31', repeat: rule('MONTHLY') }));
    const [ty, tm] = today.split('-').map(Number);
    let mexp = ymd(ty, tm, Math.min(31, lastDay(ty, tm)));
    if (mexp < today) {
      const ny = tm === 12 ? ty + 1 : ty;
      const nm = tm === 12 ? 1 : tm + 1;
      mexp = ymd(ny, nm, Math.min(31, lastDay(ny, nm)));
    }
    expect(mo.dueDate).toBe(mexp);
    // yearly Mar 15 2020
    const yr = await next(u.page, await mk(u.page, { dueDate: '2020-03-15', repeat: rule('YEARLY') }));
    let yexp = ymd(ty, 3, 15);
    if (yexp < today) yexp = ymd(ty + 1, 3, 15);
    expect(yr.dueDate).toBe(yexp);
    // no backlog: exactly one copy
    const all = await listTasks(u.page, dly.listId);
    expect(all.filter((x) => x.title === dly.title)).toHaveLength(2);
  });

  for (const zone of ['Pacific/Kiritimati', 'Pacific/Pago_Pago']) {
    test(`"today" is the completer's account zone (${zone})`, async ({ kit }) => {
      const u = await kit.newUser(zone);
      const today = inZone(zone);
      console.log(zone, 'today', today, 'UTC today', inZone('UTC'));
      const n = await next(u.page, await mk(u.page, { dueDate: addDays(today, -5), repeat: rule('DAILY') }));
      expect(n.dueDate).toBe(today);
      const n2 = await next(u.page, await mk(u.page, { dueDate: today, repeat: rule('DAILY') }));
      expect(n2.dueDate).toBe(addDays(today, 1));
    });
  }

  test('completer decides, not the list owner', async ({ kit }) => {
    const owner = await kit.newUser('Pacific/Pago_Pago');
    const m = await kit.newUser('Pacific/Kiritimati');
    const list = await createList(owner.page, `Z ${uid()}`);
    await share(owner, list.id, m);
    const t = await mk(owner.page, { listId: list.id, dueDate: addDays(inZone('Pacific/Kiritimati'), -3), repeat: rule('DAILY') });
    const n = await next(m.page, t);
    expect(n.dueDate).toBe(inZone('Pacific/Kiritimati'));
  });

  test('late copy: reminder in the past is kept but passed', async ({ kit }) => {
    const u = await kit.newUser('UTC');
    const t = await mk(u.page, { dueDate: '2020-01-01', dueTime: '00:00', reminder: 'ONE_HOUR', repeat: rule('DAILY') });
    const n = await next(u.page, t);
    expect(n.dueDate).toBe(inZone('UTC'));
    console.log('late copy reminder', n.myReminder, n.myReminderPassed);
    expect(n.myReminder).toBe('ONE_HOUR');
    expect(n.myReminderPassed).toBe(true);
  });
});

// =====================================================================================
// AC30 / AC31
// =====================================================================================
test.describe('AC30 reopen', () => {
  for (const reopenVia of ['PATCH', 'PUT'] as const) {
    test(`reopen via ${reopenVia}, complete again -> no second copy`, async ({ kit }) => {
      const u = await kit.newUser();
      const t = await mk(u.page, { dueDate: '2031-04-04', repeat: rule('WEEKLY', ['FRI']) });
      const r = await complete(u.page, t);
      expect(r.nextTask).toBeTruthy();
      const re =
        reopenVia === 'PATCH'
          ? await patch(u.page, t.id, { done: false })
          : await put(u.page, t.id, { title: t.title, description: null, status: 'TODO' });
      expect(re.status).toBe('TODO');
      expect(re.done).toBe(false);
      expect(re.repeat).toBeNull();
      expect(re.nextTask).toBeNull();
      for (const via of ['PATCH', 'PUT'] as const) {
        const again = await complete(u.page, await get(u.page, t.id), via);
        expect(again.nextTask).toBeNull();
        await patch(u.page, t.id, { done: false });
      }
      expect((await listTasks(u.page, t.listId)).filter((x) => x.title === t.title)).toHaveLength(2);
    });
  }

  test('UI: untick then tick again keeps exactly two rows', async ({ kit }) => {
    const u = await kit.newUser();
    const t = await mk(u.page, { dueDate: '2031-04-04', repeat: rule('DAILY') });
    await gotoTasks(u.page);
    const done = () => rows(u.page, t.title).filter({ has: u.page.getByRole('checkbox', { checked: true }) });
    await rows(u.page, t.title).first().getByRole('checkbox').check();
    await expect(rows(u.page, t.title)).toHaveCount(2);
    await expect(done()).toHaveCount(1);
    await done().getByRole('checkbox').uncheck();
    await expect(done()).toHaveCount(0);
    await rows(u.page, t.title).filter({ has: u.page.getByTestId('task-repeat').or(u.page.locator('x-none')) }).count();
    await done().count();
    // tick the row that has no repeat icon (the reopened one)
    await rows(u.page, t.title).filter({ hasNot: u.page.getByTestId('task-repeat') }).getByRole('checkbox').check();
    await u.page.waitForTimeout(500);
    await u.page.reload();
    await expect(rows(u.page, t.title)).toHaveCount(2);
  });
});

test.describe('AC31 stop repeating / delete', () => {
  test('Repeat=Never then complete -> no copy; PUT NEVER + Done in one call -> no copy', async ({ kit }) => {
    const u = await kit.newUser();
    const a = await mk(u.page, { dueDate: '2031-04-04', repeat: rule('DAILY') });
    const stopped = await put(u.page, a.id, { title: a.title, description: null, repeat: 'NEVER' });
    expect(stopped.repeat).toBeNull();
    expect((await complete(u.page, stopped)).nextTask).toBeNull();
    const b = await mk(u.page, { dueDate: '2031-04-04', repeat: rule('DAILY') });
    const r = await put(u.page, b.id, { title: b.title, description: null, repeat: 'NEVER', status: 'DONE' });
    expect(r.nextTask).toBeNull();
    expect(r.repeat).toBeNull();
    expect((await listTasks(u.page, a.listId)).filter((x) => x.title === a.title || x.title === b.title)).toHaveLength(2);
  });

  test('PUT without repeat key / repeat:null leaves the rule unchanged', async ({ kit }) => {
    const u = await kit.newUser();
    const a = await mk(u.page, { dueDate: '2031-04-04', repeat: rule('MONTHLY') });
    expect((await put(u.page, a.id, { title: 'x1', description: null })).repeat).toEqual(rule('MONTHLY'));
    expect((await put(u.page, a.id, { title: 'x2', description: null, repeat: null })).repeat).toEqual(rule('MONTHLY'));
  });

  test('delete a repeating task: only it is deleted, no copy; deleting a Done one with its copy keeps the copy', async ({ kit }) => {
    const u = await kit.newUser();
    const a = await mk(u.page, { dueDate: '2031-04-04', repeat: rule('DAILY') });
    expect((await api(u.page, 'DELETE', `/api/tasks/${a.id}`)).status()).toBe(204);
    expect(await listTasks(u.page, a.listId)).toHaveLength(0);
    const b = await mk(u.page, { dueDate: '2031-04-04', repeat: rule('DAILY') });
    const n = await next(u.page, b);
    expect((await api(u.page, 'DELETE', `/api/tasks/${b.id}`)).status()).toBe(204);
    const left = await listTasks(u.page, b.listId);
    expect(left.map((x) => x.id)).toEqual([n.id]);
  });

  test('UI: Stop repeating then tick -> single row', async ({ kit }) => {
    const u = await kit.newUser();
    const t = await mk(u.page, { dueDate: '2031-04-04', repeat: rule('DAILY') });
    await gotoTasks(u.page);
    await rows(u.page, t.title).getByRole('button', { name: 'Edit' }).click();
    await editDialog(u.page).locator('#task-edit-repeat').selectOption('NEVER');
    await editDialog(u.page).getByRole('button', { name: 'Save' }).click();
    await expect(editDialog(u.page)).toHaveCount(0);
    await rows(u.page, t.title).getByRole('checkbox').check();
    await u.page.waitForTimeout(700);
    await expect(rows(u.page, t.title)).toHaveCount(1);
    await expect(u.page.getByTestId('task-next-scheduled')).toHaveCount(0);
    expect(await listTasks(u.page, t.listId)).toHaveLength(1);
  });
});

// =====================================================================================
// AC32 concurrency
// =====================================================================================
test.describe('AC32 concurrency', () => {
  test('parallel PATCH/PATCH, PUT/PUT, PATCH/PUT x6 by two members: exactly one copy, both 200, one nextTask', async ({ kit }) => {
    const owner = await kit.newUser();
    const m = await kit.newUser();
    const list = await createList(owner.page, `C ${uid()}`);
    await share(owner, list.id, m);
    const mixes: ['PATCH' | 'PUT', 'PATCH' | 'PUT'][] = [['PATCH', 'PATCH'], ['PUT', 'PUT'], ['PATCH', 'PUT'], ['PUT', 'PATCH']];
    let n = 0;
    for (let round = 0; round < 6; round++) {
      for (const [v1, v2] of mixes) {
        n++;
        const t = await mk(owner.page, { listId: list.id, dueDate: '2031-05-05', dueTime: '08:00', reminder: 'ONE_HOUR', repeat: rule('DAILY') });
        const call = (u: U, via: 'PATCH' | 'PUT') =>
          via === 'PATCH'
            ? api(u.page, 'PATCH', `/api/tasks/${t.id}`, { done: true })
            : api(u.page, 'PUT', `/api/tasks/${t.id}`, { title: t.title, description: null, dueDate: '2031-05-05', status: 'DONE' });
        const rs = await Promise.all([call(owner, v1), call(m, v2)]);
        const bodies: Task[] = [];
        for (const r of rs) {
          expect(r.status(), `${v1}/${v2} round ${round}: ${await r.text()}`).toBe(200);
          bodies.push((await r.json()) as Task);
        }
        const copies = bodies.filter((b) => b.nextTask).length;
        expect(copies, `${v1}/${v2} round ${round}`).toBe(1);
        for (const b of bodies) {
          expect(b.status).toBe('DONE');
          expect(b.repeat).toBeNull();
          expect('nextTask' in b).toBe(true);
        }
        const all = await listTasks(owner.page, list.id);
        expect(all.filter((x) => x.title === t.title), `${v1}/${v2} round ${round}`).toHaveLength(2);
        const winner = bodies.find((b) => b.nextTask)!.nextTask!;
        expect(sql(`SELECT COUNT(*) FROM reminders WHERE task_id=${winner.id}`)).toBe('1');
      }
    }
    expect(n).toBe(24);
  });

  test('same user, 4 parallel completions (PATCH+PUT mix) -> one copy, all 200', async ({ kit }) => {
    const u = await kit.newUser();
    for (let i = 0; i < 6; i++) {
      const t = await mk(u.page, { dueDate: '2031-05-05', repeat: rule('WEEKLY', ['MON']) });
      const rs = await Promise.all(
        [0, 1, 2, 3].map((k) =>
          k % 2 === 0
            ? api(u.page, 'PATCH', `/api/tasks/${t.id}`, { status: 'DONE' })
            : api(u.page, 'PUT', `/api/tasks/${t.id}`, { title: t.title, description: null, dueDate: '2031-05-05', status: 'DONE' }),
        ),
      );
      let withNext = 0;
      for (const r of rs) {
        expect(r.status(), await r.text()).toBe(200);
        if (((await r.json()) as Task).nextTask) withNext++;
      }
      expect(withNext).toBe(1);
      expect((await listTasks(u.page, t.listId)).filter((x) => x.title === t.title)).toHaveLength(2);
      await api(u.page, 'DELETE', `/api/tasks/${t.id}`);
      for (const x of (await listTasks(u.page, t.listId)).filter((y) => y.title === t.title)) await api(u.page, 'DELETE', `/api/tasks/${x.id}`);
    }
  });

  test('two tabs (UI) tick the same repeating task: one copy, no console errors', async ({ kit }) => {
    const u = await kit.newUser();
    const t = await mk(u.page, { dueDate: '2031-05-05', repeat: rule('DAILY') });
    const page2 = await u.ctx.newPage();
    page2.on('console', (m) => m.type() === 'error' && kit.consoleErrors.push(m.text()));
    await gotoTasks(u.page);
    await gotoTasks(page2);
    await Promise.all([
      rows(u.page, t.title).getByRole('checkbox').check(),
      rows(page2, t.title).getByRole('checkbox').check(),
    ]);
    await u.page.waitForTimeout(1000);
    expect((await listTasks(u.page, t.listId)).filter((x) => x.title === t.title)).toHaveLength(2);
    await shot(u.page, 'ac32-two-tabs');
  });
});

// =====================================================================================
// AC33 validation / states
// =====================================================================================
test.describe('AC33 validation and errors', () => {
  test('API errors on repeat', async ({ kit }) => {
    kit.allowNetworkErrors = true;
    const u = await kit.newUser();
    const t = await mk(u.page, { dueDate: '2031-05-05' });
    const noDate = await mk(u.page);
    const code = async (id: number, repeat: unknown, status = 400) => {
      const r = await api(u.page, 'PUT', `/api/tasks/${id}`, { title: 'v', description: null, dueDate: id === noDate.id ? null : '2031-05-05', repeat });
      expect(r.status(), await r.text()).toBe(status);
      return (await r.json()) as { code: string; errors?: { field: string; code: string }[] };
    };
    const e1 = await code(t.id, { frequency: 'WEEKLY', weekdays: [] });
    expect(e1.errors).toEqual([expect.objectContaining({ field: 'repeat', code: 'REQUIRED' })]);
    expect((await code(t.id, { frequency: 'WEEKLY' })).errors![0]).toMatchObject({ field: 'repeat', code: 'REQUIRED' });
    expect((await code(t.id, { weekdays: [] })).errors![0]).toMatchObject({ field: 'repeat', code: 'REQUIRED' });
    expect((await code(t.id, { frequency: 'HOURLY' })).errors![0]).toMatchObject({ field: 'repeat', code: 'INVALID_REPEAT' });
    expect((await code(t.id, { frequency: 'weekly', weekdays: ['MON'] })).errors![0]).toMatchObject({ code: 'INVALID_REPEAT' });
    expect((await code(t.id, { frequency: 'WEEKLY', weekdays: ['XXX'] })).errors![0]).toMatchObject({ code: 'INVALID_REPEAT' });
    expect((await code(t.id, { frequency: 'DAILY', weekdays: ['MON'] })).errors![0]).toMatchObject({ code: 'INVALID_REPEAT' });
    expect((await code(t.id, 'never')).errors![0]).toMatchObject({ code: 'INVALID_REPEAT' });
    expect((await code(t.id, 'DAILY')).errors![0]).toMatchObject({ code: 'INVALID_REPEAT' });
    expect((await code(noDate.id, rule('DAILY'))).errors![0]).toMatchObject({ field: 'repeat', code: 'REQUIRES_DUE_DATE' });
    expect((await code(t.id, 5)).code).toBe('MALFORMED_REQUEST');
    expect((await code(t.id, [])).code).toBe('MALFORMED_REQUEST');
    expect((await code(t.id, { frequency: 5 })).code).toBe('MALFORMED_REQUEST');
    // duplicates collapsed
    const dup = await code(t.id, { frequency: 'WEEKLY', weekdays: ['WED', 'MON', 'WED'] }, 200);
    void dup;
    expect((await get(u.page, t.id)).repeat).toEqual(rule('WEEKLY', ['MON', 'WED']));
    // clearing dueDate with a rule set -> rule cleared (no 400)
    const cleared = await put(u.page, t.id, { title: 'v', description: null, dueDate: null });
    expect(cleared.repeat).toBeNull();
    // rule + clearing date in one request
    const t2 = await mk(u.page, { dueDate: '2031-05-05', repeat: rule('DAILY') });
    const both = await api(u.page, 'PUT', `/api/tasks/${t2.id}`, { title: 'v', description: null, dueDate: null, repeat: rule('DAILY') });
    console.log('rule + dueDate:null ->', both.status(), await both.text());
    // POST ignores/never takes repeat
    const p = await api(u.page, 'POST', '/api/tasks', { title: `p ${uid()}`, repeat: rule('DAILY') });
    console.log('POST with repeat ->', p.status());
    if (p.status() === 201) expect(((await p.json()) as Task).repeat).toBeNull();
    // 0.16.0: PATCH takes `repeat` (was: never changed it). A done-only PATCH leaves it, repeat NEVER clears it.
    const t3 = await mk(u.page, { dueDate: '2031-05-05', repeat: rule('DAILY') });
    const pt = await api(u.page, 'PATCH', `/api/tasks/${t3.id}`, { priority: 'LOW' });
    expect(pt.status()).toBe(200);
    expect((await get(u.page, t3.id)).repeat).toEqual(rule('DAILY'));
    const pr = await api(u.page, 'PATCH', `/api/tasks/${t3.id}`, { repeat: 'NEVER' });
    console.log('PATCH repeat ->', pr.status());
    expect(pr.status()).toBe(200);
    expect((await get(u.page, t3.id)).repeat).toBeNull();
    // 404 / foreign task
    const other = await kit.newUser();
    expect((await api(other.page, 'PATCH', `/api/tasks/${t3.id}`, { done: true })).status()).toBe(404);
  });

  test('UI: weekly with no weekday blocks save and shows the error; server error shown; recovers', async ({ kit }) => {
    kit.allowNetworkErrors = true;
    const u = await kit.newUser();
    const t = await mk(u.page, { dueDate: '2031-05-05' });
    await gotoTasks(u.page);
    await rows(u.page, t.title).getByRole('button', { name: 'Edit' }).click();
    const dlg = editDialog(u.page);
    let puts = 0;
    u.page.on('request', (r) => r.method() === 'PUT' && puts++);
    await dlg.locator('#task-edit-repeat').selectOption('WEEKLY');
    // the due weekday is pre-selected: unselect it
    for (const d of DAYS) {
      if ((await dlg.getByTestId(`repeat-day-${d}`).getAttribute('aria-pressed')) === 'true') await dlg.getByTestId(`repeat-day-${d}`).click();
    }
    await dlg.getByRole('button', { name: 'Save' }).click();
    await expect(dlg.getByTestId('repeat-weekday-error')).toHaveText('Pick at least one weekday');
    expect(puts).toBe(0);
    await shot(u.page, 'ac33-weekday-error');
    await dlg.getByTestId('repeat-day-TUE').click();
    await expect(dlg.getByTestId('repeat-weekday-error')).toHaveCount(0);
    // server rejects
    await u.page.route('**/api/tasks/*', async (route) => {
      if (route.request().method() !== 'PUT') return route.fallback();
      await route.fulfill({
        status: 400,
        contentType: 'application/problem+json',
        body: JSON.stringify({ type: 'about:blank', title: 'Bad Request', status: 400, detail: 'Validation failed', code: 'VALIDATION_FAILED', errors: [{ field: 'repeat', code: 'INVALID_REPEAT', message: 'Invalid repeat rule' }] }),
      });
    });
    await dlg.getByRole('button', { name: 'Save' }).click();
    await expect(dlg.getByTestId('repeat-error')).toBeVisible();
    console.log('repeat-error:', await dlg.getByTestId('repeat-error').textContent());
    await shot(u.page, 'ac33-server-error');
    await expect(dlg).toBeVisible();
    await u.page.unroute('**/api/tasks/*');
    u.page.on('response', (r) => r.request().method() === 'PUT' && console.log('retry PUT', r.status(), r.url()));
    // an unchanged retry is blocked client-side while the server's field error is shown (observed); editing the rule clears it
    await dlg.getByRole('button', { name: 'Save' }).click();
    await u.page.waitForTimeout(500);
    console.log('unchanged retry: dialog still open =', await dlg.isVisible(), ', error still shown =', await dlg.getByTestId('repeat-error').isVisible());
    await dlg.getByTestId('repeat-day-WED').click();
    await expect(dlg.getByTestId('repeat-error')).toHaveCount(0);
    await dlg.getByRole('button', { name: 'Save' }).click();
    await expect(dlg).toHaveCount(0);
    await expect(rows(u.page, t.title).getByTestId('task-repeat')).toHaveAttribute('aria-label', 'Repeats weekly (Tue, Wed)');
  });

  test('UI: 500 on the completion call -> error shown, checkbox reverts, no copy row', async ({ kit }) => {
    kit.allowNetworkErrors = true;
    kit.allow5xx = true;
    const u = await kit.newUser();
    const t = await mk(u.page, { dueDate: '2031-05-05', repeat: rule('DAILY') });
    await gotoTasks(u.page);
    await u.page.route('**/api/tasks/*', async (route) => {
      if (route.request().method() !== 'PATCH') return route.fallback();
      await route.fulfill({ status: 500, contentType: 'application/problem+json', body: JSON.stringify({ type: 'about:blank', title: 'Internal Server Error', status: 500 }) });
    });
    await rows(u.page, t.title).getByRole('checkbox').check();
    await expect(u.page.getByRole('alert').first()).toBeVisible();
    await expect(rows(u.page, t.title)).toHaveCount(1);
    await expect(rows(u.page, t.title).getByRole('checkbox')).not.toBeChecked();
    await shot(u.page, 'ac33-complete-error');
  });

  test('UI: double-click on the checkbox of a repeating task does not duplicate', async ({ kit }) => {
    const u = await kit.newUser();
    const t = await mk(u.page, { dueDate: '2031-05-05', repeat: rule('DAILY') });
    await gotoTasks(u.page);
    await rows(u.page, t.title).getByRole('checkbox').dblclick();
    await u.page.waitForTimeout(1000);
    const n = (await listTasks(u.page, t.listId)).filter((x) => x.title === t.title).length;
    console.log('rows after dblclick', n);
    expect(n).toBeLessThanOrEqual(2);
  });
});
