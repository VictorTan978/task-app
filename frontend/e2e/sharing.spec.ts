import { APIResponse, Browser, BrowserContext, expect, Locator, Page, Response, test as base } from '@playwright/test';
import { execFileSync } from 'node:child_process';

/*
 * E2E for specs/sharing-team-lists.md Part B (T3b: Sharing, AC18-AC32, AC34), contract 0.13.0.
 * AC33 (concurrency) is in sharing-stress.spec.ts.
 * Needs `docker compose up -d db mail oidc` (DB via `docker compose exec db mysql`, Mailpit on :8025, fake Google).
 * Default run: every RATE_LIMIT_* at 100000. E2E_RATE_LIMIT=1 runs ONLY the real-limit tests (invites per user 30/h,
 * silent invite emails 5/h per recipient) against a freshly started backend with default limits.
 * Every test fails on browser console errors (other than the browser's own 4xx network lines), page errors or 5xx.
 */

const RUN = `${Date.now()}-${Math.floor(Math.random() * 1e6)}`;
const SHOTS = '../test-results/sharing';
const PASSWORD = 'password123';
const BASE = process.env['E2E_BASE_URL'] ?? 'http://localhost:4200';
const MAILPIT = process.env['E2E_MAILPIT'] ?? 'http://localhost:8025';
const OIDC = 'http://localhost:8090/default';
const REAL_RL = process.env['E2E_RATE_LIMIT'] === '1';
const OTHER_MODE = ['E2E_SHORT_SESSIONS', 'E2E_SHORT_LINKS', 'E2E_MAIL_DOWN', 'E2E_GOOGLE_OFF'].some((k) => process.env[k] === '1');
let counter = 0;
function uid(): string {
  counter += 1;
  return `${RUN}-${counter}`;
}
function uniqueEmail(tag: string): string {
  return `qa-t3b-${tag}-${uid()}@example.com`;
}

const TASK_KEYS = ['addedBy', 'createdAt', 'description', 'done', 'dueDate', 'dueTime', 'id', 'listId', 'myReminder', 'myReminderPassed', 'priority', 'status', 'tags', 'title', 'updatedAt']; // 0.14.0 (T4a): dueTime, myReminder, myReminderPassed
const LIST_KEYS = ['id', 'memberCount', 'name', 'personal', 'role'];
const MEMBER_KEYS = ['displayName', 'email', 'joinedAt', 'role', 'userId'];
const LIST_INV_KEYS = ['email', 'expiresAt', 'id', 'invitedAt'];
const MY_INV_KEYS = ['expiresAt', 'id', 'invitedAt', 'invitedBy', 'list'];
const INVALID = 'This invitation is no longer valid';

interface TaskList {
  id: number;
  name: string;
  personal: boolean;
  role: 'OWNER' | 'MEMBER';
  memberCount: number;
}
interface Task {
  id: number;
  listId: number;
  addedBy: { displayName: string } | null;
  title: string;
  description: string | null;
  dueDate: string | null;
  tags: string[];
  status: string;
}
interface ListInvitation {
  id: number;
  email: string;
  invitedAt: string;
  expiresAt: string;
}
interface MyInvitation {
  id: number;
  list: { id: number; name: string };
  invitedBy: { displayName: string };
  invitedAt: string;
  expiresAt: string;
}

interface Watch {
  consoleErrors: string[];
  pageErrors: string[];
  serverErrors: string[];
  allowNetworkErrors: boolean;
}
const NETWORK_LOG = [/^Failed to load resource: net::ERR_FAILED/, /^Failed to load resource: the server responded with a status of 5\d\d/];
const EXPECTED_4XX = /^Failed to load resource: the server responded with a status of (400|401|403|404|409|429)/;

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
async function expectProblem(res: APIResponse, status: number, code: string, detail?: string) {
  expect(res.status(), await res.text()).toBe(status);
  expect(res.headers()['content-type']).toContain('application/problem+json');
  const body = await res.json();
  expect(body.code).toBe(code);
  if (detail) expect(body.detail).toBe(detail);
  return body;
}

interface User {
  ctx: BrowserContext;
  page: Page;
  w: Watch;
  id: number;
  email: string;
  displayName: string;
}

async function register(page: Page, tag: string, verified: boolean, email = uniqueEmail(tag)) {
  const displayName = `${tag} ${uid()}`;
  const res = await page.request.post('/api/auth/register', {
    data: { displayName, email, password: PASSWORD },
    headers: { 'X-XSRF-TOKEN': await xsrf(page) },
  });
  expect(res.status(), await res.text()).toBe(201);
  const id = ((await res.json()) as { id: number }).id;
  if (verified) verify(id);
  return { id, email, displayName };
}
function verify(id: number) {
  sql(`UPDATE users SET email_verified_at = NOW(6) WHERE id = ${id}`);
}
async function newUser(browser: Browser, tag: string, verified = true): Promise<User> {
  const ctx = await browser.newContext({ baseURL: BASE });
  const page = await ctx.newPage();
  const w: Watch = { consoleErrors: [], pageErrors: [], serverErrors: [], allowNetworkErrors: false };
  watchPage(page, w);
  return { ctx, page, w, ...(await register(page, tag, verified)) };
}
async function closeAll(...users: User[]) {
  for (const u of users) {
    checkWatch(u.w, u.displayName);
    await u.ctx.close();
  }
}
async function adminPage(browser: Browser): Promise<{ ctx: BrowserContext; page: Page; id: number }> {
  const ctx = await browser.newContext({ baseURL: BASE });
  const page = await ctx.newPage();
  const u = await register(page, 'adm', true);
  sql(`UPDATE users SET role = 'ADMIN' WHERE id = ${u.id}`);
  await page.context().clearCookies();
  const r = await page.request.post('/api/auth/login', {
    data: { email: u.email, password: PASSWORD },
    headers: { 'X-XSRF-TOKEN': await xsrf(page) },
  });
  expect(r.status()).toBe(200);
  return { ctx, page, id: u.id };
}

async function lists(page: Page): Promise<TaskList[]> {
  const body = await json<TaskList[]>(await page.request.get('/api/lists'));
  for (const l of body) expect(Object.keys(l).sort()).toEqual(LIST_KEYS);
  return body;
}
async function createList(page: Page, name: string): Promise<TaskList> {
  return json<TaskList>(await api(page, 'POST', '/api/lists', { name }), 201);
}
async function createTask(page: Page, title: string, listId: number, tags?: string[]): Promise<Task> {
  let t = await json<Task>(await api(page, 'POST', '/api/tasks', { title, listId }), 201);
  expect(Object.keys(t).sort()).toEqual(TASK_KEYS);
  if (tags) t = await json<Task>(await api(page, 'PUT', `/api/tasks/${t.id}`, { title, tags }));
  return t;
}
async function invite(page: Page, listId: number, email: string): Promise<APIResponse> {
  return api(page, 'POST', `/api/lists/${listId}/invitations`, { email });
}
async function myInvitations(page: Page): Promise<MyInvitation[]> {
  const body = await json<MyInvitation[]>(await page.request.get('/api/invitations'));
  for (const i of body) expect(Object.keys(i).sort()).toEqual(MY_INV_KEYS);
  return body;
}
async function invitationFor(page: Page, listId: number): Promise<MyInvitation> {
  const inv = (await myInvitations(page)).find((i) => i.list.id === listId);
  expect(inv, `invitation to list ${listId}`).toBeTruthy();
  return inv!;
}
/** Owner invites the member and the member accepts (API). */
async function share(owner: User, listId: number, member: User): Promise<void> {
  await json(await invite(owner.page, listId, member.email), 201);
  const inv = await invitationFor(member.page, listId);
  const l = await json<TaskList>(await api(member.page, 'POST', `/api/invitations/${inv.id}/accept`));
  expect(l).toMatchObject({ id: listId, role: 'MEMBER' });
}
function expireInDb(invitationId: number) {
  sql(
    `UPDATE list_invitations SET invited_at = NOW(6) - INTERVAL 15 DAY, expires_at = NOW(6) - INTERVAL 1 DAY WHERE id = ${invitationId}`,
  );
}

// Mailpit
interface MailSummary {
  ID: string;
  Subject: string;
  To: { Address: string }[];
}
async function mailsTo(page: Page, to: string): Promise<MailSummary[]> {
  const r = await page.request.get(`${MAILPIT}/api/v1/search?query=${encodeURIComponent(`to:"${to}"`)}`);
  expect(r.status()).toBe(200);
  return ((await r.json()) as { messages: MailSummary[] }).messages;
}
async function invitationMails(page: Page, to: string): Promise<MailSummary[]> {
  return (await mailsTo(page, to)).filter((m) => m.Subject.includes('invited you to a list'));
}
async function mailBody(page: Page, id: string): Promise<{ Text: string; HTML: string; Subject: string }> {
  return (await page.request.get(`${MAILPIT}/api/v1/message/${id}`)).json();
}
function delay(ms: number) {
  return new Promise((r) => setTimeout(r, ms));
}

// UI
function heading(page: Page): Locator {
  return page.locator('h1.list-header__name');
}
function picker(page: Page): Locator {
  return page.getByRole('navigation', { name: 'Lists' });
}
function menuToggle(page: Page): Locator {
  return page.getByRole('button', { name: 'List actions' });
}
function menuItems(page: Page): Locator {
  return page.locator('.list-menu__items').getByRole('button');
}
function menuItem(page: Page, name: string): Locator {
  return page.locator('.list-menu__items').getByRole('button', { name, exact: true });
}
function panel(page: Page): Locator {
  return page.getByTestId('members-panel');
}
function listTasks(page: Page): Locator {
  return page.getByRole('list', { name: 'Tasks' });
}
function row(page: Page, title: string): Locator {
  return listTasks(page)
    .getByRole('listitem')
    .filter({ has: page.getByRole('checkbox', { name: `Done: ${title}`, exact: true }) });
}
async function settled(page: Page) {
  await expect(page.getByText('Loading tasks…')).toHaveCount(0);
  await expect(page.getByText('Loading lists…')).toHaveCount(0);
}
async function gotoList(page: Page, l: TaskList | { id: number; name: string }) {
  await page.goto(`/lists/${l.id}`);
  await expect(heading(page)).toHaveText(l.name);
  await settled(page);
}
async function openMembers(page: Page) {
  await menuToggle(page).click();
  await menuItem(page, 'Members').click();
  await expect(panel(page)).toBeVisible();
  await expect(panel(page).getByText('Loading members…')).toHaveCount(0);
}
async function shot(page: Page, name: string) {
  await page.screenshot({ path: `${SHOTS}/${name}.png`, fullPage: true });
}
function badge(page: Page): Locator {
  return page.getByTestId('invitations-badge');
}

// =====================================================================================

test.describe('T3b Sharing (default run)', () => {
  test.skip(REAL_RL || OTHER_MODE, 'default-run tests');

  test('AC18: invite always says "Invitation sent" + pending, same API answer for any address; email only to active accounts; email content', async ({
    page,
    browser,
  }) => {
    test.setTimeout(90_000);
    await register(page, 'ac18own', true);
    const home = await createList(page, 'Home "quoted" <b>x</b>');
    const verifiedU = await newUser(browser, 'ac18ver', true);
    const unverifiedU = await newUser(browser, 'ac18unv', false);
    const disabledU = await newUser(browser, 'ac18dis', true);
    sql(`UPDATE users SET disabled_at = NOW(6) WHERE id = ${disabledU.id}`);
    const nobody = uniqueEmail('ac18nobody');

    await gotoList(page, home);
    await openMembers(page);
    await expect(panel(page).getByTestId('pending-empty')).toHaveText('No pending invitations');
    const posts: Response[] = [];
    page.on('response', (r) => {
      if (r.request().method() === 'POST' && /\/api\/lists\/\d+\/invitations$/.test(r.url())) posts.push(r);
    });
    const targets = [
      { label: 'verified', email: verifiedU.email.toUpperCase() },
      { label: 'unverified', email: ` ${unverifiedU.email} ` },
      { label: 'disabled', email: disabledU.email },
      { label: 'no account', email: nobody },
    ];
    for (const t of targets) {
      await panel(page).locator('#invite-email').fill(t.email);
      await panel(page).getByRole('button', { name: 'Send invitation' }).click();
      await expect(panel(page).getByTestId('invite-sent')).toHaveText('Invitation sent');
      await expect(panel(page).getByTestId('pending-row').filter({ hasText: t.email.trim().toLowerCase() })).toBeVisible();
      await expect(panel(page).locator('#invite-email')).toHaveValue('');
    }
    await expect(panel(page).getByTestId('pending-row')).toHaveCount(4);
    await expect(panel(page).getByTestId('pending-row').first()).toContainText('Pending · expires');
    await shot(page, 'ac18-invites-pending');

    // identical API answers: same status, headers that matter, keys; only id/email/timestamps differ
    expect(posts).toHaveLength(4);
    const shapes = await Promise.all(
      posts.map(async (r) => {
        const b = (await r.json()) as ListInvitation;
        expect(Object.keys(b).sort()).toEqual(LIST_INV_KEYS);
        expect(new Date(b.expiresAt).getTime() - new Date(b.invitedAt).getTime()).toBe(14 * 24 * 3600 * 1000);
        return { status: r.status(), type: r.headers()['content-type'], location: r.headers()['location'] ?? null, keys: Object.keys(b).sort() };
      }),
    );
    for (const s of shapes) expect(s).toEqual(shapes[0]);
    expect(shapes[0]).toEqual({ status: 201, type: expect.stringContaining('application/json'), location: null, keys: LIST_INV_KEYS });
    // DB: stored the same way (one row each, lowercased email)
    expect(num(`SELECT COUNT(*) FROM list_invitations WHERE list_id = ${home.id}`)).toBe(4);
    expect(num(`SELECT COUNT(*) FROM list_invitations WHERE list_id = ${home.id} AND email <> LOWER(TRIM(email))`)).toBe(0);

    // timing: invite answers do not depend on whether an account exists (email is sent after the response)
    const timing: Record<string, number[]> = { account: [], none: [] };
    const big = await createList(page, 'Timing');
    for (let i = 0; i < 6; i += 1) {
      const acct = await newUser(browser, `ac18t${i}`, true);
      for (const [k, email] of [
        ['account', acct.email],
        ['none', uniqueEmail(`ac18tn${i}`)],
      ] as const) {
        const t0 = Date.now();
        expect((await invite(page, big.id, email)).status()).toBe(201);
        timing[k].push(Date.now() - t0);
      }
      await acct.ctx.close();
    }
    const med = (a: number[]) => [...a].sort((x, y) => x - y)[Math.floor(a.length / 2)];
    console.log(`AC18 invite timing ms: account ${JSON.stringify(timing['account'])} none ${JSON.stringify(timing['none'])}`);
    expect(Math.abs(med(timing['account']) - med(timing['none']))).toBeLessThan(100);

    // emails: verified + unverified get one; disabled and no-account get none
    await expect.poll(async () => (await invitationMails(page, verifiedU.email)).length, { timeout: 15_000 }).toBe(1);
    await expect.poll(async () => (await invitationMails(page, unverifiedU.email)).length, { timeout: 15_000 }).toBe(1);
    await delay(2000);
    expect(await invitationMails(page, disabledU.email)).toHaveLength(0);
    expect(await mailsTo(page, nobody)).toHaveLength(0);

    const [m] = await invitationMails(page, verifiedU.email);
    const owner = (await (await page.request.get('/api/auth/me')).json()) as { displayName: string };
    const body = await mailBody(page, m.ID);
    expect(body.Subject).toBe(`${owner.displayName} invited you to a list on Task App`);
    expect(body.Text).toContain(`invited you to the list "Home "quoted" <b>x</b>" on Task App`);
    expect(body.HTML).toContain('&quot;Home &quot;quoted&quot; &lt;b&gt;x&lt;/b&gt;&quot;');
    expect(body.HTML).not.toContain('<b>x</b>');
    const links = [...body.HTML.matchAll(/href="([^"]+)"/g)].map((x) => x[1]);
    expect(new Set(links)).toEqual(new Set([`${new URL(process.env['APP_BASE_URL'] ?? BASE).origin}/invitations`]));
    const textUrls = body.Text.match(/https?:\/\/\S+/g) ?? [];
    expect(new Set(textUrls)).toEqual(new Set([`${new URL(BASE).origin}/invitations`]));

    // line breaks in the list name are removed from the email
    const nl = await createList(page, 'Line one\nLine two\r\nBcc: evil@example.com');
    const nlU = await newUser(browser, 'ac18nl', true);
    expect((await invite(page, nl.id, nlU.email)).status()).toBe(201);
    await expect.poll(async () => (await invitationMails(page, nlU.email)).length, { timeout: 15_000 }).toBe(1);
    const nlBody = await mailBody(page, (await invitationMails(page, nlU.email))[0].ID);
    const quoted = nlBody.Text.match(/the list "([^"]*)"/)![1];
    expect(quoted).not.toMatch(/[\r\n]/);
    expect(quoted).toContain('Line one');
    expect(quoted).toContain('Line two');
    const raw = await (await page.request.get(`${MAILPIT}/api/v1/message/${(await invitationMails(page, nlU.email))[0].ID}/headers`)).json();
    expect(raw['Bcc'] ?? []).toEqual([]);
    expect(await mailsTo(page, 'evil@example.com')).toHaveLength(0);

    await closeAll(verifiedU, unverifiedU, disabledU, nlU);
  });

  test('AC18/AC21: an unverified invitee sees nothing ("Confirm your email to see invitations") until verified; then sees it', async ({
    page,
    browser,
  }) => {
    await register(page, 'ac18own2', true);
    const l = await createList(page, 'Unverified case');
    const u = await newUser(browser, 'ac18u', false);
    expect((await invite(page, l.id, u.email)).status()).toBe(201);
    expect(await myInvitations(u.page)).toEqual([]);
    expect(await json(await u.page.request.get('/api/invitations/count'))).toEqual({ count: 0 });
    const invId = num(`SELECT id FROM list_invitations WHERE list_id = ${l.id}`);
    await expectProblem(await api(u.page, 'POST', `/api/invitations/${invId}/accept`), 404, 'INVITATION_NOT_FOUND', INVALID);
    await expectProblem(await api(u.page, 'POST', `/api/invitations/${invId}/decline`), 404, 'INVITATION_NOT_FOUND', INVALID);
    await u.page.goto('/invitations');
    await expect(u.page.getByTestId('invitations-unverified')).toContainText('Confirm your email to see invitations');
    await expect(badge(u.page)).toHaveCount(0);
    await shot(u.page, 'ac21-unverified');
    verify(u.id);
    await u.page.reload();
    await expect(u.page.getByTestId('invitation')).toHaveCount(1);
    await expect(badge(u.page)).toHaveText('1');
    await closeAll(u);
  });

  test('AC19: 20 pending max (INVITATION_LIMIT), duplicates (ALREADY_INVITED ignoring case), members (ALREADY_MEMBER), validation; messages next to the field', async ({
    page,
    browser,
  }) => {
    await register(page, 'ac19own', true);
    const me = (await (await page.request.get('/api/auth/me')).json()) as { email: string };
    const l = await createList(page, 'Pending limit');
    const m = await newUser(browser, 'ac19m', true);
    await share({ ctx: page.context(), page, w: { consoleErrors: [], pageErrors: [], serverErrors: [], allowNetworkErrors: false }, id: 0, email: me.email, displayName: '' }, l.id, m);
    for (let i = 0; i < 20; i += 1) expect((await invite(page, l.id, uniqueEmail(`ac19p${i}`))).status()).toBe(201);
    const before = num(`SELECT COUNT(*) FROM list_invitations WHERE list_id = ${l.id}`);
    expect(before).toBe(20);
    const over = await expectProblem(await invite(page, l.id, uniqueEmail('ac19over')), 409, 'INVITATION_LIMIT', 'A list can have at most 20 pending invitations');
    expect(over.errors).toBeUndefined();
    // order: ALREADY_MEMBER / ALREADY_INVITED come before the limits
    await expectProblem(await invite(page, l.id, m.email.toUpperCase()), 409, 'ALREADY_MEMBER', 'This person is already a member of the list');
    await expectProblem(await invite(page, l.id, me.email), 409, 'ALREADY_MEMBER');
    const pend = await json<ListInvitation[]>(await page.request.get(`/api/lists/${l.id}/invitations`));
    expect(pend).toHaveLength(20);
    for (const p of pend) expect(Object.keys(p).sort()).toEqual(LIST_INV_KEYS);
    await expectProblem(await invite(page, l.id, ` ${pend[0].email.toUpperCase()} `), 409, 'ALREADY_INVITED', 'This email address already has a pending invitation');
    expect(num(`SELECT COUNT(*) FROM list_invitations WHERE list_id = ${l.id}`)).toBe(20);
    // validation
    for (const [email, code] of [
      ['', 'REQUIRED'],
      ['   ', 'REQUIRED'],
      ['not-an-email', 'INVALID_EMAIL'],
      [`${'a'.repeat(64)}@${`${'b'.repeat(62)}.`.repeat(3)}co`, 'TOO_LONG'], // 256 chars, otherwise valid
    ] as const) {
      const b = await expectProblem(await invite(page, l.id, email), 400, 'VALIDATION_FAILED');
      expect(b.errors).toHaveLength(1);
      expect(b.errors[0]).toMatchObject({ field: 'email', code });
    }
    await expectProblem(await api(page, 'POST', `/api/lists/${l.id}/invitations`, { email: 5 }), 400, 'MALFORMED_REQUEST');
    await expectProblem(await api(page, 'POST', '/api/lists/abc/invitations', { email: 'a@b.co' }), 400, 'MALFORMED_REQUEST');
    // "My tasks" can't be shared
    const mine = (await lists(page)).find((x) => x.personal)!;
    await expectProblem(await invite(page, mine.id, 'x@example.com'), 409, 'PERSONAL_LIST', "Your personal list can't be shared");
    expect(await json(await page.request.get(`/api/lists/${mine.id}/invitations`))).toEqual([]);

    // UI: messages next to the field
    await gotoList(page, l);
    await openMembers(page);
    await panel(page).locator('#invite-email').fill(uniqueEmail('ac19ui'));
    await panel(page).getByRole('button', { name: 'Send invitation' }).click();
    await expect(panel(page).locator('#invite-error')).toContainText('A list can have at most 20 pending invitations');
    await panel(page).locator('#invite-email').fill(m.email);
    await panel(page).getByRole('button', { name: 'Send invitation' }).click();
    await expect(panel(page).locator('#invite-error')).toContainText('This person is already a member of the list');
    await panel(page).locator('#invite-email').fill('nope');
    await panel(page).getByRole('button', { name: 'Send invitation' }).click();
    await expect(panel(page).locator('#invite-email-error')).toBeVisible();
    await shot(page, 'ac19-limit-message');
    // UI for "My tasks": no Members invite form / Members entry has no invite
    await page.goto('/tasks');
    await settled(page);
    await menuToggle(page).click();
    // "My tasks" can't be shared: its menu has only Rename (no Members / Delete / Leave)
    await expect(menuItems(page)).toHaveText(['Rename']);
    await closeAll(m);
  });

  test('AC19: 20 members max: invite refused with MEMBER_LIMIT, accept into a full list refused (invitation kept)', async ({ page, browser }) => {
    test.setTimeout(120_000);
    const ownerInfo = await register(page, 'ac19full', true);
    const owner: User = { ctx: page.context(), page, w: { consoleErrors: [], pageErrors: [], serverErrors: [], allowNetworkErrors: false }, ...ownerInfo };
    const l = await createList(page, 'Full list');
    const late = await newUser(browser, 'ac19late', true);
    expect((await invite(page, l.id, late.email)).status()).toBe(201);
    const users: User[] = [];
    for (let i = 0; i < 19; i += 1) {
      const u = await newUser(browser, `ac19mem${i}`, true);
      await share(owner, l.id, u);
      users.push(u);
    }
    const full = (await lists(page)).find((x) => x.id === l.id)!;
    expect(full.memberCount).toBe(20);
    await expectProblem(await invite(page, l.id, uniqueEmail('ac19x')), 409, 'MEMBER_LIMIT', 'A list can have at most 20 members');
    const inv = await invitationFor(late.page, l.id);
    await expectProblem(await api(late.page, 'POST', `/api/invitations/${inv.id}/accept`), 409, 'MEMBER_LIMIT', 'A list can have at most 20 members');
    expect(num(`SELECT COUNT(*) FROM list_invitations WHERE id = ${inv.id}`)).toBe(1);
    expect(num(`SELECT COUNT(*) FROM list_members WHERE list_id = ${l.id}`)).toBe(20);
    // UI: accepting into the full list shows the message
    await late.page.goto('/invitations');
    await late.page.getByRole('button', { name: `Accept invitation to ${l.name}` }).click();
    await expect(late.page.getByTestId('invitation').getByRole('alert')).toContainText('A list can have at most 20 members');
    await shot(late.page, 'ac19-accept-full');
    // one leaves -> the kept invitation can now be accepted
    expect((await api(users[0].page, 'POST', `/api/lists/${l.id}/leave`)).status()).toBe(204);
    expect((await json<TaskList>(await api(late.page, 'POST', `/api/invitations/${inv.id}/accept`))).memberCount).toBe(20);
    for (const u of users) await u.ctx.close();
    await late.ctx.close();
  });

  test('AC20: owner cancels (UI); a cancelled invitation can\'t be accepted; cancel again -> 404', async ({ page, browser }) => {
    await register(page, 'ac20own', true);
    const l = await createList(page, 'Cancel me');
    const u = await newUser(browser, 'ac20u', true);
    expect((await invite(page, l.id, u.email)).status()).toBe(201);
    const inv = await invitationFor(u.page, l.id);
    // invitee has the page open
    await u.page.goto('/invitations');
    await expect(u.page.getByTestId('invitation')).toHaveCount(1);

    await gotoList(page, l);
    await openMembers(page);
    await panel(page).getByRole('button', { name: `Cancel invitation for ${u.email}` }).click();
    await expect(panel(page).getByTestId('pending-row')).toHaveCount(0);
    await expect(panel(page).getByTestId('pending-empty')).toHaveText('No pending invitations');
    await expectProblem(await api(page, 'DELETE', `/api/lists/${l.id}/invitations/${inv.id}`), 404, 'INVITATION_NOT_FOUND', INVALID);

    // AC22 via UI: Accept on a stale page
    await u.page.getByRole('button', { name: `Accept invitation to ${l.name}` }).click();
    await expect(u.page.getByTestId('invitations-notice')).toHaveText(INVALID);
    await expect(u.page.getByTestId('invitation')).toHaveCount(0);
    await shot(u.page, 'ac22-cancelled');
    expect((await lists(u.page)).some((x) => x.id === l.id)).toBe(false);
    await closeAll(u);
  });

  test('AC20: expiry after 14 days: not listed/counted, can\'t be accepted or declined; owner can cancel it; re-invite refreshes the same id', async ({
    page,
    browser,
  }) => {
    await register(page, 'ac20exp', true);
    const l = await createList(page, 'Expiring');
    const u = await newUser(browser, 'ac20eu', true);
    const created = await json<ListInvitation>(await invite(page, l.id, u.email), 201);
    expect(new Date(created.expiresAt).getTime() - new Date(created.invitedAt).getTime()).toBe(14 * 86400_000);
    expect(await json(await u.page.request.get('/api/invitations/count'))).toEqual({ count: 1 });
    // invitee opens the page while it's still valid
    await u.page.goto('/invitations');
    await expect(u.page.getByTestId('invitation')).toHaveCount(1);
    expireInDb(created.id);
    expect(await myInvitations(u.page)).toEqual([]);
    expect(await json(await u.page.request.get('/api/invitations/count'))).toEqual({ count: 0 });
    expect(await json(await page.request.get(`/api/lists/${l.id}/invitations`))).toEqual([]);
    // AC22: Accept on the stale page
    await u.page.getByRole('button', { name: `Accept invitation to ${l.name}` }).click();
    await expect(u.page.getByTestId('invitations-notice')).toHaveText(INVALID);
    await expectProblem(await api(u.page, 'POST', `/api/invitations/${created.id}/decline`), 404, 'INVITATION_NOT_FOUND', INVALID);
    expect((await lists(u.page)).some((x) => x.id === l.id)).toBe(false);
    // re-invite refreshes the same invitation
    const again = await json<ListInvitation>(await invite(page, l.id, u.email), 201);
    expect(again.id).toBe(created.id);
    expect(new Date(again.invitedAt).getTime()).toBeGreaterThanOrEqual(new Date(created.invitedAt).getTime());
    expect(new Date(again.expiresAt).getTime()).toBeGreaterThan(Date.now() + 13 * 86400_000);
    expect(await json(await u.page.request.get('/api/invitations/count'))).toEqual({ count: 1 });
    // an expired invitation can be cancelled (204)
    expireInDb(created.id);
    expect((await api(page, 'DELETE', `/api/lists/${l.id}/invitations/${created.id}`)).status()).toBe(204);
    await closeAll(u);
  });

  test('AC21: header badge count; page lists who invited + list; Accept joins (picker updates), Decline removes; owner sees neither in pending', async ({
    page,
    browser,
  }) => {
    const a = await newUser(browser, 'ac21a', true);
    const b = await newUser(browser, 'ac21b', true);
    const la = await createList(a.page, 'Alpha shared');
    const lb = await createList(b.page, 'Beta shared');
    await createTask(a.page, 'alpha task', la.id);
    const me = await register(page, 'ac21me', true);
    await page.goto('/tasks');
    await settled(page);
    await expect(badge(page)).toHaveCount(0);
    await expect(page.getByTestId('invitations-link')).toHaveAccessibleName('Invitations');
    expect((await invite(a.page, la.id, me.email)).status()).toBe(201);
    expect((await invite(b.page, lb.id, me.email)).status()).toBe(201);
    await page.reload();
    await expect(badge(page)).toHaveText('2');
    await expect(page.getByTestId('invitations-link')).toHaveAccessibleName('Invitations, 2 pending');
    await page.getByTestId('invitations-link').click();
    await expect(page).toHaveURL(/\/invitations$/);
    const items = page.getByTestId('invitation');
    await expect(items).toHaveCount(2);
    await expect(items.nth(0)).toContainText(`${b.displayName} invited you to Beta shared`); // newest first
    await expect(items.nth(1)).toContainText(`${a.displayName} invited you to Alpha shared`);
    await expect(items.nth(0)).toContainText('Expires');
    // no emails of the owner/members on the invitee side
    const invs = await myInvitations(page);
    expect(JSON.stringify(invs)).not.toContain(a.email);
    expect(invs[0].invitedBy).toEqual({ displayName: b.displayName });
    await shot(page, 'ac21-invitations-page');

    await page.getByRole('button', { name: 'Accept invitation to Alpha shared' }).click();
    await expect(page.getByTestId('joined')).toContainText('Joined Alpha shared.');
    await expect(badge(page)).toHaveText('1');
    await page.getByRole('button', { name: 'Decline invitation to Beta shared' }).click();
    await expect(items).toHaveCount(0);
    await expect(page.getByTestId('invitations-empty')).toHaveText('No pending invitations');
    await expect(badge(page)).toHaveCount(0);
    await shot(page, 'ac21-after-accept-decline');
    await page.getByTestId('joined').getByRole('link', { name: 'Open list' }).click();
    await expect(heading(page)).toHaveText('Alpha shared');
    await expect(row(page, 'alpha task')).toBeVisible();
    // the picker has the joined list, with its member count
    await expect(picker(page).getByRole('link', { name: /Alpha shared/ })).toBeVisible();
    await expect(picker(page).getByRole('link', { name: /Alpha shared/ }).getByTestId('list-member-count')).toContainText('2');
    await shot(page, 'ac21-joined-list');
    // owners' pending lists are empty; decline created no membership
    expect(await json(await a.page.request.get(`/api/lists/${la.id}/invitations`))).toEqual([]);
    expect(await json(await b.page.request.get(`/api/lists/${lb.id}/invitations`))).toEqual([]);
    expect(num(`SELECT COUNT(*) FROM list_members WHERE list_id = ${lb.id}`)).toBe(1);
    // decline -> owner may invite again
    expect((await invite(b.page, lb.id, me.email)).status()).toBe(201);
    await closeAll(a, b);
  });

  test('AC21/Gate 6: a signed-out visit to /invitations returns there after password sign-in, registration and Google; nothing else is remembered', async ({
    browser,
  }) => {
    test.setTimeout(90_000);
    const owner = await newUser(browser, 'ac21g6o', true);
    const l = await createList(owner.page, 'Link list');
    // password
    const u = await newUser(browser, 'ac21g6u', true);
    expect((await invite(owner.page, l.id, u.email)).status()).toBe(201);
    await u.ctx.clearCookies();
    const p = u.page;
    await p.goto('/invitations');
    await expect(p).toHaveURL(/\/login$/);
    await p.locator('#login-email').fill(u.email);
    await p.locator('#login-password').fill(PASSWORD);
    await p.getByRole('button', { name: 'Sign in' }).click();
    await expect(p).toHaveURL(/\/invitations$/);
    await expect(p.getByTestId('invitation')).toHaveCount(1);
    expect(await p.evaluate(() => sessionStorage.getItem('taskapp.afterSignIn'))).toBeNull();
    // only once: sign out and in again -> /tasks
    await p.getByRole('button', { name: 'Log out' }).click();
    await expect(p).toHaveURL(/\/login$/);
    await p.locator('#login-email').fill(u.email);
    await p.locator('#login-password').fill(PASSWORD);
    await p.getByRole('button', { name: 'Sign in' }).click();
    await expect(p).toHaveURL(/\/tasks$/);

    // abuse: forged storage values and query strings are ignored
    await p.getByRole('button', { name: 'Log out' }).click();
    await expect(p).toHaveURL(/\/login$/);
    for (const forged of ['//evil.example.com', 'https://evil.example.com/invitations', '/admin/users', '/invitations/../account', ' /invitations', '/invitations?x=1']) {
      await p.evaluate((v) => sessionStorage.setItem('taskapp.afterSignIn', v), forged);
      await p.locator('#login-email').fill(u.email);
      await p.locator('#login-password').fill(PASSWORD);
      await p.getByRole('button', { name: 'Sign in' }).click();
      await expect(p, forged).toHaveURL(/\/tasks$/);
      expect(await p.evaluate(() => sessionStorage.getItem('taskapp.afterSignIn'))).toBeNull();
      await p.getByRole('button', { name: 'Log out' }).click();
      await expect(p).toHaveURL(/\/login$/);
    }
    // a signed-out visit elsewhere is not remembered
    await p.goto('/account');
    await expect(p).toHaveURL(/\/login$/);
    await p.locator('#login-email').fill(u.email);
    await p.locator('#login-password').fill(PASSWORD);
    await p.getByRole('button', { name: 'Sign in' }).click();
    await expect(p).toHaveURL(/\/tasks$/);
    await p.getByRole('button', { name: 'Log out' }).click();
    await expect(p).toHaveURL(/\/login$/);
    // query/fragment on the link: lands on plain /invitations, never elsewhere
    await p.goto('/invitations?next=%2F%2Fevil.example.com#https://evil.example.com');
    await expect(p).toHaveURL(/\/login$/);
    expect(await p.evaluate(() => sessionStorage.getItem('taskapp.afterSignIn'))).toBe('/invitations');
    await p.locator('#login-email').fill(u.email);
    await p.locator('#login-password').fill(PASSWORD);
    await p.getByRole('button', { name: 'Sign in' }).click();
    await expect(p).toHaveURL(`${new URL(BASE).origin}/invitations`);

    // registration
    const ctx2 = await browser.newContext({ baseURL: BASE });
    const p2 = await ctx2.newPage();
    const w2: Watch = { consoleErrors: [], pageErrors: [], serverErrors: [], allowNetworkErrors: false };
    watchPage(p2, w2);
    const regEmail = uniqueEmail('ac21g6r');
    expect((await invite(owner.page, l.id, regEmail)).status()).toBe(201);
    await p2.goto('/invitations');
    await expect(p2).toHaveURL(/\/login$/);
    await p2.goto('/register');
    await p2.locator('#register-display-name').fill(`G6 reg ${uid()}`);
    await p2.locator('#register-email').fill(regEmail);
    await p2.locator('#register-password').fill(PASSWORD);
    await p2.locator('#register-confirm-password').fill(PASSWORD);
    await p2.getByRole('button', { name: 'Create account' }).click();
    await expect(p2).toHaveURL(/\/invitations$/);
    await expect(p2.getByTestId('invitations-unverified')).toContainText('Confirm your email to see invitations');
    // registering after being invited + confirming within 14 days: sees it (no email was sent)
    sql(`UPDATE users SET email_verified_at = NOW(6) WHERE email = '${regEmail}'`);
    await p2.reload();
    await expect(p2.getByTestId('invitation')).toHaveCount(1);
    checkWatch(w2, 'register path');
    await ctx2.close();

    // Google
    const ctx3 = await browser.newContext({ baseURL: BASE });
    const p3 = await ctx3.newPage();
    const w3: Watch = { consoleErrors: [], pageErrors: [], serverErrors: [], allowNetworkErrors: false };
    watchPage(p3, w3);
    const gEmail = uniqueEmail('ac21g6g');
    expect((await invite(owner.page, l.id, gEmail)).status()).toBe(201);
    await p3.goto('/invitations');
    await expect(p3).toHaveURL(/\/login$/);
    await p3.getByTestId('google-button').click();
    await p3.waitForURL((x) => x.href.startsWith(`${OIDC}/authorize`));
    await p3.locator('input[name="username"]').fill(`g-t3b-${uid()}`);
    await p3.locator('textarea[name="claims"]').fill(JSON.stringify({ email: gEmail, email_verified: true, name: `G T3b ${uid()}` }));
    await p3.locator('input[type="submit"]').click();
    await p3.waitForURL((x) => x.origin === new URL(BASE).origin && x.pathname === '/invitations');
    await expect(p3.getByTestId('invitation')).toHaveCount(1);
    await shot(p3, 'ac21-google-return');
    checkWatch(w3, 'google path');
    await ctx3.close();
    await closeAll(owner, u);
  });

  test('AC22: accept after the list was deleted -> "This invitation is no longer valid", nothing added', async ({ page, browser }) => {
    await register(page, 'ac22own', true);
    const l = await createList(page, 'Deleted soon');
    const u = await newUser(browser, 'ac22u', true);
    expect((await invite(page, l.id, u.email)).status()).toBe(201);
    const inv = await invitationFor(u.page, l.id);
    await u.page.goto('/invitations');
    await expect(u.page.getByTestId('invitation')).toHaveCount(1);
    expect((await api(page, 'DELETE', `/api/lists/${l.id}`)).status()).toBe(204);
    expect(num(`SELECT COUNT(*) FROM list_invitations WHERE list_id = ${l.id}`)).toBe(0);
    await u.page.getByRole('button', { name: `Accept invitation to ${l.name}` }).click();
    await expect(u.page.getByTestId('invitations-notice')).toHaveText(INVALID);
    await expectProblem(await api(u.page, 'POST', `/api/invitations/${inv.id}/accept`), 404, 'INVITATION_NOT_FOUND', INVALID);
    expect(await lists(u.page)).toHaveLength(1);
    await expectProblem(await api(u.page, 'POST', '/api/invitations/abc/accept'), 400, 'MALFORMED_REQUEST');
    await expectProblem(await api(u.page, 'POST', '/api/invitations/999999999/accept'), 404, 'INVITATION_NOT_FOUND', INVALID);
    await closeAll(u);
  });

  test('AC23/AC24: member can add/edit/tick/move/delete and use tags; owner-only actions are 403 OWNER_ONLY; menus by role', async ({
    page,
    browser,
  }) => {
    const ownerInfo = await register(page, 'ac23own', true);
    const owner: User = { ctx: page.context(), page, w: { consoleErrors: [], pageErrors: [], serverErrors: [], allowNetworkErrors: false }, ...ownerInfo };
    const l = await createList(page, 'Team');
    const ownersOther = await createList(page, 'Owner only');
    const ownerTask = await createTask(page, 'owner task', l.id, ['Shared']);
    const m = await newUser(browser, 'ac23m', true);
    await share(owner, l.id, m);
    const mList = await createList(m.page, 'Member own');
    const mp = m.page;

    // member does task work via the UI
    await gotoList(mp, (await lists(mp)).find((x) => x.id === l.id)!);
    await mp.getByLabel('New task').fill('member task');
    await mp.getByLabel('New task').press('Enter');
    await expect(row(mp, 'member task')).toBeVisible();
    await row(mp, 'owner task').getByRole('checkbox').check();
    await expect(row(mp, 'owner task').getByRole('checkbox')).toBeChecked();
    await row(mp, 'member task').getByRole('button', { name: 'Edit', exact: true }).click();
    const dlg = mp.getByRole('dialog', { name: 'Edit task' });
    await dlg.getByLabel('Title').fill('member task edited');
    const tagsBox = dlg.getByRole('combobox', { name: 'Tags' });
    await tagsBox.pressSequentially('sh');
    await expect(mp.getByRole('listbox', { name: 'Tag suggestions' }).getByRole('option')).toHaveText(['Shared']); // list's tags
    await mp.getByRole('listbox', { name: 'Tag suggestions' }).getByRole('option', { name: 'Shared' }).click();
    await dlg.getByRole('button', { name: 'Save' }).click();
    await expect(dlg).toHaveCount(0);
    await expect(row(mp, 'member task edited').locator('[data-testid=task-tag]')).toHaveText(['Shared']);
    // move: the List select offers only lists I'm a member of
    await row(mp, 'member task edited').getByRole('button', { name: 'Edit', exact: true }).click();
    await expect(dlg.locator('#task-edit-list option')).toHaveText(['My tasks', 'Member own', 'Team']);
    await dlg.locator('#task-edit-list').selectOption({ label: 'Member own' });
    await dlg.getByRole('button', { name: 'Save' }).click();
    await expect(row(mp, 'member task edited')).toHaveCount(0);
    // move back in (API), delete owner's task
    const moved = (await json<{ items: Task[] }>(await mp.request.get(`/api/tasks?list=${mList.id}`))).items[0];
    expect((await json<Task>(await api(mp, 'PUT', `/api/tasks/${moved.id}`, { title: moved.title, listId: l.id }))).listId).toBe(l.id);
    // member can't move into a list they are not a member of
    await expectProblem(await api(mp, 'PUT', `/api/tasks/${moved.id}`, { title: moved.title, listId: ownersOther.id }), 404, 'LIST_NOT_FOUND');
    expect((await api(mp, 'DELETE', `/api/tasks/${ownerTask.id}`)).status()).toBe(204);

    // owner-only actions -> 403 OWNER_ONLY
    const inv = await json<ListInvitation>(await invite(page, l.id, uniqueEmail('ac23p')), 201);
    for (const res of [
      await api(mp, 'PATCH', `/api/lists/${l.id}`, { name: 'hijack' }),
      await api(mp, 'DELETE', `/api/lists/${l.id}`),
      await invite(mp, l.id, uniqueEmail('ac23x')),
      await mp.request.get(`/api/lists/${l.id}/invitations`),
      await api(mp, 'DELETE', `/api/lists/${l.id}/invitations/${inv.id}`),
      await api(mp, 'DELETE', `/api/lists/${l.id}/members/${owner.id}`),
      await api(mp, 'DELETE', `/api/lists/${l.id}/members/${m.id}`),
    ]) {
      const b = await expectProblem(res, 403, 'OWNER_ONLY', 'Only the list owner can do this');
      expect(b.errors).toBeUndefined();
    }
    expect((await lists(page)).find((x) => x.id === l.id)!.name).toBe('Team');
    // member UI: menu = Members, Leave list; panel has no invite form, no pending, no Remove
    await mp.reload();
    await settled(mp);
    await menuToggle(mp).click();
    await expect(menuItems(mp)).toHaveText(['Members', 'Leave list']);
    await menuItem(mp, 'Members').click();
    await expect(panel(mp).getByTestId('member-row')).toHaveCount(2);
    await expect(panel(mp).locator('#invite-email')).toHaveCount(0);
    await expect(panel(mp).getByText('Pending invitations')).toHaveCount(0);
    await expect(panel(mp).getByRole('button', { name: /^Remove/ })).toHaveCount(0);
    await shot(mp, 'ac23-member-panel');
    // owner UI: menu = Rename, Members, Delete
    await gotoList(page, l);
    await menuToggle(page).click();
    await expect(menuItems(page)).toHaveText(['Rename', 'Members', 'Delete']);
    // owner can rename; member sees the new name after reload
    await menuItem(page, 'Rename').click();
    await page.getByLabel('List name').fill('Team renamed');
    await page.getByRole('button', { name: 'Save' }).click();
    await expect(heading(page)).toHaveText('Team renamed');
    expect((await lists(mp)).find((x) => x.id === l.id)!.name).toBe('Team renamed');
    await closeAll(m);
  });

  test('AC25: member leaves (dialog), loses access, list leaves the picker, tasks stay with their name; owner has no Leave (409)', async ({
    page,
    browser,
  }) => {
    const ownerInfo = await register(page, 'ac25own', true);
    const owner: User = { ctx: page.context(), page, w: { consoleErrors: [], pageErrors: [], serverErrors: [], allowNetworkErrors: false }, ...ownerInfo };
    const l = await createList(page, 'Leavable');
    const m = await newUser(browser, 'ac25m', true);
    await share(owner, l.id, m);
    const t = await createTask(m.page, 'added by leaver', l.id, ['LeaverTag']);
    await gotoList(m.page, { id: l.id, name: l.name });
    await menuToggle(m.page).click();
    await menuItem(m.page, 'Leave list').click();
    const dlg = m.page.locator('dialog[aria-labelledby="list-leave-heading"]');
    await expect(dlg.getByRole('heading')).toHaveText(`Leave "${l.name}"?`);
    await shot(m.page, 'ac25-leave-dialog');
    await dlg.getByRole('button', { name: 'Cancel' }).click();
    await expect(dlg).toHaveCount(0);
    expect((await lists(m.page)).some((x) => x.id === l.id)).toBe(true);
    await menuToggle(m.page).click();
    await menuItem(m.page, 'Leave list').click();
    await dlg.getByRole('button', { name: 'Leave list' }).click();
    await expect(m.page).toHaveURL(/\/tasks$/);
    await expect(picker(m.page).getByRole('link', { name: /Leavable/ })).toHaveCount(0);
    await expectProblem(await m.page.request.get(`/api/tasks?list=${l.id}`), 404, 'LIST_NOT_FOUND');
    await expectProblem(await m.page.request.get(`/api/tasks/${t.id}`), 404, 'NOT_FOUND');
    await expectProblem(await api(m.page, 'POST', `/api/lists/${l.id}/leave`), 404, 'LIST_NOT_FOUND');
    const kept = await json<Task>(await page.request.get(`/api/tasks/${t.id}`));
    expect(kept.addedBy).toEqual({ displayName: m.displayName });
    expect(kept.tags).toEqual(['LeaverTag']);
    expect((await lists(page)).find((x) => x.id === l.id)!.memberCount).toBe(1);
    await expectProblem(await api(page, 'POST', `/api/lists/${l.id}/leave`), 409, 'OWNER_CANNOT_LEAVE', "The owner can't leave the list");
    const mine = (await lists(page)).find((x) => x.personal)!;
    await expectProblem(await api(page, 'POST', `/api/lists/${mine.id}/leave`), 409, 'OWNER_CANNOT_LEAVE');
    await closeAll(m);
  });

  test('AC26: owner removes a member (inline confirm); their next request is 404, list leaves their picker; tasks stay', async ({ page, browser }) => {
    const ownerInfo = await register(page, 'ac26own', true);
    const owner: User = { ctx: page.context(), page, w: { consoleErrors: [], pageErrors: [], serverErrors: [], allowNetworkErrors: false }, ...ownerInfo };
    const l = await createList(page, 'Removal');
    const m = await newUser(browser, 'ac26m', true);
    const m2 = await newUser(browser, 'ac26m2', true);
    await share(owner, l.id, m);
    await share(owner, l.id, m2);
    const t = await createTask(m.page, 'by removed', l.id);
    await gotoList(m.page, { id: l.id, name: l.name });

    await gotoList(page, l);
    await openMembers(page);
    const rowM = panel(page).getByTestId('member-row').filter({ hasText: m.email });
    await rowM.getByRole('button', { name: `Remove ${m.displayName}` }).click();
    await expect(rowM).toContainText(`Remove ${m.displayName}?`);
    await shot(page, 'ac26-remove-confirm');
    await rowM.getByRole('button', { name: 'Cancel' }).click();
    await expect(panel(page).getByTestId('member-row')).toHaveCount(3);
    await rowM.getByRole('button', { name: `Remove ${m.displayName}` }).click();
    await rowM.getByRole('button', { name: 'Remove', exact: true }).click();
    await expect(panel(page).getByTestId('member-row')).toHaveCount(2);
    await expectProblem(await api(page, 'DELETE', `/api/lists/${l.id}/members/${m.id}`), 404, 'MEMBER_NOT_FOUND', 'Member not found');
    await expectProblem(await api(page, 'DELETE', `/api/lists/${l.id}/members/abc`), 400, 'MALFORMED_REQUEST');
    await expectProblem(await api(page, 'DELETE', `/api/lists/${l.id}/members/${owner.id}`), 409, 'OWNER_CANNOT_LEAVE');

    // removed member: open page -> next action is 404 -> "List not found"; picker drops it
    await m.page.getByLabel('New task').fill('after removal');
    await m.page.getByLabel('New task').press('Enter');
    await expect(m.page.getByTestId('list-not-found')).toBeVisible();
    await expect(picker(m.page).getByRole('link', { name: /Removal/ })).toHaveCount(0);
    await shot(m.page, 'ac26-removed-member');
    for (const res of [
      await m.page.request.get(`/api/lists/${l.id}`),
      await m.page.request.get(`/api/tasks?list=${l.id}`),
      await m.page.request.get(`/api/tags?list=${l.id}`),
      await m.page.request.get(`/api/lists/${l.id}/members`),
    ]) {
      await expectProblem(res, 404, 'LIST_NOT_FOUND');
    }
    await expectProblem(await m.page.request.get(`/api/tasks/${t.id}`), 404, 'NOT_FOUND');
    expect((await json<Task>(await page.request.get(`/api/tasks/${t.id}`))).addedBy).toEqual({ displayName: m.displayName });
    // can be invited again
    expect((await invite(page, l.id, m.email)).status()).toBe(201);
    await closeAll(m, m2);
  });

  test('AC27: "Added by" shows on lists with 2+ members only; deleted account -> "Added by a deleted user"', async ({ page, browser }) => {
    const ownerInfo = await register(page, 'ac27own', true);
    const owner: User = { ctx: page.context(), page, w: { consoleErrors: [], pageErrors: [], serverErrors: [], allowNetworkErrors: false }, ...ownerInfo };
    const solo = await createList(page, 'Solo');
    await createTask(page, 'solo task', solo.id);
    const l = await createList(page, 'Duo');
    await createTask(page, 'owner wrote', l.id);
    const m = await newUser(browser, 'ac27m', true);
    await share(owner, l.id, m);
    await createTask(m.page, 'member wrote', l.id);

    await gotoList(page, solo);
    await expect(row(page, 'solo task').getByTestId('task-added-by')).toHaveCount(0);
    expect((await json<{ items: Task[] }>(await page.request.get(`/api/tasks?list=${solo.id}`))).items[0].addedBy).toEqual({
      displayName: owner.displayName,
    });
    await page.goto('/tasks');
    await settled(page);
    await gotoList(page, l);
    await expect(row(page, 'owner wrote').getByTestId('task-added-by')).toHaveText(`Added by ${owner.displayName}`);
    await expect(row(page, 'member wrote').getByTestId('task-added-by')).toHaveText(`Added by ${m.displayName}`);
    await shot(page, 'ac27-added-by');
    // member deletes their account
    expect((await api(m.page, 'POST', '/api/account/delete', { password: PASSWORD })).status()).toBe(204);
    await page.reload();
    await settled(page);
    // memberCount is back to 1 -> nothing shown; API says null
    const items = (await json<{ items: Task[] }>(await page.request.get(`/api/tasks?list=${l.id}`))).items;
    expect(items.find((x) => x.title === 'member wrote')!.addedBy).toBeNull();
    await expect(row(page, 'member wrote').getByTestId('task-added-by')).toHaveCount(0);
    // with a second member again, the deleted author is shown
    const m2 = await newUser(browser, 'ac27m2', true);
    await share(owner, l.id, m2);
    await page.reload();
    await settled(page);
    await expect(row(page, 'member wrote').getByTestId('task-added-by')).toHaveText('Added by a deleted user');
    await shot(page, 'ac27-deleted-user');
    await m.ctx.close();
    await closeAll(m2);
  });

  test('AC28: members panel shows name, email, role (owner first); owner sees pending; member doesn\'t; invitee sees only owner name + list name', async ({
    page,
    browser,
  }) => {
    const ownerInfo = await register(page, 'ac28own', true);
    const owner: User = { ctx: page.context(), page, w: { consoleErrors: [], pageErrors: [], serverErrors: [], allowNetworkErrors: false }, ...ownerInfo };
    const l = await createList(page, 'Panel');
    const m = await newUser(browser, 'ac28m', true);
    await share(owner, l.id, m);
    const pendingEmail = uniqueEmail('ac28pend');
    expect((await invite(page, l.id, pendingEmail)).status()).toBe(201);
    const members = await json<Record<string, unknown>[]>(await m.page.request.get(`/api/lists/${l.id}/members`));
    for (const x of members) expect(Object.keys(x).sort()).toEqual(MEMBER_KEYS);
    expect(members.map((x) => [x['userId'], x['role'], x['email']])).toEqual([
      [owner.id, 'OWNER', owner.email],
      [m.id, 'MEMBER', m.email],
    ]);
    await gotoList(page, l);
    await openMembers(page);
    const rows = panel(page).getByTestId('member-row');
    await expect(rows).toHaveCount(2);
    await expect(rows.nth(0)).toContainText(owner.displayName);
    await expect(rows.nth(0)).toContainText('(you)');
    await expect(rows.nth(0)).toContainText(owner.email);
    await expect(rows.nth(0).getByTestId('member-role')).toHaveText('Owner');
    await expect(rows.nth(1)).toContainText(m.email);
    await expect(rows.nth(1).getByTestId('member-role')).toHaveText('Member');
    await expect(panel(page).getByTestId('pending-row')).toHaveCount(1);
    await expect(panel(page).getByTestId('pending-row')).toContainText(pendingEmail);
    await shot(page, 'ac28-owner-panel');
    // member's view of the same panel
    await gotoList(m.page, { id: l.id, name: l.name });
    await openMembers(m.page);
    await expect(panel(m.page).getByTestId('member-row')).toHaveCount(2);
    await expect(panel(m.page)).not.toContainText(pendingEmail);
    // the picker list response and tasks never carry members' emails
    const allLists = JSON.stringify(await lists(m.page));
    expect(allLists).not.toContain(owner.email);
    const tasks = JSON.stringify(await json(await m.page.request.get(`/api/tasks?list=${l.id}`)));
    expect(tasks).not.toContain('@');
    await closeAll(m);
  });

  test('AC29: non-members and removed members get 404 everywhere (never 403); an admin sees nothing of others\' lists', async ({ page, browser }) => {
    const ownerInfo = await register(page, 'ac29own', true);
    const owner: User = { ctx: page.context(), page, w: { consoleErrors: [], pageErrors: [], serverErrors: [], allowNetworkErrors: false }, ...ownerInfo };
    const l = await createList(page, 'Private');
    const t = await createTask(page, 'secret', l.id, ['SecretTag']);
    const inv = await json<ListInvitation>(await invite(page, l.id, uniqueEmail('ac29p')), 201);
    const stranger = await newUser(browser, 'ac29s', true);
    const removed = await newUser(browser, 'ac29r', true);
    await share(owner, l.id, removed);
    expect((await api(page, 'DELETE', `/api/lists/${l.id}/members/${removed.id}`)).status()).toBe(204);
    const adm = await adminPage(browser);
    for (const [label, p] of [
      ['stranger', stranger.page],
      ['removed', removed.page],
      ['admin', adm.page],
    ] as const) {
      const calls: [string, Promise<APIResponse>, string][] = [
        ['GET list', p.request.get(`/api/lists/${l.id}`), 'LIST_NOT_FOUND'],
        ['PATCH list', api(p, 'PATCH', `/api/lists/${l.id}`, { name: 'x' }), 'LIST_NOT_FOUND'],
        ['DELETE list', api(p, 'DELETE', `/api/lists/${l.id}`), 'LIST_NOT_FOUND'],
        ['members', p.request.get(`/api/lists/${l.id}/members`), 'LIST_NOT_FOUND'],
        ['remove', api(p, 'DELETE', `/api/lists/${l.id}/members/${owner.id}`), 'LIST_NOT_FOUND'],
        ['leave', api(p, 'POST', `/api/lists/${l.id}/leave`), 'LIST_NOT_FOUND'],
        ['invitations', p.request.get(`/api/lists/${l.id}/invitations`), 'LIST_NOT_FOUND'],
        ['invite', api(p, 'POST', `/api/lists/${l.id}/invitations`, { email: 'z@example.com' }), 'LIST_NOT_FOUND'],
        ['cancel', api(p, 'DELETE', `/api/lists/${l.id}/invitations/${inv.id}`), 'LIST_NOT_FOUND'],
        ['tasks', p.request.get(`/api/tasks?list=${l.id}`), 'LIST_NOT_FOUND'],
        ['tags', p.request.get(`/api/tags?list=${l.id}`), 'LIST_NOT_FOUND'],
        ['create task', api(p, 'POST', '/api/tasks', { title: 'x', listId: l.id }), 'LIST_NOT_FOUND'],
        ['get task', p.request.get(`/api/tasks/${t.id}`), 'NOT_FOUND'],
        ['put task', api(p, 'PUT', `/api/tasks/${t.id}`, { title: 'x' }), 'NOT_FOUND'],
        ['patch task', api(p, 'PATCH', `/api/tasks/${t.id}`, { done: true }), 'NOT_FOUND'],
        ['delete task', api(p, 'DELETE', `/api/tasks/${t.id}`), 'NOT_FOUND'],
      ];
      for (const [name, call, code] of calls) {
        const res = await call;
        expect(res.status(), `${label} ${name}`).toBe(404);
        expect((await res.json()).code, `${label} ${name}`).toBe(code);
      }
      // the invitation id is not accept-able by them either
      await expectProblem(await api(p, 'POST', `/api/invitations/${inv.id}/accept`), 404, 'INVITATION_NOT_FOUND');
      expect(JSON.stringify(await lists(p))).not.toContain('Private');
    }
    // UI: /lists/<id> -> List not found
    await removed.page.goto(`/lists/${l.id}`);
    await expect(removed.page.getByTestId('list-not-found')).toBeVisible();
    expect((await json<Task>(await page.request.get(`/api/tasks/${t.id}`))).title).toBe('secret');
    await adm.ctx.close();
    await closeAll(stranger, removed);
  });

  test('AC30: member account deletion (self and admin): memberships + invitations go, their tasks and those lists\' tags stay (addedBy null), their own lists go for every member; warning text', async ({
    page,
    browser,
  }) => {
    const ownerInfo = await register(page, 'ac30own', true);
    const owner: User = { ctx: page.context(), page, w: { consoleErrors: [], pageErrors: [], serverErrors: [], allowNetworkErrors: false }, ...ownerInfo };
    const l = await createList(page, 'Survives');
    const other = await createList(page, 'Pending for them');
    const adm = await adminPage(browser);
    for (const mode of ['self', 'admin'] as const) {
      const m = await newUser(browser, `ac30${mode}`, true);
      await share(owner, l.id, m);
      const t = await createTask(m.page, `by ${mode}`, l.id, [`Tag${mode}`]);
      expect((await invite(page, other.id, m.email)).status()).toBe(201);
      // m owns a list that the owner is a member of
      const theirs = await createList(m.page, `Theirs ${mode}`);
      await share(m, theirs.id, owner);
      expect((await lists(page)).some((x) => x.id === theirs.id)).toBe(true);
      if (mode === 'self') {
        // warning text in the UI
        await m.page.goto('/account');
        await m.page.getByRole('button', { name: 'Delete account' }).click();
        const dialog = m.page.getByRole('dialog');
        await expect(dialog).toContainText('Lists you own are deleted, and their members lose them.');
        await expect(dialog).toContainText("Tasks you added to other people's lists stay, shown as added by a deleted user.");
        await shot(m.page, 'ac30-delete-warning');
        await dialog.locator('#delete-account-password').fill(PASSWORD);
        await dialog.getByRole('button', { name: /Delete/ }).last().click();
        await expect(m.page).toHaveURL(/\/login$/);
      } else {
        expect((await api(adm.page, 'DELETE', `/api/admin/users/${m.id}`)).status()).toBe(204);
      }
      expect(num(`SELECT COUNT(*) FROM list_members WHERE user_id = ${m.id}`)).toBe(0);
      expect(num(`SELECT COUNT(*) FROM list_invitations WHERE email = '${m.email}'`)).toBe(0);
      expect(num(`SELECT COUNT(*) FROM lists WHERE id = ${theirs.id}`)).toBe(0);
      expect((await lists(page)).some((x) => x.id === theirs.id)).toBe(false);
      const kept = await json<Task>(await page.request.get(`/api/tasks/${t.id}`));
      expect(kept.addedBy).toBeNull();
      expect(kept.tags).toEqual([`Tag${mode}`]);
      expect((await json<{ name: string }[]>(await page.request.get(`/api/tags?list=${l.id}`))).map((x) => x.name)).toContain(`Tag${mode}`);
      await m.ctx.close();
    }
    expect((await lists(page)).find((x) => x.id === l.id)!.memberCount).toBe(1);
    await adm.ctx.close();
  });

  test('AC31: two members save the same task -> last write wins, no error; delete vs save -> saver gets "not found"', async ({ page, browser }) => {
    const ownerInfo = await register(page, 'ac31own', true);
    const owner: User = { ctx: page.context(), page, w: { consoleErrors: [], pageErrors: [], serverErrors: [], allowNetworkErrors: false }, ...ownerInfo };
    const l = await createList(page, 'Together');
    const m = await newUser(browser, 'ac31m', true);
    await share(owner, l.id, m);
    const t = await createTask(page, 'shared edit', l.id);
    const [r1, r2] = await Promise.all([
      api(page, 'PUT', `/api/tasks/${t.id}`, { title: 'from owner', tags: ['o'] }),
      api(m.page, 'PUT', `/api/tasks/${t.id}`, { title: 'from member', tags: ['m'] }),
    ]);
    expect([r1.status(), r2.status()]).toEqual([200, 200]);
    const final = await json<Task>(await page.request.get(`/api/tasks/${t.id}`));
    expect(['from owner', 'from member']).toContain(final.title);
    // sequential: last save wins
    await json(await api(page, 'PUT', `/api/tasks/${t.id}`, { title: 'first' }));
    await json(await api(m.page, 'PUT', `/api/tasks/${t.id}`, { title: 'second' }));
    expect((await json<Task>(await page.request.get(`/api/tasks/${t.id}`))).title).toBe('second');

    // UI: member has the dialog open, owner deletes, member saves
    await gotoList(m.page, { id: l.id, name: l.name });
    await row(m.page, 'second').getByRole('button', { name: 'Edit', exact: true }).click();
    const dlg = m.page.getByRole('dialog', { name: 'Edit task' });
    await dlg.getByLabel('Title').fill('too late');
    expect((await api(page, 'DELETE', `/api/tasks/${t.id}`)).status()).toBe(204);
    await dlg.getByRole('button', { name: 'Save' }).click();
    await expect(m.page.getByText(/Task not found|no longer exists|was deleted/i).first()).toBeVisible();
    await shot(m.page, 'ac31-deleted-while-editing');
    await expectProblem(await api(m.page, 'PUT', `/api/tasks/${t.id}`, { title: 'x' }), 404, 'NOT_FOUND', 'Task not found');
    await closeAll(m);
  });

  test('AC32: another member\'s change shows after reload / Load more, not live', async ({ page, browser }) => {
    const ownerInfo = await register(page, 'ac32own', true);
    const owner: User = { ctx: page.context(), page, w: { consoleErrors: [], pageErrors: [], serverErrors: [], allowNetworkErrors: false }, ...ownerInfo };
    const l = await createList(page, 'Not live');
    for (let i = 0; i < 55; i += 1) await createTask(page, `t${String(i).padStart(2, '0')}`, l.id);
    const m = await newUser(browser, 'ac32m', true);
    await share(owner, l.id, m);
    await gotoList(page, l);
    await expect(page.getByTestId('tasks-footer')).toContainText('Showing 50 of 55');
    await createTask(m.page, 'from member', l.id);
    const oldest = (await json<{ items: Task[] }>(await m.page.request.get(`/api/tasks?list=${l.id}&sort=oldest`))).items[0];
    await json(await api(m.page, 'PUT', `/api/tasks/${oldest.id}`, { title: 't00 edited by member' }));
    await page.waitForTimeout(1500);
    await expect(row(page, 'from member')).toHaveCount(0); // no live update
    await page.getByRole('button', { name: 'Load more' }).click();
    await expect(row(page, 't00 edited by member')).toBeVisible(); // next page shows the edit
    await page.reload();
    await settled(page);
    await expect(row(page, 'from member')).toBeVisible();
    await closeAll(m);
  });

  test('AC34: invitations page loading/empty/error + Retry; members panel loading/error/empty; invite/cancel/remove/leave/accept errors', async ({
    page,
    browser,
    watch,
  }) => {
    test.setTimeout(90_000);
    watch.allowNetworkErrors = true;
    const ownerInfo = await register(page, 'ac34own', true);
    const owner: User = { ctx: page.context(), page, w: watch, ...ownerInfo };
    const l = await createList(page, 'States');
    const m = await newUser(browser, 'ac34m', true);
    m.w.allowNetworkErrors = true;
    await share(owner, l.id, m);
    const fake500 = { status: 500, headers: { 'x-qa-fake': '1' }, contentType: 'application/problem+json', body: '{"status":500}' };

    // invitations page: loading, error + Retry, empty
    const u = await newUser(browser, 'ac34u', true);
    u.w.allowNetworkErrors = true;
    let release!: () => void;
    const gate = new Promise<void>((r) => (release = r));
    let fail = true;
    await u.page.route('**/api/invitations', async (route) => {
      await gate;
      if (fail) return route.fulfill(fake500);
      return route.continue();
    });
    await u.page.goto('/invitations');
    await expect(u.page.getByText('Loading invitations…')).toBeVisible();
    await shot(u.page, 'ac34-invitations-loading');
    release();
    await expect(u.page.getByRole('alert')).toContainText("Couldn't load your invitations.");
    await shot(u.page, 'ac34-invitations-error');
    fail = false;
    await u.page.getByRole('button', { name: 'Retry' }).click();
    await expect(u.page.getByTestId('invitations-empty')).toHaveText('No pending invitations');
    await shot(u.page, 'ac34-invitations-empty');
    await u.page.unrouteAll({ behavior: 'wait' });

    // accept/decline errors (network) keep the row with a message
    expect((await invite(page, l.id, u.email)).status()).toBe(201);
    await u.page.reload();
    await u.page.route('**/api/invitations/*/accept', (route) => route.abort('failed'));
    await u.page.getByRole('button', { name: `Accept invitation to ${l.name}` }).click();
    await expect(u.page.getByTestId('invitation').getByRole('alert')).toContainText("Couldn't accept the invitation. Please try again.");
    await u.page.route('**/api/invitations/*/decline', (route) => route.abort('failed'));
    await u.page.getByRole('button', { name: `Decline invitation to ${l.name}` }).click();
    await expect(u.page.getByTestId('invitation').getByRole('alert')).toContainText("Couldn't decline the invitation. Please try again.");
    await shot(u.page, 'ac34-accept-error');

    // members panel: loading + error + Retry
    await gotoList(page, l);
    let mfail = 1;
    let mrelease!: () => void;
    const mgate = new Promise<void>((r) => (mrelease = r));
    await page.route(`**/api/lists/${l.id}/members`, async (route) => {
      await mgate;
      if (mfail-- > 0) return route.fulfill(fake500);
      return route.continue();
    });
    await menuToggle(page).click();
    await menuItem(page, 'Members').click();
    await expect(panel(page).getByText('Loading members…')).toBeVisible();
    mrelease();
    await expect(panel(page).getByRole('alert').first()).toContainText("Couldn't load the members.");
    await shot(page, 'ac34-members-error');
    await panel(page).getByRole('button', { name: 'Retry' }).first().click();
    await expect(panel(page).getByTestId('member-row')).toHaveCount(2);
    // invite error (network) + Retry
    let ifail = 1;
    await page.route(`**/api/lists/${l.id}/invitations`, (route) =>
      route.request().method() === 'POST' && ifail-- > 0 ? route.abort('failed') : route.continue(),
    );
    const newcomer = uniqueEmail('ac34new');
    await panel(page).locator('#invite-email').fill(newcomer);
    await panel(page).getByRole('button', { name: 'Send invitation' }).click();
    await expect(panel(page).locator('#invite-error')).toContainText("Couldn't send the invitation. Please try again.");
    await panel(page).locator('#invite-error').getByRole('button', { name: 'Retry' }).click();
    await expect(panel(page).getByTestId('invite-sent')).toHaveText('Invitation sent');
    // cancel error
    await page.route(`**/api/lists/${l.id}/invitations/*`, (route) => route.abort('failed'));
    await panel(page).getByRole('button', { name: `Cancel invitation for ${newcomer}` }).click();
    await expect(panel(page).getByTestId('pending-row').filter({ hasText: newcomer }).getByRole('alert')).toContainText(
      "Couldn't cancel the invitation. Please try again.",
    );
    // remove error
    await page.route(`**/api/lists/${l.id}/members/*`, (route) => route.abort('failed'));
    const rowM = panel(page).getByTestId('member-row').filter({ hasText: m.email });
    await rowM.getByRole('button', { name: `Remove ${m.displayName}` }).click();
    await rowM.getByRole('button', { name: 'Remove', exact: true }).click();
    await expect(rowM.getByRole('alert')).toContainText("Couldn't remove the member. Please try again.");
    await shot(page, 'ac34-panel-errors');
    expect(num(`SELECT COUNT(*) FROM list_members WHERE list_id = ${l.id}`)).toBe(2);

    // leave error + Retry
    await gotoList(m.page, { id: l.id, name: l.name });
    let lfail = 1;
    await m.page.route(`**/api/lists/${l.id}/leave`, (route) => (lfail-- > 0 ? route.fulfill(fake500) : route.continue()));
    await menuToggle(m.page).click();
    await menuItem(m.page, 'Leave list').click();
    const dlg = m.page.locator('dialog[aria-labelledby="list-leave-heading"]');
    await dlg.getByRole('button', { name: 'Leave list' }).click();
    await expect(dlg.getByRole('alert')).toContainText("Couldn't leave the list. Please try again.");
    await shot(m.page, 'ac34-leave-error');
    await dlg.getByRole('button', { name: 'Retry' }).click();
    await expect(m.page).toHaveURL(/\/tasks$/);
    await closeAll(m, u);
  });

  test('AC34/header: 320/375/390/641/1280 px with a 50-char name, badge 3 and the Admin link: no horizontal scroll, Invitations + Log out fully visible and clickable, badge readable', async ({
    page,
    browser,
  }) => {
    test.setTimeout(90_000);
    const longName = `W${'x'.repeat(30)}${uid()}`.slice(0, 50).padEnd(50, 'y');
    const email = uniqueEmail('ac34hdr');
    const res = await page.request.post('/api/auth/register', {
      data: { displayName: longName, email, password: PASSWORD },
      headers: { 'X-XSRF-TOKEN': await xsrf(page) },
    });
    expect(res.status(), await res.text()).toBe(201);
    const id = ((await res.json()) as { id: number }).id;
    verify(id);
    sql(`UPDATE users SET role = 'ADMIN' WHERE id = ${id}`);
    const inviters: User[] = [];
    for (let i = 0; i < 3; i += 1) {
      const o = await newUser(browser, `ac34hdr${i}`, true);
      const l = await createList(o.page, `Hdr ${i}`);
      expect((await invite(o.page, l.id, email)).status()).toBe(201);
      inviters.push(o);
    }
    // fresh sign-in so the admin role is in the session
    await page.context().clearCookies();
    expect(
      (await page.request.post('/api/auth/login', { data: { email, password: PASSWORD }, headers: { 'X-XSRF-TOKEN': await xsrf(page) } })).status(),
    ).toBe(200);

    const inside = async (label: string, loc: Locator, vw: number) => {
      const b = (await loc.boundingBox())!;
      expect(b, label).toBeTruthy();
      expect(b.x, `${label} left`).toBeGreaterThanOrEqual(0);
      expect(b.x + b.width, `${label} right`).toBeLessThanOrEqual(vw + 0.5);
      // the element itself is what's under its centre (not covered, so clickable)
      const hit = await loc.evaluate((el) => {
        const r = el.getBoundingClientRect();
        const top = document.elementFromPoint(r.left + r.width / 2, r.top + r.height / 2);
        return !!top && (top === el || el.contains(top));
      });
      expect(hit, `${label} not covered`).toBe(true);
      return b;
    };
    for (const width of [320, 375, 390, 641, 1280]) {
      await page.setViewportSize({ width, height: 740 });
      await page.goto('/tasks');
      await settled(page);
      await expect(badge(page)).toHaveText('3');
      const sw = await page.evaluate(() => document.documentElement.scrollWidth);
      expect(sw, `${width}: no horizontal scroll`).toBeLessThanOrEqual(width);
      const header = page.locator('header.app-header');
      await inside(`${width} Invitations`, page.getByTestId('invitations-link'), width);
      await inside(`${width} Log out`, page.getByRole('button', { name: 'Log out' }), width);
      await inside(`${width} Admin`, page.getByTestId('admin-link'), width);
      const bb = await inside(`${width} badge`, badge(page), width);
      expect(bb.height, `${width} badge height`).toBeGreaterThanOrEqual(14);
      const style = await badge(page).evaluate((el) => {
        const cs = getComputedStyle(el);
        return { fontSize: parseFloat(cs.fontSize), color: cs.color, bg: cs.backgroundColor, overflow: el.scrollWidth > el.clientWidth };
      });
      expect(style.fontSize, `${width} badge font`).toBeGreaterThanOrEqual(11);
      expect(style.overflow, `${width} badge text not clipped`).toBe(false);
      expect(style.color).not.toBe(style.bg);
      // the greeting keeps the full name as its text (ellipsis is visual only) and links to /account
      await expect(page.locator('.app-header__greeting')).toHaveText(`Hi, ${longName}`);
      const hb = (await header.boundingBox())!;
      expect(hb.width, `${width} header width`).toBeLessThanOrEqual(width + 0.5);
      await shot(page, `ac34-header-${width}`);
    }
    // clickable for real at the narrowest width
    await page.setViewportSize({ width: 320, height: 740 });
    await page.goto('/tasks');
    await settled(page);
    await page.getByTestId('invitations-link').click();
    await expect(page).toHaveURL(/\/invitations$/);
    await expect(page.getByTestId('invitation')).toHaveCount(3);
    await shot(page, 'ac34-header-320-invitations');
    await page.getByRole('button', { name: 'Log out' }).click();
    await expect(page).toHaveURL(/\/login$/);
    for (const o of inviters) await o.ctx.close();
  });

  test('AC14/AC15 still hold with sharing: lists/tasks/tags of a non-member list never leak; admin sees only own lists; no list param = My tasks', async ({
    page,
    browser,
  }) => {
    const a = await register(page, 'ac14a', true);
    const al = await createList(page, 'A only');
    await createTask(page, 'A task', al.id, ['ATag']);
    const b = await newUser(browser, 'ac14b', true);
    const bodies: string[] = [];
    b.page.on('response', async (r) => {
      if (r.url().includes('/api/')) bodies.push(await r.text().catch(() => ''));
    });
    await b.page.goto('/tasks');
    await settled(b.page);
    await b.page.goto('/invitations');
    await expect(b.page.getByTestId('invitations-empty')).toBeVisible();
    for (const s of ['A only', 'A task', 'ATag', a.email]) expect(bodies.some((x) => x.includes(s)), s).toBe(false);
    const mine = (await lists(page)).find((x) => x.personal)!;
    expect(mine).toMatchObject({ role: 'OWNER', memberCount: 1 });
    expect((await json<{ items: Task[] }>(await page.request.get('/api/tasks'))).items.every((t) => t.listId === mine.id)).toBe(true);
    await closeAll(b);
  });
});

// =====================================================================================
// Real limits (fresh backend with default limits): E2E_RATE_LIMIT=1

test.describe('T3b real invite limits (E2E_RATE_LIMIT=1)', () => {
  test.skip(!REAL_RL, 'set E2E_RATE_LIMIT=1 against a freshly started backend with default limits');
  test.use({ baseURL: BASE });

  test('AC19: 31st invitation in an hour -> 429 RATE_LIMITED with Retry-After; nothing created', async ({ page }) => {
    await register(page, 'rlinv', true);
    const l1 = await createList(page, 'RL one');
    const l2 = await createList(page, 'RL two');
    for (let i = 0; i < 30; i += 1) {
      const r = await invite(page, i < 20 ? l1.id : l2.id, uniqueEmail(`rl${i}`));
      expect(r.status(), `invite ${i + 1}`).toBe(201);
    }
    const r = await invite(page, l2.id, uniqueEmail('rl31'));
    await expectProblem(r, 429, 'RATE_LIMITED');
    expect(Number(r.headers()['retry-after'])).toBeGreaterThan(0);
    expect(num(`SELECT COUNT(*) FROM list_invitations WHERE list_id IN (${l1.id}, ${l2.id})`)).toBe(30);
    // validation and owner-only errors are not counted / come first
    await expectProblem(await invite(page, l2.id, 'nope'), 400, 'VALIDATION_FAILED');
  });

  test('AC18: invitation emails per recipient are capped silently at 5 per hour (6th invite: 201, no email)', async ({ page, browser }) => {
    const target = await newUser(browser, 'rlmail', true);
    const owners: Page[] = [];
    for (let i = 0; i < 6; i += 1) {
      const ctx = await browser.newContext({ baseURL: BASE });
      const p = await ctx.newPage();
      await register(p, `rlown${i}`, true);
      const l = await createList(p, `Mail cap ${i}`);
      expect((await invite(p, l.id, target.email)).status(), `invite ${i + 1}`).toBe(201);
      owners.push(p);
    }
    await expect.poll(async () => (await invitationMails(page, target.email)).length, { timeout: 20_000 }).toBe(5);
    await delay(3000);
    expect(await invitationMails(page, target.email)).toHaveLength(5);
    expect(await myInvitations(target.page)).toHaveLength(6);
    for (const p of owners) await p.context().close();
    await target.ctx.close();
  });
});
