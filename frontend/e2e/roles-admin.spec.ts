import { APIResponse, Browser, BrowserContext, Cookie, expect, Page, Response, test } from '@playwright/test';
import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';

/**
 * Roles and admin users (specs/roles-admin.md, AC1–AC21 + constraints), contract 0.9.0.
 *
 * Needs `docker compose up -d db mail oidc` (roles are set with `docker compose exec db mysql` as the migration
 * user; Mailpit for AC18; the fake Google for AC8/AC14/AC18/AC19).
 *
 * Default run: dev backend with the e2e per-IP overrides AND a high admin limit
 *   (RATE_LIMIT_ADMIN_ACTIONS_PER_USER_PER_MINUTE=100000), frontend at E2E_BASE_URL (default http://localhost:4200).
 * Optional:
 *   E2E_BACKEND_LOG=<backend console log>  AC17 audit lines and "no secrets in the log".
 * Gated modes:
 *   E2E_RATE_LIMIT=1     ONLY the AC20 rate-limit test, against a freshly started backend with DEFAULT limits.
 *   E2E_SHORT_SESSIONS / E2E_SHORT_LINKS / E2E_MAIL_DOWN / E2E_GOOGLE_OFF: everything here is skipped.
 *
 * AC4 (ADMIN_BOOTSTRAP_EMAIL) needs backend restarts and is verified by hand (see the QA report).
 */

const RUN = `${Date.now()}-${Math.floor(Math.random() * 1e6)}`;
const BASE = process.env['E2E_BASE_URL'] ?? 'http://localhost:4200';
const OIDC = 'http://localhost:8090/default';
const MAILPIT = process.env['E2E_MAILPIT'] ?? 'http://localhost:8025';
const LOG = process.env['E2E_BACKEND_LOG'];
const REAL_RL = process.env['E2E_RATE_LIMIT'] === '1';
const OTHER_MODE =
  process.env['E2E_SHORT_SESSIONS'] === '1' ||
  process.env['E2E_SHORT_LINKS'] === '1' ||
  process.env['E2E_MAIL_DOWN'] === '1' ||
  process.env['E2E_GOOGLE_OFF'] === '1';
const REPO = '..';
const SHOTS = '../test-results/roles-admin';

const PASSWORD = 'password123';
const DISABLED = 'This account has been disabled.';
const INVALID = 'Invalid email or password';
const NO_PERMISSION = "You don't have permission to do this.";
const USER_GONE = 'This user no longer exists';
const NOT_FOUND_TEXT = 'No users found';
const GENERIC = 'Something went wrong, please try again';
const RATE_LIMITED = 'Too many attempts, please try again later';
const EMAIL_MISMATCH = "Email doesn't match";
const RESET_SENT = "If an account exists for that email, we've sent a link to reset your password.";
const LINK_INVALID = 'This link is invalid or has expired';
const CONFIRMED = 'Your email is confirmed';
const RESET_SUBJECT = 'Reset your Task App password';
const VERIFY_SUBJECT = 'Confirm your email for Task App';
const ADMIN_USER_KEYS = ['createdAt', 'disabled', 'displayName', 'email', 'emailVerified', 'googleConnected', 'id', 'role'];

let n = 0;
function uid(): string {
  n += 1;
  return `${RUN}-${n}`;
}
function email(tag: string): string {
  return `qa-adm-${tag}-${uid()}@example.com`;
}

type Role = 'USER' | 'ADMIN' | 'SUPER_ADMIN';
interface AdminUser {
  id: number;
  email: string;
  displayName: string;
  createdAt: string;
  emailVerified: boolean;
  googleConnected: boolean;
  role: Role;
  disabled: boolean;
}
interface Acct {
  ctx: BrowserContext;
  page: Page;
  w: Watch;
  id: number;
  email: string;
  name: string;
}

// ---------------------------------------------------------------- browser helpers

function watch(page: Page) {
  const consoleErrors: string[] = [];
  const pageErrors: string[] = [];
  const serverErrors: string[] = [];
  page.on('console', (m) => {
    if (m.type() === 'error') consoleErrors.push(m.text());
  });
  page.on('pageerror', (e) => pageErrors.push(e.message));
  page.on('response', (r: Response) => {
    if (r.url().includes('/api/') && r.status() >= 500) serverErrors.push(`${r.status()} ${r.request().method()} ${r.url()}`);
  });
  return { consoleErrors, pageErrors, serverErrors };
}
type Watch = ReturnType<typeof watch>;

function assertClean(w: Watch, label: string, allowServer = false) {
  const app = w.consoleErrors.filter((e) => !/^Failed to load resource: the server responded with a status of/.test(e));
  if (w.consoleErrors.length) console.log(`${label} console:`, JSON.stringify(w.consoleErrors));
  expect(app, `${label}: console errors`).toEqual([]);
  expect(w.pageErrors, `${label}: page errors`).toEqual([]);
  if (!allowServer) expect(w.serverErrors, `${label}: 5xx`).toEqual([]);
}

async function newPage(browser: Browser, cookies: Cookie[] = []) {
  const ctx = await browser.newContext({ baseURL: BASE });
  if (cookies.length) await ctx.addCookies(cookies);
  const page = await ctx.newPage();
  return { ctx, page, w: watch(page) };
}

async function shot(page: Page, name: string) {
  await page.screenshot({ path: `${SHOTS}/${name}.png`, fullPage: true });
}

async function xsrf(ctx: BrowserContext): Promise<string> {
  let c = (await ctx.cookies()).find((k) => k.name === 'XSRF-TOKEN');
  if (!c) {
    await ctx.request.get('/api/auth/me');
    c = (await ctx.cookies()).find((k) => k.name === 'XSRF-TOKEN');
  }
  return c?.value ?? '';
}

async function call(ctx: BrowserContext, method: string, path: string, data?: unknown, csrf = true): Promise<APIResponse> {
  const headers: Record<string, string> = {};
  if (csrf) headers['X-XSRF-TOKEN'] = await xsrf(ctx);
  return ctx.request.fetch(path, { method, data, headers });
}

async function persistent(ctx: BrowserContext): Promise<Cookie[]> {
  return (await ctx.cookies()).filter((c) => c.expires > 0);
}

/** Registers a new account (signed in in its own context, no remember-me) and sets its role in the DB. */
async function account(browser: Browser, tag: string, role: Role = 'USER', displayName?: string): Promise<Acct> {
  const { ctx, page, w } = await newPage(browser);
  const mail = email(tag);
  const name = displayName ?? `A ${tag} ${uid()}`;
  const r = await call(ctx, 'POST', '/api/auth/register', { email: mail, password: PASSWORD, displayName: name });
  expect(r.status(), `register ${mail}`).toBe(201);
  const body = (await r.json()) as { id: number; role: Role };
  expect(body.role).toBe('USER');
  if (role !== 'USER') setRole(mail, role);
  return { ctx, page, w, id: body.id, email: mail, name };
}

/** A second, independent sign-in of an existing account (optionally with remember-me). */
async function signIn(browser: Browser, mail: string, rememberMe = false) {
  const p = await newPage(browser);
  const r = await call(p.ctx, 'POST', '/api/auth/login', { email: mail, password: PASSWORD, rememberMe });
  expect(r.status(), `login ${mail}`).toBe(200);
  return p;
}

async function loginUi(page: Page, mail: string, password = PASSWORD) {
  await page.goto('/login');
  await page.locator('#login-email').fill(mail);
  await page.locator('#login-password').fill(password);
  await page.getByRole('button', { name: 'Sign in' }).click();
}

async function me(ctx: BrowserContext): Promise<{ status: number; body: Record<string, unknown> | null }> {
  const r = await ctx.request.get('/api/auth/me');
  return { status: r.status(), body: r.status() === 200 ? ((await r.json()) as Record<string, unknown>) : null };
}

async function addTaskApi(ctx: BrowserContext, title: string) {
  const r = await call(ctx, 'POST', '/api/tasks', { title });
  expect(r.status()).toBe(201);
}

// ---------------------------------------------------------------- admin page helpers

async function openAdmin(page: Page, q?: string) {
  const loaded = page.waitForResponse((r) => new URL(r.url()).pathname === '/api/admin/users' && r.request().method() === 'GET');
  await page.goto('/admin/users');
  await loaded;
  await expect(page.locator('#admin-users-heading')).toHaveText('Users');
  if (q !== undefined) await search(page, q);
}

async function search(page: Page, q: string) {
  const resp = page.waitForResponse(
    (r) => new URL(r.url()).pathname === '/api/admin/users' && new URL(r.url()).searchParams.get('q') === q.trim(),
  );
  await page.locator('#admin-users-search').fill(q);
  await resp;
}

function row(page: Page, id: number) {
  return page.locator(`[data-testid="admin-row"][data-user-id="${id}"]`);
}

async function startAction(page: Page, id: number, action: string) {
  await row(page, id).getByTestId('admin-menu-toggle').click();
  await page.locator(`[role=menuitem][data-action="${action}"]`).click();
  await expect(page.getByTestId('admin-action-confirm')).toBeVisible();
}

async function doAction(page: Page, id: number, action: string, notice: string) {
  await startAction(page, id, action);
  const done = page.waitForResponse((r) => r.url().includes(`/api/admin/users/${id}`) && r.request().method() !== 'GET');
  await page.getByTestId('admin-action-confirm').click();
  const resp = await done;
  expect(resp.status(), `${action} on ${id}`).toBeLessThan(300);
  await expect(page.getByTestId('admin-notice')).toHaveText(notice);
  await expect(page.locator('dialog[open]')).toHaveCount(0);
}

async function menuActions(page: Page, id: number): Promise<string[]> {
  const toggle = row(page, id).getByTestId('admin-menu-toggle');
  if ((await toggle.count()) === 0) return [];
  await toggle.click();
  await expect(page.getByTestId('admin-menu')).toBeVisible();
  const items = page.locator('[role=menuitem][data-action]');
  const out = await items.evaluateAll((els) => els.map((e) => e.getAttribute('data-action') ?? ''));
  await page.keyboard.press('Escape');
  await expect(page.getByTestId('admin-menu')).toHaveCount(0);
  return out;
}

// ---------------------------------------------------------------- DB / Mailpit / log / Google helpers

function sql(query: string): string {
  return execFileSync(
    'docker',
    ['compose', 'exec', '-T', 'db', 'mysql', '-utaskapp', '-ptaskapp', 'taskapp', '-N', '-B', '-e', query],
    { cwd: REPO, encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] },
  ).trim();
}
function setRole(mail: string, role: Role) {
  sql(`UPDATE users SET role = '${role}' WHERE email = '${mail}'`);
}
function dbRole(id: number): string {
  return sql(`SELECT role FROM users WHERE id = ${id}`);
}
function count(q: string): number {
  return Number(sql(q));
}

interface MailSummary { ID: string; Subject: string; To: { Address: string }[] }
async function mailsTo(to: string, subject: string): Promise<MailSummary[]> {
  const r = await fetch(`${MAILPIT}/api/v1/search?limit=100&query=${encodeURIComponent(`to:"${to}"`)}`);
  const j = (await r.json()) as { messages?: MailSummary[] };
  return (j.messages ?? []).filter((m) => m.Subject === subject && m.To.some((t) => t.Address.toLowerCase() === to.toLowerCase()));
}
async function waitForLink(to: string, subject: string, kind: 'verify-email' | 'reset-password'): Promise<string> {
  await expect.poll(async () => (await mailsTo(to, subject)).length, { timeout: 20_000, message: `${subject} to ${to}` }).toBeGreaterThan(0);
  const [m] = await mailsTo(to, subject);
  const full = (await (await fetch(`${MAILPIT}/api/v1/message/${m.ID}`)).json()) as { Text: string };
  const match = full.Text.match(new RegExp(`https?://[^\\s"'<>]+(/${kind}\\?token=[A-Za-z0-9_-]+)`));
  expect(match, `${kind} link`).toBeTruthy();
  return match![1];
}

function onGoogle(url: URL): boolean {
  return url.href.startsWith(`${OIDC}/authorize`);
}
function backInApp(url: URL): boolean {
  return url.origin === new URL(BASE).origin && !url.pathname.startsWith('/api/');
}
async function google(page: Page, googleSub: string, claims: Record<string, unknown>) {
  await page.goto('/login');
  await page.getByTestId('google-button').click();
  await page.waitForURL(onGoogle);
  await page.locator('input[name="username"]').fill(googleSub);
  await page.locator('textarea[name="claims"]').fill(JSON.stringify(claims));
  await page.locator('input[type="submit"]').click();
  await page.waitForURL(backInApp);
}
/** A new account created with Google (email confirmed, Google connected), signed in in its own context. */
async function googleAccount(browser: Browser, tag: string): Promise<Acct & { sub: string }> {
  const { ctx, page, w } = await newPage(browser);
  const mail = email(tag);
  const sub = `g-adm-${tag}-${uid()}`;
  const name = `G ${tag} ${uid()}`;
  await google(page, sub, { email: mail, email_verified: true, name });
  await expect(page).toHaveURL(/\/tasks$/);
  const m = await me(ctx);
  expect(m.status).toBe(200);
  expect(m.body!['role']).toBe('USER'); // AC1: Google sign-up is USER
  return { ctx, page, w, id: m.body!['id'] as number, email: mail, name: m.body!['displayName'] as string, sub };
}

function auditLines(): string[] {
  return readFileSync(LOG!, 'utf8')
    .split('\n')
    .filter((l) => l.includes('security.audit') && l.includes('SECURITY event='));
}
async function expectAudit(re: RegExp) {
  await expect.poll(() => auditLines().some((l) => re.test(l)), { message: `audit ${re}`, timeout: 8000 }).toBe(true);
}

test.beforeAll(() => {
  execFileSync('mkdir', ['-p', SHOTS]);
});

// =============================================================================================================

test.describe('roles and admin users', () => {
  test.skip(REAL_RL || OTHER_MODE, 'default run only');

  test('AC1 AC2 AC3: roles default to USER, role field everywhere, nobody raises their own role', async ({ browser }) => {
    const u = await account(browser, 'ac1');
    // AC2: /me, login, display-name responses carry role
    expect((await me(u.ctx)).body!['role']).toBe('USER');
    const keys = Object.keys((await me(u.ctx)).body!).sort();
    expect(keys).toEqual(['connections', 'displayName', 'email', 'emailVerified', 'hasPassword', 'id', 'role']);
    const second = await signIn(browser, u.email);
    const login = await call(second.ctx, 'POST', '/api/auth/login', { email: u.email, password: PASSWORD });
    expect(((await login.json()) as Record<string, unknown>)['role']).toBe('USER');
    const dn = await call(u.ctx, 'PUT', '/api/account/display-name', { displayName: `R ${uid()}`, role: 'SUPER_ADMIN' });
    expect(dn.status()).toBe(200);
    expect(((await dn.json()) as Record<string, unknown>)['role']).toBe('USER');
    // AC1: DB values only USER/ADMIN/SUPER_ADMIN; register ignores a role in the body
    expect(count(`SELECT COUNT(*) FROM users WHERE role NOT IN ('USER','ADMIN','SUPER_ADMIN')`)).toBe(0);
    const p = await newPage(browser);
    const reg = await call(p.ctx, 'POST', '/api/auth/register', {
      email: email('ac1b'),
      password: PASSWORD,
      displayName: `B ${uid()}`,
      role: 'SUPER_ADMIN',
    });
    expect(reg.status()).toBe(201);
    const regBody = (await reg.json()) as { id: number; role: string };
    expect(regBody.role).toBe('USER');
    expect(dbRole(regBody.id)).toBe('USER');
    // AC3: a USER can't use the role endpoint on themselves (FORBIDDEN), an ADMIN gets CANNOT_MODIFY_SELF on self
    // and INSUFFICIENT_ROLE on others, a SUPER_ADMIN can't set SUPER_ADMIN
    const r1 = await call(u.ctx, 'PUT', `/api/admin/users/${u.id}/role`, { role: 'ADMIN' });
    expect(r1.status()).toBe(403);
    expect(((await r1.json()) as { code: string }).code).toBe('FORBIDDEN');
    const a = await account(browser, 'ac3a', 'ADMIN');
    const r2 = await call(a.ctx, 'PUT', `/api/admin/users/${a.id}/role`, { role: 'USER' });
    expect(r2.status()).toBe(409);
    expect(((await r2.json()) as { code: string }).code).toBe('CANNOT_MODIFY_SELF');
    const r3 = await call(a.ctx, 'PUT', `/api/admin/users/${u.id}/role`, { role: 'ADMIN' });
    expect(r3.status()).toBe(403);
    expect(((await r3.json()) as { code: string }).code).toBe('INSUFFICIENT_ROLE');
    const s = await account(browser, 'ac3s', 'SUPER_ADMIN');
    for (const role of ['SUPER_ADMIN', 'super_admin', 'admin', null, 'OWNER']) {
      const r = await call(s.ctx, 'PUT', `/api/admin/users/${u.id}/role`, { role });
      expect(r.status(), `role ${role}`).toBe(400);
      const b = (await r.json()) as { code: string; errors: { field: string; code: string }[] };
      expect(b.code).toBe('VALIDATION_FAILED');
      expect(b.errors).toEqual([{ field: 'role', code: 'INVALID_ROLE', message: 'Role must be USER or ADMIN' }]);
    }
    const r4 = await call(s.ctx, 'PUT', `/api/admin/users/${s.id}/role`, { role: 'USER' });
    expect(r4.status()).toBe(409);
    expect(dbRole(u.id)).toBe('USER');
    expect(dbRole(a.id)).toBe('ADMIN');
    expect(dbRole(s.id)).toBe('SUPER_ADMIN');
  });

  test('AC6 AC7: Admin link only for admins, before "Hi, <name>"; guards and API 401/403', async ({ browser }) => {
    const u = await account(browser, 'ac6u');
    const a = await account(browser, 'ac6a', 'ADMIN');
    const s = await account(browser, 'ac6s', 'SUPER_ADMIN');

    for (const acct of [a, s]) {
      await acct.page.goto('/tasks');
      const link = acct.page.getByTestId('admin-link');
      await expect(link).toHaveText('Admin');
      await expect(link).toHaveAttribute('href', '/admin/users');
      const order = await acct.page
        .locator('.app-header__user')
        .evaluate((el) => Array.from(el.children).map((c) => c.textContent?.trim()));
      expect(order.indexOf('Admin')).toBeLessThan(order.findIndex((t) => t?.startsWith('Hi,')));
      await link.click();
      await expect(acct.page).toHaveURL(/\/admin\/users$/);
      await expect(acct.page.locator('#admin-users-heading')).toHaveText('Users');
    }
    await shot(s.page, 'ac6-header-admin-link');

    await u.page.goto('/tasks');
    await expect(u.page.getByText(/^Hi, /)).toBeVisible();
    await expect(u.page.getByTestId('admin-link')).toHaveCount(0);
    await u.page.goto('/admin/users');
    await expect(u.page).toHaveURL(/\/tasks$/);
    await expect(u.page.getByTestId('admin-link')).toHaveCount(0);

    const anon = await newPage(browser);
    await anon.page.goto('/admin/users');
    await expect(anon.page).toHaveURL(/\/login/);

    // API: every endpoint 401 signed out (with CSRF), 403 FORBIDDEN for USER
    const endpoints: [string, string, unknown?][] = [
      ['GET', '/api/admin/users'],
      ['POST', `/api/admin/users/${u.id}/disable`],
      ['POST', `/api/admin/users/${u.id}/enable`],
      ['POST', `/api/admin/users/${u.id}/revoke-sessions`],
      ['PUT', `/api/admin/users/${u.id}/role`, { role: 'ADMIN' }],
      ['DELETE', `/api/admin/users/${u.id}`],
    ];
    for (const [m, p, d] of endpoints) {
      const r401 = await call(anon.ctx, m, p, d);
      expect(r401.status(), `${m} ${p} signed out`).toBe(401);
      expect(((await r401.json()) as { code: string }).code).toBe('UNAUTHENTICATED');
      const r403 = await call(u.ctx, m, p, d);
      expect(r403.status(), `${m} ${p} USER`).toBe(403);
      expect(r403.headers()['content-type']).toContain('application/problem+json');
      expect(((await r403.json()) as { code: string }).code).toBe('FORBIDDEN');
    }
    expect(dbRole(u.id)).toBe('USER');
    expect(count(`SELECT COUNT(*) FROM users WHERE id = ${u.id} AND disabled_at IS NULL`)).toBe(1);
    for (const x of [u, a, s]) assertClean(x.w, x.email);
    assertClean(anon.w, 'anon');
  });

  test('AC8 AC10: table columns, newest first, (you), badges, only AdminUser fields', async ({ browser }) => {
    const s = await account(browser, 'ac8s', 'SUPER_ADMIN');
    const tag = `ac8t${uid()}`;
    const first = await account(browser, 'x', 'USER', `First ${tag}`);
    await addTaskApi(first.ctx, `secret task ${tag}`);
    const second = await account(browser, 'x', 'ADMIN', `Second ${tag}`);
    sql(`UPDATE users SET email_verified_at = NOW(6), disabled_at = NOW(6) WHERE id = ${second.id}`);
    // Google account (confirmed, connected), renamed so it matches the search tag
    const g = await googleAccount(browser, 'ac8g');
    const ren = await call(g.ctx, 'PUT', '/api/account/display-name', { displayName: `Third ${tag}` });
    expect(ren.status()).toBe(200);

    // API shape (AC10): only the AdminUser fields, newest first
    const list = await s.ctx.request.get(`/api/admin/users?q=${encodeURIComponent(tag)}`);
    expect(list.status()).toBe(200);
    const page = (await list.json()) as { content: AdminUser[]; page: number; size: number; totalElements: number; totalPages: number };
    expect(Object.keys(page).sort()).toEqual(['content', 'page', 'size', 'totalElements', 'totalPages']);
    expect(page).toMatchObject({ page: 0, size: 20, totalElements: 3, totalPages: 1 });
    expect(page.content.map((c) => c.id)).toEqual([g.id, second.id, first.id]);
    for (const c of page.content) expect(Object.keys(c).sort()).toEqual(ADMIN_USER_KEYS);
    expect(page.content[0]).toMatchObject({ emailVerified: true, googleConnected: true, role: 'USER', disabled: false });
    expect(page.content[1]).toMatchObject({ emailVerified: true, googleConnected: false, role: 'ADMIN', disabled: true });
    expect(page.content[2]).toMatchObject({ emailVerified: false, googleConnected: false, role: 'USER', disabled: false });
    expect(page.content[2].createdAt).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?Z$/);
    const raw = await list.text();
    expect(raw).not.toMatch(/password|hash|task|secret|sub"|g-adm/i);
    // the admin's own /api/tasks only shows the admin's tasks
    const tasks = await s.ctx.request.get('/api/tasks');
    expect(await tasks.text()).not.toContain(`secret task ${tag}`);
    // default listing is newest first overall
    const all = (await (await s.ctx.request.get('/api/admin/users')).json()) as { content: AdminUser[] };
    const times = all.content.map((c) => Date.parse(c.createdAt));
    expect([...times].sort((x, y) => y - x)).toEqual(times);
    expect(all.content.length).toBe(20);

    // UI
    await openAdmin(s.page, tag);
    const headers = await s.page.locator('thead th').allInnerTexts();
    expect(headers.slice(0, 7)).toEqual(['Email', 'Display name', 'Created', 'Email confirmed', 'Google', 'Role', 'Status']);
    const rows = s.page.getByTestId('admin-row');
    await expect(rows).toHaveCount(3);
    expect(await rows.evaluateAll((els) => els.map((e) => Number(e.getAttribute('data-user-id'))))).toEqual([
      g.id,
      second.id,
      first.id,
    ]);
    const cells = async (id: number) => (await row(s.page, id).locator('td').allInnerTexts()).map((t) => t.trim());
    const gc = await cells(g.id);
    expect(gc.slice(0, 7)).toEqual([g.email, `Third ${tag}`, expect.stringMatching(/\w{3} \d{1,2}, \d{4}/), 'Yes', 'Yes', 'User', 'Active']);
    const sc = await cells(second.id);
    expect(sc.slice(1, 7)).toEqual([`Second ${tag}`, expect.any(String), 'Yes', 'No', 'Admin', 'Disabled']);
    const fc = await cells(first.id);
    expect(fc.slice(3, 7)).toEqual(['No', 'No', 'User', 'Active']);
    await expect(s.page.getByText(/secret task/)).toHaveCount(0);
    await expect(s.page.getByTestId('admin-page')).toHaveText('Page 1 of 1');
    await shot(s.page, 'ac8-table');

    // (you) + Super admin badge
    await search(s.page, s.email);
    await expect(row(s.page, s.id).getByTestId('admin-you')).toHaveText('(you)');
    await expect(row(s.page, s.id).getByTestId('admin-role')).toHaveText('Super admin');
    await expect(row(s.page, s.id).getByTestId('admin-menu-toggle')).toHaveCount(0);
    await expect(s.page.getByTestId('admin-you')).toHaveCount(1);
    assertClean(s.w, 'super admin');
  });

  test('AC9 AC21: search (contains, case, accents), 20 per page, Previous/Next, empty and loading states', async ({ browser }) => {
    const a = await account(browser, 'ac9a', 'ADMIN');
    const tag = `pg${uid()}`.replace(/-/g, '');
    const ids: number[] = [];
    for (let i = 0; i < 21; i++) {
      const p = await newPage(browser);
      const r = await call(p.ctx, 'POST', '/api/auth/register', {
        email: `qa-adm-${tag}-${i}@example.com`,
        password: PASSWORD,
        displayName: `Pg ${uid()}`,
      });
      expect(r.status()).toBe(201);
      ids.push(((await r.json()) as { id: number }).id);
      await p.ctx.close();
    }
    const zoe = await account(browser, 'ac9z', 'USER', `Zoë Ünïcode ${tag}x`);

    // loading state (delay the first list call)
    let delayed = false;
    await a.page.route(
      (u) => u.pathname === '/api/admin/users',
      async (route) => {
        if (!delayed) {
          delayed = true;
          await new Promise((r) => setTimeout(r, 1500));
        }
        await route.continue();
      },
    );
    await a.page.goto('/admin/users');
    await expect(a.page.getByTestId('admin-loading')).toBeVisible();
    await expect(a.page.getByTestId('admin-loading')).toContainText('Loading users');
    await shot(a.page, 'ac21-loading');
    await expect(a.page.getByTestId('admin-table')).toBeVisible();
    await expect(a.page.getByTestId('admin-loading')).toHaveCount(0);
    await a.page.unrouteAll();

    // search by email part, upper case
    await search(a.page, `QA-ADM-${tag.toUpperCase()}-`);
    const rows = a.page.getByTestId('admin-row');
    await expect(rows).toHaveCount(20);
    await expect(a.page.getByTestId('admin-page')).toHaveText('Page 1 of 2');
    const prev = a.page.getByRole('button', { name: 'Previous' });
    const next = a.page.getByRole('button', { name: 'Next' });
    await expect(prev).toBeDisabled();
    await expect(next).toBeEnabled();
    // newest first: the last registered is first
    await expect(rows.first()).toHaveAttribute('data-user-id', String(ids[20]));
    await shot(a.page, 'ac9-page1');
    await next.click();
    await expect(a.page.getByTestId('admin-page')).toHaveText('Page 2 of 2');
    await expect(rows).toHaveCount(1);
    await expect(rows.first()).toHaveAttribute('data-user-id', String(ids[0]));
    await expect(next).toBeDisabled();
    await shot(a.page, 'ac9-page2');
    await prev.click();
    await expect(a.page.getByTestId('admin-page')).toHaveText('Page 1 of 2');
    await expect(rows).toHaveCount(20);

    // display name, ignoring case and accents
    await search(a.page, `zoe unicode ${tag.toUpperCase()}`);
    await expect(rows).toHaveCount(1);
    await expect(rows.first()).toHaveAttribute('data-user-id', String(zoe.id));
    await search(a.page, `ZOË`);
    await expect(row(a.page, zoe.id)).toBeVisible();
    // literal % and _
    await search(a.page, `%${tag}`);
    await expect(a.page.getByTestId('admin-empty')).toHaveText(NOT_FOUND_TEXT);

    // nothing matches
    await search(a.page, `nobody-${uid()}`);
    await expect(a.page.getByTestId('admin-empty')).toHaveText(NOT_FOUND_TEXT);
    await expect(a.page.getByTestId('admin-table')).toHaveCount(0);
    await shot(a.page, 'ac9-no-users-found');

    // refresh mid-flow keeps the admin on the page
    await a.page.reload();
    await expect(a.page).toHaveURL(/\/admin\/users$/);
    await expect(a.page.getByTestId('admin-table')).toBeVisible();
    assertClean(a.w, 'admin');
  });

  test('AC21: load error with Retry; dialog spinner and in-dialog error; double click sends one request', async ({ browser }) => {
    const a = await account(browser, 'ac21a', 'ADMIN');
    const t = await account(browser, 'ac21t');
    let fail = true;
    await a.page.route(
      (u) => u.pathname === '/api/admin/users',
      (route) => (fail ? route.fulfill({ status: 500, contentType: 'application/problem+json', body: '{"status":500}' }) : route.continue()),
    );
    await a.page.goto('/admin/users');
    const err = a.page.getByTestId('admin-load-error');
    await expect(err).toContainText(GENERIC);
    await expect(err.getByRole('button', { name: 'Retry' })).toBeVisible();
    await shot(a.page, 'ac21-load-error');
    fail = false;
    await err.getByRole('button', { name: 'Retry' }).click();
    await expect(a.page.getByTestId('admin-table')).toBeVisible();
    await a.page.unrouteAll();

    await search(a.page, t.email);
    // in-dialog error (500) and spinner while waiting; double click = one request
    let calls = 0;
    let mode: 'error' | 'slow' = 'error';
    await a.page.route(`**/api/admin/users/${t.id}/revoke-sessions`, async (route) => {
      calls += 1;
      if (mode === 'error') return route.fulfill({ status: 500, contentType: 'application/problem+json', body: '{}' });
      await new Promise((r) => setTimeout(r, 1500));
      return route.continue();
    });
    await startAction(a.page, t.id, 'revokeSessions');
    await a.page.getByTestId('admin-action-confirm').click();
    await expect(a.page.getByTestId('admin-action-error')).toHaveText(GENERIC);
    await expect(a.page.locator('dialog[open]')).toBeVisible();
    await shot(a.page, 'ac21-dialog-error');
    mode = 'slow';
    calls = 0;
    await a.page.getByTestId('admin-action-confirm').dblclick();
    await expect(a.page.locator('dialog[open]').getByTestId('spinner')).toBeVisible();
    await expect(a.page.getByTestId('admin-action-confirm')).toBeDisabled();
    await shot(a.page, 'ac21-dialog-spinner');
    await expect(a.page.getByTestId('admin-notice')).toHaveText('User signed out everywhere');
    expect(calls).toBe(1);
    assertClean(a.w, 'admin', true);
  });

  test('AC11 AC18 AC19: disable ends sessions + remember-me, data kept; enable restores; old sessions stay ended', async ({ browser }) => {
    const s = await account(browser, 'ac11s', 'SUPER_ADMIN');
    const t = await googleAccount(browser, 'ac11t');
    setRole(t.email, 'ADMIN'); // AC19: role is kept too
    await addTaskApi(t.ctx, `kept task ${t.id}`);
    // set a password for the Google account so it can also sign in with a password: via DB copy of a known hash
    sql(`UPDATE users u JOIN users k ON k.id = ${s.id} SET u.password_hash = k.password_hash WHERE u.id = ${t.id}`);
    const rm = await signIn(browser, t.email, true);
    const rmCookies = await persistent(rm.ctx);
    expect(rmCookies.some((c) => c.name === 'remember-me')).toBe(true);
    await t.page.goto('/tasks');
    await expect(t.page.getByText(`kept task ${t.id}`)).toBeVisible();

    await openAdmin(s.page, t.email);
    expect(await menuActions(s.page, t.id)).toEqual(['removeAdmin', 'revokeSessions', 'disable', 'delete']);
    await startAction(s.page, t.id, 'disable');
    await expect(s.page.getByTestId('admin-action-text')).toHaveText(
      `Disable ${t.email}? They will be signed out everywhere and can't sign in until enabled.`,
    );
    await shot(s.page, 'ac11-disable-dialog');
    await s.page.getByTestId('admin-action-confirm').click();
    await expect(s.page.getByTestId('admin-notice')).toHaveText('User disabled');
    await expect(row(s.page, t.id).getByTestId('admin-status')).toHaveText('Disabled');
    await shot(s.page, 'ac11-disabled-row');

    // AC18: the open page stops on its next request
    await t.page.getByLabel('New task').fill('after disable');
    await t.page.getByRole('button', { name: 'Add', exact: true }).click();
    await expect(t.page).toHaveURL(/\/login/);
    await expect(t.page.getByTestId('account-disabled')).toHaveText(DISABLED);
    await shot(t.page, 'ac18-open-session-disabled');
    // remember-me no longer signs in
    const reopened = await newPage(browser, rmCookies);
    await reopened.page.goto('/tasks');
    await expect(reopened.page).toHaveURL(/\/login/);
    expect((await me(reopened.ctx)).status).toBe(401);
    expect(count(`SELECT COUNT(*) FROM remember_me_tokens WHERE user_id = ${t.id}`)).toBe(0);
    // data kept
    expect(count(`SELECT COUNT(*) FROM tasks WHERE owner_id = ${t.id}`)).toBe(1);
    expect(count(`SELECT COUNT(*) FROM user_connections WHERE user_id = ${t.id}`)).toBe(1);
    expect(dbRole(t.id)).toBe('ADMIN');
    // disabled user can't use admin API either
    const sessionCookies = await t.ctx.cookies();

    // enable
    await doAction(s.page, t.id, 'enable', 'User enabled');
    await expect(row(s.page, t.id).getByTestId('admin-status')).toHaveText('Active');
    // old session and remember-me stay ended
    const old = await newPage(browser, sessionCookies);
    expect((await me(old.ctx)).status).toBe(401);
    const old2 = await newPage(browser, rmCookies);
    expect((await me(old2.ctx)).status).toBe(401);
    // signs in normally, everything back
    const fresh = await newPage(browser);
    await loginUi(fresh.page, t.email);
    await expect(fresh.page).toHaveURL(/\/tasks$/);
    await expect(fresh.page.getByText(`kept task ${t.id}`)).toBeVisible();
    await expect(fresh.page.getByTestId('admin-link')).toBeVisible();
    const m = await me(fresh.ctx);
    expect(m.body!['role']).toBe('ADMIN');
    expect((m.body!['connections'] as unknown[]).length).toBe(1);
    // Google sign-in works again too
    const gp = await newPage(browser);
    await google(gp.page, t.sub, { email: t.email, email_verified: true });
    await expect(gp.page).toHaveURL(/\/tasks$/);
    assertClean(s.w, 'super admin');
    assertClean(fresh.w, 'fresh');
  });

  test('AC18: login messages, Google, forgot password, old reset link, verify-email link', async ({ browser }) => {
    const s = await account(browser, 'ac18s', 'SUPER_ADMIN');
    const t = await account(browser, 'ac18t');
    const verifyLink = await waitForLink(t.email, VERIFY_SUBJECT, 'verify-email');
    // a reset link sent before the account is disabled
    const anon = await newPage(browser);
    expect((await call(anon.ctx, 'POST', '/api/auth/forgot-password', { email: t.email })).status()).toBe(202);
    const resetLink = await waitForLink(t.email, RESET_SUBJECT, 'reset-password');
    const g = await googleAccount(browser, 'ac18g');

    for (const id of [t.id, g.id]) {
      const r = await call(s.ctx, 'POST', `/api/admin/users/${id}/disable`);
      expect(r.status()).toBe(200);
      const body = (await r.json()) as AdminUser;
      expect(Object.keys(body).sort()).toEqual(ADMIN_USER_KEYS);
      expect(body.disabled).toBe(true);
      // idempotent
      expect((await call(s.ctx, 'POST', `/api/admin/users/${id}/disable`)).status()).toBe(200);
    }

    // password login: correct → disabled; wrong → invalid
    const p = await newPage(browser);
    await loginUi(p.page, t.email);
    await expect(p.page.getByTestId('form-error')).toHaveText(DISABLED);
    await expect(p.page).toHaveURL(/\/login/);
    await shot(p.page, 'ac18-login-correct-password');
    await loginUi(p.page, t.email, 'wrong-password-1');
    await expect(p.page.getByTestId('form-error')).toHaveText(INVALID);
    await shot(p.page, 'ac18-login-wrong-password');
    expect((await me(p.ctx)).status).toBe(401);
    const api403 = await call(p.ctx, 'POST', '/api/auth/login', { email: t.email, password: PASSWORD });
    expect(api403.status()).toBe(403);
    expect(await api403.json()).toMatchObject({ status: 403, code: 'ACCOUNT_DISABLED', detail: DISABLED });
    const api401 = await call(p.ctx, 'POST', '/api/auth/login', { email: t.email, password: 'nope-nope-1' });
    expect(api401.status()).toBe(401);
    expect(((await api401.json()) as { code: string }).code).toBe('INVALID_CREDENTIALS');
    expect((await me(p.ctx)).status).toBe(401);

    // Google: lands on /login with the message, nothing created or linked
    const gp = await newPage(browser);
    const navs: string[] = [];
    gp.page.on('framenavigated', (f) => {
      if (f === gp.page.mainFrame()) navs.push(f.url());
    });
    await google(gp.page, g.sub, { email: g.email, email_verified: true });
    await expect(gp.page).toHaveURL(/\/login/);
    expect(navs.some((u) => u.includes('/login?oauth=account_disabled'))).toBe(true);
    await expect(gp.page.getByText(DISABLED)).toBeVisible();
    await shot(gp.page, 'ac18-google-disabled');
    expect((await me(gp.ctx)).status).toBe(401);
    // a new Google id with the disabled password account's email: no account, no link
    const newSub = `g-adm-new-${uid()}`;
    const gp2 = await newPage(browser);
    await google(gp2.page, newSub, { email: t.email, email_verified: true });
    await expect(gp2.page).toHaveURL(/\/login/);
    expect((await me(gp2.ctx)).status).toBe(401);
    expect(count(`SELECT COUNT(*) FROM user_connections WHERE provider_subject = '${newSub}'`)).toBe(0);
    expect(count(`SELECT COUNT(*) FROM user_connections WHERE user_id = ${t.id}`)).toBe(0);
    expect(count(`SELECT COUNT(*) FROM users WHERE email IN ('${t.email}', '${g.email}')`)).toBe(2);
    expect(count(`SELECT COUNT(*) FROM user_connections WHERE user_id = ${g.id}`)).toBe(1);

    // forgot password: normal message, no email
    const before = (await mailsTo(t.email, RESET_SUBJECT)).length;
    const f = await newPage(browser);
    await f.page.goto('/forgot-password');
    await f.page.locator('#forgot-email').fill(t.email);
    await f.page.getByRole('button', { name: 'Send reset link' }).click();
    await expect(f.page.getByTestId('reset-link-sent')).toHaveText(RESET_SENT);
    await new Promise((r) => setTimeout(r, 4000));
    expect((await mailsTo(t.email, RESET_SUBJECT)).length).toBe(before);
    // the older reset link is invalid
    await f.page.goto(resetLink);
    await expect(f.page.getByTestId('link-invalid')).toHaveText(LINK_INVALID);
    await shot(f.page, 'ac18-old-reset-link');

    // verify-email link still confirms, signs nobody in
    const v = await newPage(browser);
    await v.page.goto(verifyLink);
    await expect(v.page.getByTestId('email-confirmed')).toHaveText(CONFIRMED);
    expect((await me(v.ctx)).status).toBe(401);
    expect(sql(`SELECT email_verified_at IS NOT NULL FROM users WHERE id = ${t.id}`)).toBe('1');
    await shot(v.page, 'ac18-verify-link-works');

    // after enable, the old reset link stays dead (decision log)
    expect((await call(s.ctx, 'POST', `/api/admin/users/${t.id}/enable`)).status()).toBe(200);
    const chk = await call(anon.ctx, 'POST', '/api/auth/check-reset-token', { token: new URL(resetLink, BASE).searchParams.get('token') });
    expect(chk.status()).toBe(400);
    expect(((await chk.json()) as { code: string }).code).toBe('TOKEN_INVALID');
    for (const x of [p, gp, f, v]) assertClean(x.w, 'ac18');
  });

  test('AC12: sign out everywhere ends every session and remember-me, account stays active', async ({ browser }) => {
    const a = await account(browser, 'ac12a', 'ADMIN');
    const t = await account(browser, 'ac12t');
    const rm = await signIn(browser, t.email, true);
    const rmCookies = await persistent(rm.ctx);
    await t.page.goto('/tasks');
    await openAdmin(a.page, t.email);
    expect(await menuActions(a.page, t.id)).toEqual(['revokeSessions', 'disable', 'delete']);
    await startAction(a.page, t.id, 'revokeSessions');
    await expect(a.page.getByTestId('admin-action-text')).toContainText(t.email);
    await a.page.getByTestId('admin-action-confirm').click();
    await expect(a.page.getByTestId('admin-notice')).toHaveText('User signed out everywhere');
    await expect(row(a.page, t.id).getByTestId('admin-status')).toHaveText('Active');
    await shot(a.page, 'ac12-signed-out-everywhere');
    expect((await me(t.ctx)).status).toBe(401);
    expect((await me(rm.ctx)).status).toBe(401);
    const reopened = await newPage(browser, rmCookies);
    expect((await me(reopened.ctx)).status).toBe(401);
    const again = await signIn(browser, t.email);
    expect((await me(again.ctx)).status).toBe(200);
    // revoke on an already signed-out user still 200 with AdminUser
    const r = await call(a.ctx, 'POST', `/api/admin/users/${t.id}/revoke-sessions`);
    expect(r.status()).toBe(200);
    expect(Object.keys(await r.json()).sort()).toEqual(ADMIN_USER_KEYS);
    expect((await me(again.ctx)).status).toBe(401);
    assertClean(a.w, 'admin');
  });

  test('AC13: make admin / remove admin take effect without a new sign-in', async ({ browser }) => {
    const s = await account(browser, 'ac13s', 'SUPER_ADMIN');
    const t = await account(browser, 'ac13t');
    await t.page.goto('/tasks');
    await expect(t.page.getByTestId('admin-link')).toHaveCount(0);

    await openAdmin(s.page, t.email);
    expect(await menuActions(s.page, t.id)).toEqual(['makeAdmin', 'revokeSessions', 'disable', 'delete']);
    await doAction(s.page, t.id, 'makeAdmin', 'Role changed');
    await expect(row(s.page, t.id).getByTestId('admin-role')).toHaveText('Admin');
    await shot(s.page, 'ac13-made-admin');
    expect((await t.ctx.request.get('/api/admin/users')).status()).toBe(200);
    // next navigation shows the link
    await t.page.getByRole('link', { name: /^Hi, / }).click();
    await expect(t.page).toHaveURL(/\/account$/);
    await expect(t.page.getByTestId('admin-link')).toBeVisible();
    await shot(t.page, 'ac13-target-admin-link');

    await doAction(s.page, t.id, 'removeAdmin', 'Role changed');
    await expect(row(s.page, t.id).getByTestId('admin-role')).toHaveText('User');
    const r = await t.ctx.request.get('/api/admin/users');
    expect(r.status()).toBe(403);
    expect(((await r.json()) as { code: string }).code).toBe('FORBIDDEN');
    // the stale link: following it ends on /tasks and the link goes away
    await t.page.getByTestId('admin-link').click();
    await expect(t.page).toHaveURL(/\/tasks$/);
    await expect(t.page.getByTestId('admin-link')).toHaveCount(0);
    // same role again = 200 no-op
    const same = await call(s.ctx, 'PUT', `/api/admin/users/${t.id}/role`, { role: 'USER' });
    expect(same.status()).toBe(200);
    expect(((await same.json()) as AdminUser).role).toBe('USER');
    expect((await me(t.ctx)).status).toBe(200); // still signed in
    assertClean(s.w, 'super admin');
    assertClean(t.w, 'target');
  });

  test('AC14: delete asks for the email, deletes account, tasks and Google connection; sessions end', async ({ browser }) => {
    const a = await account(browser, 'ac14a', 'ADMIN');
    const t = await googleAccount(browser, 'ac14t');
    await addTaskApi(t.ctx, 'doomed 1');
    await addTaskApi(t.ctx, 'doomed 2');
    await openAdmin(a.page, t.email);
    await startAction(a.page, t.id, 'delete');
    await expect(a.page.getByTestId('admin-action-text')).toContainText(
      `Delete ${t.email}? Their account and all their tasks will be permanently deleted.`,
    );
    const confirm = a.page.getByTestId('admin-action-confirm');
    await expect(confirm).toBeDisabled();
    await a.page.locator('#admin-action-email').fill('someone@else.com');
    await expect(a.page.getByText(EMAIL_MISMATCH)).toBeVisible();
    await expect(confirm).toBeDisabled();
    await shot(a.page, 'ac14-delete-mismatch');
    await a.page.locator('#admin-action-email').fill(`  ${t.email.toUpperCase()} `);
    await expect(a.page.getByText(EMAIL_MISMATCH)).toHaveCount(0);
    await expect(confirm).toBeEnabled();
    let deletes = 0;
    a.page.on('request', (r) => {
      if (r.method() === 'DELETE' && r.url().includes(`/api/admin/users/${t.id}`)) deletes += 1;
    });
    const resp = a.page.waitForResponse((r) => r.request().method() === 'DELETE');
    await confirm.dblclick();
    expect((await resp).status()).toBe(204);
    await expect(a.page.getByTestId('admin-notice')).toHaveText('User deleted');
    await expect(row(a.page, t.id)).toHaveCount(0);
    await expect(a.page.getByTestId('admin-empty')).toHaveText(NOT_FOUND_TEXT);
    await shot(a.page, 'ac14-deleted');
    expect(deletes).toBe(1);
    expect(count(`SELECT COUNT(*) FROM users WHERE id = ${t.id}`)).toBe(0);
    expect(count(`SELECT COUNT(*) FROM tasks WHERE owner_id = ${t.id}`)).toBe(0);
    expect(count(`SELECT COUNT(*) FROM user_connections WHERE user_id = ${t.id}`)).toBe(0);
    // the user's open page ends on its next request
    await t.page.goto('/tasks');
    await expect(t.page).toHaveURL(/\/login/);
    // deleting again = 404
    const again = await call(a.ctx, 'DELETE', `/api/admin/users/${t.id}`);
    expect(again.status()).toBe(404);
    expect(await again.json()).toMatchObject({ code: 'USER_NOT_FOUND', detail: 'This user no longer exists.' });
    // the Google id is free again: signing in creates a NEW account
    const gp = await newPage(browser);
    await google(gp.page, t.sub, { email: t.email, email_verified: true });
    await expect(gp.page).toHaveURL(/\/tasks$/);
    expect((await me(gp.ctx)).body!['id']).not.toBe(t.id);
    assertClean(a.w, 'admin');
  });

  test('AC15 AC16: permission matrix enforced by the backend; rows show only allowed actions', async ({ browser }) => {
    test.setTimeout(90_000);
    const s = await account(browser, 'ac15s', 'SUPER_ADMIN');
    const s2 = await account(browser, 'ac15s2', 'SUPER_ADMIN');
    const a = await account(browser, 'ac15a', 'ADMIN');
    const a2 = await account(browser, 'ac15a2', 'ADMIN');
    const u = await account(browser, 'ac15u');

    const acts = (id: number): [string, string, unknown?][] => [
      ['POST', `/api/admin/users/${id}/disable`],
      ['POST', `/api/admin/users/${id}/enable`],
      ['POST', `/api/admin/users/${id}/revoke-sessions`],
      ['PUT', `/api/admin/users/${id}/role`, { role: 'USER' }],
      ['DELETE', `/api/admin/users/${id}`],
    ];
    const expectCode = async (ctx: BrowserContext, target: number, status: number, code: string) => {
      for (const [m, p, d] of acts(target)) {
        const r = await call(ctx, m, p, d);
        expect(r.status(), `${m} ${p}`).toBe(status);
        const body = (await r.json()) as { code: string; status: number; instance: string; title: string; type: string };
        expect(body.code, `${m} ${p}`).toBe(code);
        expect(body.instance).toBe(p);
        expect(r.headers()['content-type']).toContain('application/problem+json');
      }
    };
    await expectCode(a.ctx, a2.id, 403, 'INSUFFICIENT_ROLE'); // admin → admin
    await expectCode(a.ctx, s.id, 403, 'INSUFFICIENT_ROLE'); // admin → super admin
    await expectCode(s.ctx, s2.id, 403, 'INSUFFICIENT_ROLE'); // super admin → super admin
    await expectCode(s.ctx, s.id, 409, 'CANNOT_MODIFY_SELF');
    await expectCode(a.ctx, a.id, 409, 'CANNOT_MODIFY_SELF');
    await expectCode(a.ctx, 2147483000, 404, 'USER_NOT_FOUND');
    // admin role change on a USER
    const rc = await call(a.ctx, 'PUT', `/api/admin/users/${u.id}/role`, { role: 'ADMIN' });
    expect(rc.status()).toBe(403);
    expect(((await rc.json()) as { code: string }).code).toBe('INSUFFICIENT_ROLE');
    // AC16: nothing changed on super admins
    expect(dbRole(s.id)).toBe('SUPER_ADMIN');
    expect(dbRole(s2.id)).toBe('SUPER_ADMIN');
    expect(count(`SELECT COUNT(*) FROM users WHERE id IN (${s.id},${s2.id},${a2.id}) AND disabled_at IS NULL`)).toBe(3);
    expect((await me(s2.ctx)).status).toBe(200);
    expect((await me(a2.ctx)).status).toBe(200);

    // UI menus: admin view
    const ids = [s.id, s2.id, a.id, a2.id, u.id];
    await openAdmin(a.page);
    const expectMenus = async (page: Page, expected: Record<number, string[]>) => {
      for (const id of ids) {
        const q = [s, s2, a, a2, u].find((x) => x.id === id)!.email;
        await search(page, q);
        await expect(row(page, id)).toBeVisible();
        expect(await menuActions(page, id), `actions on ${q}`).toEqual(expected[id]);
      }
    };
    await expectMenus(a.page, {
      [s.id]: [],
      [s2.id]: [],
      [a.id]: [],
      [a2.id]: [],
      [u.id]: ['revokeSessions', 'disable', 'delete'],
    });
    await openAdmin(s.page);
    await expectMenus(s.page, {
      [s.id]: [],
      [s2.id]: [],
      [a.id]: ['removeAdmin', 'revokeSessions', 'disable', 'delete'],
      [a2.id]: ['removeAdmin', 'revokeSessions', 'disable', 'delete'],
      [u.id]: ['makeAdmin', 'revokeSessions', 'disable', 'delete'],
    });
    // destructive items in red (the search box still holds u.email)
    await expect(row(s.page, u.id)).toBeVisible();
    await row(s.page, u.id).getByTestId('admin-menu-toggle').click();
    await expect(s.page.locator('[data-action="disable"]')).toHaveClass(/admin-menu__item--danger/);
    await expect(s.page.locator('[data-action="delete"]')).toHaveClass(/admin-menu__item--danger/);
    await expect(s.page.locator('[data-action="revokeSessions"]')).not.toHaveClass(/danger/);
    await shot(s.page, 'ac15-menu-open');
    await s.page.keyboard.press('Escape');
    assertClean(a.w, 'admin');
    assertClean(s.w, 'super admin');
  });

  test('AC15: UI on 403 INSUFFICIENT_ROLE, 404 USER_NOT_FOUND, and a lowered own role', async ({ browser }) => {
    const a = await account(browser, 'ac15ui', 'ADMIN');
    const t1 = await account(browser, 'ac15t1');
    const t2 = await account(browser, 'ac15t2');

    // t1 became admin meanwhile → "You don't have permission to do this." + refreshed table
    await openAdmin(a.page, t1.email);
    await startAction(a.page, t1.id, 'disable');
    setRole(t1.email, 'ADMIN');
    await a.page.getByTestId('admin-action-confirm').click();
    await expect(a.page.getByTestId('admin-action-error')).toHaveText(NO_PERMISSION);
    await expect(row(a.page, t1.id).getByTestId('admin-role')).toHaveText('Admin');
    await shot(a.page, 'ac15-no-permission');
    await a.page.getByRole('button', { name: 'Cancel' }).click();
    await expect(row(a.page, t1.id).getByTestId('admin-menu-toggle')).toHaveCount(0);
    expect(count(`SELECT COUNT(*) FROM users WHERE id = ${t1.id} AND disabled_at IS NULL`)).toBe(1);

    // t2 deleted meanwhile → "This user no longer exists" + refreshed table
    await search(a.page, t2.email);
    await startAction(a.page, t2.id, 'revokeSessions');
    sql(`DELETE FROM users WHERE id = ${t2.id}`);
    await a.page.getByTestId('admin-action-confirm').click();
    await expect(a.page.getByTestId('admin-action-error')).toHaveText(USER_GONE);
    await expect(a.page.getByTestId('admin-empty')).toHaveText(NOT_FOUND_TEXT);
    await shot(a.page, 'ac15-user-gone');
    await a.page.getByRole('button', { name: 'Cancel' }).click();

    // own role lowered meanwhile → checked against the new role (403 FORBIDDEN), leaves the admin page
    const t3 = await account(browser, 'ac15t3');
    await search(a.page, t3.email);
    await startAction(a.page, t3.id, 'disable');
    setRole(a.email, 'USER');
    const resp = a.page.waitForResponse((r) => r.url().includes(`/api/admin/users/${t3.id}/disable`));
    await a.page.getByTestId('admin-action-confirm').click();
    const r = await resp;
    expect(r.status()).toBe(403);
    expect(((await r.json()) as { code: string }).code).toBe('FORBIDDEN');
    await expect(a.page).toHaveURL(/\/tasks$/);
    await expect(a.page.getByTestId('admin-link')).toHaveCount(0);
    expect(count(`SELECT COUNT(*) FROM users WHERE id = ${t3.id} AND disabled_at IS NULL`)).toBe(1);
    // admin lowered: an ADMIN acting on an ADMIN → INSUFFICIENT_ROLE immediately
    setRole(a.email, 'ADMIN');
    const r2 = await call(a.ctx, 'POST', `/api/admin/users/${t1.id}/disable`);
    expect(r2.status()).toBe(403);
    expect(((await r2.json()) as { code: string }).code).toBe('INSUFFICIENT_ROLE');
    assertClean(a.w, 'admin');
  });

  test('AC15: 409 CANNOT_MODIFY_SELF shows the agreed message in the dialog (forced row)', async ({ browser }) => {
    // The UI never offers actions on yourself; force the request to the own id to see the message.
    const a = await account(browser, 'ac15self', 'ADMIN');
    const t = await account(browser, 'ac15selft');
    await openAdmin(a.page, t.email);
    await a.page.route(`**/api/admin/users/${t.id}/disable`, (route) =>
      route.continue({ url: route.request().url().replace(`/users/${t.id}/`, `/users/${a.id}/`) }),
    );
    await startAction(a.page, t.id, 'disable');
    await a.page.getByTestId('admin-action-confirm').click();
    await expect(a.page.getByTestId('admin-action-error')).toHaveText(
      "You can't do this to your own account. Use your Account page instead.",
    );
    assertClean(a.w, 'admin');
  });

  test('AC20: CSRF required on every admin change', async ({ browser }) => {
    const s = await account(browser, 'ac20s', 'SUPER_ADMIN');
    const t = await account(browser, 'ac20t');
    const paths: [string, string, unknown?][] = [
      ['POST', `/api/admin/users/${t.id}/disable`],
      ['POST', `/api/admin/users/${t.id}/enable`],
      ['POST', `/api/admin/users/${t.id}/revoke-sessions`],
      ['PUT', `/api/admin/users/${t.id}/role`, { role: 'ADMIN' }],
      ['DELETE', `/api/admin/users/${t.id}`],
    ];
    for (const [m, p, d] of paths) {
      const r = await call(s.ctx, m, p, d, false);
      expect(r.status(), `${m} ${p}`).toBe(403);
      expect(((await r.json()) as { code: string }).code).toBe('CSRF_INVALID');
      const bad = await s.ctx.request.fetch(p, { method: m, data: d, headers: { 'X-XSRF-TOKEN': 'forged' } });
      expect(bad.status()).toBe(403);
    }
    expect(dbRole(t.id)).toBe('USER');
    expect((await me(t.ctx)).status).toBe(200);
    // the list is a GET: no CSRF needed
    expect((await s.ctx.request.get('/api/admin/users')).status()).toBe(200);
  });

  test('AC17: audit lines for every admin action and refusal, no secrets', async ({ browser }) => {
    test.skip(!LOG, 'needs E2E_BACKEND_LOG');
    const s = await account(browser, 'ac17s', 'SUPER_ADMIN');
    const a = await account(browser, 'ac17a', 'ADMIN');
    const t = await account(browser, 'ac17t');
    const rm = await signIn(browser, t.email, true);
    const rmCookie = (await rm.ctx.cookies()).find((c) => c.name === 'remember-me')!.value;
    const sessionId = (await t.ctx.cookies()).find((c) => c.name === 'JSESSIONID')!.value;
    const ok = async (m: string, p: string, d?: unknown) => expect((await call(s.ctx, m, p, d)).status()).toBeLessThan(300);
    await ok('POST', `/api/admin/users/${t.id}/disable`);
    await ok('POST', `/api/admin/users/${t.id}/enable`);
    await ok('POST', `/api/admin/users/${t.id}/revoke-sessions`);
    await ok('PUT', `/api/admin/users/${t.id}/role`, { role: 'ADMIN' });
    await ok('PUT', `/api/admin/users/${t.id}/role`, { role: 'USER' });
    await call(a.ctx, 'POST', `/api/admin/users/${s.id}/disable`); // refused
    await call(a.ctx, 'PUT', `/api/admin/users/${t.id}/role`, { role: 'ADMIN' }); // refused
    await ok('DELETE', `/api/admin/users/${t.id}`);
    const A = `actor_id=${s.id} target_id=${t.id}`;
    await expectAudit(new RegExp(`event=ADMIN_USER_DISABLED ${A}`));
    await expectAudit(new RegExp(`event=ADMIN_USER_ENABLED ${A}`));
    await expectAudit(new RegExp(`event=ADMIN_USER_SESSIONS_REVOKED ${A}`));
    await expectAudit(new RegExp(`event=ADMIN_ROLE_CHANGED ${A}.*from=USER.*to=ADMIN`));
    await expectAudit(new RegExp(`event=ADMIN_ROLE_CHANGED ${A}.*from=ADMIN.*to=USER`));
    await expectAudit(new RegExp(`event=ADMIN_USER_DELETED ${A}`));
    await expectAudit(new RegExp(`event=ADMIN_ACCESS_DENIED actor_id=${a.id} target_id=${s.id} .*reason=`));
    await expectAudit(new RegExp(`event=ADMIN_ACCESS_DENIED actor_id=${a.id} target_id=${t.id} .*reason=`));
    // every line carries a timestamp; exactly one line per change
    const mine = auditLines().filter((l) => l.includes(A));
    for (const l of mine) expect(l).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}/);
    expect(mine.filter((l) => l.includes('event=ADMIN_USER_DISABLED')).length).toBe(1);
    expect(mine.filter((l) => l.includes('event=ADMIN_USER_DELETED')).length).toBe(1);
    const log = readFileSync(LOG!, 'utf8');
    expect(log.includes(PASSWORD), 'password in log').toBe(false);
    for (const part of rmCookie.split('.')) expect(log.includes(part), 'remember-me in log').toBe(false);
    expect(log.includes(sessionId), 'session id in log').toBe(false);
  });

  test('UI: phone layout stacks the table as cards', async ({ browser }) => {
    const s = await account(browser, 'mobile', 'SUPER_ADMIN');
    await s.page.setViewportSize({ width: 390, height: 844 });
    await openAdmin(s.page);
    await expect(s.page.getByTestId('admin-row').first()).toBeVisible();
    await shot(s.page, 'ui-phone');
    await s.page.screenshot({ path: `${SHOTS}/ui-phone-top.png` });
    const width = await s.page.evaluate(() => document.documentElement.scrollWidth);
    expect(width).toBeLessThanOrEqual(390);
    assertClean(s.w, 'phone');
  });
});

// =============================================================================================================
// E2E_RATE_LIMIT=1: freshly started backend with DEFAULT limits (60 admin actions per minute per admin).

test.describe('admin rate limit (real backend limits)', () => {
  test.skip(!REAL_RL, 'set E2E_RATE_LIMIT=1 against a fresh backend with default limits');

  test('AC20: 61st admin change within a minute → 429; dialog shows the message; list not limited', async ({ browser }) => {
    const a = await account(browser, 'rl', 'ADMIN');
    const t = await account(browser, 'rlt');
    const other = await account(browser, 'rlo', 'ADMIN');
    await openAdmin(a.page, t.email);
    // validation 400s are not counted
    for (let i = 0; i < 5; i++) {
      expect((await call(a.ctx, 'POST', '/api/admin/users/abc/disable')).status()).toBe(400);
    }
    for (let i = 0; i < 60; i++) {
      const r = await call(a.ctx, 'POST', `/api/admin/users/${a.id}/disable`); // refused 409s count
      expect(r.status(), `request ${i + 1}`).toBe(409);
    }
    const r = await call(a.ctx, 'POST', `/api/admin/users/${t.id}/revoke-sessions`);
    expect(r.status()).toBe(429);
    expect(Number(r.headers()['retry-after'])).toBeGreaterThan(0);
    expect(await r.json()).toMatchObject({ status: 429, code: 'RATE_LIMITED' });
    expect((await me(t.ctx)).status).toBe(200); // nothing changed
    // GET list not limited
    for (let i = 0; i < 5; i++) expect((await a.ctx.request.get('/api/admin/users')).status()).toBe(200);
    // per admin: another admin is not affected
    expect((await call(other.ctx, 'POST', `/api/admin/users/${t.id}/revoke-sessions`)).status()).toBe(200);
    // UI message inside the dialog
    await startAction(a.page, t.id, 'disable');
    await a.page.getByTestId('admin-action-confirm').click();
    await expect(a.page.getByTestId('admin-action-error')).toHaveText(RATE_LIMITED);
    await shot(a.page, 'ac20-rate-limited');
    await expect(a.page.getByTestId('admin-action-confirm')).toBeEnabled();
    assertClean(a.w, 'admin');
  });
});
