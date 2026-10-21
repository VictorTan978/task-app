import { Browser, BrowserContext, expect, Page, request, Response, Route, test } from '@playwright/test';
import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';

/**
 * Sign in with Google (specs/google-sign-in.md, AC1–AC20 + constraints), against the fake Google
 * (`docker compose up -d db mail oidc`, mock-oauth2-server on http://localhost:8090/default).
 *
 * Default run: a dev backend (SPRING_PROFILES_ACTIVE=dev, Google = the fake provider) with the e2e per-IP
 * overrides (register, login, forgot, verify, check, reset, google start, google callback raised), frontend at
 * E2E_BASE_URL (default http://localhost:4200), the backend's APP_BASE_URL = E2E_APP_BASE_URL (default E2E_BASE_URL).
 * Uses `docker compose exec db mysql` (as the migration user) to confirm emails and to check connection rows.
 *
 * Optional:
 *   E2E_BACKEND_LOG=<backend console log>  AC18 audit lines and "no code / state / token / secret in the logs".
 *   E2E_MAILPIT=<url>                       Mailpit API (default http://localhost:8025).
 *
 * Gated modes (each runs ONLY its own tests):
 *   E2E_GOOGLE_OFF=1      backend started with GOOGLE_CLIENT_ID= GOOGLE_CLIENT_SECRET= (feature off, AC1 / 404s).
 *   E2E_RATE_LIMIT=1      freshly started backend with DEFAULT Google limits (AC19: 20/min start, 20/min callback).
 *   E2E_SHORT_SESSIONS=1  backend with SERVER_SERVLET_SESSION_TIMEOUT=1m SESSION_MAX_LENGTH=2m (AC7 timing).
 */

const RUN = `${Date.now()}-${Math.floor(Math.random() * 1e6)}`;
const BASE = process.env['E2E_BASE_URL'] ?? 'http://localhost:4200';
const APP_BASE = process.env['E2E_APP_BASE_URL'] ?? BASE;
const OIDC = 'http://localhost:8090/default';
const MAILPIT = process.env['E2E_MAILPIT'] ?? 'http://localhost:8025';
const LOG = process.env['E2E_BACKEND_LOG'];
const OFF = process.env['E2E_GOOGLE_OFF'] === '1';
const REAL_RL = process.env['E2E_RATE_LIMIT'] === '1';
const SHORT = process.env['E2E_SHORT_SESSIONS'] === '1';
const SPECIAL = OFF || REAL_RL || SHORT;
const REPO = '..';
const SHOTS = '../test-results/google-sign-in';
const SECRET = 'task-app-dev-secret';

const PASSWORD = 'password123';
const NEW_PASSWORD = 'n3w-Google-pw!';
const FAILED = 'Google sign-in was cancelled or failed. Please try again.';
const UNVERIFIED =
  "Google hasn't confirmed this email address, or it belongs to an account that isn't connected yet. Sign in with your email and password, or create an account.";
const CONNECTED = 'Google connected';
const DISCONNECTED = 'Google disconnected';
const IN_USE = 'This Google account is already connected to another account.';
const CONNECT_FAILED = "Couldn't connect Google. Please try again.";
const NO_PASSWORD =
  "You sign in with Google. To also sign in with a password, set one with 'Forgot password?' on the sign-in page.";
const SET_PASSWORD_FIRST = "Set a password first so you don't lose access to your account.";
const GENERIC = 'Something went wrong, please try again';
const INVALID = 'Invalid email or password';
const RESET_SUBJECT = 'Reset your Task App password';
const ACCOUNT_DELETED = 'Your account has been deleted';

let n = 0;
function uid(): string {
  n += 1;
  return `${RUN}-${n}`;
}
function email(tag: string): string {
  return `qa-g-${tag}-${uid()}@example.com`;
}
function sub(tag: string): string {
  return `g-${tag}-${uid()}`;
}
/** Display names must be unique ignoring case/accents across the whole DB. */
function uname(tag: string): string {
  return `G ${tag} ${uid()}`;
}

type Claims = Record<string, unknown>;

// ---------------------------------------------------------------- browser helpers

function watch(page: Page) {
  const consoleErrors: string[] = [];
  const pageErrors: string[] = [];
  const serverErrors: string[] = [];
  const apiBodies: Promise<string>[] = [];
  page.on('console', (m) => {
    if (m.type() === 'error') consoleErrors.push(m.text());
  });
  page.on('pageerror', (e) => pageErrors.push(e.message));
  page.on('response', (r: Response) => {
    if (!r.url().includes('/api/')) return;
    if (r.status() >= 500) serverErrors.push(`${r.status()} ${r.request().method()} ${r.url()}`);
    apiBodies.push(
      r
        .text()
        .then((t) => `${r.url()} ${JSON.stringify(r.headers())} ${t}`)
        .catch(() => ''),
    );
  });
  return { consoleErrors, pageErrors, serverErrors, apiBodies };
}
type Watch = ReturnType<typeof watch>;

/** Expected 4xx are logged by Chrome as "Failed to load resource"; anything else is an app error. */
function assertClean(w: Watch, label: string, allow: RegExp[] = []) {
  const app = w.consoleErrors.filter(
    (e) =>
      !/^Failed to load resource: the server responded with a status of 4\d\d/.test(e) && !allow.some((a) => a.test(e)),
  );
  console.log(`${label} console errors:`, JSON.stringify(w.consoleErrors));
  expect(app, `${label}: browser console errors`).toEqual([]);
  expect(w.pageErrors, `${label}: page errors`).toEqual([]);
  expect(w.serverErrors, `${label}: 5xx`).toEqual([]);
}

async function newPage(browser: Browser): Promise<{ ctx: BrowserContext; page: Page; w: Watch }> {
  const ctx = await browser.newContext({ baseURL: BASE });
  const page = await ctx.newPage();
  return { ctx, page, w: watch(page) };
}

async function shot(page: Page, name: string) {
  await page.screenshot({ path: `${SHOTS}/${name}.png`, fullPage: true });
}

async function cookie(ctx: BrowserContext, name: string) {
  return (await ctx.cookies()).find((c) => c.name === name);
}

async function xsrf(ctx: BrowserContext, page: Page): Promise<string> {
  let c = await cookie(ctx, 'XSRF-TOKEN');
  if (!c && page.url() !== 'about:blank') {
    // The app's own boot request (GET /api/auth/me) sets the cookie; firing our own GET would issue a
    // different token that the late boot response then overwrites.
    for (let i = 0; i < 50 && !c; i++) {
      await page.waitForTimeout(100);
      c = await cookie(ctx, 'XSRF-TOKEN');
    }
  }
  if (!c) {
    await page.request.get('/api/auth/me');
    c = await cookie(ctx, 'XSRF-TOKEN');
  }
  return c!.value;
}

async function api(
  ctx: BrowserContext,
  page: Page,
  method: 'POST' | 'PUT' | 'DELETE',
  path: string,
  body?: unknown,
  csrf = true,
) {
  const headers: Record<string, string> = {};
  if (csrf) headers['X-XSRF-TOKEN'] = await xsrf(ctx, page);
  return page.request.fetch(path, { method, data: body, headers });
}

interface User {
  id: number;
  email: string;
  displayName: string;
  emailVerified: boolean;
  hasPassword: boolean;
  connections: { provider: string; email: string }[];
}

async function me(page: Page): Promise<{ status: number; body: User | null }> {
  const r = await page.request.get('/api/auth/me');
  return { status: r.status(), body: r.status() === 200 ? ((await r.json()) as User) : null };
}

function expectUserShape(u: User) {
  expect(Object.keys(u).sort()).toEqual(['connections', 'displayName', 'email', 'emailVerified', 'hasPassword', 'id', 'reminderEmails', 'role', 'timeZone']); // role in 0.9.0; reminderEmails + timeZone in 0.14.0
  expect(typeof u.hasPassword).toBe('boolean');
  expect(Array.isArray(u.connections)).toBe(true);
  for (const c of u.connections) expect(Object.keys(c).sort()).toEqual(['email', 'provider']);
}

async function registerApi(ctx: BrowserContext, page: Page, mail: string, name = uname('pw'), password = PASSWORD) {
  const r = await api(ctx, page, 'POST', '/api/auth/register', { displayName: name, email: mail, password });
  expect(r.status(), await r.text()).toBe(201);
  return (await r.json()) as User;
}

async function logoutApi(ctx: BrowserContext, page: Page) {
  const r = await api(ctx, page, 'POST', '/api/auth/logout');
  expect(r.status()).toBe(204);
}

async function loginUi(page: Page, mail: string, password = PASSWORD) {
  await page.goto('/login');
  await page.locator('#login-email').fill(mail);
  await page.locator('#login-password').fill(password);
  await page.getByRole('button', { name: 'Sign in' }).click();
}

async function logoutUi(page: Page) {
  await page.getByRole('button', { name: 'Log out', exact: true }).click();
  await expect(page).toHaveURL(/\/login$/);
}

/** A separate browser with a password account (optionally confirmed); signed out at the end. */
async function passwordAccount(browser: Browser, mail: string, confirmed: boolean, name = uname('pw')) {
  const a = await newPage(browser);
  await a.page.goto('/login');
  const u = await registerApi(a.ctx, a.page, mail, name);
  await logoutApi(a.ctx, a.page);
  if (confirmed) confirmDb(mail);
  await a.ctx.close();
  return u;
}

// ---------------------------------------------------------------- fake Google helpers

function onGoogle(url: URL): boolean {
  return url.href.startsWith(`${OIDC}/authorize`);
}

/** Back in the app (not on Google, not on an /api URL). */
function backInApp(url: URL): boolean {
  return url.origin === new URL(BASE).origin && !url.pathname.startsWith('/api/');
}

async function approveAtGoogle(page: Page, googleSub: string, claims: Claims) {
  await page.waitForURL(onGoogle);
  await page.locator('input[name="username"]').fill(googleSub);
  await page.locator('textarea[name="claims"]').fill(JSON.stringify(claims));
  await page.locator('input[type="submit"]').click();
}

/** "Continue with Google" from /login (or /register), approve, wait until back in the app. */
async function google(page: Page, googleSub: string, claims: Claims, from = '/login') {
  await page.goto(from);
  await page.getByTestId('google-button').click();
  await approveAtGoogle(page, googleSub, claims);
  await page.waitForURL(backInApp);
}

/** "Connect Google" on /account, approve, wait until back in the app. */
async function connect(page: Page, googleSub: string, claims: Claims) {
  await page.goto('/account');
  await page.locator('#account-connect-google').click();
  await approveAtGoogle(page, googleSub, claims);
  await page.waitForURL(backInApp);
}

/**
 * Approves at Google but does NOT deliver the callback: the fake provider's 302 is read and replaced by a blank
 * page, so the callback URL (with a real code and state) can be opened elsewhere.
 */
async function captureCallback(page: Page, googleSub: string, claims: Claims): Promise<string> {
  let location = '';
  const handler = async (route: Route) => {
    if (route.request().method() !== 'POST') return route.continue();
    const resp = await route.fetch({ maxRedirects: 0 });
    location = resp.headers()['location'] ?? '';
    await route.fulfill({ status: 200, contentType: 'text/html', body: '<p>captured</p>' });
  };
  await page.route(`${OIDC}/authorize**`, handler);
  await approveAtGoogle(page, googleSub, claims);
  await expect.poll(() => location).not.toBe('');
  await page.unroute(`${OIDC}/authorize**`, handler);
  expect(location).toContain('/api/auth/oauth2/callback/google?');
  return location;
}

/** verified: true/false sets email_verified; null omits the claim. */
function googleUser(mail: string, name?: string, verified: boolean | null = true): Claims {
  const c: Claims = { email: mail };
  if (verified !== null) c['email_verified'] = verified;
  if (name !== undefined) c['name'] = name;
  return c;
}

// ---------------------------------------------------------------- DB / Mailpit / log helpers

function sql(query: string): string {
  return execFileSync(
    'docker',
    ['compose', 'exec', '-T', 'db', 'mysql', '-utaskapp', '-ptaskapp', 'taskapp', '-N', '-B', '-e', query],
    { cwd: REPO, encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] },
  ).trim();
}
function confirmDb(mail: string) {
  sql(`UPDATE users SET email_verified_at = NOW(6) WHERE email = '${mail}'`);
}
function usersWithEmail(mail: string): number {
  return Number(sql(`SELECT COUNT(*) FROM users WHERE email = '${mail.toLowerCase()}'`));
}
function connectionsOfSub(googleSub: string): { userId: number; email: string }[] {
  const out = sql(
    `SELECT user_id, provider_email FROM user_connections WHERE provider = 'google' AND provider_subject = '${googleSub}'`,
  );
  return out
    ? out.split('\n').map((l) => {
        const [userId, mail] = l.split('\t');
        return { userId: Number(userId), email: mail };
      })
    : [];
}

interface MailSummary { ID: string; Subject: string; To: { Address: string }[] }
async function waitForMail(to: string, subject: string): Promise<string> {
  let found: MailSummary | undefined;
  await expect
    .poll(
      async () => {
        const r = await fetch(`${MAILPIT}/api/v1/search?limit=50&query=${encodeURIComponent(`to:"${to}"`)}`);
        const j = (await r.json()) as { messages?: MailSummary[] };
        found = (j.messages ?? []).find((m) => m.Subject === subject);
        return !!found;
      },
      { timeout: 20_000, message: `"${subject}" to ${to}` },
    )
    .toBe(true);
  const msg = (await (await fetch(`${MAILPIT}/api/v1/message/${found!.ID}`)).json()) as { Text: string };
  const m = msg.Text.match(/https?:\/\/[^\s"'<>]+(\/reset-password\?token=[A-Za-z0-9_-]+)/);
  expect(m, 'reset link').toBeTruthy();
  return m![1];
}

function logText(): string {
  return readFileSync(LOG!, 'utf8');
}
function securityLines(mail: string): string[] {
  return logText()
    .split('\n')
    .filter((l) => l.includes('SECURITY event=') && l.toLowerCase().includes(`email=${mail.toLowerCase()} `))
    .map((l) => l.slice(l.indexOf('SECURITY event=')).replace(/ ip=\S+/, ''));
}
async function expectLog(mail: string, re: RegExp) {
  await expect
    .poll(() => securityLines(mail).some((l) => re.test(l)), { message: `log ${re} for ${mail}`, timeout: 8000 })
    .toBe(true);
}

/** Every code / state / nonce / token value seen during the run, checked against the log in AC18. */
const SECRETS_SEEN = new Set<string>();
function remember(callbackUrl: string) {
  const u = new URL(callbackUrl);
  for (const k of ['code', 'state']) {
    const v = u.searchParams.get(k);
    if (v) SECRETS_SEEN.add(v);
  }
}

test.beforeAll(() => {
  execFileSync('mkdir', ['-p', SHOTS]);
});

// =============================================================================================================
// Default run (Google on)
// =============================================================================================================

test.describe('Google on', () => {
  test.skip(SPECIAL, 'a gated mode runs only its own tests');
  test.describe.configure({ timeout: 120_000 });

  test('AC1: /login and /register show "Continue with Google" below the form with an "or" divider', async ({ browser }) => {
    const a = await newPage(browser);
    const providers = await a.page.request.get('/api/auth/providers');
    expect(providers.status()).toBe(200);
    expect(await providers.json()).toEqual({ google: true });

    for (const [path, submit] of [
      ['/login', 'Sign in'],
      ['/register', 'Create account'],
    ] as const) {
      await a.page.goto(path);
      const btn = a.page.getByTestId('google-button');
      await expect(btn).toBeVisible();
      await expect(btn).toHaveText('Continue with Google');
      await expect(btn).toBeEnabled();
      await expect(btn.locator('svg')).toBeVisible(); // Google "G"
      const divider = a.page.locator('.google-sign-in__divider');
      await expect(divider).toHaveText('or');
      const formBtn = await a.page.getByRole('button', { name: submit, exact: true }).boundingBox();
      const div = await divider.boundingBox();
      const gb = await btn.boundingBox();
      expect(div!.y, 'divider below the form').toBeGreaterThan(formBtn!.y);
      expect(gb!.y, 'button below the divider').toBeGreaterThan(div!.y);
      // white button (Google branding)
      const bg = await btn.evaluate((e) => getComputedStyle(e).backgroundColor);
      expect(bg).toBe('rgb(255, 255, 255)');
      await shot(a.page, `ac1-${path.slice(1)}`);
    }
    assertClean(a.w, 'AC1');
    await a.ctx.close();
  });

  test('AC2 + AC16: new sign-up lands on /tasks, confirmed, no password; authorize request has state, nonce, PKCE', async ({ browser }) => {
    const a = await newPage(browser);
    const s = sub('ac2');
    const mail = email('ac2');
    const name = uname('Ana López');
    await a.page.goto('/register');
    await a.page.getByTestId('google-button').click();
    await a.page.waitForURL(onGoogle);

    // AC16: the authorization request
    const auth = new URL(a.page.url());
    const p = auth.searchParams;
    expect(p.get('response_type')).toBe('code');
    expect(p.get('client_id')).toBe('task-app-dev');
    expect(p.get('scope')!.split(' ').sort()).toEqual(['email', 'openid', 'profile']);
    expect(p.get('state')!.length).toBeGreaterThanOrEqual(20);
    expect(p.get('nonce')!.length).toBeGreaterThanOrEqual(20);
    expect(p.get('code_challenge')!.length).toBeGreaterThanOrEqual(43);
    expect(p.get('code_challenge_method')).toBe('S256');
    expect(p.get('redirect_uri')).toBe(`${APP_BASE}/api/auth/oauth2/callback/google`);
    expect(auth.href).not.toContain(SECRET);
    expect(auth.href).not.toContain('client_secret');
    await shot(a.page, 'ac2-fake-google');

    await a.page.locator('input[name="username"]').fill(s);
    // the Google email in mixed case: stored normalized
    await a.page.locator('textarea[name="claims"]').fill(JSON.stringify(googleUser(mail.toUpperCase(), name)));
    await a.page.locator('input[type="submit"]').click();
    await expect(a.page).toHaveURL(/\/tasks$/);
    expect(a.page.url()).not.toMatch(/code=|state=|oauth=/);
    // T3a: both the "My tasks" heading and the add box are visible now, so take the first match.
    await expect(a.page.getByRole('heading', { name: 'My tasks' }).or(a.page.getByLabel('New task')).first()).toBeVisible();
    await expect(a.page.getByTestId('verify-email-banner')).toHaveCount(0);
    await expect(a.page.locator('header')).toContainText(name);

    const m = await me(a.page);
    expect(m.status).toBe(200);
    expectUserShape(m.body!);
    expect(m.body).toMatchObject({
      email: mail,
      displayName: name,
      emailVerified: true,
      hasPassword: false,
      connections: [{ provider: 'google', email: mail }],
    });
    expect(connectionsOfSub(s)).toEqual([{ userId: m.body!.id, email: mail }]);
    // still no banner after a reload
    await a.page.reload();
    await expect(a.page.getByLabel('New task')).toBeVisible();
    await expect(a.page.getByTestId('verify-email-banner')).toHaveCount(0);
    await shot(a.page, 'ac2-tasks-after-signup');
    assertClean(a.w, 'AC2');
    await a.ctx.close();
  });

  test('AC3: signing in again with the same Google account reaches the same account', async ({ browser }) => {
    const a = await newPage(browser);
    const s = sub('ac3');
    const mail = email('ac3');
    await google(a.page, s, googleUser(mail, uname('ac3')));
    await expect(a.page).toHaveURL(/\/tasks$/);
    const first = (await me(a.page)).body!;
    await a.page.getByLabel('New task').fill(`task ${RUN}`);
    await a.page.getByRole('button', { name: 'Add', exact: true }).click();
    await logoutUi(a.page);
    await expect(a.page.getByTestId('google-button')).toBeVisible();

    // a different browser, same Google account
    const b = await newPage(browser);
    await google(b.page, s, googleUser(mail, 'Another Name'));
    await expect(b.page).toHaveURL(/\/tasks$/);
    const again = (await me(b.page)).body!;
    expect(again.id).toBe(first.id);
    expect(again.displayName).toBe(first.displayName); // not overwritten by Google's name
    await expect(b.page.getByRole('checkbox', { name: `Done: task ${RUN}` })).toBeVisible();
    expect(usersWithEmail(mail)).toBe(1);
    await shot(b.page, 'ac3-same-account');
    assertClean(a.w, 'AC3 a');
    assertClean(b.w, 'AC3 b');
    await a.ctx.close();
    await b.ctx.close();
  });

  test('AC4: display-name rules (cut to 50, taken → " 2"/" 3" ignoring case and accents, empty → email local part, editable)', async ({ browser }) => {
    const ctxs: BrowserContext[] = [];
    async function signUp(claims: Claims): Promise<User> {
      const a = await newPage(browser);
      ctxs.push(a.ctx);
      await google(a.page, sub('ac4'), claims);
      await expect(a.page).toHaveURL(/\/tasks$/);
      const u = (await me(a.page)).body!;
      assertClean(a.w, 'AC4');
      return u;
    }
    const tag = uid().replace(/-/g, '');
    // 60 characters → first 50
    const long = `Long${tag}`.padEnd(60, 'x');
    expect((await signUp(googleUser(email('ac4long'), long))).displayName).toBe(long.slice(0, 50));
    // padded name is trimmed
    const padded = `Trim ${tag}`;
    expect((await signUp(googleUser(email('ac4trim'), `   ${padded}   `))).displayName).toBe(padded);

    // taken name: José → "José 2", "José 3"; accent/case variants count as taken
    const base = `José ${tag}`;
    const owner = await newPage(browser);
    ctxs.push(owner.ctx);
    await owner.page.goto('/login');
    await registerApi(owner.ctx, owner.page, email('ac4owner'), `jose ${tag}`); // case + accent variant
    expect((await signUp(googleUser(email('ac4a'), base))).displayName).toBe(`${base} 2`);
    expect((await signUp(googleUser(email('ac4b'), `JOSÉ ${tag}`))).displayName).toBe(`JOSÉ ${tag} 3`);

    // taken 50-character name: cut further so "<name> 2" stays within 50
    const fifty = `Fifty${tag}`.padEnd(50, 'y');
    expect((await signUp(googleUser(email('ac4f1'), fifty))).displayName).toBe(fifty);
    const second = (await signUp(googleUser(email('ac4f2'), fifty))).displayName;
    expect(second.length).toBeLessThanOrEqual(50);
    expect(second).toBe(`${fifty.slice(0, 48)} 2`);

    // empty / missing / blank name → part of the email before "@"
    const m1 = email('ac4empty');
    expect((await signUp(googleUser(m1, ''))).displayName).toBe(m1.split('@')[0]);
    const m2 = email('ac4none');
    expect((await signUp(googleUser(m2))).displayName).toBe(m2.split('@')[0]);
    const m3 = email('ac4blank');
    expect((await signUp(googleUser(m3, '    '))).displayName).toBe(m3.split('@')[0]);

    // can change it later on /account
    const a = await newPage(browser);
    ctxs.push(a.ctx);
    await google(a.page, sub('ac4edit'), googleUser(email('ac4edit'), `Edit ${tag}`));
    await a.page.goto('/account');
    const field = a.page.locator('#account-display-name');
    await expect(field).toHaveValue(`Edit ${tag}`);
    await field.fill(`Edited ${tag}`);
    await a.page.getByRole('button', { name: 'Save' }).first().click();
    await expect(a.page.getByText('Display name updated')).toBeVisible();
    expect((await me(a.page)).body!.displayName).toBe(`Edited ${tag}`);
    await shot(a.page, 'ac4-name-edited');
    assertClean(a.w, 'AC4 edit');
    for (const c of ctxs) await c.close();
  });

  test('AC5a: confirmed password account + verified Google email (case/accent variant) → linked and signed in', async ({ browser }) => {
    const n0 = uid().replace(/-/g, '');
    const mail = `jose${n0}@example.com`;
    const pw = await passwordAccount(browser, mail, true);
    const s = sub('ac5a');
    const a = await newPage(browser);
    // accent + upper-case variant of the same email
    await google(a.page, s, googleUser(`JOSÉ${n0}@EXAMPLE.COM`, 'Some Google Name'));
    await expect(a.page).toHaveURL(/\/tasks$/);
    const u = (await me(a.page)).body!;
    expect(u.id).toBe(pw.id);
    expect(u.displayName).toBe(pw.displayName);
    expect(u.hasPassword).toBe(true);
    expect(u.connections).toEqual([{ provider: 'google', email: `josé${n0}@example.com` }]);
    expect(connectionsOfSub(s).map((c) => c.userId)).toEqual([pw.id]);
    await shot(a.page, 'ac5a-linked');
    // the password still works
    await logoutUi(a.page);
    await loginUi(a.page, mail);
    await expect(a.page).toHaveURL(/\/tasks$/);
    assertClean(a.w, 'AC5a');
    await a.ctx.close();
  });

  test('AC5b: unconfirmed password account + verified Google → not linked, unverified notice', async ({ browser }) => {
    const mail = email('ac5b');
    const pw = await passwordAccount(browser, mail, false);
    const s = sub('ac5b');
    const a = await newPage(browser);
    await google(a.page, s, googleUser(mail, 'Stranger'));
    await expect(a.page).toHaveURL(/\/login$/);
    await expect(a.page.getByTestId('oauth-notice')).toHaveText(UNVERIFIED);
    expect((await me(a.page)).status).toBe(401);
    expect(connectionsOfSub(s)).toEqual([]);
    expect(usersWithEmail(mail)).toBe(1);
    await shot(a.page, 'ac5b-unverified-notice');
    // a refresh does not repeat the notice
    await a.page.reload();
    await expect(a.page.getByTestId('google-button')).toBeVisible();
    await expect(a.page.getByTestId('oauth-notice')).toHaveCount(0);
    // the account itself is untouched
    await loginUi(a.page, mail);
    await expect(a.page).toHaveURL(/\/tasks$/);
    const u = (await me(a.page)).body!;
    expect(u.id).toBe(pw.id);
    expect(u.connections).toEqual([]);
    expect(u.emailVerified).toBe(false);
    // "confirm first" path: once confirmed, Google links
    await logoutUi(a.page);
    confirmDb(mail);
    await google(a.page, s, googleUser(mail, 'Stranger'));
    await expect(a.page).toHaveURL(/\/tasks$/);
    expect((await me(a.page)).body!.id).toBe(pw.id);
    assertClean(a.w, 'AC5b');
    await a.ctx.close();
  });

  test('AC5c: confirmed account + Google says not verified (or omits email_verified) → unverified, nothing linked', async ({ browser }) => {
    const mail = email('ac5c');
    const pw = await passwordAccount(browser, mail, true);
    const a = await newPage(browser);
    const s1 = sub('ac5c');
    await google(a.page, s1, googleUser(mail, 'X', false));
    await expect(a.page).toHaveURL(/\/login$/);
    await expect(a.page.getByTestId('oauth-notice')).toHaveText(UNVERIFIED);
    expect(connectionsOfSub(s1)).toEqual([]);
    const s2 = sub('ac5c-missing');
    await google(a.page, s2, googleUser(mail, 'X', null));
    await expect(a.page).toHaveURL(/\/login$/);
    await expect(a.page.getByTestId('oauth-notice')).toHaveText(UNVERIFIED);
    expect(connectionsOfSub(s2)).toEqual([]);
    expect((await me(a.page)).status).toBe(401);
    expect(usersWithEmail(mail)).toBe(1);
    void pw;
    assertClean(a.w, 'AC5c');
    await a.ctx.close();
  });

  test('AC5d: account already connected to Google sub A; Google sub B with the same verified email → unverified', async ({ browser }) => {
    const mail = email('ac5d');
    const pw = await passwordAccount(browser, mail, true);
    const a = await newPage(browser);
    const sA = sub('ac5d-A');
    await google(a.page, sA, googleUser(mail));
    await expect(a.page).toHaveURL(/\/tasks$/);
    await logoutUi(a.page);
    const sB = sub('ac5d-B');
    await google(a.page, sB, googleUser(mail));
    await expect(a.page).toHaveURL(/\/login$/);
    await expect(a.page.getByTestId('oauth-notice')).toHaveText(UNVERIFIED);
    expect((await me(a.page)).status).toBe(401);
    expect(connectionsOfSub(sA).map((c) => c.userId)).toEqual([pw.id]);
    expect(connectionsOfSub(sB)).toEqual([]);
    assertClean(a.w, 'AC5d');
    await a.ctx.close();
  });

  test('AC5e: no account + Google says not verified → nothing created, unverified notice', async ({ browser }) => {
    const mail = email('ac5e');
    const s = sub('ac5e');
    const a = await newPage(browser);
    await google(a.page, s, googleUser(mail, 'Nobody', false));
    await expect(a.page).toHaveURL(/\/login$/);
    await expect(a.page.getByTestId('oauth-notice')).toHaveText(UNVERIFIED);
    expect(usersWithEmail(mail)).toBe(0);
    expect(connectionsOfSub(s)).toEqual([]);
    expect((await me(a.page)).status).toBe(401);
    // the email is still free
    await registerApi(a.ctx, a.page, mail);
    await shot(a.page, 'ac5e-not-created');
    assertClean(a.w, 'AC5e');
    await a.ctx.close();
  });

  test('AC6: cancel at Google → /login failed notice, nothing created', async ({ browser }) => {
    const a = await newPage(browser);
    const mail = email('ac6');
    await a.page.goto('/login');
    await a.page.getByTestId('google-button').click();
    await a.page.waitForURL(onGoogle);
    const state = new URL(a.page.url()).searchParams.get('state')!;
    await a.page.goto(`/api/auth/oauth2/callback/google?error=access_denied&state=${encodeURIComponent(state)}`);
    await expect(a.page).toHaveURL(/\/login$/);
    await expect(a.page.getByTestId('oauth-notice')).toHaveText(FAILED);
    expect((await me(a.page)).status).toBe(401);
    expect(usersWithEmail(mail)).toBe(0);
    await shot(a.page, 'ac6-cancelled');
    assertClean(a.w, 'AC6 cancel');
    await a.ctx.close();
  });

  test('AC6: tampered state, replayed state, unknown state, missing params → failed notice, nothing created', async ({ browser }) => {
    const a = await newPage(browser);
    const mail = email('ac6t');
    const s = sub('ac6t');
    await a.page.goto('/login');
    await a.page.getByTestId('google-button').click();
    const cb = await captureCallback(a.page, s, googleUser(mail));
    remember(cb);
    const u = new URL(cb);
    u.searchParams.set('state', `${u.searchParams.get('state')}x`);
    await a.page.goto(u.pathname + u.search);
    await expect(a.page).toHaveURL(/\/login$/);
    await expect(a.page.getByTestId('oauth-notice')).toHaveText(FAILED);
    // the genuine callback now: the pending request was consumed by the failure → also fails
    await a.page.goto(new URL(cb).pathname + new URL(cb).search);
    await expect(a.page).toHaveURL(/\/login$/);
    await expect(a.page.getByTestId('oauth-notice')).toHaveText(FAILED);
    expect(usersWithEmail(mail)).toBe(0);
    expect(connectionsOfSub(s)).toEqual([]);

    // first flow superseded by a second one in the same browser: its callback fails
    const s2 = sub('ac6t2');
    const mail2 = email('ac6t2');
    await a.page.goto('/login');
    await a.page.getByTestId('google-button').click();
    const cb1 = await captureCallback(a.page, s2, googleUser(mail2));
    remember(cb1);
    await a.page.goto('/login');
    await a.page.getByTestId('google-button').click();
    await a.page.waitForURL(onGoogle);
    await a.page.goto(new URL(cb1).pathname + new URL(cb1).search);
    await expect(a.page).toHaveURL(/\/login$/);
    await expect(a.page.getByTestId('oauth-notice')).toHaveText(FAILED);
    expect(usersWithEmail(mail2)).toBe(0);

    // no params / junk
    for (const q of ['', '?state=abc', '?code=abc', '?code=abc&state=def', '?error=server_error']) {
      const r = await a.page.request.get(`/api/auth/oauth2/callback/google${q}`, { maxRedirects: 0 });
      expect(r.status(), q).toBe(302);
      expect(r.headers()['location'], q).toBe(`${APP_BASE}/login?oauth=google_failed`);
    }
    expect((await me(a.page)).status).toBe(401);
    // unknown oauth codes are ignored and stripped
    await a.page.goto('/login?oauth=bogus');
    await expect(a.page).toHaveURL(/\/login$/);
    await expect(a.page.getByTestId('oauth-notice')).toHaveCount(0);
    assertClean(a.w, 'AC6 tampered');
    await a.ctx.close();
  });

  test('AC7: Google login = normal session (new session id + CSRF token, no remember-me, even if ticked)', async ({ browser }) => {
    const a = await newPage(browser);
    const s = sub('ac7');
    const mail = email('ac7');
    await a.page.goto('/login');
    await a.page.locator('#login-remember-me').check(); // must not apply to Google
    const xsrfBefore = (await cookie(a.ctx, 'XSRF-TOKEN'))!.value;
    await a.page.getByTestId('google-button').click();
    await a.page.waitForURL(onGoogle);
    const anonSession = (await cookie(a.ctx, 'JSESSIONID'))?.value;
    expect(anonSession, 'start creates an anonymous session for the pending request').toBeTruthy();
    const cbResp = a.page.waitForResponse((r) => r.url().startsWith(`${BASE}/api/auth/oauth2/callback/google`));
    await approveAtGoogle(a.page, s, googleUser(mail));
    const resp = await cbResp;
    expect(resp.status()).toBe(302);
    expect(resp.headers()['location']).toBe(`${APP_BASE}/tasks`);
    const setCookies = (await resp.headersArray())
      .filter((h) => h.name.toLowerCase() === 'set-cookie')
      .map((h) => h.value);
    console.log('AC7 callback Set-Cookie:', JSON.stringify(setCookies.map((c) => c.replace(/=[^;]+/, '=<v>'))));
    expect(setCookies.some((c) => /^XSRF-TOKEN=[^;]+/.test(c))).toBe(true);
    await expect(a.page).toHaveURL(/\/tasks$/);

    const sid = await cookie(a.ctx, 'JSESSIONID');
    const tok = await cookie(a.ctx, 'XSRF-TOKEN');
    expect(sid!.value).not.toBe(anonSession);
    expect(sid!.httpOnly).toBe(true);
    expect(sid!.sameSite).toBe('Lax');
    expect(sid!.expires, 'session cookie (no expiry)').toBe(-1);
    expect(tok!.value).not.toBe(xsrfBefore);
    expect(tok!.httpOnly).toBe(false);
    expect(await cookie(a.ctx, 'remember-me'), 'no remember-me cookie').toBeUndefined();
    // the old anonymous session id gives nothing (no fixation)
    const b = await newPage(browser);
    await b.ctx.addCookies([{ name: 'JSESSIONID', value: anonSession!, url: BASE }]);
    expect((await me(b.page)).status).toBe(401);
    // "closing the browser" (only persistent cookies survive) → signed out
    const persistent = (await a.ctx.cookies()).filter((c) => c.expires > 0);
    const c = await newPage(browser);
    await c.ctx.addCookies(persistent);
    expect((await me(c.page)).status).toBe(401);
    // the CSRF token works for state-changing calls
    const r = await api(a.ctx, a.page, 'PUT', '/api/account/display-name', { displayName: uname('ac7') });
    expect(r.status()).toBe(200);
    assertClean(a.w, 'AC7');
    await a.ctx.close();
    await b.ctx.close();
    await c.ctx.close();
  });

  test('AC7: a remember-me cookie of another account is ended by a Google login', async ({ browser }) => {
    const mail = email('ac7rm');
    await passwordAccount(browser, mail, false);
    const a = await newPage(browser);
    await a.page.goto('/login');
    await a.page.locator('#login-email').fill(mail);
    await a.page.locator('#login-password').fill(PASSWORD);
    await a.page.locator('#login-remember-me').check();
    await a.page.getByRole('button', { name: 'Sign in' }).click();
    await expect(a.page).toHaveURL(/\/tasks$/);
    const rm = await cookie(a.ctx, 'remember-me');
    expect(rm).toBeTruthy();
    // log out only the session (keep the remember-me cookie as a stale browser would), then Google
    await a.ctx.clearCookies({ name: 'JSESSIONID' });
    // the start form is reachable from /login only when signed out; remember-me would sign back in, so submit it
    // straight from the page (as the frontend does)
    await a.page.goto('/login');
    await a.page.waitForLoadState('networkidle');
    const onLogin = /\/login$/.test(a.page.url());
    if (onLogin) {
      await a.page.getByTestId('google-button').click();
    } else {
      // remember-me restored the session and guestGuard sent us to /tasks: start a login flow from here
      const t = await xsrf(a.ctx, a.page);
      await a.page.evaluate((token) => {
        const f = document.createElement('form');
        f.method = 'post';
        f.action = '/api/auth/oauth2/authorization/google';
        for (const [k, v] of [
          ['intent', 'login'],
          ['_csrf', token],
        ]) {
          const i = document.createElement('input');
          i.type = 'hidden';
          i.name = k;
          i.value = v;
          f.append(i);
        }
        document.body.append(f);
        f.submit();
      }, t);
    }
    const s = sub('ac7rm');
    const gmail = email('ac7rm-g');
    const cbResp = a.page.waitForResponse((r) => r.url().startsWith(`${BASE}/api/auth/oauth2/callback/google`));
    await approveAtGoogle(a.page, s, googleUser(gmail));
    const resp = await cbResp;
    const setCookies = (await resp.headersArray())
      .filter((h) => h.name.toLowerCase() === 'set-cookie')
      .map((h) => h.value);
    expect(setCookies.some((x) => /^remember-me=.*Max-Age=0/i.test(x))).toBe(true);
    await expect(a.page).toHaveURL(/\/tasks$/);
    expect(await cookie(a.ctx, 'remember-me')).toBeUndefined();
    expect((await me(a.page)).body!.email).toBe(gmail);
    // the old remember-me value no longer signs anyone in
    const b = await newPage(browser);
    await b.ctx.addCookies([{ ...rm!, expires: rm!.expires }]);
    expect((await me(b.page)).status).toBe(401);
    assertClean(a.w, 'AC7 remember-me');
    await a.ctx.close();
    await b.ctx.close();
  });

  test('AC8: a Google-only account gets the usual "Invalid email or password" (same API answer as unknown email)', async ({ browser }) => {
    const a = await newPage(browser);
    const mail = email('ac8');
    await google(a.page, sub('ac8'), googleUser(mail));
    await expect(a.page).toHaveURL(/\/tasks$/);
    await logoutUi(a.page);
    await a.page.locator('#login-email').fill(mail);
    await a.page.locator('#login-password').fill('anything-at-all');
    await a.page.getByRole('button', { name: 'Sign in' }).click();
    await expect(a.page.getByText(INVALID)).toBeVisible();
    await expect(a.page).toHaveURL(/\/login$/);
    await shot(a.page, 'ac8-invalid');

    const b = await newPage(browser);
    await b.page.goto('/login');
    const strip = (x: Record<string, unknown>) => ({ ...x, instance: undefined });
    const r1 = await api(b.ctx, b.page, 'POST', '/api/auth/login', { email: mail, password: 'whatever1' });
    const r2 = await api(b.ctx, b.page, 'POST', '/api/auth/login', { email: email('ac8-unknown'), password: 'whatever1' });
    expect(r1.status()).toBe(401);
    expect(r2.status()).toBe(401);
    expect(strip(await r1.json())).toEqual(strip(await r2.json()));
    expect((await r1.json()).code).toBe('INVALID_CREDENTIALS');
    // empty-string password too
    const r3 = await api(b.ctx, b.page, 'POST', '/api/auth/login', { email: mail, password: ' ' });
    expect([400, 401]).toContain(r3.status());
    // rough timing (informational): google-only vs unknown email
    const time = async (m: string) => {
      const t0 = Date.now();
      await api(b.ctx, b.page, 'POST', '/api/auth/login', { email: m, password: 'whatever1' });
      return Date.now() - t0;
    };
    const tg: number[] = [];
    const tu: number[] = [];
    for (let i = 0; i < 3; i++) {
      tg.push(await time(mail));
      tu.push(await time(email('ac8t')));
    }
    console.log('AC8 timing ms google-only', tg, 'unknown', tu);
    assertClean(a.w, 'AC8');
    await a.ctx.close();
    await b.ctx.close();
  });

  test('AC9: Password card, PUT password → 409, "Forgot password?" sets a password, then both methods work', async ({ browser }) => {
    const a = await newPage(browser);
    const mail = email('ac9');
    const s = sub('ac9');
    await google(a.page, s, googleUser(mail));
    await a.page.goto('/account');
    const card = a.page.getByTestId('password-card');
    await expect(card).toBeVisible();
    await expect(card.getByRole('heading')).toHaveText('Password');
    await expect(card).toContainText(NO_PASSWORD);
    await expect(a.page.getByRole('heading', { name: 'Change password' })).toHaveCount(0);
    await expect(a.page.locator('#account-current-password')).toHaveCount(0);
    await shot(a.page, 'ac9-password-card');
    const pr = await api(a.ctx, a.page, 'PUT', '/api/account/password', { currentPassword: 'x', newPassword: 'longenough1' });
    expect(pr.status()).toBe(409);
    expect((await pr.json()).code).toBe('PASSWORD_REQUIRED');
    // validation still comes first
    const pv = await api(a.ctx, a.page, 'PUT', '/api/account/password', {});
    expect(pv.status()).toBe(400);
    await logoutUi(a.page);

    await a.page.getByRole('link', { name: 'Forgot password?' }).click();
    await expect(a.page).toHaveURL(/\/forgot-password$/);
    await a.page.locator('#forgot-email').fill(mail);
    await a.page.getByRole('button', { name: 'Send reset link' }).click();
    await expect(a.page.getByTestId('reset-link-sent')).toBeVisible();
    const link = await waitForMail(mail, RESET_SUBJECT);
    await a.page.goto(link);
    await a.page.locator('#reset-new-password').fill(NEW_PASSWORD);
    await a.page.locator('#reset-confirm-password').fill(NEW_PASSWORD);
    await a.page.getByRole('button', { name: 'Reset password' }).click();
    await expect(a.page).toHaveURL(/\/login$/);

    await loginUi(a.page, mail, NEW_PASSWORD);
    await expect(a.page).toHaveURL(/\/tasks$/);
    const u = (await me(a.page)).body!;
    expect(u.hasPassword).toBe(true);
    expect(u.connections).toEqual([{ provider: 'google', email: mail }]);
    await a.page.goto('/account');
    await expect(a.page.getByRole('heading', { name: 'Change password' })).toBeVisible();
    await expect(a.page.getByTestId('password-card')).toHaveCount(0);
    await expect(a.page.locator('#account-disconnect-google')).toBeEnabled();
    await expect(a.page.locator('#account-disconnect-google-hint')).toHaveCount(0);
    await shot(a.page, 'ac9-after-reset-account');
    await logoutUi(a.page);
    await google(a.page, s, googleUser(mail));
    await expect(a.page).toHaveURL(/\/tasks$/);
    expect((await me(a.page)).body!.id).toBe(u.id);
    assertClean(a.w, 'AC9');
    await a.ctx.close();
  });

  test('AC10: delete without a password asks for the email; mismatch → "Email doesn\'t match"; correct → deleted', async ({ browser }) => {
    const a = await newPage(browser);
    const mail = email('ac10');
    const s = sub('ac10');
    await google(a.page, s, googleUser(mail));
    const u = (await me(a.page)).body!;
    await a.page.goto('/account');
    await a.page.locator('#account-delete').click();
    const dialog = a.page.locator('app-delete-account-dialog dialog');
    await expect(dialog).toBeVisible();
    await expect(dialog).toContainText('Enter your email address to confirm.');
    await expect(dialog.locator('#delete-account-email')).toBeVisible();
    await expect(dialog.locator('#delete-account-password')).toHaveCount(0);
    // empty → required (no request)
    let deletes = 0;
    a.page.on('request', (r) => {
      if (r.url().endsWith('/api/account/delete')) deletes += 1;
    });
    await dialog.getByRole('button', { name: 'Delete permanently' }).click();
    await expect(dialog.locator('#delete-account-email-error')).toBeVisible();
    console.log('AC10 empty-email message:', await dialog.locator('#delete-account-email-error').textContent());
    expect(deletes).toBe(0);
    await dialog.locator('#delete-account-email').fill(`other-${mail}`);
    await dialog.getByRole('button', { name: 'Delete permanently' }).click();
    await expect(dialog.locator('#delete-account-email-error')).toHaveText("Email doesn't match");
    await expect(dialog.locator('#delete-account-email')).toHaveAttribute('aria-invalid', 'true');
    await shot(a.page, 'ac10-email-mismatch');
    expect((await me(a.page)).status).toBe(200);
    // API details
    const v = await api(a.ctx, a.page, 'POST', '/api/account/delete', { password: 'whatever' });
    expect(v.status()).toBe(400);
    const vb = await v.json();
    expect(vb.code).toBe('VALIDATION_FAILED');
    expect(vb.errors).toEqual([{ field: 'email', code: 'REQUIRED', message: 'Email is required' }]);
    const mm = await api(a.ctx, a.page, 'POST', '/api/account/delete', { email: 'nope@example.com' });
    expect(mm.status()).toBe(400);
    const mb = await mm.json();
    expect(mb.code).toBe('EMAIL_MISMATCH');
    expect(mb.errors).toEqual([{ field: 'email', code: 'EMAIL_MISMATCH', message: "Email doesn't match" }]);

    // correct email, different case and spaces → deleted
    await dialog.locator('#delete-account-email').fill(`  ${mail.toUpperCase()} `);
    const del = a.page.waitForResponse((r) => r.url().endsWith('/api/account/delete'));
    await dialog.getByRole('button', { name: 'Delete permanently' }).click();
    expect((await del).status()).toBe(204);
    await expect(a.page).toHaveURL(/\/login$/);
    await expect(a.page.getByText(ACCOUNT_DELETED)).toBeVisible();
    expect((await me(a.page)).status).toBe(401);
    expect(usersWithEmail(mail)).toBe(0);
    expect(connectionsOfSub(s)).toEqual([]);
    // the same Google account now creates a new account
    await google(a.page, s, googleUser(mail));
    await expect(a.page).toHaveURL(/\/tasks$/);
    expect((await me(a.page)).body!.id).not.toBe(u.id);
    assertClean(a.w, 'AC10');
    await a.ctx.close();
  });

  test('AC10: wrong-email confirmations share the per-user limit (5 → 429)', async ({ browser }) => {
    const a = await newPage(browser);
    await google(a.page, sub('ac10rl'), googleUser(email('ac10rl')));
    for (let i = 0; i < 5; i++) {
      const r = await api(a.ctx, a.page, 'POST', '/api/account/delete', { email: `x${i}@example.com` });
      expect(r.status()).toBe(400);
    }
    const r = await api(a.ctx, a.page, 'POST', '/api/account/delete', { email: 'x@example.com' });
    expect(r.status()).toBe(429);
    expect((await r.json()).code).toBe('RATE_LIMITED');
    await a.page.goto('/account');
    await a.page.locator('#account-delete').click();
    const dialog = a.page.locator('app-delete-account-dialog dialog');
    await dialog.locator('#delete-account-email').fill('again@example.com');
    await dialog.getByRole('button', { name: 'Delete permanently' }).click();
    await expect(dialog.getByRole('alert')).toHaveText('Too many attempts, please try again later');
    await shot(a.page, 'ac10-rate-limited');
    await a.ctx.close();
  });

  test('AC11: "Connected accounts" card between Sessions and Delete account, both states', async ({ browser }) => {
    const a = await newPage(browser);
    const mail = email('ac11');
    await a.page.goto('/login');
    await registerApi(a.ctx, a.page, mail);
    await a.page.goto('/account');
    const card = a.page.getByTestId('connected-accounts');
    await expect(card.getByRole('heading')).toHaveText('Connected accounts');
    await expect(a.page.getByTestId('google-connection-status')).toHaveText('Google: not connected');
    await expect(a.page.locator('#account-connect-google')).toHaveText('Connect Google');
    const headings = await a.page.locator('.account-card h2').allTextContents();
    console.log('AC11 card order:', headings);
    const i = headings.indexOf('Connected accounts');
    expect(headings[i - 1]).toBe('Sessions');
    expect(headings[i + 1]).toBe('Delete account');
    await shot(a.page, 'ac11-not-connected');
    const b = await newPage(browser);
    const gmail = email('ac11-g');
    await google(b.page, sub('ac11'), googleUser(gmail));
    await b.page.goto('/account');
    await expect(b.page.getByTestId('google-connection-status')).toHaveText(`Google: connected as ${gmail}`);
    await shot(b.page, 'ac11-connected-no-password');
    assertClean(a.w, 'AC11 a');
    assertClean(b.w, 'AC11 b');
    await a.ctx.close();
    await b.ctx.close();
  });

  test('AC12 + AC17: connect a Google account with a different email; session kept; sub matching survives an email change', async ({ browser }) => {
    const a = await newPage(browser);
    const mail = email('ac12');
    await a.page.goto('/login');
    const pw = await registerApi(a.ctx, a.page, mail); // unconfirmed
    await a.page.goto('/account');
    const sidBefore = (await cookie(a.ctx, 'JSESSIONID'))!.value;
    const tokBefore = (await cookie(a.ctx, 'XSRF-TOKEN'))!.value;
    const s = sub('ac12');
    const gmail = email('ac12-gmail');
    await connect(a.page, s, googleUser(gmail.toUpperCase(), 'Ignored Name', false));
    await expect(a.page).toHaveURL(/\/account$/);
    await expect(a.page.getByTestId('oauth-notice')).toHaveText(CONNECTED);
    await expect(a.page.getByTestId('google-connection-status')).toHaveText(`Google: connected as ${gmail}`);
    await expect(a.page.locator('#account-disconnect-google')).toBeEnabled();
    await shot(a.page, 'ac12-connected');
    expect((await cookie(a.ctx, 'JSESSIONID'))!.value).toBe(sidBefore);
    expect((await cookie(a.ctx, 'XSRF-TOKEN'))!.value).toBe(tokBefore);
    const u = (await me(a.page)).body!;
    expect(u).toMatchObject({ id: pw.id, email: mail, emailVerified: false, connections: [{ provider: 'google', email: gmail }] });
    // refresh: notice not repeated
    await a.page.reload();
    await expect(a.page.getByTestId('google-connection-status')).toBeVisible();
    await expect(a.page.getByTestId('oauth-notice')).toHaveCount(0);

    // sign in with that Google account from another browser → this account
    const b = await newPage(browser);
    await google(b.page, s, googleUser(gmail));
    await expect(b.page).toHaveURL(/\/tasks$/);
    expect((await me(b.page)).body!.id).toBe(pw.id);
    await logoutUi(b.page);

    // AC17: same sub, Gmail address changed at Google → same account; stored Google email updated
    const changed = email('ac17-renamed');
    await google(b.page, s, googleUser(changed));
    await expect(b.page).toHaveURL(/\/tasks$/);
    const after = (await me(b.page)).body!;
    expect(after.id).toBe(pw.id);
    expect(after.email).toBe(mail);
    expect(after.connections).toEqual([{ provider: 'google', email: changed }]);
    expect(usersWithEmail(changed)).toBe(0);
    // sub is matched exactly (case-sensitive)
    await logoutUi(b.page);
    await google(b.page, s.toUpperCase(), googleUser(email('ac17-case')));
    await expect(b.page).toHaveURL(/\/tasks$/);
    expect((await me(b.page)).body!.id).not.toBe(pw.id);
    assertClean(a.w, 'AC12');
    assertClean(b.w, 'AC17');
    await a.ctx.close();
    await b.ctx.close();
  });

  test('AC12: connecting a verified Google email equal to the account email confirms the account email', async ({ browser }) => {
    const a = await newPage(browser);
    const mail = email('ac12v');
    await a.page.goto('/login');
    await registerApi(a.ctx, a.page, mail);
    await a.page.goto('/tasks');
    await expect(a.page.getByTestId('verify-email-banner')).toBeVisible();
    await connect(a.page, sub('ac12v'), googleUser(mail.toUpperCase()));
    await expect(a.page.getByTestId('oauth-notice')).toHaveText(CONNECTED);
    expect((await me(a.page)).body!.emailVerified).toBe(true);
    await a.page.goto('/tasks');
    await expect(a.page.getByLabel('New task')).toBeVisible();
    await expect(a.page.getByTestId('verify-email-banner')).toHaveCount(0);
    assertClean(a.w, 'AC12 verified');
    await a.ctx.close();
  });

  test('AC13: a Google account connected to another user → "already connected" and nothing changes', async ({ browser }) => {
    const owner = await newPage(browser);
    const s = sub('ac13');
    const gmail = email('ac13-g');
    await google(owner.page, s, googleUser(gmail));
    const ownerId = (await me(owner.page)).body!.id;

    const a = await newPage(browser);
    const mail = email('ac13');
    await a.page.goto('/login');
    const u = await registerApi(a.ctx, a.page, mail);
    await connect(a.page, s, googleUser(gmail));
    await expect(a.page).toHaveURL(/\/account$/);
    await expect(a.page.getByTestId('oauth-notice')).toHaveText(IN_USE);
    await expect(a.page.getByTestId('google-connection-status')).toHaveText('Google: not connected');
    await shot(a.page, 'ac13-in-use');
    expect((await me(a.page)).body!.connections).toEqual([]);
    expect((await me(a.page)).body!.id).toBe(u.id);
    expect(connectionsOfSub(s).map((c) => c.userId)).toEqual([ownerId]);
    // still signed in as the same user
    await a.page.reload();
    await expect(a.page.getByTestId('oauth-notice')).toHaveCount(0);
    assertClean(a.w, 'AC13');
    await a.ctx.close();
    await owner.ctx.close();
  });

  test('AC12 edge: connect cancelled → "Couldn\'t connect Google"; connect from an already-connected account with another sub fails', async ({ browser }) => {
    const a = await newPage(browser);
    await a.page.goto('/login');
    const u = await registerApi(a.ctx, a.page, email('ac12c'));
    await a.page.goto('/account');
    await a.page.locator('#account-connect-google').click();
    await a.page.waitForURL(onGoogle);
    const state = new URL(a.page.url()).searchParams.get('state')!;
    await a.page.goto(`/api/auth/oauth2/callback/google?error=access_denied&state=${encodeURIComponent(state)}`);
    await expect(a.page).toHaveURL(/\/account$/);
    await expect(a.page.getByTestId('oauth-notice')).toHaveText(CONNECT_FAILED);
    expect((await me(a.page)).body!.id).toBe(u.id); // still signed in
    await shot(a.page, 'ac12-connect-cancelled');

    // connect A, then (by submitting the start form directly) try to connect B
    const sA = sub('ac12c-A');
    await connect(a.page, sA, googleUser(email('ac12c-A')));
    await expect(a.page.getByTestId('oauth-notice')).toHaveText(CONNECTED);
    await expect(a.page.locator('#account-connect-google')).toHaveCount(0);
    const t = await xsrf(a.ctx, a.page);
    await a.page.evaluate((token) => {
      const f = document.createElement('form');
      f.method = 'post';
      f.action = '/api/auth/oauth2/authorization/google';
      for (const [k, v] of [
        ['intent', 'connect'],
        ['_csrf', token],
      ]) {
        const i = document.createElement('input');
        i.type = 'hidden';
        i.name = k;
        i.value = v;
        f.append(i);
      }
      document.body.append(f);
      f.submit();
    }, t);
    const sB = sub('ac12c-B');
    await approveAtGoogle(a.page, sB, googleUser(email('ac12c-B')));
    await a.page.waitForURL(backInApp);
    await expect(a.page).toHaveURL(/\/account$/);
    await expect(a.page.getByTestId('oauth-notice')).toHaveText(CONNECT_FAILED);
    expect(connectionsOfSub(sA).map((c) => c.userId)).toEqual([u.id]);
    expect(connectionsOfSub(sB)).toEqual([]);
    assertClean(a.w, 'AC12 edge');
    await a.ctx.close();
  });

  test('AC14: disconnect with a password (cancel keeps it); later Google sign-in follows AC2/AC5', async ({ browser }) => {
    const a = await newPage(browser);
    const mail = email('ac14');
    await a.page.goto('/login');
    const u = await registerApi(a.ctx, a.page, mail);
    confirmDb(mail);
    const s = sub('ac14');
    await connect(a.page, s, googleUser(mail));
    await expect(a.page.getByTestId('google-connection-status')).toHaveText(`Google: connected as ${mail}`);
    const sid = (await cookie(a.ctx, 'JSESSIONID'))!.value;
    const tok = (await cookie(a.ctx, 'XSRF-TOKEN'))!.value;

    // cancel, and Escape
    await a.page.locator('#account-disconnect-google').click();
    const dialog = a.page.locator('app-disconnect-google-dialog dialog');
    await expect(dialog).toBeVisible();
    await expect(dialog.getByRole('heading')).toHaveText('Disconnect Google?');
    await shot(a.page, 'ac14-dialog');
    await dialog.getByRole('button', { name: 'Cancel' }).click();
    await expect(dialog).toHaveCount(0);
    await a.page.locator('#account-disconnect-google').click();
    await expect(dialog).toBeVisible();
    await a.page.keyboard.press('Escape');
    await expect(dialog).toHaveCount(0);
    expect((await me(a.page)).body!.connections.length).toBe(1);

    // confirm (double-click → one request)
    let calls = 0;
    a.page.on('request', (r) => {
      if (r.url().endsWith('/api/account/connections/google') && r.method() === 'DELETE') calls += 1;
    });
    await a.page.locator('#account-disconnect-google').click();
    const resp = a.page.waitForResponse((r) => r.url().endsWith('/api/account/connections/google'));
    await a.page.locator('#disconnect-google-confirm').dblclick();
    const r = await resp;
    expect(r.status()).toBe(200);
    const body = (await me(a.page)).body!;
    expectUserShape(body);
    expect(body.connections).toEqual([]);
    expect(calls).toBe(1);
    await expect(dialog).toHaveCount(0);
    await expect(a.page.locator('#account-connections-status')).toHaveText(DISCONNECTED);
    await expect(a.page.getByTestId('google-connection-status')).toHaveText('Google: not connected');
    await expect(a.page.locator('#account-connect-google')).toBeVisible();
    await shot(a.page, 'ac14-disconnected');
    expect((await cookie(a.ctx, 'JSESSIONID'))!.value).toBe(sid);
    expect((await cookie(a.ctx, 'XSRF-TOKEN'))!.value).toBe(tok);
    expect(connectionsOfSub(s)).toEqual([]);
    // second DELETE → 404
    const again = await api(a.ctx, a.page, 'DELETE', '/api/account/connections/google');
    expect(again.status()).toBe(404);
    expect((await again.json()).code).toBe('NOT_FOUND');

    // later: same Google account, same verified email, account confirmed → re-linked (AC5)
    const b = await newPage(browser);
    await google(b.page, s, googleUser(mail));
    await expect(b.page).toHaveURL(/\/tasks$/);
    expect((await me(b.page)).body!.id).toBe(u.id);
    // and a disconnected Google account with a different, unused email → a new account (AC2)
    await a.page.goto('/account');
    await a.page.reload();
    await a.page.locator('#account-disconnect-google').click();
    await a.page.locator('#disconnect-google-confirm').click();
    await expect(a.page.locator('#account-connections-status')).toHaveText(DISCONNECTED);
    const c = await newPage(browser);
    const other = email('ac14-new');
    await google(c.page, s, googleUser(other));
    await expect(c.page).toHaveURL(/\/tasks$/);
    const nu = (await me(c.page)).body!;
    expect(nu.id).not.toBe(u.id);
    expect(nu.email).toBe(other);
    assertClean(a.w, 'AC14');
    await a.ctx.close();
    await b.ctx.close();
    await c.ctx.close();
  });

  test('AC15: without a password, Disconnect is disabled with the hint; API answers 409', async ({ browser }) => {
    const a = await newPage(browser);
    await google(a.page, sub('ac15'), googleUser(email('ac15')));
    await a.page.goto('/account');
    const btn = a.page.locator('#account-disconnect-google');
    await expect(btn).toBeDisabled();
    await expect(a.page.locator('#account-disconnect-google-hint')).toHaveText(SET_PASSWORD_FIRST);
    await expect(btn).toHaveAttribute('aria-describedby', 'account-disconnect-google-hint');
    await btn.click({ force: true });
    await expect(a.page.locator('app-disconnect-google-dialog dialog')).toHaveCount(0);
    await shot(a.page, 'ac15-disabled');
    const r = await api(a.ctx, a.page, 'DELETE', '/api/account/connections/google');
    expect(r.status()).toBe(409);
    const b = await r.json();
    expect(b.code).toBe('PASSWORD_REQUIRED');
    expect(b.detail).toBe(SET_PASSWORD_FIRST);
    expect((await me(a.page)).body!.connections.length).toBe(1);
    assertClean(a.w, 'AC15');
    await a.ctx.close();
  });

  test('AC16: login CSRF — an attacker\'s callback URL opened in the victim\'s browser signs nobody in', async ({ browser }) => {
    // attacker approves at Google but keeps the callback URL
    const attacker = await newPage(browser);
    const sAtt = sub('ac16-attacker');
    const attMail = email('ac16-attacker');
    await attacker.page.goto('/login');
    await attacker.page.getByTestId('google-button').click();
    const cb = await captureCallback(attacker.page, sAtt, googleUser(attMail));
    remember(cb);
    const path = new URL(cb).pathname + new URL(cb).search;

    // 1) fresh victim browser
    const v1 = await newPage(browser);
    await v1.page.goto(path);
    await expect(v1.page).toHaveURL(/\/login$/);
    await expect(v1.page.getByTestId('oauth-notice')).toHaveText(FAILED);
    expect((await me(v1.page)).status).toBe(401);
    await shot(v1.page, 'ac16-victim-fresh');

    // 2) victim signed in to their own account
    const v2 = await newPage(browser);
    const vMail = email('ac16-victim');
    await v2.page.goto('/login');
    const victim = await registerApi(v2.ctx, v2.page, vMail);
    await v2.page.goto(path);
    await v2.page.waitForURL(backInApp);
    const m2 = await me(v2.page);
    console.log('AC16 signed-in victim after attacker URL:', v2.page.url(), m2.status, m2.body?.email);
    if (m2.status === 200) expect(m2.body!.id).toBe(victim.id);

    // 3) victim in the middle of their own flow
    const v3 = await newPage(browser);
    await v3.page.goto('/login');
    await v3.page.getByTestId('google-button').click();
    await v3.page.waitForURL(onGoogle);
    await v3.page.goto(path);
    await expect(v3.page).toHaveURL(/\/login$/);
    await expect(v3.page.getByTestId('oauth-notice')).toHaveText(FAILED);
    expect((await me(v3.page)).status).toBe(401);

    // nothing was created for the attacker's Google account
    expect(connectionsOfSub(sAtt)).toEqual([]);
    expect(usersWithEmail(attMail)).toBe(0);
    for (const x of [attacker, v1, v2, v3]) await x.ctx.close();
  });

  test('AC16 + AC19: a late boot /api/auth/me response does not make the captured CSRF token stale', async ({ browser }) => {
    const a = await newPage(browser);
    // Fetch the app's boot request through an isolated cookie jar and hold the response, so its
    // Set-Cookie (a NEW XSRF-TOKEN) lands late, after a helper that fires its own GET would have read one.
    const iso = await request.newContext();
    await a.page.route('**/api/auth/me', async (route) => {
      const res = await iso.get(route.request().url(), {
        headers: route.request().headers(),
        failOnStatusCode: false,
      });
      await new Promise((r) => setTimeout(r, 1500));
      await route.fulfill({ status: res.status(), headers: res.headers(), body: await res.body() });
    });
    await a.page.goto('/login');
    const token = await xsrf(a.ctx, a.page);
    const r = await a.page.request.post('/api/auth/oauth2/authorization/google', {
      form: { intent: 'login', _csrf: token },
      maxRedirects: 0,
    });
    expect(r.status()).toBe(302);
    await iso.dispose();
    await a.ctx.close();
  });

  test('AC16 + AC19: start endpoint — 403 without/with a wrong _csrf, 405 on GET, 302s otherwise', async ({ browser }) => {
    const a = await newPage(browser);
    await a.page.goto('/login');
    const token = await xsrf(a.ctx, a.page);
    const start = (form: Record<string, string>, headers: Record<string, string> = {}) =>
      a.page.request.post('/api/auth/oauth2/authorization/google', { form, headers, maxRedirects: 0 });

    const noCsrf = await start({ intent: 'login' });
    expect(noCsrf.status()).toBe(403);
    expect(noCsrf.headers()['content-type']).toContain('application/problem+json');
    expect((await noCsrf.json()).code).toBe('CSRF_INVALID');
    const wrong = await start({ intent: 'login', _csrf: `${token}x` });
    expect(wrong.status()).toBe(403);
    expect((await wrong.json()).code).toBe('CSRF_INVALID');
    // without the cookie (another site can't read or set it) the field alone fails too
    const naked = await browser.newContext({ baseURL: BASE });
    const nr = await naked.request.post('/api/auth/oauth2/authorization/google', {
      form: { intent: 'login', _csrf: token },
      maxRedirects: 0,
    });
    expect(nr.status()).toBe(403);
    await naked.close();

    const get = await a.page.request.get('/api/auth/oauth2/authorization/google?intent=login', { maxRedirects: 0 });
    expect(get.status()).toBe(405);

    const ok = await start({ intent: 'login', _csrf: token });
    expect(ok.status()).toBe(302);
    expect(ok.headers()['location'].startsWith(`${OIDC}/authorize?`)).toBe(true);
    const noIntent = await start({ _csrf: token });
    expect(noIntent.status()).toBe(302);
    expect(noIntent.headers()['location'].startsWith(`${OIDC}/authorize?`)).toBe(true);
    const header = await start({ intent: 'login' }, { 'X-XSRF-TOKEN': token });
    expect(header.status()).toBe(302);
    expect(header.headers()['location'].startsWith(`${OIDC}/authorize?`)).toBe(true);
    const bogus = await start({ intent: 'bogus', _csrf: token });
    expect(bogus.status()).toBe(302);
    expect(bogus.headers()['location']).toBe(`${APP_BASE}/login?oauth=google_failed`);
    const anonConnect = await start({ intent: 'connect', _csrf: token });
    expect(anonConnect.status()).toBe(302);
    expect(anonConnect.headers()['location']).toBe(`${APP_BASE}/login?oauth=google_failed`);
    // browser-supplied identity is ignored: extra fields don't skip Google
    const spoof = await start({ intent: 'login', _csrf: token, email: 'victim@example.com', sub: 'victim' });
    expect(spoof.headers()['location'].startsWith(`${OIDC}/authorize?`)).toBe(true);
    expect((await me(a.page)).status).toBe(401);
    await a.ctx.close();
  });

  test('AC16: the client secret never reaches the frontend', async ({ browser }) => {
    const a = await newPage(browser);
    const urls = new Set<string>();
    a.page.on('response', (r) => {
      const t = r.request().resourceType();
      if (r.url().startsWith(BASE) && r.request().method() === 'GET' && ['document', 'script', 'xhr', 'fetch', 'stylesheet'].includes(t))
        urls.add(r.url());
    });
    await a.page.goto('/login');
    await a.page.goto('/register');
    await google(a.page, sub('ac16s'), googleUser(email('ac16s')));
    await a.page.goto('/account');
    await a.page.waitForLoadState('networkidle');
    // re-fetch every page, script and API GET the browser loaded (reading bodies of navigated responses can hang)
    const all: string[] = [];
    for (const u of urls) all.push(await (await a.page.request.get(u)).text());
    console.log(`AC16 secret check: ${all.length} responses`);
    expect(all.length).toBeGreaterThan(3);
    for (const t of all) expect(t.includes(SECRET)).toBe(false);
    const api2 = await Promise.all(a.w.apiBodies.map((p) => Promise.race([p, new Promise<string>((r) => setTimeout(() => r(''), 2000))])));
    for (const t of api2) {
      expect(t.includes(SECRET)).toBe(false);
      expect(t).not.toMatch(/eyJ[\w-]+\.eyJ/); // no JWT (ID token) in any API response
    }
    await a.ctx.close();
  });

  test('AC19: DELETE /api/account/connections/google — 403 / 401 / 404 / 409 / 200; providers always public', async ({ browser }) => {
    const a = await newPage(browser);
    await a.page.goto('/login');
    // anonymous with CSRF → 401; without CSRF → 403
    const anon = await api(a.ctx, a.page, 'DELETE', '/api/account/connections/google');
    expect(anon.status()).toBe(401);
    expect((await anon.json()).code).toBe('UNAUTHENTICATED');
    const anonNoCsrf = await api(a.ctx, a.page, 'DELETE', '/api/account/connections/google', undefined, false);
    expect(anonNoCsrf.status()).toBe(403);
    // signed in, password, no connection → 404; no CSRF → 403
    const u = await registerApi(a.ctx, a.page, email('ac19'));
    expectUserShape(u);
    expect(u.hasPassword).toBe(true);
    expect(u.connections).toEqual([]);
    const noCsrf = await api(a.ctx, a.page, 'DELETE', '/api/account/connections/google', undefined, false);
    expect(noCsrf.status()).toBe(403);
    expect((await noCsrf.json()).code).toBe('CSRF_INVALID');
    const none = await api(a.ctx, a.page, 'DELETE', '/api/account/connections/google');
    expect(none.status()).toBe(404);
    expect(none.headers()['content-type']).toContain('application/problem+json');
    // the User shape on login and display-name too
    const dn = await api(a.ctx, a.page, 'PUT', '/api/account/display-name', { displayName: uname('ac19') });
    expectUserShape((await dn.json()) as User);
    await logoutApi(a.ctx, a.page);
    const login = await api(a.ctx, a.page, 'POST', '/api/auth/login', { email: u.email, password: PASSWORD });
    expectUserShape((await login.json()) as User);

    // providers: public, with junk cookies too
    const b = await browser.newContext({ baseURL: BASE });
    await b.addCookies([
      { name: 'JSESSIONID', value: 'junk', url: BASE },
      { name: 'remember-me', value: 'junk', url: BASE },
    ]);
    const pr = await b.request.get('/api/auth/providers');
    expect(pr.status()).toBe(200);
    expect(await pr.json()).toEqual({ google: true });
    await b.close();
    await a.ctx.close();
  });

  test('AC20: Google button disabled with a spinner while starting; double-click = one start; Back re-enables', async ({ browser }) => {
    const a = await newPage(browser);
    let starts = 0;
    a.page.on('request', (r) => {
      if (r.url().endsWith('/api/auth/oauth2/authorization/google')) starts += 1;
    });
    await a.page.goto('/login');
    await expect(a.page.getByTestId('google-button')).toBeVisible();
    // Observable pending window: no XSRF cookie yet, so the button first calls GET /api/auth/me (delayed here).
    // (A delayed form POST can't be observed: Playwright waits for the navigation before reading the page.)
    await a.ctx.clearCookies({ name: 'XSRF-TOKEN' });
    await a.page.route('**/api/auth/me', async (route) => {
      await new Promise((r) => setTimeout(r, 2000));
      await route.continue();
    });
    const btn = a.page.getByTestId('google-button');
    await btn.dblclick();
    await expect(btn).toBeDisabled();
    await expect(a.page.getByTestId('google-spinner')).toBeVisible();
    await expect(btn).toHaveAttribute('aria-busy', 'true');
    await shot(a.page, 'ac20-google-pending');
    await a.page.waitForURL(onGoogle);
    expect(starts).toBe(1);
    expect(await a.page.locator('#google-start-form').count()).toBe(0); // we're on Google now
    await a.page.goBack();
    await expect(a.page).toHaveURL(/\/login$/);
    await expect(a.page.getByTestId('google-button')).toBeEnabled();
    await expect(a.page.getByTestId('google-spinner')).toHaveCount(0);
    await shot(a.page, 'ac20-google-after-back');
    assertClean(a.w, 'AC20 pending');
    await a.ctx.close();
  });

  test('AC20: Google button error state when no CSRF token can be obtained', async ({ browser }) => {
    const a = await newPage(browser);
    await a.page.goto('/register');
    await expect(a.page.getByTestId('google-button')).toBeVisible();
    await a.ctx.clearCookies({ name: 'XSRF-TOKEN' });
    await a.page.route('**/api/auth/me', (r) => r.fulfill({ status: 500, body: '' }));
    await a.page.getByTestId('google-button').click();
    await expect(a.page.locator('.google-sign-in').getByRole('alert')).toHaveText(GENERIC);
    await expect(a.page.getByTestId('google-button')).toBeEnabled();
    await expect(a.page).toHaveURL(/\/register$/);
    await shot(a.page, 'ac20-google-error');
    // retry works once the API is back
    await a.page.unroute('**/api/auth/me');
    await a.page.getByTestId('google-button').click();
    await a.page.waitForURL(onGoogle);
    await a.ctx.close();
  });

  test('AC20: Connect button loading + error; disconnect loading + error states', async ({ browser }) => {
    const a = await newPage(browser);
    await a.page.goto('/login');
    await registerApi(a.ctx, a.page, email('ac20c'));
    await a.page.goto('/account');
    // loading
    // connect first calls GET /api/auth/me (delayed here), then submits the form
    await a.page.route('**/api/auth/me', async (route) => {
      await new Promise((r) => setTimeout(r, 2000));
      await route.continue();
    });
    const cbtn = a.page.locator('#account-connect-google');
    await cbtn.click();
    await expect(cbtn).toBeDisabled();
    await expect(cbtn).toHaveAttribute('aria-busy', 'true');
    await expect(cbtn.getByTestId('spinner')).toBeVisible();
    await shot(a.page, 'ac20-connect-pending');
    await a.page.waitForURL(onGoogle);
    await a.page.unroute('**/api/auth/me');
    // error: /me fails before the submit
    await a.page.goto('/account');
    await a.page.route('**/api/auth/me', (r) => r.fulfill({ status: 500, body: '' }));
    await a.page.locator('#account-connect-google').click();
    const err = a.page.getByTestId('connected-accounts').getByRole('alert');
    await expect(err).toBeVisible();
    const text = (await err.textContent())!.trim();
    console.log('AC20 connect error text:', JSON.stringify(text));
    await shot(a.page, 'ac20-connect-error');
    await expect(a.page.locator('#account-connect-google')).toBeEnabled();
    await a.page.unroute('**/api/auth/me');
    // session gone → /me 401 → /login
    await a.page.route('**/api/auth/me', (r) =>
      r.fulfill({ status: 401, contentType: 'application/problem+json', body: '{"status":401,"code":"UNAUTHENTICATED"}' }),
    );
    await a.page.locator('#account-connect-google').click();
    await expect(a.page).toHaveURL(/\/login/);
    await a.page.unroute('**/api/auth/me');

    // disconnect states (password user with a connection)
    const b = await newPage(browser);
    await b.page.goto('/login');
    await registerApi(b.ctx, b.page, email('ac20d'));
    await connect(b.page, sub('ac20d'), googleUser(email('ac20d-g')));
    await expect(b.page.getByTestId('oauth-notice')).toHaveText(CONNECTED);
    await b.page.route('**/api/account/connections/google', async (route) => {
      await new Promise((r) => setTimeout(r, 1500));
      await route.fulfill({ status: 500, contentType: 'application/problem+json', body: '{"status":500}' });
    });
    await b.page.locator('#account-disconnect-google').click();
    const confirm = b.page.locator('#disconnect-google-confirm');
    await confirm.click();
    await expect(confirm).toBeDisabled();
    await expect(confirm.getByTestId('spinner')).toBeVisible();
    await shot(b.page, 'ac20-disconnect-pending');
    const dialog = b.page.locator('app-disconnect-google-dialog dialog');
    await expect(dialog.getByRole('alert')).toHaveText(GENERIC);
    await expect(confirm).toBeEnabled();
    await shot(b.page, 'ac20-disconnect-error');
    // 404 (already disconnected elsewhere) → generic + card refreshed
    await b.page.unroute('**/api/account/connections/google');
    const other = await api(b.ctx, b.page, 'DELETE', '/api/account/connections/google');
    expect(other.status()).toBe(200);
    await confirm.click();
    await expect(dialog.getByRole('alert')).toHaveText(GENERIC);
    await dialog.getByRole('button', { name: 'Cancel' }).click();
    await expect(b.page.getByTestId('google-connection-status')).toHaveText('Google: not connected');
    expect(text).toBe(CONNECT_FAILED); // AC20 (updated 2026-10-15): in-page connect error uses the connect-specific text
    await a.ctx.close();
    await b.ctx.close();
  });

  test('Edge: login intent while signed in replaces the session; unknown/refresh cases', async ({ browser }) => {
    const a = await newPage(browser);
    await a.page.goto('/login');
    const pw = await registerApi(a.ctx, a.page, email('edge'));
    await a.page.goto('/tasks');
    const t = await xsrf(a.ctx, a.page);
    await a.page.evaluate((token) => {
      const f = document.createElement('form');
      f.method = 'post';
      f.action = '/api/auth/oauth2/authorization/google';
      for (const [k, v] of [
        ['intent', 'login'],
        ['_csrf', token],
      ]) {
        const i = document.createElement('input');
        i.type = 'hidden';
        i.name = k;
        i.value = v;
        f.append(i);
      }
      document.body.append(f);
      f.submit();
    }, t);
    const gmail = email('edge-g');
    await approveAtGoogle(a.page, sub('edge'), googleUser(gmail));
    await a.page.waitForURL(backInApp);
    await expect(a.page).toHaveURL(/\/tasks$/);
    const u = (await me(a.page)).body!;
    expect(u.id).not.toBe(pw.id);
    expect(u.email).toBe(gmail);
    // refresh on /tasks after a Google login: still signed in
    await a.page.reload();
    await expect(a.page.getByLabel('New task')).toBeVisible();
    assertClean(a.w, 'edge');
    await a.ctx.close();
  });

  test('AC18: audit lines for each Google event; no code, state, token or secret in the log', async ({ browser }) => {
    test.skip(!LOG, 'E2E_BACKEND_LOG not set');
    // created + success
    const a = await newPage(browser);
    const gmail = email('ac18');
    const s = sub('ac18');
    await google(a.page, s, googleUser(gmail));
    await expectLog(gmail, /^SECURITY event=GOOGLE_ACCOUNT_CREATED email=\S+ path=\/api\/auth\/oauth2\/callback\/google$/);
    await expectLog(gmail, /^SECURITY event=GOOGLE_LOGIN_SUCCESS email=\S+ path=\/api\/auth\/oauth2\/callback\/google$/);
    // linked by sign-in
    const mail = email('ac18-link');
    await passwordAccount(browser, mail, true);
    const b = await newPage(browser);
    await google(b.page, sub('ac18-link'), googleUser(mail));
    await expect(b.page).toHaveURL(/\/tasks$/);
    await expectLog(mail, /event=GOOGLE_LINKED trigger=login /);
    // connect + unlink
    const c = await newPage(browser);
    const cmail = email('ac18-connect');
    await c.page.goto('/login');
    await registerApi(c.ctx, c.page, cmail);
    await connect(c.page, sub('ac18-connect'), googleUser(email('ac18-cg')));
    await expectLog(cmail, /event=GOOGLE_LINKED trigger=connect /);
    const d = await api(c.ctx, c.page, 'DELETE', '/api/account/connections/google');
    expect(d.status()).toBe(200);
    const db = (await d.json()) as User;
    expectUserShape(db);
    expect(db.connections).toEqual([]);
    await expectLog(cmail, /^SECURITY event=GOOGLE_UNLINKED email=\S+ path=\/api\/account\/connections\/google$/);
    // failures with reasons (from a signed-out browser)
    await logoutUi(a.page);
    const umail = email('ac18-unv');
    await google(a.page, sub('ac18-unv'), googleUser(umail, 'x', false));
    await expectLog(umail, /event=GOOGLE_LOGIN_FAILURE intent=login reason=google_email_unverified /);
    const unconf = email('ac18-unconf');
    await passwordAccount(browser, unconf, false);
    await google(a.page, sub('ac18-unconf'), googleUser(unconf));
    await expectLog(unconf, /event=GOOGLE_LOGIN_FAILURE intent=login reason=account_email_unconfirmed /);
    // cancelled (no email known) → a line without an email; check by path
    await a.page.goto('/login');
    await a.page.getByTestId('google-button').click();
    await a.page.waitForURL(onGoogle);
    const st = new URL(a.page.url()).searchParams.get('state')!;
    SECRETS_SEEN.add(st);
    await a.page.goto(`/api/auth/oauth2/callback/google?error=access_denied&state=${encodeURIComponent(st)}`);
    await expect
      .poll(() => /SECURITY event=GOOGLE_LOGIN_FAILURE intent=login reason=cancelled/.test(logText()))
      .toBe(true);
    // delete with a wrong email
    const e = await newPage(browser);
    const emDel = email('ac18-del');
    await google(e.page, sub('ac18-del'), googleUser(emDel));
    await api(e.ctx, e.page, 'POST', '/api/account/delete', { email: 'nope@example.com' });
    await expectLog(emDel, /event=PASSWORD_CHECK_FAILURE action=delete_account_email /);
    // a captured real code + state
    await a.page.goto('/login');
    await a.page.getByTestId('google-button').click();
    const cb = await captureCallback(a.page, sub('ac18-cap'), googleUser(email('ac18-cap')));
    remember(cb);
    await a.page.goto(new URL(cb).pathname + new URL(cb).search);
    await expect(a.page).toHaveURL(/\/tasks$/);

    const log = logText();
    for (const v of SECRETS_SEEN) expect(log.includes(v), `code/state ${v.slice(0, 8)}… in log`).toBe(false);
    expect(log).not.toContain(SECRET);
    expect(log).not.toMatch(/eyJ[\w-]+\.eyJ[\w-]+/); // no JWT
    expect(log).not.toMatch(/[?&](code|state)=[\w-]{8,}/);
    const googleLines = log.split('\n').filter((l) => /event=GOOGLE_/.test(l));
    console.log('AC18 sample lines:\n' + googleLines.slice(-12).map((l) => l.slice(l.indexOf('SECURITY'))).join('\n'));
    for (const x of [a, b, c, e]) await x.ctx.close();
  });
});

// =============================================================================================================
// E2E_GOOGLE_OFF=1: feature off
// =============================================================================================================

test.describe('E2E_GOOGLE_OFF=1: Google sign-in not configured', () => {
  test.skip(!OFF, 'needs E2E_GOOGLE_OFF=1 and a backend started with GOOGLE_CLIENT_ID= GOOGLE_CLIENT_SECRET=');

  test('AC1 off: no button or divider, providers false, endpoints 404 (after 403)', async ({ browser }) => {
    const a = await newPage(browser);
    const pr = await a.page.request.get('/api/auth/providers');
    expect(await pr.json()).toEqual({ google: false });
    for (const path of ['/login', '/register']) {
      await a.page.goto(path);
      await expect(a.page.getByRole('button', { name: path === '/login' ? 'Sign in' : 'Create account' })).toBeVisible();
      await a.page.waitForLoadState('networkidle');
      await expect(a.page.getByTestId('google-button')).toHaveCount(0);
      await expect(a.page.locator('.google-sign-in__divider')).toHaveCount(0);
      await shot(a.page, `off-${path.slice(1)}`);
    }
    const token = await xsrf(a.ctx, a.page);
    const s403 = await a.page.request.post('/api/auth/oauth2/authorization/google', { form: { intent: 'login' }, maxRedirects: 0 });
    expect(s403.status()).toBe(403);
    const s404 = await a.page.request.post('/api/auth/oauth2/authorization/google', {
      form: { intent: 'login', _csrf: token },
      maxRedirects: 0,
    });
    expect(s404.status()).toBe(404);
    expect((await s404.json()).code).toBe('NOT_FOUND');
    const cb = await a.page.request.get('/api/auth/oauth2/callback/google?code=x&state=y', { maxRedirects: 0 });
    expect(cb.status()).toBe(404);
    expect((await cb.json()).code).toBe('NOT_FOUND');

    // signed in, no connection: no "Connected accounts" card; DELETE → 404
    const mail = email('off');
    const u = await registerApi(a.ctx, a.page, mail);
    expect(u.connections).toEqual([]);
    await a.page.goto('/account');
    await expect(a.page.getByRole('heading', { name: 'Delete account' })).toBeVisible();
    await a.page.waitForLoadState('networkidle');
    await expect(a.page.getByTestId('connected-accounts')).toHaveCount(0);
    await expect(a.page.getByRole('heading', { name: 'Change password' })).toBeVisible();
    await shot(a.page, 'off-account');
    const d = await api(a.ctx, a.page, 'DELETE', '/api/account/connections/google');
    expect(d.status()).toBe(404);

    // an existing connection is listed read-only
    sql(
      `INSERT INTO user_connections (user_id, provider, provider_subject, provider_email) VALUES (${u.id}, 'google', 'off-${uid()}', 'off-g@example.com')`,
    );
    await a.page.reload();
    await expect(a.page.getByTestId('google-connection-status')).toHaveText('Google: connected as off-g@example.com');
    await expect(a.page.locator('#account-disconnect-google')).toHaveCount(0);
    await expect(a.page.locator('#account-connect-google')).toHaveCount(0);
    expect((await me(a.page)).body!.connections).toEqual([{ provider: 'google', email: 'off-g@example.com' }]);
    const d2 = await api(a.ctx, a.page, 'DELETE', '/api/account/connections/google');
    expect(d2.status()).toBe(404);
    await shot(a.page, 'off-account-readonly');
    // password login and register unchanged
    await logoutApi(a.ctx, a.page);
    await loginUi(a.page, mail);
    await expect(a.page).toHaveURL(/\/tasks$/);
    assertClean(a.w, 'off');
    await a.ctx.close();
  });
});

// =============================================================================================================
// E2E_RATE_LIMIT=1: real Google limits (fresh backend, defaults 20/min start, 20/min callback)
// =============================================================================================================

test.describe('E2E_RATE_LIMIT=1: real Google per-IP limits', () => {
  test.skip(!REAL_RL, 'needs E2E_RATE_LIMIT=1 and a freshly started backend with default Google limits');

  test('AC19: 21st callback and 21st start in a minute → failure redirect, not Google / not signed in', async ({ browser }) => {
    test.setTimeout(120_000);
    const a = await newPage(browser);
    await a.page.goto('/login');
    // start #1 (via the UI), keep its genuine callback
    await a.page.getByTestId('google-button').click();
    const mail = email('rl');
    const cb = await captureCallback(a.page, sub('rl'), googleUser(mail));
    // 20 junk callbacks use up the callback budget
    for (let i = 0; i < 20; i++) {
      const r = await a.page.request.get(`/api/auth/oauth2/callback/google?state=junk${i}`, { maxRedirects: 0 });
      expect(r.status()).toBe(302);
    }
    // the genuine one (#21) is refused: failure, nothing created
    await a.page.goto(new URL(cb).pathname + new URL(cb).search);
    await expect(a.page).toHaveURL(/\/login$/);
    await expect(a.page.getByTestId('oauth-notice')).toHaveText(FAILED);
    expect(usersWithEmail(mail)).toBe(0);
    await shot(a.page, 'ac19-callback-limited');

    // starts: 19 more go to Google (total 20), the 21st is refused
    const token = await xsrf(a.ctx, a.page);
    for (let i = 0; i < 19; i++) {
      const r = await a.page.request.post('/api/auth/oauth2/authorization/google', {
        form: { intent: 'login', _csrf: token },
        maxRedirects: 0,
      });
      expect(r.headers()['location'].startsWith(`${OIDC}/authorize?`), `start #${i + 2}`).toBe(true);
    }
    const over = await a.page.request.post('/api/auth/oauth2/authorization/google', {
      form: { intent: 'login', _csrf: token },
      maxRedirects: 0,
    });
    expect(over.status()).toBe(302);
    expect(over.headers()['location']).toBe(`${APP_BASE}/login?oauth=google_failed`);
    // UI: the button now ends on /login with the failed notice
    await a.page.goto('/login');
    await a.page.getByTestId('google-button').click();
    await expect(a.page).toHaveURL(/\/login$/);
    await expect(a.page.getByTestId('oauth-notice')).toHaveText(FAILED);
    // connect intent over the limit → /account?oauth=google_connect_failed (password login is a different budget)
    const pw = await newPage(browser);
    await pw.page.goto('/login');
    await registerApi(pw.ctx, pw.page, email('rl-pw'));
    await pw.page.goto('/account');
    await pw.page.locator('#account-connect-google').click();
    await expect(pw.page).toHaveURL(/\/account$/);
    await expect(pw.page.getByTestId('oauth-notice')).toHaveText(CONNECT_FAILED);
    await shot(pw.page, 'ac19-connect-limited');
    if (LOG) {
      expect(logText()).toMatch(/RATE_LIMITED limit=google-callback-ip/);
      expect(logText()).toMatch(/RATE_LIMITED limit=google-start-ip/);
    }
    await a.ctx.close();
    await pw.ctx.close();
  });
});

// =============================================================================================================
// E2E_SHORT_SESSIONS=1: idle 1m, max 2m
// =============================================================================================================

test.describe('E2E_SHORT_SESSIONS=1: Google sessions follow the normal limits', () => {
  test.skip(!SHORT, 'needs SERVER_SERVLET_SESSION_TIMEOUT=1m SESSION_MAX_LENGTH=2m');

  test('AC7: idle timeout ends a Google session', async ({ browser }) => {
    test.setTimeout(240_000);
    const a = await newPage(browser);
    await google(a.page, sub('short-idle'), googleUser(email('short-idle')));
    await expect(a.page).toHaveURL(/\/tasks$/);
    expect((await me(a.page)).status).toBe(200);
    await new Promise((r) => setTimeout(r, 100_000));
    expect((await me(a.page)).status).toBe(401);
    await a.ctx.close();
  });

  test('AC7: maximum length ends an active Google session', async ({ browser }) => {
    test.setTimeout(240_000);
    const a = await newPage(browser);
    await google(a.page, sub('short-max'), googleUser(email('short-max')));
    await expect(a.page).toHaveURL(/\/tasks$/);
    const t0 = Date.now();
    while (Date.now() - t0 < 110_000) {
      expect((await me(a.page)).status).toBe(200);
      await new Promise((r) => setTimeout(r, 20_000));
    }
    await new Promise((r) => setTimeout(r, 20_000));
    expect((await me(a.page)).status).toBe(401);
    await a.ctx.close();
  });
});
