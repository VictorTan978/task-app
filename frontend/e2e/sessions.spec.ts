import { Browser, BrowserContext, Cookie, expect, Page, Response, test } from '@playwright/test';
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';

/**
 * Sessions (specs/sessions.md, AC1–AC16 + constraints).
 *
 * Default suite: a backend with DEFAULT session limits (idle 30m, max 12h, remember-me 30d) plus the e2e
 * IP rate-limit overrides. Idle expiry is simulated there by replacing JSESSIONID with an unknown id
 * (exactly what the server sees after Tomcat dropped an idle session).
 *
 * Optional:
 *   E2E_BACKEND_LOG=<backend console log>  enables the audit-log checks (AC6, AC10, AC11, AC14).
 *   E2E_DB=1                                enables the MySQL checks via `docker compose exec db mysql` (AC5, AC6, AC10, AC12, AC13).
 *   E2E_SHORT_SESSIONS=1                    runs ONLY the timing tests (AC4, AC7, AC8), against a backend started with
 *       SERVER_SERVLET_SESSION_TIMEOUT=1m SESSION_MAX_LENGTH=2m REMEMBER_ME_LENGTH=5m
 *     (the other tests are skipped in that mode, and the timing tests are skipped without it).
 *
 * "Closing the browser" = a new context that only keeps the persistent cookies (those with an expiry),
 * i.e. the remember-me cookie; session cookies (JSESSIONID, XSRF-TOKEN) are dropped, as a browser does.
 */

const RUN = `${Date.now()}-${Math.floor(Math.random() * 1e6)}`;
const SHOTS = '../test-results/sessions';
const PASSWORD = 'password123';
const NEW_PASSWORD = 'n3w-passw0rd!';
const GENERIC = 'Something went wrong, please try again';
const SESSION_EXPIRED = 'Your session expired, please sign in again';
const LOGGED_OUT_OTHERS = "You've been logged out on all other devices";
const LOG = process.env['E2E_BACKEND_LOG'];
const DB = process.env['E2E_DB'] === '1';
const SHORT = process.env['E2E_SHORT_SESSIONS'] === '1';
const BASE = process.env['E2E_BASE_URL'] ?? 'http://localhost:4200';
const REPO = '..';

let n = 0;
function uid(): string {
  n += 1;
  return `${RUN}-${n}`;
}
function email(tag: string): string {
  return `qa-sess-${tag}-${uid()}@example.com`;
}

// ---------------------------------------------------------------- helpers

function watch(page: Page) {
  const consoleErrors: string[] = [];
  const pageErrors: string[] = [];
  const serverErrors: string[] = [];
  const loginVisits: string[] = [];
  page.on('console', (m) => {
    if (m.type() === 'error') consoleErrors.push(m.text());
  });
  page.on('pageerror', (e) => pageErrors.push(e.message));
  page.on('response', (r: Response) => {
    if (r.url().includes('/api/') && r.status() >= 500) {
      serverErrors.push(`${r.status()} ${r.request().method()} ${r.url()}`);
    }
  });
  page.on('framenavigated', (f) => {
    if (f === page.mainFrame() && /\/login/.test(f.url())) loginVisits.push(f.url());
  });
  return { consoleErrors, pageErrors, serverErrors, loginVisits };
}
type Watch = ReturnType<typeof watch>;

function assertClean(w: Watch, label: string) {
  const app = w.consoleErrors.filter(
    (e) => !/^Failed to load resource: the server responded with a status of/.test(e),
  );
  console.log(`${label} console errors:`, JSON.stringify(w.consoleErrors));
  expect(app).toEqual([]);
  expect(w.pageErrors).toEqual([]);
  expect(w.serverErrors).toEqual([]);
}

async function newPage(browser: Browser, cookies: Cookie[] = []): Promise<{ ctx: BrowserContext; page: Page }> {
  const ctx = await browser.newContext({ baseURL: BASE });
  if (cookies.length) await ctx.addCookies(cookies);
  const page = await ctx.newPage();
  return { ctx, page };
}

/** Creates a user through the API in a throwaway context (register signs in a normal session there). */
async function createUser(browser: Browser, mail: string, password = PASSWORD): Promise<string> {
  const ctx = await browser.newContext({ baseURL: BASE });
  await ctx.request.get('/api/auth/me');
  const name = `S${uid()}`.slice(0, 40);
  const r = await ctx.request.post('/api/auth/register', {
    data: { email: mail, password, displayName: name },
    headers: { 'X-XSRF-TOKEN': await cookieValue(ctx, 'XSRF-TOKEN') },
  });
  expect(r.status()).toBe(201);
  await ctx.close();
  return name;
}

async function loginUi(page: Page, mail: string, remember: boolean, password = PASSWORD) {
  await page.goto('/login');
  await page.locator('#login-email').fill(mail);
  await page.locator('#login-password').fill(password);
  if (remember) await page.locator('#login-remember-me').check();
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page).toHaveURL(/\/tasks$/);
}

async function cookie(ctx: BrowserContext, name: string): Promise<Cookie | undefined> {
  return (await ctx.cookies()).find((c) => c.name === name);
}
async function cookieValue(ctx: BrowserContext, name: string): Promise<string> {
  return (await cookie(ctx, name))?.value ?? '';
}

/** Persistent cookies only (what survives closing the browser). */
async function persistent(ctx: BrowserContext): Promise<Cookie[]> {
  return (await ctx.cookies()).filter((c) => c.expires > 0);
}

/** Replaces this context's session id by an unknown one = what the server sees after an idle expiry. */
async function killSession(ctx: BrowserContext) {
  const s = await cookie(ctx, 'JSESSIONID');
  expect(s, 'JSESSIONID present').toBeTruthy();
  await ctx.addCookies([{ ...s!, value: 'DEADBEEFDEADBEEFDEADBEEFDEADBEEF' }]);
}

async function addTask(page: Page, title: string) {
  await page.getByLabel('New task').fill(title);
  await page.getByRole('button', { name: 'Add', exact: true }).click();
}
async function expectTask(page: Page, title: string) {
  await expect(page.getByRole('checkbox', { name: `Done: ${title}`, exact: true })).toBeVisible();
}

/** Restart the browser with the given persistent cookies and open the app. */
async function reopen(browser: Browser, cookies: Cookie[], path = '/') {
  const { ctx, page } = await newPage(browser, cookies);
  const w = watch(page);
  await page.goto(path);
  return { ctx, page, w };
}

async function expectSignedInOnTasks(page: Page, w: Watch) {
  await expect(page).toHaveURL(/\/tasks$/);
  await expect(page.getByLabel('New task')).toBeVisible();
  await expect(page.getByText(SESSION_EXPIRED)).toHaveCount(0);
  expect(w.loginVisits, 'never shown the login page').toEqual([]);
}

async function expectLoginNoMessage(page: Page) {
  await expect(page).toHaveURL(/\/login/);
  await expect(page.locator('#login-email')).toBeVisible();
  await expect(page.getByText(SESSION_EXPIRED)).toHaveCount(0);
}

async function expectSessionExpiredFlow(page: Page) {
  await expect(page).toHaveURL(/\/login/);
  await expect(page.getByText(SESSION_EXPIRED)).toBeVisible();
}

async function openLogoutOthers(page: Page) {
  await page.goto('/account');
  await page.locator('#account-logout-others').click();
  await expect(page.locator('#logout-others-heading')).toBeVisible();
}

function sql(query: string): string {
  return execFileSync(
    'docker',
    ['compose', 'exec', '-T', 'db', 'mysql', '-utaskapp', '-ptaskapp', 'taskapp', '-N', '-B', '-e', query],
    { cwd: REPO, encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] },
  ).trim();
}
function tokenRows(mail: string): string[][] {
  const out = sql(
    `SELECT HEX(t.series_hash), HEX(t.token_hash), IFNULL(HEX(t.previous_token_hash),'NULL'), t.expires_at, t.created_at ` +
      `FROM remember_me_tokens t JOIN users u ON u.id = t.user_id WHERE u.email = '${mail}'`,
  );
  return out ? out.split('\n').map((l) => l.split('\t')) : [];
}
function sha256Hex(b64url: string): string {
  return createHash('sha256').update(Buffer.from(b64url, 'base64url')).digest('hex').toUpperCase();
}

function logLines(mail: string): string[] {
  return readFileSync(LOG!, 'utf8')
    .split('\n')
    .filter((l) => l.includes('SECURITY') && l.includes(`email=${mail}`));
}
async function expectLog(mail: string, re: RegExp) {
  await expect.poll(() => logLines(mail).some((l) => re.test(l)), { message: `log ${re}`, timeout: 5000 }).toBe(true);
}
function expectNoSecretsInLog(values: string[]) {
  const log = readFileSync(LOG!, 'utf8');
  for (const v of values.filter(Boolean)) {
    for (const part of v.split('.')) {
      expect(log.includes(part), 'remember-me cookie part in log').toBe(false);
    }
  }
}

// ================================================================ default-limit suite

test.describe('default limits', () => {
  test.skip(SHORT, 'E2E_SHORT_SESSIONS=1 runs only the timing tests');

  test('AC1: "Remember me" checkbox below password, above Sign in, unchecked by default', async ({ page }) => {
    const w = watch(page);
    await page.goto('/login');
    const box = page.locator('#login-remember-me');
    await expect(box).toBeVisible();
    await expect(box).not.toBeChecked();
    await expect(page.getByLabel('Remember me')).toHaveAttribute('type', 'checkbox');
    const pw = (await page.locator('#login-password').boundingBox())!;
    const cb = (await box.boundingBox())!;
    const btn = (await page.getByRole('button', { name: 'Sign in' }).boundingBox())!;
    expect(cb.y).toBeGreaterThan(pw.y + pw.height - 1);
    expect(btn.y).toBeGreaterThan(cb.y + cb.height - 1);
    await page.screenshot({ path: `${SHOTS}/ac1-login-remember-me.png`, fullPage: true });
    // label toggles it
    await page.getByText('Remember me', { exact: true }).click();
    await expect(box).toBeChecked();
    assertClean(w, 'AC1');
  });

  test('AC2 + AC12: remember me survives a browser restart, lands on /tasks; cookie attributes; only hashes in DB; rotation', async ({
    browser,
  }) => {
    const mail = email('ac2');
    await createUser(browser, mail);
    const { ctx, page } = await newPage(browser);
    const w = watch(page);
    const loginResp = page.waitForResponse((r) => r.url().endsWith('/api/auth/login'));
    await loginUi(page, mail, true);
    const req = (await loginResp).request().postDataJSON();
    expect(req.rememberMe).toBe(true);

    // AC12 cookie attributes
    const rm = (await cookie(ctx, 'remember-me'))!;
    expect(rm).toBeTruthy();
    expect(rm.httpOnly).toBe(true);
    expect(rm.sameSite).toBe('Lax');
    expect(rm.path).toBe('/api');
    expect(rm.secure).toBe(false); // dev profile over http
    const days = (rm.expires * 1000 - Date.now()) / 86_400_000;
    expect(days).toBeGreaterThan(29.9);
    expect(days).toBeLessThanOrEqual(30.01);
    const hdr = (await (await loginResp).allHeaders())['set-cookie'] ?? '';
    console.log('AC12 login Set-Cookie:', hdr.replace(/remember-me=[^;]+/, 'remember-me=<redacted>'));
    expect(hdr).toMatch(/remember-me=[^;]+; Path=\/api; Max-Age=2592000; Expires=[^;]+; HttpOnly; SameSite=Lax/);
    expect(hdr).not.toMatch(/remember-me=[^\n]*Domain=/i);

    if (DB) {
      const rows = tokenRows(mail);
      expect(rows.length).toBe(1);
      const [series, secret] = rm.value.split('.');
      expect(rows[0][0]).toBe(sha256Hex(series));
      expect(rows[0][1]).toBe(sha256Hex(secret));
      expect(rows[0][2]).toBe('NULL');
      const dump = sql(`SELECT * FROM remember_me_tokens t JOIN users u ON u.id=t.user_id WHERE u.email='${mail}'`);
      expect(dump.includes(series)).toBe(false);
      expect(dump.includes(secret)).toBe(false);
      expect(dump.toUpperCase().includes(Buffer.from(secret, 'base64url').toString('hex').toUpperCase())).toBe(false);
      console.log('AC12 DB row (hex):', rows[0].join(' | '));
    }

    // "close the browser": only persistent cookies survive
    const kept = await persistent(ctx);
    expect(kept.map((c) => c.name)).toEqual(['remember-me']);
    await ctx.close();
    const r1 = await reopen(browser, kept);
    await expectSignedInOnTasks(r1.page, r1.w);
    await r1.page.screenshot({ path: `${SHOTS}/ac2-restart-signed-in.png`, fullPage: true });
    // rotated: new value, same expiry
    const rotated = (await cookie(r1.ctx, 'remember-me'))!;
    expect(rotated.value).not.toBe(rm.value);
    expect(rotated.value.split('.')[0]).toBe(rm.value.split('.')[0]); // same series (device)
    expect(Math.abs(rotated.expires - rm.expires)).toBeLessThanOrEqual(2);
    if (DB) {
      const rows = tokenRows(mail);
      expect(rows.length).toBe(1);
      expect(rows[0][1]).toBe(sha256Hex(rotated.value.split('.')[1]));
      expect(rows[0][2]).toBe(sha256Hex(rm.value.split('.')[1]));
    }
    // signed-in app works: add a task (POST)
    await addTask(r1.page, `after restart ${uid()}`);
    await expect(r1.page.getByText(SESSION_EXPIRED)).toHaveCount(0);

    if (LOG) {
      await expectLog(mail, /INFO .*event=REMEMBER_ME_LOGIN email=/);
      expectNoSecretsInLog([rm.value, rotated.value]);
    }
    assertClean(w, 'AC2 first');
    assertClean(r1.w, 'AC2 restart');
    await r1.ctx.close();
  });

  test('AC2: idle-expired session + remember me → reload stays on /tasks, and a POST (add task) after expiry works', async ({
    browser,
  }) => {
    const mail = email('ac2idle');
    await createUser(browser, mail);
    const { ctx, page } = await newPage(browser);
    const w = watch(page);
    await loginUi(page, mail, true);
    const xsrfBefore = await cookieValue(ctx, 'XSRF-TOKEN');

    // 1) the session idles out while the tab is open; the next action is a POST
    await killSession(ctx);
    const title = `post after idle ${uid()}`;
    const post = page.waitForResponse((r) => r.url().endsWith('/api/tasks') && r.request().method() === 'POST');
    await addTask(page, title);
    const resp = await post;
    expect(resp.status()).toBe(201);
    const sc = (await resp.allHeaders())['set-cookie'] ?? '';
    expect(sc.match(/XSRF-TOKEN=/g)?.length).toBe(1);
    expect(sc).toMatch(/JSESSIONID=/);
    expect(sc).toMatch(/remember-me=/);
    await expectTask(page, title);
    await expect(page.getByText(SESSION_EXPIRED)).toHaveCount(0);
    await expect(page).toHaveURL(/\/tasks$/);
    expect(await cookieValue(ctx, 'XSRF-TOKEN')).not.toBe(xsrfBefore);
    // the rotated XSRF token is used by the next state change (toggle)
    const put = page.waitForResponse((r) => /\/api\/tasks\/\d+$/.test(r.url()));
    await page.getByRole('checkbox', { name: `Done: ${title}`, exact: true }).check();
    expect((await put).status()).toBe(200);

    // 2) idle again, then reload: straight to /tasks, no login page, no message
    await killSession(ctx);
    w.loginVisits.length = 0;
    await page.goto('/tasks');
    await expectSignedInOnTasks(page, w);
    await expectTask(page, title);
    await page.screenshot({ path: `${SHOTS}/ac2-idle-expired-still-signed-in.png`, fullPage: true });
    assertClean(w, 'AC2 idle');
    await ctx.close();
  });

  test('AC3: without remember me, no cookie and a browser restart lands on /login (no message)', async ({ browser }) => {
    const mail = email('ac3');
    await createUser(browser, mail);
    const { ctx, page } = await newPage(browser);
    const w = watch(page);
    const loginResp = page.waitForResponse((r) => r.url().endsWith('/api/auth/login'));
    await loginUi(page, mail, false);
    const r = await loginResp;
    expect(r.request().postDataJSON().rememberMe ?? false).toBe(false);
    expect((await r.allHeaders())['set-cookie'] ?? '').not.toMatch(/remember-me=/);
    expect(await cookie(ctx, 'remember-me')).toBeUndefined();
    const kept = await persistent(ctx);
    expect(kept).toEqual([]);
    await ctx.close();
    const r1 = await reopen(browser, kept, '/tasks');
    await expectLoginNoMessage(r1.page);
    await r1.page.screenshot({ path: `${SHOTS}/ac3-restart-login.png`, fullPage: true });
    assertClean(w, 'AC3');
    assertClean(r1.w, 'AC3 restart');
    await r1.ctx.close();
  });

  test('AC5: log out on a remember-me device → /login, cookie expired, token deleted; other devices unaffected', async ({
    browser,
  }) => {
    const mail = email('ac5');
    await createUser(browser, mail);
    const a = await newPage(browser);
    const b = await newPage(browser);
    const w = watch(a.page);
    await loginUi(a.page, mail, true);
    await loginUi(b.page, mail, true);
    if (DB) expect(tokenRows(mail).length).toBe(2);
    const stolenBeforeLogout = await persistent(a.ctx);
    const logoutResp = a.page.waitForResponse((r) => r.url().endsWith('/api/auth/logout'));
    await a.page.getByRole('button', { name: 'Log out', exact: true }).click();
    await expect(a.page).toHaveURL(/\/login/);
    expect((await (await logoutResp).allHeaders())['set-cookie']).toMatch(/remember-me=; Path=\/api; Max-Age=0/);
    expect(await cookie(a.ctx, 'remember-me')).toBeUndefined();
    await a.page.screenshot({ path: `${SHOTS}/ac5-logout.png`, fullPage: true });
    if (DB) expect(tokenRows(mail).length).toBe(1); // only this device's token is gone

    // reopen A (nothing persistent left) → /login
    const kept = await persistent(a.ctx);
    expect(kept).toEqual([]);
    const r1 = await reopen(browser, kept, '/tasks');
    await expectLoginNoMessage(r1.page);
    // even the pre-logout cookie (e.g. a backup) no longer signs in
    const r2 = await reopen(browser, stolenBeforeLogout, '/tasks');
    await expectLoginNoMessage(r2.page);
    // device B (remember me) is not affected: restart B → /tasks
    const bKept = await persistent(b.ctx);
    const r3 = await reopen(browser, bKept);
    await expectSignedInOnTasks(r3.page, r3.w);
    assertClean(w, 'AC5');
    for (const c of [a.ctx, b.ctx, r1.ctx, r2.ctx, r3.ctx]) await c.close();
  });

  test('AC6: stolen cookie reused after rotation (outside 60s grace) → rejected, all logins and sessions of the user end, WARN logged', async ({
    browser,
  }) => {
    test.setTimeout(180_000);
    const mail = email('ac6');
    await createUser(browser, mail);
    const victim = await newPage(browser);
    await loginUi(victim.page, mail, true);
    const stolen = await persistent(victim.ctx); // thief copies the cookie now
    // a second remember-me device of the same user
    const other = await newPage(browser);
    await loginUi(other.page, mail, true);
    const otherKept = await persistent(other.ctx);
    await victim.ctx.close();

    // the victim reopens the browser: the cookie is used and rotated
    const v2 = await reopen(browser, stolen);
    const vw = v2.w;
    await expectSignedInOnTasks(v2.page, vw);
    expect(await cookieValue(v2.ctx, 'remember-me')).not.toBe(stolen[0].value);

    // within the 60s grace the old value is still accepted (parallel requests)
    const graceCtx = await browser.newContext({ baseURL: BASE });
    await graceCtx.addCookies(stolen);
    expect((await graceCtx.request.get('/api/auth/me')).status()).toBe(200);
    await graceCtx.close();

    // after the grace window the thief uses the old, already rotated cookie
    await new Promise((r) => setTimeout(r, 62_000));
    const t = await reopen(browser, stolen, '/tasks');
    await expectLoginNoMessage(t.page);
    expect(await cookie(t.ctx, 'remember-me')).toBeUndefined(); // expired by the response
    await t.page.screenshot({ path: `${SHOTS}/ac6-thief-rejected.png`, fullPage: true });
    assertClean(t.w, 'AC6 thief');

    if (DB) expect(tokenRows(mail).length).toBe(0);
    // the victim's session (made from the cookie) is ended too (decision: all sessions)
    await addTask(v2.page, `victim ${uid()}`);
    await expectSessionExpiredFlow(v2.page);
    // and neither the victim's rotated cookie nor the other device's cookie signs in any more
    const v3 = await reopen(browser, await persistent(v2.ctx), '/tasks');
    await expectLoginNoMessage(v3.page);
    const o2 = await reopen(browser, otherKept, '/tasks');
    await expectLoginNoMessage(o2.page);
    // the other device's still-open session is ended too
    await other.page.goto('/account');
    await expect(other.page).toHaveURL(/\/login/);
    if (LOG) {
      await expectLog(mail, /WARN .*event=REMEMBER_ME_REJECTED reason=reused email=/);
      expectNoSecretsInLog(stolen.map((c) => c.value));
    }
    assertClean(vw, 'AC6 victim');
    for (const c of [v2.ctx, other.ctx, t.ctx, v3.ctx, o2.ctx]) await c.close();
  });

  test('AC9: Sessions card between Change password and Delete account; dialog can be cancelled', async ({ browser }) => {
    const mail = email('ac9');
    await createUser(browser, mail);
    const { ctx, page } = await newPage(browser);
    const w = watch(page);
    await loginUi(page, mail, false);
    await page.goto('/account');
    await expect(page.getByRole('heading', { name: 'Sessions' })).toBeVisible();
    const h2s = (await page.locator('h2').allTextContents()).map((t) => t.trim());
    const i = h2s.indexOf('Sessions');
    expect(i).toBeGreaterThan(-1);
    expect(h2s[i - 1]).toBe('Change password');
    // 0.8.0 (specs/google-sign-in.md): "Connected accounts" sits between Sessions and Delete account when Google is on
    expect(h2s.slice(i + 1).filter((h) => h !== 'Connected accounts')[0]).toBe('Delete account');
    await expect(
      page.getByText('Signed in somewhere else, like a shared computer? Log out everywhere except this device.'),
    ).toBeVisible();
    await expect(page.getByRole('button', { name: 'Log out all other devices' })).toBeEnabled();
    await expect(page.locator('#account-logout-others-status')).toHaveText('');
    await page.screenshot({ path: `${SHOTS}/ac9-sessions-card.png`, fullPage: true });

    let calls = 0;
    page.on('request', (r) => {
      if (r.url().includes('logout-other-sessions')) calls += 1;
    });
    await page.locator('#account-logout-others').click();
    await expect(page.getByRole('dialog')).toBeVisible();
    await expect(page.locator('#logout-others-heading')).toHaveText('Log out all other devices?');
    await page.screenshot({ path: `${SHOTS}/ac9-dialog.png`, fullPage: true });
    await page.getByRole('button', { name: 'Cancel' }).click();
    await expect(page.getByRole('dialog')).toHaveCount(0);
    await page.locator('#account-logout-others').click();
    await expect(page.getByRole('dialog')).toBeVisible();
    await page.keyboard.press('Escape');
    await expect(page.getByRole('dialog')).toHaveCount(0);
    expect(calls).toBe(0);
    assertClean(w, 'AC9');
    await ctx.close();
  });

  test('AC10: log out others on A → message, A stays; B (remember me) and C (normal) get the expired flow; B cannot sign back in', async ({
    browser,
  }) => {
    const mail = email('ac10');
    await createUser(browser, mail);
    const a = await newPage(browser);
    const b = await newPage(browser);
    const c = await newPage(browser);
    const wa = watch(a.page);
    const wb = watch(b.page);
    await loginUi(a.page, mail, true);
    await loginUi(b.page, mail, true);
    await loginUi(c.page, mail, false);
    const aKept = await persistent(a.ctx);
    const bKept = await persistent(b.ctx);
    if (DB) expect(tokenRows(mail).length).toBe(2);

    await openLogoutOthers(a.page);
    const sidBefore = await cookieValue(a.ctx, 'JSESSIONID');
    const xsrfBefore = await cookieValue(a.ctx, 'XSRF-TOKEN');
    const rmBefore = await cookieValue(a.ctx, 'remember-me');
    let calls = 0;
    a.page.on('request', (r) => {
      if (r.url().includes('logout-other-sessions')) calls += 1;
    });
    const resp = a.page.waitForResponse((r) => r.url().endsWith('/api/account/logout-other-sessions'));
    await a.page.locator('#logout-others-confirm').dblclick();
    const r = await resp;
    expect(r.status()).toBe(204);
    await expect(a.page.locator('#account-logout-others-status')).toHaveText(LOGGED_OUT_OTHERS);
    await expect(a.page.getByRole('dialog')).toHaveCount(0);
    expect(calls).toBe(1); // double-click sends one request
    await a.page.screenshot({ path: `${SHOTS}/ac10-a-success.png`, fullPage: true });
    // A: new session id, same XSRF token, same remember-me cookie
    expect(await cookieValue(a.ctx, 'JSESSIONID')).not.toBe(sidBefore);
    expect(await cookieValue(a.ctx, 'XSRF-TOKEN')).toBe(xsrfBefore);
    expect(await cookieValue(a.ctx, 'remember-me')).toBe(rmBefore);
    if (DB) expect(tokenRows(mail).length).toBe(1);
    // A stays signed in
    await a.page.goto('/tasks');
    await addTask(a.page, `a still in ${uid()}`);
    await expect(a.page.getByText(SESSION_EXPIRED)).toHaveCount(0);

    // B: next API call → session expired flow
    await addTask(b.page, `b after ${uid()}`);
    await expectSessionExpiredFlow(b.page);
    await b.page.screenshot({ path: `${SHOTS}/ac10-b-session-expired.png`, fullPage: true });
    // C (normal session) too
    await addTask(c.page, `c after ${uid()}`);
    await expectSessionExpiredFlow(c.page);
    // reopening B's browser does not sign it back in
    const b2 = await reopen(browser, bKept, '/tasks');
    await expectLoginNoMessage(b2.page);
    await b2.page.screenshot({ path: `${SHOTS}/ac10-b-reopen-login.png`, fullPage: true });
    // A's own remember-me login still works after a restart
    const a2 = await reopen(browser, aKept);
    await expectSignedInOnTasks(a2.page, a2.w);

    if (LOG) await expectLog(mail, /INFO .*event=OTHER_SESSIONS_LOGGED_OUT trigger=button email=.* path=\/api\/account\/logout-other-sessions/);
    assertClean(wa, 'AC10 A');
    assertClean(wb, 'AC10 B');
    for (const x of [a.ctx, b.ctx, c.ctx, b2.ctx, a2.ctx]) await x.close();
  });

  test('AC11: password change on A logs out B (remember me) and C, A stays with a new session id', async ({ browser }) => {
    const mail = email('ac11');
    await createUser(browser, mail);
    const a = await newPage(browser);
    const b = await newPage(browser);
    const c = await newPage(browser);
    const wa = watch(a.page);
    await loginUi(a.page, mail, false);
    await loginUi(b.page, mail, true);
    await loginUi(c.page, mail, false);
    const bKept = await persistent(b.ctx);
    await a.page.goto('/account');
    const sidBefore = await cookieValue(a.ctx, 'JSESSIONID');
    const xsrfBefore = await cookieValue(a.ctx, 'XSRF-TOKEN');
    await a.page.locator('#account-current-password').fill(PASSWORD);
    await a.page.locator('#account-new-password').fill(NEW_PASSWORD);
    await a.page.locator('#account-confirm-password').fill(NEW_PASSWORD);
    const resp = a.page.waitForResponse((r) => r.url().endsWith('/api/account/password'));
    await a.page.getByRole('button', { name: 'Change password' }).click();
    expect((await resp).status()).toBe(204);
    await expect(a.page.getByText('Password changed')).toBeVisible();
    expect(await cookieValue(a.ctx, 'JSESSIONID')).not.toBe(sidBefore);
    expect(await cookieValue(a.ctx, 'XSRF-TOKEN')).toBe(xsrfBefore);
    if (DB) expect(tokenRows(mail).length).toBe(0);
    await a.page.goto('/tasks');
    await addTask(a.page, `a after pw ${uid()}`);
    await expect(a.page.getByText(SESSION_EXPIRED)).toHaveCount(0);

    await addTask(b.page, `b after pw ${uid()}`);
    await expectSessionExpiredFlow(b.page);
    await b.page.screenshot({ path: `${SHOTS}/ac11-b-session-expired.png`, fullPage: true });
    await c.page.goto('/account');
    await expect(c.page).toHaveURL(/\/login/);
    const b2 = await reopen(browser, bKept, '/tasks');
    await expectLoginNoMessage(b2.page);
    // the new password works for B
    await loginUi(b2.page, mail, false, NEW_PASSWORD);
    if (LOG) await expectLog(mail, /event=OTHER_SESSIONS_LOGGED_OUT trigger=password_change email=/);
    assertClean(wa, 'AC11 A');
    for (const x of [a.ctx, b.ctx, c.ctx, b2.ctx]) await x.close();
  });

  test('AC13: deleting the account removes all its remember-me logins and expires the cookie', async ({ browser }) => {
    const mail = email('ac13');
    await createUser(browser, mail);
    const a = await newPage(browser);
    const b = await newPage(browser);
    const w = watch(a.page);
    await loginUi(a.page, mail, true);
    await loginUi(b.page, mail, true);
    const bKept = await persistent(b.ctx);
    if (DB) expect(tokenRows(mail).length).toBe(2);
    await a.page.goto('/account');
    await a.page.locator('#account-delete').click();
    const dlg = a.page.getByRole('dialog');
    await dlg.locator('#delete-account-password').fill(PASSWORD);
    const resp = a.page.waitForResponse((r) => r.url().endsWith('/api/account/delete'));
    await dlg.getByRole('button', { name: /delete/i }).click();
    const r = await resp;
    expect(r.status()).toBe(204);
    expect((await r.allHeaders())['set-cookie']).toMatch(/remember-me=; Path=\/api; Max-Age=0/);
    await expect(a.page).toHaveURL(/\/login/);
    expect(await cookie(a.ctx, 'remember-me')).toBeUndefined();
    if (DB) {
      expect(tokenRows(mail).length).toBe(0);
      expect(sql(`SELECT COUNT(*) FROM users WHERE email='${mail}'`)).toBe('0');
    }
    const b2 = await reopen(browser, bKept, '/tasks');
    await expectLoginNoMessage(b2.page);
    assertClean(w, 'AC13');
    for (const x of [a.ctx, b.ctx, b2.ctx]) await x.close();
  });

  test('AC15: logout-other-sessions → 401 signed out, 403 without/with wrong CSRF, 204 otherwise', async ({ browser }) => {
    const ctx = await browser.newContext({ baseURL: BASE });
    await ctx.request.get('/api/auth/me');
    const x = await cookieValue(ctx, 'XSRF-TOKEN');
    const r401 = await ctx.request.post('/api/account/logout-other-sessions', { headers: { 'X-XSRF-TOKEN': x } });
    expect(r401.status()).toBe(401);
    expect(r401.headers()['content-type']).toContain('application/problem+json');
    expect((await r401.json()).code).toBe('UNAUTHENTICATED');
    const mail = email('ac15');
    await ctx.request.post('/api/auth/register', {
      data: { email: mail, password: PASSWORD, displayName: `S${uid()}`.slice(0, 40) },
      headers: { 'X-XSRF-TOKEN': x },
    });
    const x2 = await cookieValue(ctx, 'XSRF-TOKEN');
    const r403 = await ctx.request.post('/api/account/logout-other-sessions');
    expect(r403.status()).toBe(403);
    expect((await r403.json()).code).toBe('CSRF_INVALID');
    const r403b = await ctx.request.post('/api/account/logout-other-sessions', { headers: { 'X-XSRF-TOKEN': 'wrong' } });
    expect(r403b.status()).toBe(403);
    expect((await r403b.json()).code).toBe('CSRF_INVALID');
    const r204 = await ctx.request.post('/api/account/logout-other-sessions', { headers: { 'X-XSRF-TOKEN': x2 } });
    expect(r204.status()).toBe(204);
    expect(await r204.text()).toBe('');
    expect(r204.headers()['set-cookie']).toMatch(/^JSESSIONID=[^;]+; Path=\/; HttpOnly; SameSite=Lax$/);
    // not rate limited: 25 calls in a row are all 204
    for (let i = 0; i < 25; i++) {
      const r = await ctx.request.post('/api/account/logout-other-sessions', { headers: { 'X-XSRF-TOKEN': x2 } });
      expect(r.status()).toBe(204);
    }
    await ctx.close();
  });

  test('AC16: loading state (buttons disabled, spinner) and unreachable server error', async ({ browser }) => {
    const mail = email('ac16');
    await createUser(browser, mail);
    const { ctx, page } = await newPage(browser);
    const w = watch(page);
    await loginUi(page, mail, false);
    await openLogoutOthers(page);
    let release!: () => void;
    const gate = new Promise<void>((r) => (release = r));
    await page.route('**/api/account/logout-other-sessions', async (route) => {
      await gate;
      await route.continue();
    });
    const confirm = page.locator('#logout-others-confirm');
    await confirm.click();
    await expect(confirm).toBeDisabled();
    await expect(confirm).toHaveAttribute('aria-busy', 'true');
    await expect(confirm.getByTestId('spinner')).toBeVisible();
    await expect(page.getByRole('dialog').getByRole('button', { name: 'Cancel' })).toBeDisabled();
    await expect(page.locator('#account-logout-others')).toBeDisabled();
    await expect(page.locator('#account-delete')).toBeDisabled();
    await expect(page.getByRole('button', { name: 'Change password' })).toBeDisabled();
    await expect(page.getByRole('region', { name: 'Display name' }).getByRole('button', { name: 'Save' })).toBeDisabled(); // 0.14.0: Time zone has its own Save
    await page.keyboard.press('Escape');
    await expect(page.getByRole('dialog')).toBeVisible(); // can't close while in flight
    await page.screenshot({ path: `${SHOTS}/ac16-loading.png`, fullPage: true });
    release();
    await expect(page.locator('#account-logout-others-status')).toHaveText(LOGGED_OUT_OTHERS);
    await expect(page.locator('#account-delete')).toBeEnabled();
    await page.unroute('**/api/account/logout-other-sessions');

    // unreachable server
    await page.route('**/api/account/logout-other-sessions', (route) => route.abort('connectionrefused'));
    await page.locator('#account-logout-others').click();
    await expect(page.locator('#account-logout-others-status')).toHaveText('');
    await confirm.click();
    const alert = page.getByRole('dialog').getByRole('alert');
    await expect(alert).toHaveText(GENERIC);
    await expect(confirm).toBeEnabled();
    await expect(page.locator('#account-delete')).toBeEnabled();
    await expect(page).toHaveURL(/\/account$/);
    await page.screenshot({ path: `${SHOTS}/ac16-unreachable.png`, fullPage: true });
    // retry works once the server is back
    await page.unroute('**/api/account/logout-other-sessions');
    await confirm.click();
    await expect(page.locator('#account-logout-others-status')).toHaveText(LOGGED_OUT_OTHERS);
    // 500 also shows the generic error
    await page.route('**/api/account/logout-other-sessions', (route) =>
      route.fulfill({ status: 500, contentType: 'application/problem+json', body: '{"status":500}' }),
    );
    await page.locator('#account-logout-others').click();
    await confirm.click();
    await expect(page.getByRole('dialog').getByRole('alert')).toHaveText(GENERIC);
    console.log('AC16 console errors (expected: the aborted/500 request lines):', JSON.stringify(w.consoleErrors));
    expect(w.pageErrors).toEqual([]);
    await ctx.close();
  });

  test('Edge: log out others from a remember-me-signed-in session after an idle expiry; 401 on the dialog → expired flow', async ({
    browser,
  }) => {
    const mail = email('edge');
    await createUser(browser, mail);
    const { ctx, page } = await newPage(browser);
    const w = watch(page);
    await loginUi(page, mail, true);
    await openLogoutOthers(page);
    // the session idles out while the dialog is open: the POST is signed in from remember-me
    await killSession(ctx);
    const resp = page.waitForResponse((r) => r.url().endsWith('/api/account/logout-other-sessions'));
    await page.locator('#logout-others-confirm').click();
    expect((await resp).status()).toBe(204);
    await expect(page.locator('#account-logout-others-status')).toHaveText(LOGGED_OUT_OTHERS);
    await page.reload();
    await expect(page).toHaveURL(/\/account$/);

    // without a remember-me cookie, a 401 on confirm → session expired flow
    await ctx.clearCookies({ name: 'remember-me' });
    await page.locator('#account-logout-others').click();
    await killSession(ctx);
    await page.locator('#logout-others-confirm').click();
    await expectSessionExpiredFlow(page);
    assertClean(w, 'Edge');
    await ctx.close();
  });

  test('Edge: login with rememberMe=false on a device that had a cookie expires it; register expires it too', async ({
    browser,
  }) => {
    const mail = email('edge2');
    await createUser(browser, mail);
    const { ctx, page } = await newPage(browser);
    await loginUi(page, mail, true);
    const rm = await persistent(ctx);
    // the browser still carries the cookie; sign in again (API) with rememberMe=false
    const resp = await ctx.request.post('/api/auth/login', {
      data: { email: mail, password: PASSWORD, rememberMe: false },
      headers: { 'X-XSRF-TOKEN': await cookieValue(ctx, 'XSRF-TOKEN') },
    });
    expect(resp.status()).toBe(200);
    expect(resp.headers()['set-cookie']).toMatch(/remember-me=; Path=\/api; Max-Age=0/);
    expect(await cookie(ctx, 'remember-me')).toBeUndefined();
    if (DB) expect(tokenRows(mail).length).toBe(0);
    const r1 = await reopen(browser, rm, '/tasks');
    await expectLoginNoMessage(r1.page);
    // register with a remember-me cookie of another account
    const other = email('edge2b');
    await createUser(browser, other);
    const c2 = await newPage(browser);
    await loginUi(c2.page, other, true);
    const reg = await c2.ctx.request.post('/api/auth/register', {
      data: { email: email('edge2c'), password: PASSWORD, displayName: `S${uid()}`.slice(0, 40) },
      headers: { 'X-XSRF-TOKEN': await cookieValue(c2.ctx, 'XSRF-TOKEN') },
    });
    expect(reg.status()).toBe(201);
    expect(reg.headers()['set-cookie']).toMatch(/remember-me=; Path=\/api; Max-Age=0/);
    expect(await cookie(c2.ctx, 'remember-me')).toBeUndefined();
    if (DB) expect(tokenRows(other).length).toBe(0);
    for (const x of [ctx, r1.ctx, c2.ctx]) await x.close();
  });
});

// ================================================================ short-limit suite (timing)

test.describe('short limits (E2E_SHORT_SESSIONS=1: idle 1m, max 2m, remember-me 5m)', () => {
  test.skip(!SHORT, 'needs a backend with SERVER_SERVLET_SESSION_TIMEOUT=1m SESSION_MAX_LENGTH=2m REMEMBER_ME_LENGTH=5m');

  test('AC7: a normal session ends at the maximum length although it was active the whole time', async ({ browser }) => {
    test.setTimeout(300_000);
    const mail = email('ac7');
    await createUser(browser, mail);
    const { ctx, page } = await newPage(browser);
    const w = watch(page);
    const t0 = Date.now();
    await loginUi(page, mail, false);
    // stay active: a request every 20s (well inside the 1m idle timeout); the last one before 1:50
    while (Date.now() - t0 + 20_000 < 110_000) {
      await page.waitForTimeout(20_000);
      await page.goto('/tasks');
      await expect(page).toHaveURL(/\/tasks$/);
      await expect(page.getByLabel('New task')).toBeVisible();
    }
    // past 2m since sign-in
    await page.waitForTimeout(Math.max(0, 125_000 - (Date.now() - t0)));
    await addTask(page, `after max ${uid()}`);
    await expectSessionExpiredFlow(page);
    await page.screenshot({ path: `${SHOTS}/ac7-max-length-expired.png`, fullPage: true });
    if (LOG) await expectLog(mail, /event=SESSION_MAX_LENGTH_REACHED kind=normal email=/);
    assertClean(w, 'AC7');
    await ctx.close();
  });

  test('AC8: idle timeout still applies to a normal session; with remember me an idle expiry is invisible (GET reload and POST)', async ({
    browser,
  }) => {
    test.setTimeout(300_000);
    const mail = email('ac8');
    await createUser(browser, mail);
    const n1 = await newPage(browser);
    const r1 = await newPage(browser);
    const wn = watch(n1.page);
    const wr = watch(r1.page);
    await loginUi(n1.page, mail, false);
    await loginUi(r1.page, mail, true);
    const sid = await cookieValue(r1.ctx, 'JSESSIONID');
    // idle > 1m (Tomcat checks idle sessions in a background job every ~60s, so wait a bit more)
    await n1.page.waitForTimeout(80_000);
    // normal session → expired flow
    await addTask(n1.page, `normal idle ${uid()}`);
    await expectSessionExpiredFlow(n1.page);
    await n1.page.screenshot({ path: `${SHOTS}/ac8-idle-normal-expired.png`, fullPage: true });
    // remember-me session: a POST after the idle expiry just works
    const title = `rm idle ${uid()}`;
    const post = r1.page.waitForResponse((r) => r.url().endsWith('/api/tasks') && r.request().method() === 'POST');
    await addTask(r1.page, title);
    expect((await post).status()).toBe(201);
    await expectTask(r1.page, title);
    expect(await cookieValue(r1.ctx, 'JSESSIONID')).not.toBe(sid); // a new session was made
    await expect(r1.page.getByText(SESSION_EXPIRED)).toHaveCount(0);
    // idle again, then reload → /tasks, no login page
    await r1.page.waitForTimeout(80_000);
    wr.loginVisits.length = 0;
    await r1.page.goto('/tasks');
    await expectSignedInOnTasks(r1.page, wr);
    await r1.page.screenshot({ path: `${SHOTS}/ac8-idle-remember-me-signed-in.png`, fullPage: true });
    if (LOG) await expectLog(mail, /event=REMEMBER_ME_LOGIN email=/);
    assertClean(wn, 'AC8 normal');
    assertClean(wr, 'AC8 rm');
    await n1.ctx.close();
    await r1.ctx.close();
  });

  test('AC4: remember-me ends at its original expiry although it was used; 2m max does not apply to it', async ({ browser }) => {
    test.setTimeout(480_000);
    const mail = email('ac4');
    await createUser(browser, mail);
    const { ctx, page } = await newPage(browser);
    const w = watch(page);
    const t0 = Date.now();
    await loginUi(page, mail, true);
    const first = (await cookie(ctx, 'remember-me'))!;
    expect((first.expires * 1000 - t0) / 1000).toBeGreaterThan(290);
    expect((first.expires * 1000 - t0) / 1000).toBeLessThan(305);
    const sid = await cookieValue(ctx, 'JSESSIONID');
    // the remember-me SESSION is not cut at 2m: keep it busy until 2:30 and check it is the same session
    while (Date.now() - t0 + 20_000 < 150_000) {
      await page.waitForTimeout(20_000);
      await page.goto('/tasks');
      await expect(page).toHaveURL(/\/tasks$/);
    }
    expect(await cookieValue(ctx, 'JSESSIONID')).toBe(sid);
    // use the cookie via "browser restarts" every ~40s; each rotation keeps the original expiry
    let kept = await persistent(ctx);
    await ctx.close();
    while (Date.now() - t0 < 270_000) {
      const r = await reopen(browser, kept);
      await expectSignedInOnTasks(r.page, r.w);
      const rm = (await cookie(r.ctx, 'remember-me'))!;
      expect(Math.abs(rm.expires - first.expires)).toBeLessThanOrEqual(2);
      kept = await persistent(r.ctx);
      await r.ctx.close();
      await new Promise((res) => setTimeout(res, 40_000));
    }
    // past 5m from the original sign-in (cookie's own Max-Age is gone; also try the value with a forced expiry)
    await new Promise((res) => setTimeout(res, Math.max(0, 310_000 - (Date.now() - t0))));
    const forced = kept.map((c) => ({ ...c, expires: Math.floor(Date.now() / 1000) + 3600 }));
    const end = await reopen(browser, forced, '/tasks');
    await expectLoginNoMessage(end.page);
    await end.page.screenshot({ path: `${SHOTS}/ac4-remember-me-expired.png`, fullPage: true });
    if (LOG) await expectLog(mail, /event=REMEMBER_ME_REJECTED reason=expired email=/);
    assertClean(w, 'AC4');
    await end.ctx.close();
  });

  test('AC4: an open remember-me session ends at the token expiry while busy (expired flow)', async ({ browser }) => {
    test.setTimeout(480_000);
    const mail = email('ac4b');
    await createUser(browser, mail);
    const { ctx, page } = await newPage(browser);
    const w = watch(page);
    const t0 = Date.now();
    await loginUi(page, mail, true);
    // busy until shortly before the 5m token expiry (the last request before 4:45)
    while (Date.now() - t0 + 30_000 < 285_000) {
      await page.waitForTimeout(30_000);
      await page.goto('/tasks');
      await expect(page).toHaveURL(/\/tasks$/);
    }
    await page.waitForTimeout(Math.max(0, 310_000 - (Date.now() - t0)));
    await addTask(page, `after rm expiry ${uid()}`);
    await expectSessionExpiredFlow(page);
    if (LOG) await expectLog(mail, /event=SESSION_MAX_LENGTH_REACHED kind=remember_me email=/);
    assertClean(w, 'AC4b');
    await ctx.close();
  });
});
