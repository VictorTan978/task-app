import { APIResponse, Browser, BrowserContext, expect, Locator, Page, test as base } from '@playwright/test';
import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';

/*
 * E2E for specs/due-times-reminders.md Part A (T4a: AC1-AC26), contract 0.14.0.
 *
 * Needs `docker compose up -d db mail oidc` (SQL through `docker compose exec db mysql`, Mailpit :8025, fake Google :8090)
 * and a backend with REMINDERS_POLL_INTERVAL=2s and raised rate limits (see memory/project-e2e-run-setup).
 * Reminders are never waited for in real time except in two tests (AC10 end to end, AC5 badge flip): everywhere else
 * the test saves a reminder with a future fire time, then moves `reminders.fire_at` / `next_attempt_at` into the past
 * with SQL (UTC) and lets the real scheduler pick it up.
 *
 * Special modes (off by default, counted as skips):
 *   E2E_SECOND=1  a second backend on :8082 (same env, same DB) is running: AC20 two-instance test.
 *   E2E_CAP=1     backend started with the DEFAULT reminder-email cap (20/h): AC14 cap + AC18 retention (needs the
 *                 backend started < 1 min ago for the purge job, or any time: the test waits up to 100 s).
 *   E2E_MAIL_DOWN=1  stops the Mailpit container during the test and starts it again (AC22). Run alone.
 */

const RUN = `${Date.now()}-${Math.floor(Math.random() * 1e6)}`;
const BASE = process.env['E2E_BASE_URL'] ?? 'http://localhost:4200';
const MAILPIT = process.env['E2E_MAILPIT'] ?? 'http://localhost:8025';
const OIDC = 'http://localhost:8090/default';
const SHOTS = '../test-results/t4a';
const PASSWORD = 'password123';
const SECOND = process.env['E2E_SECOND'] === '1';
const CAP = process.env['E2E_CAP'] === '1';
const MAIL_DOWN = process.env['E2E_MAIL_DOWN'] === '1';
const SPECIAL = SECOND || CAP || MAIL_DOWN;
let counter = 0;
function uid(): string {
  counter += 1;
  return `${RUN}-${counter}`;
}

const NETWORK_LOG = [
  /^Failed to load resource: net::ERR_FAILED/,
  /^Failed to load resource: the server responded with a status of (400|401|404|500|503)/,
];

// ---------- types ----------
type Reminder = 'AT_DUE' | 'TEN_MINUTES' | 'ONE_HOUR' | 'ONE_DAY' | 'ON_THE_DAY';
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
  myReminder: Reminder | null;
  myReminderPassed: boolean;
  listId: number;
  createdAt: string;
  updatedAt: string;
  addedBy: unknown;
}
interface TaskPage {
  items: Task[];
  total: number;
  nextCursor: string | null;
}
interface Notif {
  id: number;
  type: string;
  read: boolean;
  createdAt: string;
  late: boolean;
  title: string;
  dueDate: string;
  dueTime: string | null;
  taskId: number | null;
  listId: number | null;
}
interface NotifPage {
  items: Notif[];
  nextCursor: string | null;
}
interface TaskList {
  id: number;
  name: string;
  personal: boolean;
  role: string;
  memberCount: number;
}
const TASK_KEYS = [
  'addedBy', 'createdAt', 'description', 'done', 'dueDate', 'dueTime', 'id', 'listId', 'myReminder',
  'myReminderPassed', 'priority', 'status', 'tags', 'title', 'updatedAt',
];
const USER_KEYS = ['connections', 'displayName', 'email', 'emailVerified', 'hasPassword', 'id', 'reminderEmails', 'role', 'timeZone'];
const NOTIF_KEYS = ['createdAt', 'dueDate', 'dueTime', 'id', 'late', 'listId', 'read', 'taskId', 'title', 'type'];

// ---------- DB / mail helpers ----------
function sql(query: string): string {
  return execFileSync(
    'docker',
    ['compose', 'exec', '-T', 'db', 'mysql', '-utaskapp', '-ptaskapp', 'taskapp', '-N', '-B', '-e', query],
    { cwd: '..', encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] },
  ).trim();
}
function sqlNum(query: string): number {
  return Number(sql(query));
}
/** fire_at of (task, user) as `YYYY-MM-DDTHH:mm:ssZ` (UTC), or '' when there is no reminder. */
function fireAt(taskId: number, userId: number): string {
  return sql(
    `SELECT DATE_FORMAT(fire_at, '%Y-%m-%dT%H:%i:%sZ') FROM reminders WHERE task_id = ${taskId} AND user_id = ${userId}`,
  );
}
function remStatus(taskId: number, userId: number): string {
  return sql(`SELECT status FROM reminders WHERE task_id = ${taskId} AND user_id = ${userId}`);
}
/** Moves the reminder(s) of the given tasks `minutes` into the past (UTC); the real scheduler then fires them. */
function pokeDue(taskIds: number[], minutes: number, userId?: number) {
  if (taskIds.length === 0) return;
  sql(
    `UPDATE reminders SET fire_at = UTC_TIMESTAMP(6) - INTERVAL ${minutes} MINUTE, next_attempt_at = UTC_TIMESTAMP(6) - INTERVAL ${minutes} MINUTE` +
      ` WHERE task_id IN (${taskIds.join(',')})${userId ? ` AND user_id = ${userId}` : ''}`,
  );
}
function verify(userId: number) {
  sql(`UPDATE users SET email_verified_at = NOW(6) WHERE id = ${userId}`);
}
function notifCount(userId: number): number {
  return sqlNum(`SELECT COUNT(*) FROM notifications WHERE user_id = ${userId}`);
}

interface Mail {
  ID: string;
  Subject: string;
  To: { Address: string }[];
}
async function mailsTo(address: string): Promise<Mail[]> {
  const r = await fetch(`${MAILPIT}/api/v1/search?query=${encodeURIComponent(`to:"${address}"`)}`);
  const b = (await r.json()) as { messages: Mail[] };
  return (b.messages ?? []).filter((m) => m.Subject.startsWith('Reminder:'));
}
async function mailBody(id: string): Promise<{ HTML: string; Text: string; Subject: string }> {
  return (await (await fetch(`${MAILPIT}/api/v1/message/${id}`)).json()) as { HTML: string; Text: string; Subject: string };
}

/** `2030-10-03` -> `Thu, 3 Oct 2030` (how the email writes the date). */
function mailDate(date: string): string {
  const d = new Date(`${date}T00:00:00Z`);
  const wd = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'][d.getUTCDay()];
  const mon = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'][d.getUTCMonth()];
  return `${wd}, ${d.getUTCDate()} ${mon} ${d.getUTCFullYear()}`;
}

/** Date and time of "now + plusMin" as the wall clock of `zone`. */
function inZone(zone: string, plusMin = 0): { date: string; time: string } {
  const d = new Date(Date.now() + plusMin * 60_000);
  const parts = Object.fromEntries(
    new Intl.DateTimeFormat('en-CA', {
      timeZone: zone,
      hourCycle: 'h23',
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
    })
      .formatToParts(d)
      .map((p) => [p.type, p.value]),
  );
  return { date: `${parts['year']}-${parts['month']}-${parts['day']}`, time: `${parts['hour']}:${parts['minute']}` };
}
function addDays(date: string, n: number): string {
  const d = new Date(`${date}T00:00:00Z`);
  d.setUTCDate(d.getUTCDate() + n);
  return d.toISOString().slice(0, 10);
}

// ---------- fixture: users, contexts and console watching ----------
interface U {
  ctx: BrowserContext;
  page: Page;
  id: number;
  email: string;
  name: string;
  zone: string | null;
}
interface NewUserOpts {
  tag?: string;
  /** browser (context) time zone */
  browserTz?: string;
  /** stored account zone; null = leave unset (the frontend effect will then set it on first load) */
  zone?: string | null;
  verified?: boolean;
}

class Kit {
  contexts: BrowserContext[] = [];
  consoleErrors: string[] = [];
  pageErrors: string[] = [];
  serverErrors: string[] = [];
  allowNetworkErrors = false;
  allow5xx = false;
  constructor(readonly browser: Browser) {}

  watch(page: Page) {
    page.on('console', (m) => m.type() === 'error' && this.consoleErrors.push(m.text()));
    page.on('pageerror', (e) => this.pageErrors.push(e.message));
    page.on('response', (r) => {
      if (r.url().includes('/api/') && r.status() >= 500) {
        this.serverErrors.push(`${r.status()} ${r.request().method()} ${r.url()}`);
      }
    });
  }

  async ctx(browserTz = 'UTC'): Promise<{ ctx: BrowserContext; page: Page }> {
    const ctx = await this.browser.newContext({ baseURL: BASE, timezoneId: browserTz });
    this.contexts.push(ctx);
    const page = await ctx.newPage();
    this.watch(page);
    return { ctx, page };
  }

  async newUser(o: NewUserOpts = {}): Promise<U> {
    const { ctx, page } = await this.ctx(o.browserTz ?? 'UTC');
    const email = `qa-t4a-${uid()}-${o.tag ?? 'u'}@example.com`;
    const name = `QA T4a ${uid()}`;
    const res = await register(page, name, email);
    const u: U = { ctx, page, id: res.id, email, name, zone: null };
    if (o.zone !== null) {
      const zone = o.zone ?? 'Asia/Tokyo';
      const r = await api(page, 'PUT', '/api/account/time-zone', { timeZone: zone });
      expect(r.status(), await r.text()).toBe(200);
      u.zone = zone;
    }
    if (o.verified !== false) verify(u.id);
    return u;
  }

  async finish() {
    await Promise.all(this.contexts.map((c) => c.close().catch(() => undefined)));
    const errors = this.allowNetworkErrors
      ? this.consoleErrors.filter((e) => !NETWORK_LOG.some((r) => r.test(e)))
      : this.consoleErrors;
    expect(errors, 'browser console errors').toEqual([]);
    expect(this.pageErrors, 'uncaught page errors').toEqual([]);
    if (!this.allow5xx) expect(this.serverErrors, '5xx responses seen by the browser').toEqual([]);
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
async function register(page: Page, displayName: string, email: string): Promise<{ id: number }> {
  const me = await page.request.get('/api/auth/me');
  expect(me.status()).toBe(401);
  const res = await page.request.post('/api/auth/register', {
    data: { displayName, email, password: PASSWORD },
    headers: { 'X-XSRF-TOKEN': await xsrf(page) },
  });
  expect(res.status(), await res.text()).toBe(201);
  return { id: ((await res.json()) as { id: number }).id };
}
async function me(page: Page): Promise<Record<string, unknown>> {
  return json(await page.request.get('/api/auth/me'));
}
async function loginViaUi(page: Page, email: string) {
  await page.goto('/login');
  await page.locator('#login-email').fill(email);
  await page.locator('#login-password').fill(PASSWORD);
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page).toHaveURL(/\/tasks/);
}

interface MkTask {
  title?: string;
  description?: string | null;
  dueDate?: string | null;
  dueTime?: string | null;
  reminder?: Reminder | 'NONE';
  listId?: number;
  status?: string;
}
/** Creates a task (POST) then sets the T4a fields (PUT). Returns the final task. */
async function mkTask(u: U | Page, o: MkTask = {}): Promise<Task> {
  const page = 'page' in u ? u.page : u;
  const title = o.title ?? `T ${uid()}`;
  const body: Record<string, unknown> = { title };
  if (o.listId) body['listId'] = o.listId;
  let t = await json<Task>(await api(page, 'POST', '/api/tasks', body), 201);
  if (o.dueDate !== undefined || o.dueTime !== undefined || o.reminder !== undefined || o.description || o.status) {
    t = await putTask(page, t.id, { title, description: o.description ?? null, ...o });
  }
  return t;
}
async function putTask(page: Page, id: number, body: Record<string, unknown>, status = 200): Promise<Task> {
  return json<Task>(await api(page, 'PUT', `/api/tasks/${id}`, body), status);
}
async function getTask(page: Page, id: number): Promise<Task | null> {
  const r = await page.request.get(`/api/tasks/${id}`);
  return r.status() === 200 ? ((await r.json()) as Task) : null;
}
async function notifs(page: Page, query = ''): Promise<NotifPage> {
  return json<NotifPage>(await page.request.get(`/api/notifications${query}`));
}
async function unread(page: Page): Promise<number> {
  return (await json<{ count: number }>(await page.request.get('/api/notifications/count'))).count;
}
async function createList(page: Page, name: string): Promise<TaskList> {
  return json<TaskList>(await api(page, 'POST', '/api/lists', { name }), 201);
}
async function personalList(page: Page): Promise<TaskList> {
  const ls = await json<TaskList[]>(await page.request.get('/api/lists'));
  return ls.find((l) => l.personal)!;
}
async function share(owner: U, listId: number, member: U) {
  await json(await api(owner.page, 'POST', `/api/lists/${listId}/invitations`, { email: member.email }), 201);
  const invs = await json<{ id: number; list: { id: number } }[]>(await member.page.request.get('/api/invitations'));
  const inv = invs.find((i) => i.list.id === listId)!;
  await json(await api(member.page, 'POST', `/api/invitations/${inv.id}/accept`));
}
async function waitFor(fn: () => Promise<boolean> | boolean, what: string, timeout = 25_000) {
  await expect.poll(async () => fn(), { message: what, timeout, intervals: [500, 750, 1000] }).toBe(true);
}
async function shot(page: Page, name: string) {
  await page.screenshot({ path: `${SHOTS}/${name}.png`, fullPage: true });
}
function editDialog(page: Page) {
  return page.getByRole('dialog', { name: 'Edit task' });
}
function rowOf(page: Page, title: string): Locator {
  return page
    .getByRole('list', { name: 'Tasks' })
    .getByRole('listitem')
    .filter({ has: page.getByRole('checkbox', { name: `Done: ${title}`, exact: true }) });
}
async function openEdit(page: Page, title: string) {
  await rowOf(page, title).getByRole('button', { name: 'Edit' }).click();
  await expect(editDialog(page)).toBeVisible();
}
async function gotoTasks(page: Page, query = '') {
  await page.goto(`/tasks${query}`);
  await expect(page.getByRole('heading', { name: 'My tasks' })).toBeVisible();
  await expect(page.getByText('Loading tasks…')).toHaveCount(0);
}
const PROBLEM = (code: string, field: string, fieldCode: string, message: string, status = 400) => ({
  status,
  contentType: 'application/problem+json',
  body: JSON.stringify({
    type: 'about:blank',
    title: 'Bad Request',
    status,
    detail: 'Validation failed',
    code,
    errors: [{ field, code: fieldCode, message }],
  }),
});

// =====================================================================================
// Time zone (AC1, AC2)
// =====================================================================================

test.describe('time zone', () => {
  for (const tz of ['Europe/Bucharest', 'UTC', 'Etc/UTC']) {
    test(`AC1: password sign-in under browser zone ${tz} saves the zone (non-null in /api/auth/me)`, async ({ kit }) => {
      kit.allowNetworkErrors = true; // the app's own signed-out /api/auth/me probe logs a 401
      // account created over the API in a separate context, so the frontend effect has not run for it yet
      const seed = await kit.ctx('UTC');
      const email = `qa-t4a-${uid()}-ac1@example.com`;
      await register(seed.page, `QA T4a ${uid()}`, email);
      expect((await me(seed.page))['timeZone']).toBeNull();
      await api(seed.page, 'POST', '/api/auth/logout');

      const { page } = await kit.ctx(tz);
      await loginViaUi(page, email);
      await waitFor(async () => (await me(page))['timeZone'] !== null, 'timeZone saved after sign-in');
      const stored = (await me(page))['timeZone'] as string;
      console.log(`AC1 browser zone ${tz} -> stored ${stored}`);
      expect(stored).toBeTruthy();
      expect(['Etc/UTC', 'UTC']).toContain(tz === 'Europe/Bucharest' ? stored.replace('Europe/Bucharest', 'UTC') : stored);
      if (tz === 'Europe/Bucharest') expect(stored).toBe('Europe/Bucharest');
    });
  }

  test('AC1: stored zone is never overwritten by a later sign-in; Account shows the hint for the new browser zone', async ({ kit }) => {
    kit.allowNetworkErrors = true;
    const a = await kit.ctx('Europe/Bucharest');
    const email = `qa-t4a-${uid()}-ac1b@example.com`;
    await register(a.page, `QA T4a ${uid()}`, email);
    await api(a.page, 'POST', '/api/auth/logout');
    await loginViaUi(a.page, email);
    await waitFor(async () => (await me(a.page))['timeZone'] === 'Europe/Bucharest', 'first sign-in saves Bucharest');
    await api(a.page, 'POST', '/api/auth/logout');

    const b = await kit.ctx('Asia/Tokyo');
    await loginViaUi(b.page, email);
    await b.page.waitForTimeout(1500);
    expect((await me(b.page))['timeZone']).toBe('Europe/Bucharest');
    await b.page.goto('/account');
    await expect(b.page.locator('#account-time-zone')).toHaveValue('Europe/Bucharest');
    await expect(b.page.getByTestId('time-zone-hint')).toContainText('Asia/Tokyo');
    await shot(b.page, 'ac1-hint');
    await b.page.getByTestId('use-browser-zone').click();
    await waitFor(async () => (await me(b.page))['timeZone'] === 'Asia/Tokyo', 'Use browser zone saves it');
    await expect(b.page.getByTestId('time-zone-hint')).toHaveCount(0);
    await expect(b.page.locator('#account-time-zone')).toHaveValue('Asia/Tokyo');
  });

  test('AC1: existing user with no zone (pre-V12) gets the browser zone on sign-in', async ({ kit }) => {
    kit.allowNetworkErrors = true;
    const u = await kit.newUser({ zone: null, tag: 'old' });
    sql(`UPDATE users SET time_zone = NULL WHERE id = ${u.id}`);
    const { page } = await kit.ctx('America/New_York');
    await loginViaUi(page, u.email);
    await waitFor(async () => (await me(page))['timeZone'] === 'America/New_York', 'zone set for old user');
  });

  test('AC1: Google sign-in saves the browser zone, and a later one does not overwrite it', async ({ kit }) => {
    kit.allowNetworkErrors = true;
    const email = `qa-t4a-${uid()}-g@example.com`;
    const sub = `g-t4a-${uid()}`;
    async function google(tz: string) {
      const { page } = await kit.ctx(tz);
      await page.goto('/login');
      await page.getByTestId('google-button').click();
      await page.waitForURL((x) => x.href.startsWith(`${OIDC}/authorize`));
      await page.locator('input[name="username"]').fill(sub);
      await page.locator('textarea[name="claims"]').fill(JSON.stringify({ email, email_verified: true, name: `G T4a ${uid()}` }));
      await page.locator('input[type="submit"]').click();
      await page.waitForURL((x) => x.origin === new URL(BASE).origin && x.pathname.startsWith('/tasks'));
      return page;
    }
    const p1 = await google('America/New_York');
    await waitFor(async () => (await me(p1))['timeZone'] === 'America/New_York', 'google sign-in saves the zone');
    const p2 = await google('Asia/Tokyo');
    await p2.waitForTimeout(1500);
    expect((await me(p2))['timeZone']).toBe('America/New_York');
  });

  test('AC2: Account shows the stored zone, saves another, refuses unknown ones with a field error; API rules', async ({ kit }) => {
    const u = await kit.newUser({ zone: 'Europe/Bucharest', browserTz: 'Europe/Bucharest' });
    kit.allowNetworkErrors = true; // 400s are logged by Chrome
    await u.page.goto('/account');
    const field = u.page.locator('#account-time-zone');
    await expect(field).toHaveValue('Europe/Bucharest');
    await expect(u.page.getByTestId('time-zone-hint')).toHaveCount(0); // browser = stored
    await field.fill('Mars/Olympus');
    await u.page.locator('app-time-zone-form').getByRole('button', { name: 'Save' }).click();
    await expect(u.page.locator('#account-time-zone-error')).toBeVisible();
    await shot(u.page, 'ac2-invalid-zone');
    expect((await me(u.page))['timeZone']).toBe('Europe/Bucharest');
    await field.fill('America/Los_Angeles');
    await u.page.locator('app-time-zone-form').getByRole('button', { name: 'Save' }).click();
    await waitFor(async () => (await me(u.page))['timeZone'] === 'America/Los_Angeles', 'zone saved');
    await expect(u.page.locator('#account-time-zone-error')).toHaveCount(0);
    await expect(u.page.getByTestId('time-zone-hint')).toContainText('Europe/Bucharest');

    // API: shape + refusals
    const ok = await json<Record<string, unknown>>(await api(u.page, 'PUT', '/api/account/time-zone', { timeZone: 'America/Los_Angeles' }));
    expect(Object.keys(ok).sort()).toEqual(USER_KEYS);
    const keep = await json<Record<string, unknown>>(await api(u.page, 'PUT', '/api/account/time-zone', { timeZone: 'Asia/Tokyo', ifUnset: true }));
    expect(keep['timeZone']).toBe('America/Los_Angeles'); // ifUnset keeps
    for (const bad of ['+02:00', 'UTC+2', 'EST', 'Mars/Olympus', 'europe/bucharest', 'x'.repeat(65)]) {
      const r = await api(u.page, 'PUT', '/api/account/time-zone', { timeZone: bad });
      expect(r.status(), bad).toBe(400);
      expect(r.headers()['content-type']).toContain('application/problem+json');
      const b = (await r.json()) as { code: string; errors: { field: string; code: string }[] };
      expect(b.code).toBe('VALIDATION_FAILED');
      expect(b.errors).toEqual([expect.objectContaining({ field: 'timeZone', code: 'INVALID_TIME_ZONE' })]);
    }
    for (const missing of [{}, { timeZone: '' }, { timeZone: null }]) {
      const r = await api(u.page, 'PUT', '/api/account/time-zone', missing);
      expect(r.status()).toBe(400);
      expect(((await r.json()) as { errors: { code: string }[] }).errors[0].code).toBe('REQUIRED');
    }
    expect((await api(u.page, 'PUT', '/api/account/time-zone', { timeZone: 'UTC', ifUnset: 'yes' })).status()).toBe(400);
    // unauthenticated
    const anon = await kit.ctx();
    expect((await anon.page.request.put('/api/account/time-zone', { data: { timeZone: 'UTC' } })).status()).toBeGreaterThanOrEqual(401);
  });
});

// =====================================================================================
// Due time (AC3-AC7)
// =====================================================================================

test.describe('due time', () => {
  test('AC3 AC4 AC8: dialog -- time disabled without a date, clearing the date clears time; picker options; row text; quick-add unchanged', async ({ kit }) => {
    const u = await kit.newUser({ zone: 'Asia/Tokyo', browserTz: 'Asia/Tokyo' });
    const posts: unknown[] = [];
    u.page.on('request', (r) => r.method() === 'POST' && new URL(r.url()).pathname === '/api/tasks' && posts.push(r.postDataJSON()));
    await gotoTasks(u.page);
    // quick-add: unchanged (title only, no dueTime / reminder)
    await u.page.locator('#task-add-title').fill('Quick one');
    await u.page.getByRole('button', { name: 'Add', exact: true }).click();
    await expect(rowOf(u.page, 'Quick one')).toBeVisible();
    expect(Object.keys(posts[0] as object)).toEqual(['title']);
    await expect(rowOf(u.page, 'Quick one').locator('.task-row__due')).toHaveCount(0);

    await openEdit(u.page, 'Quick one');
    const dlg = editDialog(u.page);
    await expect(dlg.locator('#task-edit-due-time')).toBeDisabled();
    await expect(dlg.locator('#task-edit-reminder')).toBeDisabled();
    // date-only picker
    await dlg.locator('#task-edit-due').fill('2030-03-04');
    await expect(dlg.locator('#task-edit-due-time')).toBeEnabled();
    await expect(dlg.locator('#task-edit-reminder')).toBeEnabled();
    expect(await dlg.locator('#task-edit-reminder option').allTextContents()).toEqual([
      'None', 'On the day at 09:00', '1 day before at 09:00',
    ]);
    // timed picker
    await dlg.locator('#task-edit-due-time').fill('09:30');
    expect(await dlg.locator('#task-edit-reminder option').allTextContents()).toEqual([
      'None', 'At due time', '10 minutes before', '1 hour before', '1 day before',
    ]);
    expect(await dlg.locator('#task-edit-reminder option').evaluateAll((o) => o.map((x) => (x as HTMLOptionElement).value))).toEqual([
      'NONE', 'AT_DUE', 'TEN_MINUTES', 'ONE_HOUR', 'ONE_DAY',
    ]);
    await dlg.locator('#task-edit-reminder').selectOption('ONE_HOUR');
    await shot(u.page, 'ac3-dialog-timed');
    await dlg.getByRole('button', { name: 'Save' }).click();
    await expect(editDialog(u.page)).toBeHidden();
    await expect(rowOf(u.page, 'Quick one').locator('.task-row__due')).toHaveText('Due 2030-03-04 09:30');
    await expect(rowOf(u.page, 'Quick one').getByTestId('task-reminder')).toHaveAttribute('aria-label', 'Reminder set');
    const t = (await json<TaskPage>(await u.page.request.get('/api/tasks'))).items[0];
    expect(t).toMatchObject({ dueDate: '2030-03-04', dueTime: '09:30', myReminder: 'ONE_HOUR', myReminderPassed: false });

    // reload: values persist in the dialog
    await u.page.reload();
    await openEdit(u.page, 'Quick one');
    await expect(editDialog(u.page).locator('#task-edit-due-time')).toHaveValue('09:30');
    await expect(editDialog(u.page).locator('#task-edit-reminder')).toHaveValue('ONE_HOUR');
    // changing time to empty -> date-only: the 1h offset no longer fits (cleared)
    await editDialog(u.page).locator('#task-edit-due-time').fill('');
    await editDialog(u.page).getByRole('button', { name: 'Save' }).click();
    await expect(editDialog(u.page)).toBeHidden();
    await expect(rowOf(u.page, 'Quick one').locator('.task-row__due')).toHaveText('Due 2030-03-04');
    await expect(rowOf(u.page, 'Quick one').getByTestId('task-reminder')).toHaveCount(0);
    // date-only reminder, then clear the date: time + reminder dropped
    await openEdit(u.page, 'Quick one');
    await editDialog(u.page).locator('#task-edit-due-time').fill('18:00');
    await editDialog(u.page).locator('#task-edit-reminder').selectOption('AT_DUE');
    await editDialog(u.page).getByRole('button', { name: 'Clear date' }).click();
    await expect(editDialog(u.page).locator('#task-edit-due-time')).toBeDisabled();
    await expect(editDialog(u.page).locator('#task-edit-due-time')).toHaveValue('');
    await expect(editDialog(u.page).locator('#task-edit-reminder')).toBeDisabled();
    await editDialog(u.page).getByRole('button', { name: 'Save' }).click();
    await expect(editDialog(u.page)).toBeHidden();
    const t2 = await getTask(u.page, t.id);
    expect(t2).toMatchObject({ dueDate: null, dueTime: null, myReminder: null });
    expect(sqlNum(`SELECT COUNT(*) FROM reminders WHERE task_id = ${t.id}`)).toBe(0);
  });

  test('AC4: a date-only task looks exactly as before (Due <date>, no time, no bell)', async ({ kit }) => {
    const u = await kit.newUser({ browserTz: 'Asia/Tokyo' });
    const t = await mkTask(u, { title: 'Dateonly', dueDate: '2031-01-02' });
    expect(t).toMatchObject({ dueDate: '2031-01-02', dueTime: null, myReminder: null, myReminderPassed: false });
    expect(Object.keys(t).sort()).toEqual(TASK_KEYS);
    await gotoTasks(u.page);
    await expect(rowOf(u.page, 'Dateonly').locator('.task-row__due')).toHaveText('Due 2031-01-02');
    await expect(rowOf(u.page, 'Dateonly').getByTestId('task-reminder')).toHaveCount(0);
    await shot(u.page, 'ac4-rows');
  });

  test('AC5: overdue is evaluated in the ACCOUNT zone, not the browser zone (date-only)', async ({ kit }) => {
    // account zone UTC-11, browser UTC+14: browser date is 1-2 days ahead of the account date
    const u = await kit.newUser({ zone: 'Pacific/Pago_Pago', browserTz: 'Pacific/Kiritimati' });
    const today = inZone('Pacific/Pago_Pago').date;
    expect(inZone('Pacific/Kiritimati').date > today).toBe(true);
    await mkTask(u, { title: 'DueTodayAcct', dueDate: today });
    await mkTask(u, { title: 'DueYesterdayAcct', dueDate: addDays(today, -1) });
    await mkTask(u, { title: 'DueTomorrowAcct', dueDate: addDays(today, 1) });
    await gotoTasks(u.page);
    await expect(rowOf(u.page, 'DueYesterdayAcct').locator('.task-row__overdue')).toBeVisible();
    await expect(rowOf(u.page, 'DueTodayAcct').locator('.task-row__overdue')).toHaveCount(0);
    await expect(rowOf(u.page, 'DueTomorrowAcct').locator('.task-row__overdue')).toHaveCount(0);
    await shot(u.page, 'ac5-account-zone');
  });

  test('AC5: a timed task is overdue only once date+time has passed in the account zone; badge flips without reload', async ({ kit }) => {
    test.setTimeout(260_000);
    const zone = 'Asia/Tokyo';
    const u = await kit.newUser({ zone, browserTz: zone });
    const now = inZone(zone);
    const soon = inZone(zone, 1);
    const earlier = inZone(zone, -30);
    await mkTask(u, { title: 'TimedPast', dueDate: earlier.date, dueTime: earlier.time });
    await mkTask(u, { title: 'TimedLater', dueDate: soon.date, dueTime: soon.time });
    await mkTask(u, { title: 'TimedFuture', dueDate: addDays(now.date, 1), dueTime: '00:01' });
    const done = await mkTask(u, { title: 'TimedPastDone', dueDate: earlier.date, dueTime: earlier.time });
    await putTask(u.page, done.id, { title: 'TimedPastDone', dueDate: earlier.date, status: 'DONE' });
    await gotoTasks(u.page);
    await expect(rowOf(u.page, 'TimedPast').locator('.task-row__overdue')).toBeVisible();
    await expect(rowOf(u.page, 'TimedFuture').locator('.task-row__overdue')).toHaveCount(0);
    await expect(rowOf(u.page, 'TimedPastDone').locator('.task-row__overdue')).toHaveCount(0);
    await expect(rowOf(u.page, 'TimedLater').locator('.task-row__overdue')).toHaveCount(0);
    // no reload: wait for the clock to pass the time (becomes overdue when the minute after `soon` starts)
    await expect(rowOf(u.page, 'TimedLater').locator('.task-row__overdue')).toBeVisible({ timeout: 200_000 });
    await shot(u.page, 'ac5-badge-flipped');
  });

  test('AC6: sort by due date -- date, timed by time first, date-only after, no date last (API + UI)', async ({ kit }) => {
    const u = await kit.newUser({ browserTz: 'Asia/Tokyo' });
    await mkTask(u, { title: 'A-dateonly-10', dueDate: '2030-01-10' });
    await mkTask(u, { title: 'B-1000', dueDate: '2030-01-10', dueTime: '10:00' });
    await mkTask(u, { title: 'C-0900', dueDate: '2030-01-10', dueTime: '09:00' });
    await mkTask(u, { title: 'D-dateonly-09', dueDate: '2030-01-09' });
    await mkTask(u, { title: 'E-nodate' });
    await mkTask(u, { title: 'F-11-0800', dueDate: '2030-01-11', dueTime: '08:00' });
    const want = ['D-dateonly-09', 'C-0900', 'B-1000', 'A-dateonly-10', 'F-11-0800', 'E-nodate'];
    const p = await json<TaskPage>(await u.page.request.get('/api/tasks?sort=due'));
    expect(p.items.map((t) => t.title)).toEqual(want);
    await gotoTasks(u.page, '?sort=due');
    const labels = await u.page
      .getByRole('list', { name: 'Tasks' })
      .getByRole('checkbox')
      .evaluateAll((els) => els.map((e) => (e.getAttribute('aria-label') ?? '').replace(/^Done: /, '')));
    expect(labels).toEqual(want);
    await shot(u.page, 'ac6-sort-due');
  });

  test('AC6: 130 tasks with mixed times page 50/50/30 without skips or repeats in due order; bad cursor -> INVALID_CURSOR', async ({ kit }) => {
    test.setTimeout(120_000);
    const u = await kit.newUser({ browserTz: 'Asia/Tokyo' });
    const created: Task[] = [];
    for (let i = 0; i < 130; i++) {
      const date = i % 9 === 0 ? null : `2030-02-${String(1 + (i % 6)).padStart(2, '0')}`;
      const time = date && i % 3 !== 0 ? `${String((i * 7) % 24).padStart(2, '0')}:${String((i * 13) % 60).padStart(2, '0')}` : null;
      created.push(await mkTask(u, { title: `N${String(i).padStart(3, '0')}`, dueDate: date, dueTime: time }));
    }
    const seen: Task[] = [];
    let cursor: string | null = null;
    const sizes: number[] = [];
    do {
      const q: string = `/api/tasks?sort=due${cursor ? `&cursor=${cursor}` : ''}`;
      const pg = await json<TaskPage>(await u.page.request.get(q));
      expect(pg.total).toBe(130);
      sizes.push(pg.items.length);
      seen.push(...pg.items);
      cursor = pg.nextCursor;
    } while (cursor);
    expect(sizes).toEqual([50, 50, 30]);
    expect(new Set(seen.map((t) => t.id)).size).toBe(130);
    const key = (t: Task) => (t.dueDate === null ? 'z' : `a${t.dueDate}${t.dueTime ? `a${t.dueTime}` : 'b'}`);
    for (let i = 1; i < seen.length; i++) expect(key(seen[i - 1]) <= key(seen[i]), `${i}: ${key(seen[i - 1])} <= ${key(seen[i])}`).toBe(true);
    const bad = await api(u.page, 'GET', '/api/tasks?sort=due&cursor=notacursor');
    expect(bad.status()).toBe(400);
    expect(((await bad.json()) as { errors: { code: string }[] }).errors[0].code).toBe('INVALID_CURSOR');
  });

  test('AC7: shared list -- a member sets/clears the due time; both see the same wall-clock; reminders fire in each own zone', async ({ kit }) => {
    const owner = await kit.newUser({ zone: 'Europe/Bucharest', browserTz: 'Europe/Bucharest', tag: 'own' });
    const member = await kit.newUser({ zone: 'America/Los_Angeles', browserTz: 'America/Los_Angeles', tag: 'mem' });
    const list = await createList(owner.page, `Shared ${uid()}`);
    await share(owner, list.id, member);
    const t = await mkTask(owner, { title: 'SharedTimed', listId: list.id, dueDate: '2030-05-10' });
    // member sets the time + their own AT_DUE reminder
    const byMember = await putTask(member.page, t.id, { title: 'SharedTimed', dueDate: '2030-05-10', dueTime: '09:30', reminder: 'AT_DUE' });
    expect(byMember).toMatchObject({ dueTime: '09:30', myReminder: 'AT_DUE' });
    const ownerView = await getTask(owner.page, t.id);
    expect(ownerView).toMatchObject({ dueDate: '2030-05-10', dueTime: '09:30', myReminder: null });
    await putTask(owner.page, t.id, { title: 'SharedTimed', dueDate: '2030-05-10', reminder: 'AT_DUE' });
    // 09:30 in each own zone: Bucharest is UTC+3 in May (06:30Z), Los Angeles UTC-7 (16:30Z)
    expect(fireAt(t.id, owner.id)).toBe('2030-05-10T06:30:00Z');
    expect(fireAt(t.id, member.id)).toBe('2030-05-10T16:30:00Z');
    // both UIs show the same text
    for (const p of [owner.page, member.page]) {
      await p.goto(`/lists/${list.id}`);
      await expect(rowOf(p, 'SharedTimed').locator('.task-row__due')).toHaveText('Due 2030-05-10 09:30');
    }
    // member clears the time
    const cleared = await putTask(member.page, t.id, { title: 'SharedTimed', dueDate: '2030-05-10', dueTime: null });
    expect(cleared.dueTime).toBeNull();
    expect((await getTask(owner.page, t.id))!.dueTime).toBeNull();
    // AT_DUE does not fit a date-only task: both reminders dropped
    expect(sqlNum(`SELECT COUNT(*) FROM reminders WHERE task_id = ${t.id}`)).toBe(0);
  });
});

// =====================================================================================
// Reminders: setting, privacy, recompute (AC8, AC9, AC11, AC12, AC13)
// =====================================================================================

test.describe('reminders', () => {
  test('AC8: API -- offsets by task kind; REQUIRES_DUE_DATE / INVALID_REMINDER; one per user per task; NONE clears', async ({ kit }) => {
    const u = await kit.newUser();
    kit.allowNetworkErrors = true;
    const t = await mkTask(u, { title: 'Offsets' });
    const bad = async (body: Record<string, unknown>, field: string, code: string) => {
      const r = await api(u.page, 'PUT', `/api/tasks/${t.id}`, { title: 'Offsets', ...body });
      expect(r.status(), JSON.stringify(body)).toBe(400);
      const b = (await r.json()) as { code: string; errors: { field: string; code: string }[] };
      expect(b.code).toBe('VALIDATION_FAILED');
      expect(b.errors).toEqual([expect.objectContaining({ field, code })]);
    };
    await bad({ reminder: 'AT_DUE' }, 'reminder', 'REQUIRES_DUE_DATE');
    await bad({ dueTime: '09:30' }, 'dueTime', 'REQUIRES_DUE_DATE');
    await bad({ dueDate: '2030-06-01', reminder: 'ON_THE_DAY', dueTime: '09:30' }, 'reminder', 'INVALID_REMINDER');
    await bad({ dueDate: '2030-06-01', reminder: 'AT_DUE' }, 'reminder', 'INVALID_REMINDER');
    await bad({ dueDate: '2030-06-01', reminder: 'TEN_MINUTES' }, 'reminder', 'INVALID_REMINDER');
    await bad({ dueDate: '2030-06-01', reminder: 'bogus' }, 'reminder', 'INVALID_REMINDER');
    const mal = await api(u.page, 'PUT', `/api/tasks/${t.id}`, { title: 'Offsets', dueDate: '2030-06-01', dueTime: '9:30' });
    expect(mal.status()).toBe(400);
    expect(((await mal.json()) as { code: string }).code).toBe('MALFORMED_REQUEST');
    // date-only: ON_THE_DAY (09:00 that day), ONE_DAY (09:00 the day before)
    let r = await putTask(u.page, t.id, { title: 'Offsets', dueDate: '2030-06-01', reminder: 'ON_THE_DAY' });
    expect(r.myReminder).toBe('ON_THE_DAY');
    expect(fireAt(t.id, u.id)).toBe('2030-06-01T00:00:00Z'); // 09:00 Asia/Tokyo
    r = await putTask(u.page, t.id, { title: 'Offsets', dueDate: '2030-06-01', reminder: 'ONE_DAY' });
    expect(fireAt(t.id, u.id)).toBe('2030-05-31T00:00:00Z');
    // timed: all four
    for (const [offset, expected] of [
      ['AT_DUE', '2030-06-01T01:30:00Z'], ['TEN_MINUTES', '2030-06-01T01:20:00Z'],
      ['ONE_HOUR', '2030-06-01T00:30:00Z'], ['ONE_DAY', '2030-05-31T01:30:00Z'],
    ] as const) {
      r = await putTask(u.page, t.id, { title: 'Offsets', dueDate: '2030-06-01', dueTime: '10:30', reminder: offset });
      expect(r.myReminder).toBe(offset);
      expect(fireAt(t.id, u.id), offset).toBe(expected);
    }
    // unchanged when reminder missing / null; NONE clears; only one row
    r = await putTask(u.page, t.id, { title: 'Offsets 2', dueDate: '2030-06-01' });
    expect(r.myReminder).toBe('ONE_DAY');
    r = await putTask(u.page, t.id, { title: 'Offsets 2', dueDate: '2030-06-01', reminder: null });
    expect(r.myReminder).toBe('ONE_DAY');
    expect(sqlNum(`SELECT COUNT(*) FROM reminders WHERE task_id = ${t.id} AND user_id = ${u.id}`)).toBe(1);
    r = await putTask(u.page, t.id, { title: 'Offsets 2', dueDate: '2030-06-01', reminder: 'NONE' });
    expect(r.myReminder).toBeNull();
    expect(sqlNum(`SELECT COUNT(*) FROM reminders WHERE task_id = ${t.id}`)).toBe(0);
  });

  test('AC9: a member\'s reminder is private -- other members neither see nor get it; each sets own; bell on my row only', async ({ kit }) => {
    const a = await kit.newUser({ tag: 'a', browserTz: 'Asia/Tokyo' });
    const b = await kit.newUser({ tag: 'b', browserTz: 'Asia/Tokyo' });
    const list = await createList(a.page, `Priv ${uid()}`);
    await share(a, list.id, b);
    const t = await mkTask(a, { title: 'PrivateRem', listId: list.id, dueDate: '2030-07-01', dueTime: '12:00', reminder: 'ONE_HOUR' });
    expect(t.myReminder).toBe('ONE_HOUR');
    const bView = await getTask(b.page, t.id);
    expect(bView!.myReminder).toBeNull();
    await a.page.goto(`/lists/${list.id}`);
    await b.page.goto(`/lists/${list.id}`);
    await expect(rowOf(a.page, 'PrivateRem').getByTestId('task-reminder')).toBeVisible();
    await expect(rowOf(b.page, 'PrivateRem').getByTestId('task-reminder')).toHaveCount(0);
    await shot(a.page, 'ac9-bell-owner');
    // B sets their own; A's stays
    await putTask(b.page, t.id, { title: 'PrivateRem', dueDate: '2030-07-01', dueTime: '12:00', reminder: 'TEN_MINUTES' });
    expect((await getTask(a.page, t.id))!.myReminder).toBe('ONE_HOUR');
    expect((await getTask(b.page, t.id))!.myReminder).toBe('TEN_MINUTES');
    // A fires; B gets nothing
    pokeDue([t.id], 1, a.id);
    await waitFor(async () => (await notifs(a.page)).items.length === 1, 'A notified');
    await b.page.waitForTimeout(3000);
    expect((await notifs(b.page)).items).toHaveLength(0);
    expect(remStatus(t.id, b.id)).toBe('PENDING');
  });

  test('AC11: fire time is recomputed on date, time, offset and zone changes; a fired one re-arms only if the new time is future', async ({ kit }) => {
    const u = await kit.newUser({ zone: 'Europe/Bucharest' });
    const t = await mkTask(u, { title: 'Recompute', dueDate: '2030-08-01', dueTime: '10:00', reminder: 'AT_DUE' });
    expect(fireAt(t.id, u.id)).toBe('2030-08-01T07:00:00Z'); // UTC+3
    await putTask(u.page, t.id, { title: 'Recompute', dueDate: '2030-08-02', dueTime: '10:00' });
    expect(fireAt(t.id, u.id)).toBe('2030-08-02T07:00:00Z');
    await putTask(u.page, t.id, { title: 'Recompute', dueDate: '2030-08-02', dueTime: '11:15' });
    expect(fireAt(t.id, u.id)).toBe('2030-08-02T08:15:00Z');
    await putTask(u.page, t.id, { title: 'Recompute', dueDate: '2030-08-02', dueTime: '11:15', reminder: 'ONE_HOUR' });
    expect(fireAt(t.id, u.id)).toBe('2030-08-02T07:15:00Z');
    await json(await api(u.page, 'PUT', '/api/account/time-zone', { timeZone: 'America/Los_Angeles' })); // UTC-7
    expect(fireAt(t.id, u.id)).toBe('2030-08-02T17:15:00Z');
    await json(await api(u.page, 'PUT', '/api/account/time-zone', { timeZone: 'Europe/Bucharest' }));
    expect(fireAt(t.id, u.id)).toBe('2030-08-02T07:15:00Z');

    // fire it, then: move into the past -> stays fired; move into the future -> re-armed and fires again
    pokeDue([t.id], 1, u.id);
    await waitFor(() => remStatus(t.id, u.id) === 'SENT', 'reminder sent');
    expect(notifCount(u.id)).toBe(1);
    await putTask(u.page, t.id, { title: 'Recompute', dueDate: '2030-08-02', dueTime: '12:15', reminder: 'ONE_HOUR' });
    expect(remStatus(t.id, u.id)).toBe('PENDING'); // new future fire time -> re-armed
    expect(fireAt(t.id, u.id)).toBe('2030-08-02T08:15:00Z');
    pokeDue([t.id], 1, u.id);
    await waitFor(() => notifCount(u.id) === 2, 'second notification after re-arm');
    await waitFor(() => remStatus(t.id, u.id) === 'SENT', 'sent again');
    // now a change whose new fire time is in the PAST must not fire again
    const past = inZone('Europe/Bucharest', -120);
    const p = await putTask(u.page, t.id, { title: 'Recompute', dueDate: past.date, dueTime: past.time, reminder: 'AT_DUE' });
    expect(p.myReminderPassed).toBe(true);
    await u.page.waitForTimeout(4500);
    expect(notifCount(u.id)).toBe(2);
    expect(remStatus(t.id, u.id)).not.toBe('PENDING');
  });

  test('AC12: a reminder saved in the past is stored, never fires, and the dialog says "This reminder time has passed"', async ({ kit }) => {
    const zone = 'Asia/Tokyo';
    const u = await kit.newUser({ zone, browserTz: zone });
    const past = inZone(zone, -45);
    await mkTask(u, { title: 'PastRem', dueDate: past.date, dueTime: past.time });
    await gotoTasks(u.page);
    await openEdit(u.page, 'PastRem');
    await editDialog(u.page).locator('#task-edit-reminder').selectOption('AT_DUE');
    await editDialog(u.page).getByRole('button', { name: 'Save' }).click();
    await expect(u.page.getByTestId('reminder-passed')).toContainText('This reminder time has passed');
    await expect(editDialog(u.page)).toBeVisible(); // stays open (build decision)
    await shot(u.page, 'ac12-passed');
    const tid = (await json<TaskPage>(await u.page.request.get('/api/tasks'))).items[0];
    expect(tid.myReminder).toBe('AT_DUE');
    expect(tid.myReminderPassed).toBe(true);
    expect(remStatus(tid.id, u.id)).toBe('SKIPPED');
    await u.page.waitForTimeout(5000);
    expect(notifCount(u.id)).toBe(0);
    await editDialog(u.page).getByRole('button', { name: 'Close' }).click();
    await expect(editDialog(u.page)).toBeHidden();
    await expect(rowOf(u.page, 'PastRem').getByTestId('task-reminder')).toBeVisible();
  });

  test('AC13: Done task does not fire; reopening re-arms a future reminder; deleted task does not fire', async ({ kit }) => {
    const u = await kit.newUser();
    const mk = (title: string) => mkTask(u, { title, dueDate: '2030-09-01', dueTime: '10:00', reminder: 'AT_DUE' });
    // Done at fire time -> SKIPPED, nothing created
    const done = await mk('DoneNoFire');
    await putTask(u.page, done.id, { title: 'DoneNoFire', dueDate: '2030-09-01', status: 'DONE' });
    pokeDue([done.id], 1, u.id);
    await waitFor(() => remStatus(done.id, u.id) === 'SKIPPED', 'done -> skipped');
    expect(notifCount(u.id)).toBe(0);
    // Done, then reopened before fire time -> re-armed and fires
    const re = await mk('Reopen');
    await putTask(u.page, re.id, { title: 'Reopen', dueDate: '2030-09-01', status: 'DONE' });
    await putTask(u.page, re.id, { title: 'Reopen', dueDate: '2030-09-01', status: 'TODO' });
    expect(remStatus(re.id, u.id)).toBe('PENDING');
    pokeDue([re.id], 1, u.id);
    await waitFor(() => notifCount(u.id) === 1, 'reopened task fires');
    expect((await notifs(u.page)).items[0].title).toBe('Reopen');
    // deleted
    const del = await mk('Deleted');
    expect((await api(u.page, 'DELETE', `/api/tasks/${del.id}`)).status()).toBe(204);
    expect(sqlNum(`SELECT COUNT(*) FROM reminders WHERE task_id = ${del.id}`)).toBe(0);
    await u.page.waitForTimeout(3000);
    expect(notifCount(u.id)).toBe(1);
  });

  test('AC13: member who left / was removed / lost the task by a move gets nothing; account deletion removes reminders + notifications', async ({ kit }) => {
    const owner = await kit.newUser({ tag: 'own' });
    const m1 = await kit.newUser({ tag: 'leaver' });
    const m2 = await kit.newUser({ tag: 'removed' });
    const m3 = await kit.newUser({ tag: 'moved' });
    const list = await createList(owner.page, `Gone ${uid()}`);
    for (const m of [m1, m2, m3]) await share(owner, list.id, m);
    const own = await mkTask(owner, { title: 'GoneTask', listId: list.id, dueDate: '2030-09-05', dueTime: '10:00' });
    const reminders: Record<number, number> = {};
    for (const m of [m1, m2, m3]) {
      await putTask(m.page, own.id, { title: 'GoneTask', dueDate: '2030-09-05', dueTime: '10:00', reminder: 'AT_DUE' });
      reminders[m.id] = sqlNum(`SELECT id FROM reminders WHERE task_id = ${own.id} AND user_id = ${m.id}`);
    }
    expect((await api(m1.page, 'POST', `/api/lists/${list.id}/leave`)).status()).toBe(204);
    expect((await api(owner.page, 'DELETE', `/api/lists/${list.id}/members/${m2.id}`)).status()).toBe(204);
    const ownerPersonal = await personalList(owner.page);
    await putTask(owner.page, own.id, { title: 'GoneTask', dueDate: '2030-09-05', listId: ownerPersonal.id });
    // whatever the implementation did with the rows, forcing them due must create nothing
    sql(`UPDATE reminders SET fire_at = UTC_TIMESTAMP(6) - INTERVAL 1 MINUTE, next_attempt_at = UTC_TIMESTAMP(6) - INTERVAL 1 MINUTE, status = 'PENDING' WHERE task_id = ${own.id}`);
    await owner.page.waitForTimeout(6000);
    for (const m of [m1, m2, m3]) {
      expect(notifCount(m.id), `user ${m.email}`).toBe(0);
      expect((await mailsTo(m.email)).length).toBe(0);
    }
    const rows = sql(`SELECT status FROM reminders WHERE task_id = ${own.id}`);
    console.log('AC13 remaining reminder rows after leave/remove/move:', JSON.stringify(rows));
    expect(rows).not.toContain('SENT');

    // account deletion
    const d = await kit.newUser({ tag: 'del' });
    const t = await mkTask(d, { title: 'DelAcct', dueDate: '2030-09-06', dueTime: '10:00', reminder: 'AT_DUE' });
    sql(`INSERT INTO notifications (user_id, task_id, title, due_date) VALUES (${d.id}, ${t.id}, 'x', '2030-09-06')`);
    expect(sqlNum(`SELECT COUNT(*) FROM reminders WHERE user_id = ${d.id}`)).toBe(1);
    expect((await api(d.page, 'POST', '/api/account/delete', { password: PASSWORD })).status()).toBe(204);
    expect(sqlNum(`SELECT COUNT(*) FROM reminders WHERE user_id = ${d.id}`)).toBe(0);
    expect(sqlNum(`SELECT COUNT(*) FROM notifications WHERE user_id = ${d.id}`)).toBe(0);
  });

  test('AC23: DST -- spring-forward gap and fall-back overlap (America/New_York 2027), Europe/Bucharest', async ({ kit }) => {
    // zdump: 2027-03-14 02:00 EST -> 03:00 EDT (gap 02:00-03:00 local; the jump is 07:00Z);
    //        2027-11-07 02:00 EDT -> 01:00 EST (01:00-02:00 happens twice; earlier = EDT, UTC-4).
    const u = await kit.newUser({ zone: 'America/New_York' });
    const fa = async (date: string, time: string | null, reminder: Reminder) => {
      const t = await mkTask(u, { dueDate: date, dueTime: time, reminder });
      return fireAt(t.id, u.id);
    };
    expect(await fa('2027-03-14', '02:30', 'AT_DUE')).toBe('2027-03-14T07:00:00Z'); // gap -> the instant of the jump (03:00 EDT)
    expect(await fa('2027-03-14', '03:30', 'AT_DUE')).toBe('2027-03-14T07:30:00Z'); // 03:30 EDT, the first real time after the gap
    expect(await fa('2027-03-14', '01:59', 'AT_DUE')).toBe('2027-03-14T06:59:00Z'); // last EST minute
    expect(await fa('2027-11-07', '01:30', 'AT_DUE')).toBe('2027-11-07T05:30:00Z'); // overlap -> earlier (EDT, UTC-4)
    expect(await fa('2027-11-07', '02:30', 'AT_DUE')).toBe('2027-11-07T07:30:00Z'); // after fall-back: EST
    expect(await fa('2027-03-14', '09:00', 'AT_DUE')).toBe('2027-03-14T13:00:00Z'); // EDT
    // ONE_DAY across the gap: same wall clock the day before
    expect(await fa('2027-03-15', '02:30', 'ONE_DAY')).toBe('2027-03-14T07:00:00Z'); // 03-14 02:30 is in the gap
    // date-only 09:00 reminders around DST
    expect(await fa('2027-03-14', null, 'ON_THE_DAY')).toBe('2027-03-14T13:00:00Z'); // 09:00 EDT
    expect(await fa('2027-03-14', null, 'ONE_DAY')).toBe('2027-03-13T14:00:00Z'); // 09:00 EST the day before
    // the zone change moves it
    const t = await mkTask(u, { dueDate: '2027-03-14', dueTime: '02:30', reminder: 'AT_DUE' });
    await json(await api(u.page, 'PUT', '/api/account/time-zone', { timeZone: 'Europe/Bucharest' }));
    expect(fireAt(t.id, u.id)).toBe('2027-03-14T00:30:00Z'); // 02:30 EET (UTC+2), no gap there
    // the wall-clock stays what the user typed
    expect((await getTask(u.page, t.id))!.dueTime).toBe('02:30');
  });
});

// =====================================================================================
// Firing, email, notifications (AC10, AC14-AC19, AC21)
// =====================================================================================

test.describe('firing, email, notifications', () => {
  test('AC10 AC14 AC15 AC17: a real reminder fires on time, once, creates a notification + email (escaped title, link, no description); bell refreshes without navigation', async ({ kit }) => {
    test.setTimeout(300_000);
    const zone = 'Europe/Bucharest';
    const u = await kit.newUser({ zone, browserTz: zone });
    const TITLE = `Pay <b>rent</b> & "co" ${uid()}`;
    const MARKER = `DESCMARKER-${uid()}`;
    const target = inZone(zone, 2);
    const t = await mkTask(u, { title: TITLE, description: MARKER, dueDate: target.date, dueTime: target.time, reminder: 'AT_DUE' });
    expect(t.myReminderPassed).toBe(false);
    const list = await personalList(u.page);
    await gotoTasks(u.page);
    await expect(u.page.getByTestId('notifications-badge')).toHaveCount(0);
    await expect(u.page.getByTestId('notifications-link')).toHaveAttribute('aria-label', 'Notifications');
    const started = Date.now();
    // the bell must update by itself (60 s poll), not only on navigation
    await expect(u.page.getByTestId('notifications-badge')).toHaveText('1', { timeout: 240_000 });
    console.log(`AC10/17: bell showed 1 after ${Math.round((Date.now() - started) / 1000)} s (target ${target.time} ${zone})`);
    await expect(u.page.getByTestId('notifications-link')).toHaveAttribute('aria-label', 'Notifications, 1 unread');
    await shot(u.page, 'ac17-bell-polled');
    // once: wait a few more scans
    await u.page.waitForTimeout(7000);
    const n = await notifs(u.page);
    expect(n.items).toHaveLength(1);
    expect(Object.keys(n.items[0]).sort()).toEqual(NOTIF_KEYS);
    expect(n.items[0]).toMatchObject({ type: 'REMINDER', read: false, late: false, title: TITLE, dueDate: target.date, dueTime: target.time, taskId: t.id, listId: list.id });
    const fired = new Date(n.items[0].createdAt).getTime();
    const dueInstant = Date.parse(
      `${target.date}T${target.time}:00${zone === 'Europe/Bucharest' ? (inZone(zone).date >= '2026-10-25' ? '+02:00' : '+03:00') : 'Z'}`,
    );
    console.log(`AC10: fired ${Math.round((fired - dueInstant) / 1000)} s after the due instant`);
    expect(fired - dueInstant).toBeGreaterThanOrEqual(-1000);
    expect(fired - dueInstant).toBeLessThan(15_000);
    // mail
    let mails: Mail[] = [];
    await waitFor(async () => (mails = await mailsTo(u.email)).length >= 1, 'reminder mail in Mailpit');
    await u.page.waitForTimeout(3000);
    mails = await mailsTo(u.email);
    expect(mails).toHaveLength(1);
    const body = await mailBody(mails[0].ID);
    expect(body.Subject).toContain('Reminder: ');
    expect(body.HTML).toContain('Pay &lt;b&gt;rent&lt;/b&gt; &amp;');
    expect(body.HTML).not.toContain('<b>rent</b>');
    expect(body.HTML).not.toContain(MARKER);
    expect(body.Text).not.toContain(MARKER);
    expect(body.HTML).toContain(mailDate(target.date));
    expect(body.HTML).toContain(target.time);
    expect(body.HTML).toContain(list.name);
    expect(body.HTML).toMatch(new RegExp(`href="${BASE.replace(/\./g, '\\.')}/tasks"`));
    expect(body.Text.toLowerCase()).toMatch(/reminder emails/);
    expect(body.Text.toLowerCase()).toContain('account');
    console.log('AC15 subject:', body.Subject);
    console.log('AC15 text:', body.Text.replace(/\n+/g, ' | ').slice(0, 600));
  });

  test('AC14: email only for active + verified + toggle on; the notification always; unread/disabled/off users get no mail', async ({ kit }) => {
    const mk = async (tag: string) => {
      const u = await kit.newUser({ tag, verified: tag !== 'unverified' });
      const t = await mkTask(u, { title: `Mail ${tag} ${uid()}`, dueDate: '2030-10-01', dueTime: '10:00', reminder: 'AT_DUE' });
      return { u, t };
    };
    const ok = await mk('ok');
    const unverified = await mk('unverified');
    const disabled = await mk('disabled');
    const off = await mk('off');
    await json(await api(off.u.page, 'PUT', '/api/account/reminder-emails', { enabled: false }));
    sql(`UPDATE users SET disabled_at = NOW(6) WHERE id = ${disabled.u.id}`);
    for (const x of [ok, unverified, disabled, off]) pokeDue([x.t.id], 1, x.u.id);
    await waitFor(
      () => [ok, unverified, disabled, off].every((x) => remStatus(x.t.id, x.u.id) !== 'PENDING'),
      'all four processed',
    );
    for (const x of [ok, unverified, disabled, off]) expect(notifCount(x.u.id), x.u.email).toBe(1);
    await waitFor(async () => (await mailsTo(ok.u.email)).length === 1, 'mail for the eligible user');
    await off.u.page.waitForTimeout(2000);
    for (const x of [unverified, disabled, off]) expect((await mailsTo(x.u.email)).length, x.u.email).toBe(0);
    expect((await mailsTo(ok.u.email)).length).toBe(1);
  });

  test('AC16: "Reminder emails" toggle saves immediately and is respected from the next reminder', async ({ kit }) => {
    const u = await kit.newUser({ browserTz: 'Asia/Tokyo' });
    expect((await me(u.page))['reminderEmails']).toBe(true);
    await u.page.goto('/account');
    const box = u.page.locator('#account-reminder-emails');
    await expect(box).toBeChecked();
    await box.uncheck();
    await waitFor(async () => (await me(u.page))['reminderEmails'] === false, 'saved off');
    await u.page.reload();
    await expect(u.page.locator('#account-reminder-emails')).not.toBeChecked();
    await shot(u.page, 'ac16-off');
    const t1 = await mkTask(u, { title: `Off ${uid()}`, dueDate: '2030-10-02', dueTime: '10:00', reminder: 'AT_DUE' });
    pokeDue([t1.id], 1);
    await waitFor(() => notifCount(u.id) === 1, 'notification while off');
    await u.page.waitForTimeout(2500);
    expect((await mailsTo(u.email)).length).toBe(0);
    await u.page.locator('#account-reminder-emails').check();
    await waitFor(async () => (await me(u.page))['reminderEmails'] === true, 'saved on');
    const t2 = await mkTask(u, { title: `On ${uid()}`, dueDate: '2030-10-02', dueTime: '11:00', reminder: 'AT_DUE' });
    pokeDue([t2.id], 1);
    await waitFor(async () => (await mailsTo(u.email)).length === 1, 'mail once on again');
    // API validation
    expect((await api(u.page, 'PUT', '/api/account/reminder-emails', {})).status()).toBe(400);
    expect((await api(u.page, 'PUT', '/api/account/reminder-emails', { enabled: 'maybe' })).status()).toBe(400);
  });

  test('AC15: shared-list reminder email links to /lists/<id> and names the list', async ({ kit }) => {
    const u = await kit.newUser();
    const list = await createList(u.page, `Team <i>List</i> ${uid()}`);
    const t = await mkTask(u, { title: `SharedMail ${uid()}`, listId: list.id, dueDate: '2030-10-03', dueTime: '10:00', reminder: 'AT_DUE' });
    pokeDue([t.id], 1);
    await waitFor(async () => (await mailsTo(u.email)).length === 1, 'mail');
    const m = await mailBody((await mailsTo(u.email))[0].ID);
    expect(m.HTML).toContain(`href="${BASE}/lists/${list.id}"`);
    expect(m.HTML).toContain('Team &lt;i&gt;List&lt;/i&gt;');
    expect(m.HTML).not.toContain('<i>List</i>');
    expect(m.HTML).toContain(mailDate('2030-10-03'));
  });

  test('AC21: <1 h late fires (flagged late if >5 min, email says so); >=1 h late is skipped with no notification or email', async ({ kit }) => {
    const u = await kit.newUser();
    const mk = (title: string) => mkTask(u, { title, dueDate: '2030-10-04', dueTime: '10:00', reminder: 'AT_DUE' });
    const slight = await mk('Slightly3');
    const late10 = await mk('Late10');
    const late59 = await mk('Late59');
    const veryLate = await mk('Late90');
    const edge = await mk('Late61');
    pokeDue([slight.id], 3);
    pokeDue([late10.id], 10);
    pokeDue([late59.id], 59);
    pokeDue([veryLate.id], 90);
    pokeDue([edge.id], 61);
    await waitFor(() => [slight, late10, late59, veryLate, edge].every((t) => remStatus(t.id, u.id) !== 'PENDING'), 'processed');
    const items = (await notifs(u.page)).items;
    const by = Object.fromEntries(items.map((n) => [n.title, n]));
    expect(Object.keys(by).sort()).toEqual(['Late10', 'Late59', 'Slightly3']);
    expect(by['Slightly3'].late).toBe(false);
    expect(by['Late10'].late).toBe(true);
    expect(by['Late59'].late).toBe(true);
    expect(remStatus(veryLate.id, u.id)).toBe('SKIPPED');
    expect(remStatus(edge.id, u.id)).toBe('SKIPPED');
    await waitFor(async () => (await mailsTo(u.email)).length === 3, '3 mails');
    await u.page.waitForTimeout(2000);
    const mails = await mailsTo(u.email);
    expect(mails).toHaveLength(3);
    const subjects = mails.map((m) => m.Subject).sort();
    expect(subjects.join(' ')).not.toMatch(/Late90|Late61/);
    for (const m of mails) {
      const b = await mailBody(m.ID);
      const isLate = !b.Subject.includes('Slightly3');
      console.log('AC21 mail:', b.Subject, '| late wording:', /late/i.test(b.Text));
      expect(/late/i.test(b.Text) || /late/i.test(b.Subject), b.Subject).toBe(isLate);
    }
  });

  test('AC17 AC18: /notifications -- newest first, text format, unread marked, click marks read + opens the list, unavailable, Mark all read, Load more', async ({ kit }) => {
    const zone = 'Asia/Tokyo';
    const u = await kit.newUser({ zone, browserTz: zone });
    const other = await kit.newUser({ tag: 'other' });
    const shared = await createList(other.page, `NotifShared ${uid()}`);
    await share(other, shared.id, u);
    const personal = await personalList(u.page);
    const tp = await mkTask(u, { title: 'NPersonal', dueDate: '2030-11-01', dueTime: '08:05' });
    const ts = await mkTask(other, { title: 'NShared', listId: shared.id, dueDate: '2030-11-02' });
    const tgone = await mkTask(u, { title: 'NGone', dueDate: '2030-11-03', dueTime: '09:00' });
    const tleft = await mkTask(other, { title: 'NLeft', listId: shared.id, dueDate: '2030-11-04' });
    const ins = (task: Task, title: string, date: string, time: string | null, ago: number, extra = '') =>
      sql(
        `INSERT INTO notifications (user_id, task_id, title, due_date, due_time, created_at ${extra ? ', late' : ''}) VALUES (${u.id}, ${task.id}, '${title}', '${date}', ${time ? `'${time}'` : 'NULL'}, UTC_TIMESTAMP(6) - INTERVAL ${ago} MINUTE ${extra ? ', 1' : ''})`,
      );
    ins(tp, 'NPersonal', '2030-11-01', '08:05', 5);
    ins(ts, 'NShared', '2030-11-02', null, 4, 'late');
    ins(tgone, 'NGone', '2030-11-03', '09:00', 3);
    ins(tleft, 'NLeft', '2030-11-04', null, 2);
    // filler -> 25 total for paging (older than the four above)
    for (let i = 0; i < 21; i++) ins(tp, `Filler${String(i).padStart(2, '0')}`, '2030-11-05', '10:00', 100 + i);
    // gone: delete the task (SET NULL); lost access: owner removes me from the list... (shared task NLeft only; NShared stays reachable)
    expect((await api(u.page, 'DELETE', `/api/tasks/${tgone.id}`)).status()).toBe(204);
    expect(await unread(u.page)).toBe(25);

    await u.page.goto('/tasks');
    await expect(u.page.getByTestId('notifications-badge')).toHaveText('25');
    await u.page.getByTestId('notifications-link').click();
    await expect(u.page).toHaveURL(/\/notifications$/);
    const items = u.page.getByTestId('notification');
    await expect(items).toHaveCount(20);
    await expect(items.nth(0).getByTestId('notification-text')).toHaveText('Reminder: NLeft — due 2030-11-04');
    await expect(items.nth(1).getByTestId('notification-text')).toHaveText('Reminder: NGone — due 2030-11-03 09:00');
    await expect(items.nth(2).getByTestId('notification-text')).toHaveText('Reminder: NShared — due 2030-11-02');
    await expect(items.nth(2).getByTestId('notification-late')).toBeVisible();
    await expect(items.nth(3).getByTestId('notification-text')).toHaveText('Reminder: NPersonal — due 2030-11-01 08:05');
    await expect(items.nth(1).getByTestId('notification-unavailable')).toContainText('This task is no longer available');
    await expect(items.nth(0).getByTestId('notification-unavailable')).toHaveCount(0);
    for (let i = 0; i < 20; i++) await expect(items.nth(i)).toHaveAttribute('data-read', 'false');
    await shot(u.page, 'ac18-list');
    // paging
    await u.page.getByTestId('load-more').click();
    await expect(items).toHaveCount(25);
    await expect(u.page.getByTestId('load-more')).toHaveCount(0);
    const texts = await items.getByTestId('notification-text').allTextContents();
    expect(new Set(texts).size).toBe(25); // no repeats
    expect(texts[24]).toBe('Reminder: Filler20 — due 2030-11-05 10:00');
    // click the personal one -> read + /tasks
    await items.nth(3).locator('.notification__main').click();
    await expect(u.page).toHaveURL(/\/tasks$/);
    expect(await unread(u.page)).toBe(24);
    await expect(u.page.getByTestId('notifications-badge')).toHaveText('24');
    await u.page.goto('/notifications');
    await expect(u.page.getByTestId('notification').nth(3)).toHaveAttribute('data-read', 'true');
    // click the shared one -> /lists/<id>
    await u.page.getByTestId('notification').nth(2).locator('.notification__main').click();
    await expect(u.page).toHaveURL(new RegExp(`/lists/${shared.id}$`));
    // gone: stays on the page, marked read
    await u.page.goto('/notifications');
    await u.page.getByTestId('notification').nth(1).locator('.notification__main').click();
    await expect(u.page).toHaveURL(/\/notifications$/);
    await expect(u.page.getByTestId('notification').nth(1)).toHaveAttribute('data-read', 'true');
    // lost access: leave the list -> NLeft / NShared become unavailable
    expect((await api(u.page, 'POST', `/api/lists/${shared.id}/leave`)).status()).toBe(204);
    await u.page.reload();
    await expect(u.page.getByTestId('notification').nth(0).getByTestId('notification-unavailable')).toBeVisible();
    const apiItems = (await notifs(u.page)).items;
    expect(apiItems[0]).toMatchObject({ title: 'NLeft', taskId: null, listId: null });
    await shot(u.page, 'ac18-unavailable');
    // mark all read
    await u.page.getByTestId('mark-all-read').click();
    await expect(u.page.getByTestId('notifications-badge')).toHaveCount(0);
    expect(await unread(u.page)).toBe(0);
    for (const n of await u.page.getByTestId('notification').all()) await expect(n).toHaveAttribute('data-read', 'true');
    await expect(u.page.getByTestId('mark-all-read')).toBeDisabled();
    void personal;
  });

  test('AC18: API -- idempotent read, read-all, limits, MalformedNotificationId / not found; empty state text', async ({ kit }) => {
    const u = await kit.newUser();
    kit.allowNetworkErrors = true;
    await u.page.goto('/notifications');
    await expect(u.page.getByTestId('notifications-empty')).toHaveText('No notifications yet');
    await shot(u.page, 'ac26-empty');
    for (let i = 0; i < 3; i++) {
      sql(`INSERT INTO notifications (user_id, title, due_date, created_at) VALUES (${u.id}, 'N${i}', '2030-12-01', UTC_TIMESTAMP(6) - INTERVAL ${i} MINUTE)`);
    }
    const all = await notifs(u.page);
    expect(all.items.map((n) => n.title)).toEqual(['N0', 'N1', 'N2']);
    const lim = await notifs(u.page, '?limit=2');
    expect(lim.items).toHaveLength(2);
    expect(lim.nextCursor).toBeTruthy();
    expect((await notifs(u.page, `?limit=2&cursor=${lim.nextCursor}`)).items.map((n) => n.title)).toEqual(['N2']);
    for (const bad of ['?limit=0', '?limit=51', '?limit=x']) {
      const r = await u.page.request.get(`/api/notifications${bad}`);
      expect(r.status(), bad).toBe(400);
      expect(((await r.json()) as { errors: { field: string; code: string }[] }).errors[0]).toMatchObject({ field: 'limit', code: 'OUT_OF_RANGE' });
    }
    const badc = await u.page.request.get('/api/notifications?cursor=zzz');
    expect(badc.status()).toBe(400);
    expect(((await badc.json()) as { errors: { code: string }[] }).errors[0].code).toBe('INVALID_CURSOR');
    const id = all.items[0].id;
    expect((await api(u.page, 'POST', `/api/notifications/${id}/read`)).status()).toBe(200);
    expect((await api(u.page, 'POST', `/api/notifications/${id}/read`)).status()).toBe(200); // idempotent
    expect(await unread(u.page)).toBe(2);
    expect((await api(u.page, 'POST', '/api/notifications/read-all')).status()).toBeLessThan(300);
    expect(await unread(u.page)).toBe(0);
    const nf = await api(u.page, 'POST', '/api/notifications/99999999/read');
    expect(nf.status()).toBe(404);
    expect(((await nf.json()) as { code: string }).code).toBe('NOTIFICATION_NOT_FOUND');
    expect((await api(u.page, 'POST', '/api/notifications/abc/read')).status()).toBe(400);
    // unauthenticated
    const anon = await kit.ctx();
    for (const p of ['/api/notifications', '/api/notifications/count']) expect((await anon.page.request.get(p)).status(), p).toBe(401);
  });

  test('AC19: user A never sees B\'s reminders or notifications; B\'s notification id answers 404 for A', async ({ kit }) => {
    const a = await kit.newUser({ tag: 'a' });
    const b = await kit.newUser({ tag: 'b' });
    kit.allowNetworkErrors = true;
    const ta = await mkTask(a, { title: 'AOnly', dueDate: '2030-12-02', dueTime: '10:00', reminder: 'AT_DUE' });
    const tb = await mkTask(b, { title: 'BOnly', dueDate: '2030-12-02', dueTime: '10:00', reminder: 'AT_DUE' });
    pokeDue([ta.id, tb.id], 1);
    await waitFor(async () => (await notifs(a.page)).items.length === 1 && (await notifs(b.page)).items.length === 1, 'both fired');
    const bn = (await notifs(b.page)).items[0];
    expect((await notifs(a.page)).items.map((n) => n.title)).toEqual(['AOnly']);
    expect(await unread(a.page)).toBe(1);
    const r = await api(a.page, 'POST', `/api/notifications/${bn.id}/read`);
    expect(r.status()).toBe(404);
    expect(await unread(b.page)).toBe(1); // untouched
    // A cannot read B's task at all
    expect((await a.page.request.get(`/api/tasks/${tb.id}`)).status()).toBe(404);
    // an admin does not see reminders either: there is no admin endpoint returning them (tasks aren't visible to admins)
    sql(`UPDATE users SET role = 'ADMIN' WHERE id = ${a.id}`);
    expect((await a.page.request.get(`/api/tasks/${tb.id}`)).status()).toBe(404);
    expect((await notifs(a.page)).items.map((n) => n.title)).toEqual(['AOnly']);
  });

  test('AC17: bell shows no badge at 0, the count above 0, 99+ style text is not relied on; refreshes on navigation', async ({ kit }) => {
    const u = await kit.newUser({ browserTz: 'Asia/Tokyo' });
    await gotoTasks(u.page);
    await expect(u.page.getByTestId('notifications-badge')).toHaveCount(0);
    sql(`INSERT INTO notifications (user_id, title, due_date) VALUES (${u.id}, 'Z1', '2030-12-03'), (${u.id}, 'Z2', '2030-12-03')`);
    await u.page.getByRole('link', { name: 'Invitations' }).click();
    await expect(u.page.getByTestId('notifications-badge')).toHaveText('2', { timeout: 10_000 });
    await shot(u.page, 'ac17-bell');
  });

  test('AC17: the bell polls every ~60 s while the tab is visible (no navigation)', async ({ kit }) => {
    test.setTimeout(120_000);
    const u = await kit.newUser({ browserTz: 'Asia/Tokyo' });
    await gotoTasks(u.page);
    await expect(u.page.getByTestId('notifications-badge')).toHaveCount(0);
    sql(`INSERT INTO notifications (user_id, title, due_date) VALUES (${u.id}, 'Poll', '2030-12-03')`);
    const t0 = Date.now();
    await expect(u.page.getByTestId('notifications-badge')).toHaveText('1', { timeout: 90_000 });
    console.log(`AC17 poll: badge appeared after ${Math.round((Date.now() - t0) / 1000)} s without navigation`);
  });
});

// =====================================================================================
// States (AC26)
// =====================================================================================

test.describe('states', () => {
  test('AC26: /notifications loading, error with Retry, load-more error, action error', async ({ kit }) => {
    const u = await kit.newUser({ browserTz: 'Asia/Tokyo' });
    kit.allowNetworkErrors = true;
    kit.allow5xx = true;
    for (let i = 0; i < 25; i++) {
      sql(`INSERT INTO notifications (user_id, title, due_date, created_at) VALUES (${u.id}, 'S${String(i).padStart(2, '0')}', '2030-12-04', UTC_TIMESTAMP(6) - INTERVAL ${i} MINUTE)`);
    }
    // loading
    await u.page.route('**/api/notifications?**', async (r) => {
      await new Promise((res) => setTimeout(res, 1500));
      await r.continue();
    });
    await u.page.goto('/notifications');
    await expect(u.page.getByText('Loading notifications…')).toBeVisible();
    await shot(u.page, 'ac26-loading');
    await expect(u.page.getByTestId('notification')).toHaveCount(20);
    await u.page.unroute('**/api/notifications?**');
    // error + Retry
    await u.page.route('**/api/notifications?**', (r) => r.fulfill({ status: 500, contentType: 'application/problem+json', body: JSON.stringify({ status: 500, code: 'INTERNAL_ERROR', title: 'x' }) }));
    await u.page.goto('/notifications');
    const alert = u.page.locator('.notifications-page__error');
    await expect(alert).toBeVisible();
    await shot(u.page, 'ac26-error');
    await u.page.unroute('**/api/notifications?**');
    await alert.getByRole('button', { name: 'Retry' }).click();
    await expect(u.page.getByTestId('notification')).toHaveCount(20);
    // load more fails, then retry
    await u.page.route('**/api/notifications?*cursor=*', (r) => r.abort());
    await u.page.getByTestId('load-more').click();
    await expect(u.page.locator('.notifications-page__error').getByRole('button', { name: 'Retry' })).toBeVisible();
    await expect(u.page.getByTestId('notification')).toHaveCount(20);
    await u.page.unroute('**/api/notifications?*cursor=*');
    await u.page.locator('.notifications-page__error').getByRole('button', { name: 'Retry' }).click();
    await expect(u.page.getByTestId('notification')).toHaveCount(25);
    // mark-all-read fails
    await u.page.route('**/api/notifications/read-all', (r) => r.fulfill({ status: 500, contentType: 'application/problem+json', body: JSON.stringify({ status: 500, code: 'INTERNAL_ERROR', title: 'x' }) }));
    await u.page.getByTestId('mark-all-read').click();
    await expect(u.page.getByTestId('notifications-action-error')).toBeVisible();
    await expect(u.page.getByTestId('notification').first()).toHaveAttribute('data-read', 'false');
    await shot(u.page, 'ac26-action-error');
    // click a notification whose read call fails
    await u.page.route('**/api/notifications/*/read', (r) => r.fulfill({ status: 500, contentType: 'application/problem+json', body: JSON.stringify({ status: 500, code: 'INTERNAL_ERROR', title: 'x' }) }));
    await u.page.getByTestId('notification').first().locator('.notification__main').click();
    await expect(u.page.getByTestId('notifications-action-error')).toBeVisible();
  });

  test('AC26: bell survives a failing count endpoint (no badge, no crash)', async ({ kit }) => {
    const u = await kit.newUser({ browserTz: 'Asia/Tokyo' });
    kit.allowNetworkErrors = true;
    kit.allow5xx = true;
    sql(`INSERT INTO notifications (user_id, title, due_date) VALUES (${u.id}, 'Q', '2030-12-05')`);
    await u.page.route('**/api/notifications/count', (r) => r.fulfill({ status: 500, contentType: 'application/problem+json', body: JSON.stringify({ status: 500, code: 'INTERNAL_ERROR', title: 'x' }) }));
    await gotoTasks(u.page);
    await expect(u.page.getByTestId('notifications-link')).toBeVisible();
    await expect(u.page.getByTestId('notifications-badge')).toHaveCount(0);
    await u.page.unroute('**/api/notifications/count');
    await u.page.getByRole('link', { name: 'Invitations' }).click();
    await expect(u.page.getByTestId('notifications-badge')).toHaveText('1');
  });

  test('AC26: Account time zone and reminder-emails errors / pending states', async ({ kit }) => {
    const u = await kit.newUser({ zone: 'Europe/Bucharest', browserTz: 'Europe/Bucharest' });
    kit.allowNetworkErrors = true;
    kit.allow5xx = true;
    await u.page.goto('/account');
    await expect(u.page.locator('#account-time-zone')).toHaveValue('Europe/Bucharest');
    // time zone: server error
    await u.page.route('**/api/account/time-zone', (r) => r.fulfill({ status: 500, contentType: 'application/problem+json', body: JSON.stringify({ status: 500, code: 'INTERNAL_ERROR', title: 'x' }) }));
    await u.page.locator('#account-time-zone').fill('Asia/Tokyo');
    await u.page.locator('app-time-zone-form').getByRole('button', { name: 'Save' }).click();
    await expect(u.page.locator('app-time-zone-form .notice--error, app-time-zone-form .field-error')).toBeVisible();
    await shot(u.page, 'ac26-zone-error');
    expect((await me(u.page))['timeZone']).toBe('Europe/Bucharest');
    await u.page.unroute('**/api/account/time-zone');
    // pending: slow response keeps the button busy (no double submit)
    let puts = 0;
    await u.page.route('**/api/account/time-zone', async (r) => {
      puts++;
      await new Promise((res) => setTimeout(res, 1200));
      await r.continue();
    });
    await u.page.locator('app-time-zone-form').getByRole('button', { name: 'Save' }).dblclick();
    await expect(u.page.locator('app-time-zone-form').getByTestId('spinner')).toBeVisible();
    await waitFor(async () => (await me(u.page))['timeZone'] === 'Asia/Tokyo', 'saved after delay');
    expect(puts).toBe(1);
    await u.page.unroute('**/api/account/time-zone');
    // reminder emails: error reverts
    await u.page.route('**/api/account/reminder-emails', (r) => r.fulfill({ status: 500, contentType: 'application/problem+json', body: JSON.stringify({ status: 500, code: 'INTERNAL_ERROR', title: 'x' }) }));
    await u.page.locator('#account-reminder-emails').uncheck();
    await expect(u.page.locator('app-reminder-emails-toggle .notice--error')).toBeVisible();
    await expect(u.page.locator('#account-reminder-emails')).toBeChecked();
    expect((await me(u.page))['reminderEmails']).toBe(true);
    await shot(u.page, 'ac26-emails-error');
  });

  test('AC26: edit dialog shows server field errors for dueTime and reminder, keeps input; save error offers Retry', async ({ kit }) => {
    const u = await kit.newUser({ browserTz: 'Asia/Tokyo' });
    kit.allowNetworkErrors = true;
    await mkTask(u, { title: 'DlgErr', dueDate: '2030-12-06' });
    await gotoTasks(u.page);
    await openEdit(u.page, 'DlgErr');
    await editDialog(u.page).locator('#task-edit-due-time').fill('10:00');
    await editDialog(u.page).locator('#task-edit-reminder').selectOption('AT_DUE');
    await u.page.route('**/api/tasks/*', (r) =>
      r.request().method() === 'PUT'
        ? r.fulfill({
            ...PROBLEM('VALIDATION_FAILED', 'reminder', 'INVALID_REMINDER', 'Reminder is not valid for this task'),
            body: JSON.stringify({
              type: 'about:blank', title: 'Bad Request', status: 400, detail: 'Validation failed', code: 'VALIDATION_FAILED',
              errors: [
                { field: 'reminder', code: 'INVALID_REMINDER', message: 'Reminder is not valid for this task' },
                { field: 'dueTime', code: 'REQUIRES_DUE_DATE', message: 'A due time needs a due date' },
              ],
            }),
          })
        : r.continue(),
    );
    await editDialog(u.page).getByRole('button', { name: 'Save' }).click();
    await expect(u.page.locator('#task-edit-reminder-error')).toBeVisible();
    await expect(u.page.locator('#task-edit-due-time-error')).toBeVisible();
    await expect(editDialog(u.page).locator('#task-edit-due-time')).toHaveValue('10:00');
    await shot(u.page, 'ac26-dialog-errors');
    await u.page.unroute('**/api/tasks/*');
    // network failure -> alert with Retry; Retry works
    await editDialog(u.page).locator('#task-edit-reminder').selectOption('ONE_HOUR'); // fixes the field the server rejected
    await editDialog(u.page).locator('#task-edit-due-time').fill('10:30');
    await u.page.route('**/api/tasks/*', (r) => (r.request().method() === 'PUT' ? r.abort() : r.continue()));
    await editDialog(u.page).getByRole('button', { name: 'Save' }).click();
    const retry = editDialog(u.page).getByRole('alert').getByRole('button', { name: 'Retry' });
    await expect(retry).toBeVisible();
    await u.page.unroute('**/api/tasks/*');
    await retry.click();
    await expect(editDialog(u.page)).toBeHidden();
    await expect(rowOf(u.page, 'DlgErr').locator('.task-row__due')).toHaveText('Due 2030-12-06 10:30');
  });

  test('AC26: double-click Save in the edit dialog sends one PUT', async ({ kit }) => {
    const u = await kit.newUser({ browserTz: 'Asia/Tokyo' });
    await mkTask(u, { title: 'Dbl', dueDate: '2030-12-07', dueTime: '10:00' });
    await gotoTasks(u.page);
    let puts = 0;
    await u.page.route('**/api/tasks/*', async (r) => {
      if (r.request().method() === 'PUT') {
        puts++;
        await new Promise((res) => setTimeout(res, 600));
      }
      await r.continue();
    });
    await openEdit(u.page, 'Dbl');
    await editDialog(u.page).locator('#task-edit-reminder').selectOption('ONE_HOUR');
    await editDialog(u.page).getByRole('button', { name: 'Save' }).dblclick();
    await expect(editDialog(u.page)).toBeHidden();
    expect(puts).toBe(1);
  });
});

// =====================================================================================
// Concurrency (AC20, AC24), SMTP failure (AC22), cap (AC14), retention (AC18)
// =====================================================================================

test.describe('concurrency', () => {
  test('AC24: PUT / DELETE / leave / zone changes while reminders fire -- no 5xx, no double notification, nothing for tasks deleted before the claim', async ({ kit }) => {
    test.setTimeout(240_000);
    kit.allowNetworkErrors = true;
    const owner = await kit.newUser({ tag: 'sown' });
    const members = [await kit.newUser({ tag: 'sm1' }), await kit.newUser({ tag: 'sm2' })];
    const list = await createList(owner.page, `Stress ${uid()}`);
    for (const m of members) await share(owner, list.id, m);
    const statuses: number[] = [];
    const call = async (p: Page, method: string, path: string, data?: unknown) => {
      const r = await api(p, method, path, data);
      statuses.push(r.status());
      return r;
    };
    // deterministic part: deleted before the claim never notifies / mails
    const pre = await Promise.all(
      Array.from({ length: 8 }, (_, i) => mkTask(owner, { title: `Pre${i}-${uid()}`, listId: list.id, dueDate: '2030-12-10', dueTime: '10:00', reminder: 'AT_DUE' })),
    );
    for (const t of pre.slice(0, 4)) await call(owner.page, 'DELETE', `/api/tasks/${t.id}`);
    pokeDue(pre.map((t) => t.id), 1);

    // racing part (set A): firing vs title edits + deletes
    const setA: Task[] = [];
    for (let i = 0; i < 40; i++) setA.push(await mkTask(owner, { title: `RaceA${i}-${RUN}`, listId: list.id, dueDate: '2030-12-11', dueTime: '10:00', reminder: 'AT_DUE' }));
    const setB: Task[] = [];
    for (let i = 0; i < 30; i++) {
      const t = await mkTask(members[i % 2], { title: `RaceB${i}-${RUN}`, listId: list.id, dueDate: '2030-12-12', dueTime: '10:00', reminder: 'ONE_HOUR' });
      setB.push(t);
    }
    const ops: Promise<unknown>[] = [];
    pokeDue(setA.map((t) => t.id), 1);
    setA.forEach((t, i) => {
      if (i % 4 === 0) ops.push(call(owner.page, 'DELETE', `/api/tasks/${t.id}`));
      else ops.push(call(owner.page, 'PUT', `/api/tasks/${t.id}`, { title: `RaceA${i}-${RUN}`, description: `edit ${i}`, dueDate: '2030-12-11' }));
    });
    // set B: zone flips + moving + leaving while their reminders are poked
    pokeDue(setB.map((t) => t.id), 1);
    ops.push(
      (async () => {
        for (let k = 0; k < 6; k++) {
          for (const m of members) await call(m.page, 'PUT', '/api/account/time-zone', { timeZone: k % 2 ? 'Asia/Tokyo' : 'America/Los_Angeles' });
        }
      })(),
    );
    ops.push(call(members[0].page, 'POST', `/api/lists/${list.id}/leave`));
    setB.slice(0, 10).forEach((t, i) => ops.push(call(owner.page, 'DELETE', `/api/tasks/${t.id}`).then(() => i)));
    await Promise.all(ops);
    await owner.page.waitForTimeout(8000);
    const bad = statuses.filter((s) => s >= 500);
    console.log(`AC24: ${statuses.length} calls, statuses:`, JSON.stringify(Object.fromEntries(statuses.map((s) => [s, statuses.filter((x) => x === s).length]))));
    expect(bad, 'no 5xx').toEqual([]);
    expect(kit.serverErrors, 'no 5xx seen by browsers').toEqual([]);
    // no task title notified twice for the same user (set A edits/deletes do not re-arm)
    const dup = sql(
      `SELECT CONCAT(user_id, ':', title, ':', COUNT(*)) FROM notifications WHERE title LIKE 'RaceA%-${RUN}' OR title LIKE 'Pre%' AND user_id = ${owner.id} GROUP BY user_id, title HAVING COUNT(*) > 1`,
    );
    expect(dup, 'duplicate notifications').toBe('');
    // deleted before the claim: 4 Pre tasks deleted first -> exactly the 4 survivors notified
    const preTitles = sql(`SELECT title FROM notifications WHERE user_id = ${owner.id} AND title LIKE 'Pre%' ORDER BY title`).split('\n').filter(Boolean);
    expect(preTitles).toEqual(pre.slice(4).map((t) => t.title).sort());
    // no reminder left pending/in limbo for set A
    // (a title edit recomputes the artificial past fire_at back to 2030 -> PENDING with a future time is legitimate)
    expect(sqlNum(`SELECT COUNT(*) FROM reminders WHERE task_id IN (${setA.map((t) => t.id).join(',')}) AND status = 'PENDING' AND next_attempt_at < UTC_TIMESTAMP(6) - INTERVAL 20 SECOND`)).toBe(0);
    // mails: at most one per notified title for the verified owner
    const mails = await mailsTo(owner.email);
    const subjects = mails.map((m) => m.Subject);
    const dupMail = subjects.filter((s, i) => subjects.indexOf(s) !== i);
    expect(dupMail, 'duplicate emails').toEqual([]);
  });

  test('AC20: two backend instances (8081 + 8082) on one database -- every reminder fires exactly once', async ({ kit }) => {
    test.skip(!SECOND, 'set E2E_SECOND=1 with a second backend on :8082 (same env)');
    test.setTimeout(240_000);
    const second = await fetch('http://localhost:8082/api/auth/me').then((r) => r.status);
    expect(second, 'second instance reachable').toBe(401);
    const users: U[] = [];
    const tasks: Task[] = [];
    for (let i = 0; i < 4; i++) {
      const u = await kit.newUser({ tag: `two${i}`, verified: i === 0 });
      users.push(u);
      for (let k = 0; k < 40; k++) tasks.push(await mkTask(u, { title: `Two-${i}-${k}-${RUN}`, dueDate: '2030-12-15', dueTime: '10:00', reminder: 'AT_DUE' }));
    }
    const ids = tasks.map((t) => t.id).join(',');
    sql(`UPDATE reminders SET fire_at = UTC_TIMESTAMP(6) - INTERVAL 1 MINUTE, next_attempt_at = UTC_TIMESTAMP(6) - INTERVAL 1 MINUTE WHERE task_id IN (${ids})`);
    await waitFor(() => sqlNum(`SELECT COUNT(*) FROM reminders WHERE task_id IN (${ids}) AND status = 'PENDING'`) === 0, 'all processed', 90_000);
    expect(sqlNum(`SELECT COUNT(*) FROM notifications WHERE user_id IN (${users.map((u) => u.id).join(',')})`)).toBe(160);
    expect(sql(`SELECT COUNT(*) FROM (SELECT user_id, title FROM notifications WHERE user_id IN (${users.map((u) => u.id).join(',')}) GROUP BY user_id, title HAVING COUNT(*) > 1) d`)).toBe('0');
    await waitFor(async () => (await mailsTo(users[0].email)).length >= 40, '40 mails', 60_000);
    await users[0].page.waitForTimeout(4000);
    const mails = await mailsTo(users[0].email);
    expect(mails).toHaveLength(40);
    expect(new Set(mails.map((m) => m.Subject)).size).toBe(40);
    // both schedulers really ran: the 8082 instance answers and shares the DB
    expect(sqlNum(`SELECT COUNT(*) FROM reminders WHERE task_id IN (${ids}) AND status IN ('SENT')`)).toBe(160);
  });

  test('AC22: SMTP down -- notification still exists, email retried up to 3 times, then FAILED; others keep going; no 500', async ({ kit }) => {
    test.skip(!MAIL_DOWN, 'set E2E_MAIL_DOWN=1 (stops the Mailpit container during the test). Run alone.');
    test.setTimeout(180_000);
    const u = await kit.newUser({ tag: 'smtp' });
    const t1 = await mkTask(u, { title: `Smtp1 ${uid()}`, dueDate: '2030-12-16', dueTime: '10:00', reminder: 'AT_DUE' });
    const t2 = await mkTask(u, { title: `Smtp2 ${uid()}`, dueDate: '2030-12-16', dueTime: '11:00', reminder: 'AT_DUE' });
    const noMail = await kit.newUser({ tag: 'smtpoff', verified: false });
    const t3 = await mkTask(noMail, { title: `Smtp3 ${uid()}`, dueDate: '2030-12-16', dueTime: '12:00', reminder: 'AT_DUE' });
    execFileSync('docker', ['compose', 'stop', 'mail'], { cwd: '..', stdio: 'ignore' });
    try {
      pokeDue([t1.id, t2.id, t3.id], 1);
      await waitFor(() => notifCount(u.id) === 2 && notifCount(noMail.id) === 1, 'notifications exist despite SMTP down');
      expect(remStatus(t3.id, noMail.id)).toBe('SENT'); // no email needed -> done
      await waitFor(() => sqlNum(`SELECT attempts FROM reminders WHERE task_id = ${t1.id}`) >= 1, 'first attempt');
      expect(remStatus(t1.id, u.id)).toBe('PENDING');
      for (let round = 0; round < 3; round++) {
        sql(`UPDATE reminders SET next_attempt_at = UTC_TIMESTAMP(6) - INTERVAL 1 SECOND WHERE task_id IN (${t1.id}, ${t2.id}) AND status = 'PENDING'`);
        await u.page.waitForTimeout(4000);
      }
      await waitFor(() => remStatus(t1.id, u.id) === 'FAILED' && remStatus(t2.id, u.id) === 'FAILED', 'FAILED after 3 attempts');
      expect(sqlNum(`SELECT attempts FROM reminders WHERE task_id = ${t1.id}`)).toBe(3);
      expect(notifCount(u.id)).toBe(2); // not duplicated by retries
      expect((await api(u.page, 'GET', '/api/tasks')).status()).toBe(200);
      const log = process.env['E2E_BACKEND_LOG'] ?? process.env['BACKEND_LOG'];
      if (log) {
        const text = readFileSync(log, 'utf8');
        expect(text).toMatch(/Reminder email failed for reminder \d+ after 3 attempts/);
        const lines = text.split('\n').filter((l) => l.includes('ReminderProcessor'));
        expect(lines.length).toBeGreaterThan(0);
        expect(lines.join('\n')).not.toContain(u.email); // the failure log never names the address (register audit lines do, legitimately)
        expect(lines.join('\n')).not.toContain('Smtp1');
      }
    } finally {
      execFileSync('docker', ['compose', 'start', 'mail'], { cwd: '..', stdio: 'ignore' });
      await waitFor(async () => (await fetch(`${MAILPIT}/api/v1/info`).then((r) => r.ok).catch(() => false)), 'Mailpit back', 40_000);
    }
  });

  test('AC14: hourly cap per account (default 20) is silent -- notifications for all, mails capped', async ({ kit }) => {
    test.skip(!CAP, 'set E2E_CAP=1 with a backend on DEFAULT RATE_LIMIT_REMINDER_EMAILS_PER_ACCOUNT_PER_HOUR (single instance)');
    test.setTimeout(120_000);
    const u = await kit.newUser({ tag: 'cap' });
    const tasks: Task[] = [];
    for (let i = 0; i < 25; i++) tasks.push(await mkTask(u, { title: `Cap${i} ${RUN}`, dueDate: '2030-12-17', dueTime: '10:00', reminder: 'AT_DUE' }));
    pokeDue(tasks.map((t) => t.id), 1);
    await waitFor(() => notifCount(u.id) === 25 && tasks.every((t) => remStatus(t.id, u.id) !== 'PENDING'), 'all processed');
    await u.page.waitForTimeout(2000);
    expect((await mailsTo(u.email)).length).toBe(20);
    expect(await unread(u.page)).toBe(25);
  });

  test('AC18: notifications older than 90 days are deleted by the hourly job (first run ~1 min after start)', async ({ kit }) => {
    test.skip(!CAP, 'runs in the E2E_CAP phase (fresh backend start): the purge job first runs 1 minute after startup');
    test.setTimeout(150_000);
    const u = await kit.newUser({ tag: 'old' });
    sql(
      `INSERT INTO notifications (user_id, title, due_date, created_at) VALUES (${u.id}, 'Old91', '2030-01-01', UTC_TIMESTAMP(6) - INTERVAL 91 DAY), (${u.id}, 'Old89', '2030-01-01', UTC_TIMESTAMP(6) - INTERVAL 89 DAY)`,
    );
    await waitFor(() => notifCount(u.id) === 1, 'old notification purged', 130_000);
    expect((await notifs(u.page)).items.map((n) => n.title)).toEqual(['Old89']);
  });
});

void SPECIAL;
