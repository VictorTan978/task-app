import { APIRequestContext, Browser, BrowserContext, Cookie, expect, Page, Response, test } from '@playwright/test';
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';

/**
 * Email verification and password reset (specs/email-auth.md, AC1–AC19), reading the real emails from Mailpit.
 *
 * Default run: a dev backend with the e2e per-IP overrides (register, login, forgot, verify, check, reset raised)
 * and the per-account limits at their DEFAULTS (resend 3/h per user, reset emails 3/h per account), Mailpit up
 * (`docker compose up -d db mail`), frontend at E2E_BASE_URL (default http://localhost:4200).
 *
 * Optional:
 *   E2E_BACKEND_LOG=<backend console log>  audit-log checks (AC15, AC16, AC17) and "no token in logs".
 *   E2E_DB=1                                MySQL checks via `docker compose exec db mysql` (hashes only, lifetimes,
 *                                           email_verified_at).
 *   E2E_MAILPIT=<url>                       Mailpit API (default http://localhost:8025).
 *   E2E_APP_BASE_URL=<url>                  the backend's APP_BASE_URL the links must point to (default E2E_BASE_URL).
 *   E2E_PRE_V6_EMAIL / E2E_PRE_V6_PASSWORD  an account created BEFORE migration V6 (AC6).
 *
 * Gated modes (each runs ONLY its own tests; all other tests are skipped in that mode):
 *   E2E_SHORT_LINKS=1  backend with VERIFY_EMAIL_LINK_LIFETIME=30s RESET_PASSWORD_LINK_LIFETIME=30s (expiry, AC4/AC11).
 *   E2E_RATE_LIMIT=1   freshly started backend with DEFAULT per-IP limits (AC12 per-IP 429). Uses up the IP budget.
 *   E2E_MAIL_DOWN=1    stops the Mailpit container during the test and starts it again (AC15). Run alone.
 */

const RUN = `${Date.now()}-${Math.floor(Math.random() * 1e6)}`;
const SHOTS = '../test-results/email-auth';
const PASSWORD = 'password123';
const NEW_PASSWORD = 'n3w-Reset-pw!';
const BASE = process.env['E2E_BASE_URL'] ?? 'http://localhost:4200';
const APP_BASE = process.env['E2E_APP_BASE_URL'] ?? BASE;
const MAILPIT = process.env['E2E_MAILPIT'] ?? 'http://localhost:8025';
const LOG = process.env['E2E_BACKEND_LOG'];
const DB = process.env['E2E_DB'] === '1';
const SHORT = process.env['E2E_SHORT_LINKS'] === '1';
const REAL_RL = process.env['E2E_RATE_LIMIT'] === '1';
const MAIL_DOWN = process.env['E2E_MAIL_DOWN'] === '1';
const SPECIAL = SHORT || REAL_RL || MAIL_DOWN;
const PRE_V6_EMAIL = process.env['E2E_PRE_V6_EMAIL'];
const PRE_V6_PASSWORD = process.env['E2E_PRE_V6_PASSWORD'];
const REPO = '..';

const VERIFY_SUBJECT = 'Confirm your email for Task App';
const RESET_SUBJECT = 'Reset your Task App password';
const BANNER_TEXT = (mail: string) => `Please confirm your email address. We sent a link to ${mail}.`;
const CONFIRMED = 'Your email is confirmed';
const LINK_INVALID = 'This link is invalid or has expired';
const EMAIL_SENT = 'Email sent';
const TOO_MANY_EMAILS = 'Too many emails, please try again later';
const TOO_MANY_ATTEMPTS = 'Too many attempts, please try again later';
const RESET_SENT = "If an account exists for that email, we've sent a link to reset your password.";
const RESET_DONE = 'Your password has been reset. Please sign in.';
const GENERIC = 'Something went wrong, please try again';
const SESSION_EXPIRED = 'Your session expired, please sign in again';

let n = 0;
function uid(): string {
  n += 1;
  return `${RUN}-${n}`;
}
function email(tag: string): string {
  return `qa-ea-${tag}-${uid()}@example.com`;
}

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
function assertClean(w: Watch, label: string, allowServerErrors = false, allowAborted = false) {
  const app = w.consoleErrors.filter(
    (e) =>
      !/^Failed to load resource: the server responded with a status of 4\d\d/.test(e) &&
      !(allowAborted && /^Failed to load resource: (net::ERR_CONNECTION_REFUSED|the server responded with a status of 503)/.test(e)),
  );
  console.log(`${label} console errors:`, JSON.stringify(w.consoleErrors));
  expect(app, `${label}: browser console errors`).toEqual([]);
  expect(w.pageErrors, `${label}: page errors`).toEqual([]);
  if (!allowServerErrors) expect(w.serverErrors, `${label}: 5xx`).toEqual([]);
}

/** AC16: no token appears in any API response (body or headers) this page saw. */
async function assertTokensNotInResponses(w: Watch, tokens: string[]) {
  const bodies = await Promise.all(w.apiBodies);
  for (const t of tokens) for (const b of bodies) expect(b.includes(t), `token in API response: ${b.slice(0, 120)}`).toBe(false);
}

async function newPage(browser: Browser, cookies: Cookie[] = []): Promise<{ ctx: BrowserContext; page: Page; w: Watch }> {
  const ctx = await browser.newContext({ baseURL: BASE });
  if (cookies.length) await ctx.addCookies(cookies);
  const page = await ctx.newPage();
  return { ctx, page, w: watch(page) };
}

async function registerUi(page: Page, mail: string, password = PASSWORD) {
  await page.goto('/register');
  await page.locator('#register-display-name').fill(`EA ${uid()}`);
  await page.locator('#register-email').fill(mail);
  await page.locator('#register-password').fill(password);
  await page.locator('#register-confirm-password').fill(password);
  await page.getByRole('button', { name: 'Create account' }).click();
  await expect(page).toHaveURL(/\/tasks$/);
}

async function loginUi(page: Page, mail: string, password = PASSWORD, rememberMe = false) {
  await page.goto('/login');
  await page.locator('#login-email').fill(mail);
  await page.locator('#login-password').fill(password);
  if (rememberMe) await page.locator('#login-remember-me').check();
  await page.getByRole('button', { name: 'Sign in' }).click();
}

async function xsrf(ctx: BrowserContext, request: APIRequestContext, page?: Page): Promise<string> {
  let c = (await ctx.cookies()).find((x) => x.name === 'XSRF-TOKEN');
  if (!c && page && page.url() !== 'about:blank') {
    // Let the app's own boot request set the cookie; our own GET would get a different token.
    for (let i = 0; i < 50 && !c; i++) {
      await page.waitForTimeout(100);
      c = (await ctx.cookies()).find((x) => x.name === 'XSRF-TOKEN');
    }
  }
  if (!c) {
    await request.get('/api/auth/me');
    c = (await ctx.cookies()).find((x) => x.name === 'XSRF-TOKEN');
  }
  return c!.value;
}

/** POST with the context's cookies and the CSRF header (as the app does). */
async function post(ctx: BrowserContext, page: Page, path: string, body?: unknown) {
  const token = await xsrf(ctx, page.request, page);
  return page.request.post(path, { data: body, headers: { 'X-XSRF-TOKEN': token } });
}

async function registerApi(ctx: BrowserContext, page: Page, mail: string, password = PASSWORD) {
  const r = await post(ctx, page, '/api/auth/register', { displayName: `EA ${uid()}`, email: mail, password });
  expect(r.status(), await r.text()).toBe(201);
}

async function me(page: Page) {
  const r = await page.request.get('/api/auth/me');
  return { status: r.status(), body: r.status() === 200 ? await r.json() : null };
}

async function persistent(ctx: BrowserContext): Promise<Cookie[]> {
  return (await ctx.cookies()).filter((c) => c.expires > 0);
}

async function addTask(page: Page, title: string) {
  await page.getByLabel('New task').fill(title);
  await page.getByRole('button', { name: 'Add', exact: true }).click();
}

async function shot(page: Page, name: string) {
  await page.screenshot({ path: `${SHOTS}/${name}.png`, fullPage: true });
}

/** The page reads the token, then removes it from the address bar (AC16, Decisions log). */
async function expectTokenStripped(page: Page, path: string) {
  await expect(page).toHaveURL(new RegExp(`${path}$`));
  expect(page.url()).not.toContain('token=');
  await expect(page.locator('meta[name="referrer"]')).toHaveAttribute('content', 'no-referrer');
}

// ---------------------------------------------------------------- Mailpit helpers

type MailSummary = { ID: string; Subject: string; From: { Name: string; Address: string }; To: { Address: string }[]; Created: string };
type MailFull = MailSummary & { Text: string; HTML: string };

async function mailsTo(to: string, subject?: string): Promise<MailSummary[]> {
  const r = await fetch(`${MAILPIT}/api/v1/search?limit=200&query=${encodeURIComponent(`to:"${to}"`)}`);
  const j = (await r.json()) as { messages?: MailSummary[] };
  return (j.messages ?? []).filter(
    (m) => m.To.some((t) => t.Address.toLowerCase() === to.toLowerCase()) && (!subject || m.Subject === subject),
  );
}

/** Waits until `count` emails (with that subject) reached `to`; returns them newest first. */
async function waitForMails(to: string, subject: string, count: number): Promise<MailSummary[]> {
  await expect
    .poll(async () => (await mailsTo(to, subject)).length, { message: `${count} x "${subject}" to ${to}`, timeout: 20_000 })
    .toBeGreaterThanOrEqual(count);
  return mailsTo(to, subject);
}

/** Async sending: give the background job time, then assert the exact count. */
async function expectMailCountStays(to: string, subject: string, count: number, ms = 4000) {
  await new Promise((r) => setTimeout(r, ms));
  expect((await mailsTo(to, subject)).length, `"${subject}" to ${to}`).toBe(count);
}

async function message(id: string): Promise<MailFull> {
  const r = await fetch(`${MAILPIT}/api/v1/message/${id}`);
  return (await r.json()) as MailFull;
}

function linkIn(text: string, kind: 'verify-email' | 'reset-password'): { url: string; token: string } {
  const m = text.match(new RegExp(`(https?://[^\\s"'<>]+/${kind}\\?token=([A-Za-z0-9_-]+))`));
  expect(m, `link to /${kind} in email`).toBeTruthy();
  return { url: m![1], token: m![2] };
}

/** Full check of one email (AC1, AC8, AC14, AC16) and its link. */
async function readLink(summary: MailSummary, kind: 'verify-email' | 'reset-password') {
  const msg = await message(summary.ID);
  expect(msg.From.Address).toBe('no-reply@taskapp.local');
  expect(msg.From.Name).toBe('Task App');
  expect(msg.Text.trim().length, 'plain-text part').toBeGreaterThan(0);
  expect(msg.HTML.trim().length, 'HTML part').toBeGreaterThan(0);
  const fromText = linkIn(msg.Text, kind);
  const fromHtml = linkIn(msg.HTML.replace(/&amp;/g, '&'), kind);
  expect(fromHtml.token, 'same link in text and HTML').toBe(fromText.token);
  expect(fromText.url.startsWith(`${APP_BASE}/${kind}?token=`), `${fromText.url.split('?')[0]} uses APP_BASE_URL ${APP_BASE}`).toBe(true);
  // 256 random bits = 32 bytes = 43 base64url characters
  expect(fromText.token.length).toBeGreaterThanOrEqual(43);
  expect(Buffer.from(fromText.token, 'base64url').length).toBeGreaterThanOrEqual(32);
  return { ...fromText, msg };
}

/** The link as the app opens it (relative to the frontend under test). */
function local(url: string): string {
  return url.slice(APP_BASE.length);
}

// ---------------------------------------------------------------- DB / log helpers

function sql(query: string): string {
  return execFileSync(
    'docker',
    ['compose', 'exec', '-T', 'db', 'mysql', '-utaskapp', '-ptaskapp', 'taskapp', '-N', '-B', '-e', query],
    { cwd: REPO, encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] },
  ).trim();
}
function sha256Hex(b64url: string): string {
  return createHash('sha256').update(Buffer.from(b64url, 'base64url')).digest('hex').toUpperCase();
}
function tokenRows(mail: string): { hash: string; purpose: string; lifetimeS: number; used: boolean }[] {
  const out = sql(
    `SELECT HEX(t.token_hash), t.purpose, TIMESTAMPDIFF(SECOND, t.created_at, t.expires_at), t.used_at IS NOT NULL ` +
      `FROM email_tokens t JOIN users u ON u.id = t.user_id WHERE u.email = '${mail}' ORDER BY t.created_at`,
  );
  return out
    ? out.split('\n').map((l) => {
        const [hash, purpose, life, used] = l.split('\t');
        return { hash, purpose, lifetimeS: Number(life), used: used === '1' };
      })
    : [];
}
function verifiedAt(mail: string): string {
  return sql(`SELECT IFNULL(email_verified_at,'NULL') FROM users WHERE email = '${mail}'`);
}

function logLines(mail: string): string[] {
  return readFileSync(LOG!, 'utf8')
    .split('\n')
    .filter((l) => l.includes('SECURITY') && l.toLowerCase().includes(`email=${mail.toLowerCase()}`));
}
async function expectLog(mail: string, re: RegExp) {
  await expect
    .poll(() => logLines(mail).some((l) => re.test(l)), { message: `log line ${re} for ${mail}`, timeout: 8000 })
    .toBe(true);
}
function expectNoTokensInLog(tokens: string[]) {
  const log = readFileSync(LOG!, 'utf8');
  for (const t of tokens) expect(log.includes(t), 'token in backend log').toBe(false);
  expect(log).not.toMatch(/verify-email\?token=|reset-password\?token=/);
}

// =============================================================================================================

test.describe('email auth (default run)', () => {
  test.skip(SPECIAL, 'a gated mode (E2E_SHORT_LINKS / E2E_RATE_LIMIT / E2E_MAIL_DOWN) runs only its own tests');

  test('AC1 AC2 AC3 AC13 AC14 AC16 AC17: register → email → confirm in another (signed-out) browser → banner gone after refresh', async ({
    browser,
  }) => {
    const mail = email('ac1');
    const a = await newPage(browser);
    await registerUi(a.page, mail);

    // AC2: banner on every signed-in page, app works normally
    const banner = a.page.getByTestId('verify-email-banner');
    await expect(banner).toBeVisible();
    await expect(banner).toContainText(BANNER_TEXT(mail));
    await expect(a.page.locator('#resend-verification')).toHaveText(/Resend email/);
    await addTask(a.page, `works while unconfirmed ${uid()}`);
    await expect(a.page.getByRole('checkbox', { name: /Done: works while unconfirmed/ })).toBeVisible();
    await shot(a.page, 'ac2-banner-tasks');
    await a.page.goto('/account');
    await expect(a.page.getByTestId('verify-email-banner')).toContainText(BANNER_TEXT(mail));
    await shot(a.page, 'ac2-banner-account');
    expect((await me(a.page)).body.emailVerified).toBe(false);

    // AC1 / AC13 / AC14: the confirmation email in Mailpit
    const [mailSummary] = await waitForMails(mail, VERIFY_SUBJECT, 1);
    const link = await readLink(mailSummary, 'verify-email');
    expect(link.msg.HTML).toMatch(/>\s*Confirm email\s*</); // the "Confirm email" button
    expect(link.msg.Text).toContain(link.url); // plain-text version carries the link itself
    expect(link.msg.Text).toMatch(/24 hours/);
    if (DB) {
      const rows = tokenRows(mail);
      expect(rows.length).toBe(1);
      expect(rows[0].purpose).toBe('VERIFY_EMAIL');
      expect(rows[0].hash).toBe(sha256Hex(link.token)); // only the hash is stored
      expect(rows[0].lifetimeS).toBe(24 * 3600);
      expect(verifiedAt(mail)).toBe('NULL');
    }

    // AC3: open it in another, signed-out browser
    const b = await newPage(browser);
    await b.page.goto(local(link.url));
    await expect(b.page.getByTestId('email-confirmed')).toHaveText(CONFIRMED);
    await expectTokenStripped(b.page, '/verify-email');
    await shot(b.page, 'ac3-confirmed-signed-out');
    await b.page.getByRole('button', { name: 'Continue' }).click();
    await expect(b.page).toHaveURL(/\/login$/); // signed out → sign in

    // signed-in browser: banner gone after refresh
    await a.page.goto('/tasks');
    await a.page.reload();
    await expect(a.page.getByLabel('New task')).toBeVisible();
    await expect(a.page.getByTestId('verify-email-banner')).toHaveCount(0);
    expect((await me(a.page)).body.emailVerified).toBe(true);
    await shot(a.page, 'ac3-banner-gone-after-refresh');
    if (DB) {
      expect(verifiedAt(mail)).not.toBe('NULL');
      expect(tokenRows(mail)[0].used).toBe(true);
    }

    // AC4 (used): the same link again → invalid; signed out, so no resend button
    const c = await newPage(browser);
    await c.page.goto(local(link.url));
    await expect(c.page.getByTestId('link-invalid')).toHaveText(LINK_INVALID);
    await expect(c.page.locator('#resend-verification')).toHaveCount(0);
    await shot(c.page, 'ac4-used-link-signed-out');

    for (const p of [a, b]) await assertTokensNotInResponses(p.w, [link.token]);
    if (LOG) {
      await expectLog(mail, /event=EMAIL_VERIFICATION_SENT .*trigger=register|trigger=register.*event=EMAIL_VERIFICATION_SENT/);
      await expectLog(mail, /event=EMAIL_VERIFIED/);
      await expectLog(mail, /WARN .*event=EMAIL_TOKEN_REJECTED .*reason=/);
      expectNoTokensInLog([link.token]);
    }
    assertClean(a.w, 'AC1 a');
    assertClean(b.w, 'AC1 b');
    assertClean(c.w, 'AC1 c');
    for (const x of [a, b, c]) await x.ctx.close();
  });

  test("AC3: confirm while signed in (same browser) → Continue to /tasks, no banner; confirming ANOTHER user's link keeps my banner", async ({
    browser,
  }) => {
    const mine = email('ac3-me');
    const other = email('ac3-other');
    const a = await newPage(browser);
    await registerUi(a.page, mine);
    const o = await newPage(browser);
    await registerApi(o.ctx, o.page, other);
    const [otherMail] = await waitForMails(other, VERIFY_SUBJECT, 1);
    const otherLink = await readLink(otherMail, 'verify-email');

    // signed in as "mine", open other's link: other confirmed, my session unchanged, my banner stays
    await a.page.goto(local(otherLink.url));
    await expect(a.page.getByTestId('email-confirmed')).toHaveText(CONFIRMED);
    await a.page.getByRole('button', { name: 'Continue' }).click();
    await expect(a.page).toHaveURL(/\/tasks$/);
    await expect(a.page.getByTestId('verify-email-banner')).toContainText(BANNER_TEXT(mine));
    expect((await me(a.page)).body.email).toBe(mine);
    expect((await me(o.page)).body.emailVerified).toBe(true);

    // now my own link, same browser
    const [myMail] = await waitForMails(mine, VERIFY_SUBJECT, 1);
    const myLink = await readLink(myMail, 'verify-email');
    await a.page.goto(local(myLink.url));
    await expect(a.page.getByTestId('email-confirmed')).toHaveText(CONFIRMED);
    await shot(a.page, 'ac3-confirmed-signed-in');
    await a.page.getByRole('button', { name: 'Continue' }).click();
    await expect(a.page).toHaveURL(/\/tasks$/);
    await expect(a.page.getByLabel('New task')).toBeVisible();
    await expect(a.page.getByTestId('verify-email-banner')).toHaveCount(0); // no refresh needed here
    assertClean(a.w, 'AC3 a');
    for (const x of [a, o]) await x.ctx.close();
  });

  test('AC4 AC5: resend (double-click = 1 request) replaces the old link; resend from the invalid page; 429 over 3/h; 409 when confirmed elsewhere', async ({
    browser,
  }) => {
    const mail = email('ac5');
    const a = await newPage(browser);
    await registerUi(a.page, mail);
    const [first] = await waitForMails(mail, VERIFY_SUBJECT, 1);
    const link1 = await readLink(first, 'verify-email');

    // resend #1 (double-click → one request), with its loading state
    let resendCalls = 0;
    a.page.on('request', (r) => {
      if (r.url().endsWith('/api/account/resend-verification')) resendCalls += 1;
    });
    const r1 = a.page.waitForResponse((r) => r.url().endsWith('/api/account/resend-verification'));
    await a.page.locator('#resend-verification').dblclick();
    expect((await r1).status()).toBe(204);
    await expect(a.page.getByTestId('resend-status')).toHaveText(EMAIL_SENT);
    expect(resendCalls).toBe(1);
    await shot(a.page, 'ac5-email-sent');
    const two = await waitForMails(mail, VERIFY_SUBJECT, 2);
    const link2 = await readLink(two[0], 'verify-email');
    expect(link2.token).not.toBe(link1.token);

    // AC4 (replaced): link1 → invalid; signed in and unconfirmed → can resend from there (resend #2)
    await a.page.goto(local(link1.url));
    await expect(a.page.getByTestId('link-invalid')).toHaveText(LINK_INVALID);
    await expectTokenStripped(a.page, '/verify-email');
    await expect(a.page.locator('#resend-verification')).toBeVisible();
    await shot(a.page, 'ac4-replaced-link-signed-in-resend');
    await a.page.locator('#resend-verification').click();
    await expect(a.page.getByTestId('resend-status')).toHaveText(EMAIL_SENT);
    const three = await waitForMails(mail, VERIFY_SUBJECT, 3);
    const link3 = await readLink(three[0], 'verify-email');

    // link2 is now replaced too
    const b = await newPage(browser);
    await b.page.goto(local(link2.url));
    await expect(b.page.getByTestId('link-invalid')).toHaveText(LINK_INVALID);

    // resend #3 from the banner, then #4 → 429
    await a.page.goto('/tasks');
    await a.page.locator('#resend-verification').click();
    await expect(a.page.getByTestId('resend-status')).toHaveText(EMAIL_SENT);
    const r4 = a.page.waitForResponse((r) => r.url().endsWith('/api/account/resend-verification'));
    await a.page.locator('#resend-verification').click();
    const resp4 = await r4;
    expect(resp4.status()).toBe(429);
    expect(resp4.headers()['retry-after']).toMatch(/^\d+$/);
    expect((await resp4.json()).code).toBe('RATE_LIMITED');
    await expect(a.page.getByTestId('verify-email-banner').getByRole('alert')).toHaveText(TOO_MANY_EMAILS);
    await expect(a.page.getByTestId('resend-status')).toHaveText('');
    await shot(a.page, 'ac5-too-many-emails');
    const four = await waitForMails(mail, VERIFY_SUBJECT, 4);
    await expectMailCountStays(mail, VERIFY_SUBJECT, 4, 2000); // register + 3 resends, none for the 429
    const link4 = await readLink(four[0], 'verify-email');
    expect(new Set([link1.token, link2.token, link3.token, link4.token]).size).toBe(4);

    // link3 replaced by link4; link4 confirms (in another browser)
    await b.page.goto(local(link3.url));
    await expect(b.page.getByTestId('link-invalid')).toHaveText(LINK_INVALID);
    await b.page.goto(local(link4.url));
    await expect(b.page.getByTestId('email-confirmed')).toHaveText(CONFIRMED);

    // A still shows the banner (no refresh). Resend → 409 ALREADY_VERIFIED (409 beats 429) → banner disappears
    await expect(a.page.getByTestId('verify-email-banner')).toBeVisible();
    const r5 = a.page.waitForResponse((r) => r.url().endsWith('/api/account/resend-verification'));
    await a.page.locator('#resend-verification').click();
    const resp5 = await r5;
    expect(resp5.status()).toBe(409);
    expect((await resp5.json()).code).toBe('ALREADY_VERIFIED');
    await expect(a.page.getByTestId('verify-email-banner')).toHaveCount(0);
    await shot(a.page, 'ac5-409-banner-hidden');

    if (DB) {
      // replaced links are gone (deleted); the last one is stored as a hash and used
      const rows = tokenRows(mail).filter((r) => r.purpose === 'VERIFY_EMAIL');
      const hashes = rows.map((r) => r.hash);
      expect(hashes).toContain(sha256Hex(link4.token));
      for (const l of [link1, link2, link3]) expect(hashes).not.toContain(sha256Hex(l.token));
      expect(rows.find((r) => r.hash === sha256Hex(link4.token))!.used).toBe(true);
    }
    await assertTokensNotInResponses(a.w, [link1.token, link2.token, link3.token, link4.token]);
    if (LOG) {
      await expectLog(mail, /event=EMAIL_VERIFICATION_SENT.*trigger=resend|trigger=resend.*event=EMAIL_VERIFICATION_SENT/);
      // replaced links are deleted, so their rejection is logged without the account: reason=unknown email=-
      expect(readFileSync(LOG, 'utf8')).toMatch(/WARN .*event=EMAIL_TOKEN_REJECTED reason=unknown email=- .*path=\/api\/auth\/verify-email/);
      await expectLog(mail, /event=RATE_LIMITED.*limit=/);
      expectNoTokensInLog([link1.token, link2.token, link3.token, link4.token]);
    }
    assertClean(a.w, 'AC5 a');
    assertClean(b.w, 'AC5 b');
    for (const x of [a, b]) await x.ctx.close();
  });

  test('AC6: an account created before V6 is unconfirmed, sees the banner and can confirm', async ({ browser }) => {
    test.skip(!PRE_V6_EMAIL || !PRE_V6_PASSWORD, 'E2E_PRE_V6_EMAIL / E2E_PRE_V6_PASSWORD not set');
    const mail = PRE_V6_EMAIL!;
    const a = await newPage(browser);
    await loginUi(a.page, mail, PRE_V6_PASSWORD);
    await expect(a.page).toHaveURL(/\/tasks$/);
    expect((await me(a.page)).body.emailVerified).toBe(false);
    await expect(a.page.getByTestId('verify-email-banner')).toContainText(BANNER_TEXT(mail));
    await shot(a.page, 'ac6-pre-v6-banner');
    const before = (await mailsTo(mail, VERIFY_SUBJECT)).length;
    await a.page.locator('#resend-verification').click();
    await expect(a.page.getByTestId('resend-status')).toHaveText(EMAIL_SENT);
    const list = await waitForMails(mail, VERIFY_SUBJECT, before + 1);
    const link = await readLink(list[0], 'verify-email');
    await a.page.goto(local(link.url));
    await expect(a.page.getByTestId('email-confirmed')).toHaveText(CONFIRMED);
    await a.page.getByRole('button', { name: 'Continue' }).click();
    await expect(a.page.getByTestId('verify-email-banner')).toHaveCount(0);
    expect((await me(a.page)).body.emailVerified).toBe(true);
    assertClean(a.w, 'AC6');
    await a.ctx.close();
  });

  test('AC7 AC8: forgot link on /login; validation; registered (other case) vs unknown → identical UI and 202, only one email', async ({
    browser,
  }) => {
    const registered = email('ac8');
    const unknown = email('ac8-nobody');
    const setup = await newPage(browser);
    await registerApi(setup.ctx, setup.page, registered);
    await setup.ctx.close();

    const a = await newPage(browser);
    await a.page.goto('/login');
    const link = a.page.getByRole('link', { name: 'Forgot password?' });
    await expect(link).toHaveAttribute('href', '/forgot-password');
    await shot(a.page, 'ac7-login-forgot-link');
    await link.click();
    await expect(a.page).toHaveURL(/\/forgot-password$/);
    await expect(a.page.locator('#forgot-email')).toBeVisible();
    const button = a.page.getByRole('button', { name: 'Send reset link' });
    await expect(button).toBeVisible();

    // client-side validation, no request
    let forgotCalls = 0;
    a.page.on('request', (r) => {
      if (r.url().endsWith('/api/auth/forgot-password')) forgotCalls += 1;
    });
    await button.click();
    await expect(a.page.locator('#forgot-email-error')).toBeVisible();
    await a.page.locator('#forgot-email').fill('not-an-email');
    await button.click();
    await expect(a.page.locator('#forgot-email-error')).toBeVisible();
    expect(forgotCalls).toBe(0);
    await shot(a.page, 'ac8-forgot-validation');

    // registered, typed in other letter case with spaces (matched like login); double-click → 1 request
    await a.page.locator('#forgot-email').fill(`  ${registered.toUpperCase()} `);
    const rr = a.page.waitForResponse((r) => r.url().endsWith('/api/auth/forgot-password'));
    await button.dblclick();
    const respReg = await rr;
    await expect(a.page.getByTestId('reset-link-sent')).toHaveText(RESET_SENT);
    expect(forgotCalls).toBe(1);
    const cardReg = await a.page.locator('.auth-card').innerText();
    await shot(a.page, 'ac8-sent-registered');

    const b = await newPage(browser);
    await b.page.goto('/forgot-password');
    await b.page.locator('#forgot-email').fill(unknown);
    const ru = b.page.waitForResponse((r) => r.url().endsWith('/api/auth/forgot-password'));
    await b.page.getByRole('button', { name: 'Send reset link' }).click();
    const respUnk = await ru;
    await expect(b.page.getByTestId('reset-link-sent')).toHaveText(RESET_SENT);
    const cardUnk = await b.page.locator('.auth-card').innerText();
    await shot(b.page, 'ac8-sent-unknown');

    // same UI, same status, same (empty) body
    expect(cardUnk).toBe(cardReg);
    expect(respReg.status()).toBe(202);
    expect(respUnk.status()).toBe(202);
    expect(await respReg.text()).toBe('');
    expect(await respUnk.text()).toBe('');

    // only the registered account gets an email, to its stored address
    const [resetMail] = await waitForMails(registered, RESET_SUBJECT, 1);
    const reset = await readLink(resetMail, 'reset-password');
    expect(reset.msg.Text).toMatch(/1 hour/);
    await expectMailCountStays(registered, RESET_SUBJECT, 1);
    expect((await mailsTo(unknown)).length).toBe(0);
    if (DB) {
      const rows = tokenRows(registered).filter((r) => r.purpose === 'RESET_PASSWORD');
      expect(rows.length).toBe(1);
      expect(rows[0].hash).toBe(sha256Hex(reset.token));
      expect(rows[0].lifetimeS).toBe(3600);
    }
    if (LOG) {
      await expectLog(registered, /event=PASSWORD_RESET_REQUESTED.*outcome=sent/);
      await expectLog(unknown, /event=PASSWORD_RESET_REQUESTED.*outcome=unknown_email/);
      expectNoTokensInLog([reset.token]);
    }
    assertClean(a.w, 'AC8 a');
    assertClean(b.w, 'AC8 b');
    for (const x of [a, b]) await x.ctx.close();
  });

  test('AC8: accent variant matches (via API, the form rejects non-ASCII local parts); timing equal for registered vs unknown', async ({
    browser,
  }) => {
    const id = uid().replace(/-/g, '');
    const plain = `qa-ea-jose${id}@example.com`;
    const accented = `qa-ea-JOSÉ${id}@example.com`;
    const a = await newPage(browser);
    await registerApi(a.ctx, a.page, plain);
    const r = await post(a.ctx, a.page, '/api/auth/forgot-password', { email: accented });
    expect(r.status()).toBe(202);
    const [m] = await waitForMails(plain, RESET_SUBJECT, 1);
    await readLink(m, 'reset-password');
    if (LOG) await expectLog(accented, /event=PASSWORD_RESET_REQUESTED.*outcome=sent/); // logged as typed

    // timing: registered vs unknown, alternating (only 3 reset emails per account; extra requests are silent)
    const times: Record<'reg' | 'unk', number[]> = { reg: [], unk: [] };
    for (let i = 0; i < 6; i++) {
      for (const k of ['reg', 'unk'] as const) {
        const t0 = Date.now();
        const resp = await post(a.ctx, a.page, '/api/auth/forgot-password', {
          email: k === 'reg' ? plain : `qa-ea-nobody-${uid()}@example.com`,
        });
        times[k].push(Date.now() - t0);
        expect(resp.status()).toBe(202);
      }
    }
    const median = (xs: number[]) => [...xs].sort((x, y) => x - y)[Math.floor(xs.length / 2)];
    console.log('AC8 timing ms', JSON.stringify(times));
    expect(Math.abs(median(times.reg) - median(times.unk))).toBeLessThan(100);
    await a.ctx.close();
  });

  test('AC9 AC10 AC11 AC16 AC17: reset flow ends every session and remember-me login, unlocks login, confirms the email', async ({
    browser,
  }) => {
    const mail = email('ac9');
    // S1: registered (normal session); S2: remember me; locked by 5 failed logins
    const s1 = await newPage(browser);
    await registerUi(s1.page, mail);
    const s2 = await newPage(browser);
    await loginUi(s2.page, mail, PASSWORD, true);
    await expect(s2.page).toHaveURL(/\/tasks$/);
    const s2Kept = await persistent(s2.ctx);
    expect(s2Kept.some((c) => c.name === 'remember-me')).toBe(true);
    const lock = await newPage(browser);
    for (let i = 0; i < 5; i++) {
      const r = await post(lock.ctx, lock.page, '/api/auth/login', { email: mail, password: `wrong-${i}-pw` });
      expect(r.status()).toBe(401);
    }
    const locked = await post(lock.ctx, lock.page, '/api/auth/login', { email: mail, password: PASSWORD });
    expect(locked.status()).toBe(429);

    // P: signed out, forgot → email → open link
    const p = await newPage(browser);
    await p.page.goto('/forgot-password');
    await p.page.locator('#forgot-email').fill(mail);
    await p.page.getByRole('button', { name: 'Send reset link' }).click();
    await expect(p.page.getByTestId('reset-link-sent')).toHaveText(RESET_SENT);
    const [resetMail] = await waitForMails(mail, RESET_SUBJECT, 1);
    const link = await readLink(resetMail, 'reset-password');

    const checked = p.page.waitForResponse((r) => r.url().endsWith('/api/auth/check-reset-token'));
    await p.page.goto(local(link.url));
    expect((await checked).status()).toBe(204);
    await expectTokenStripped(p.page, '/reset-password');
    await expect(p.page.getByLabel('New password', { exact: true })).toBeVisible();
    await expect(p.page.getByLabel('Confirm new password')).toBeVisible();
    await shot(p.page, 'ac9-reset-form');

    // the check does not use the token up: another browser can open it too
    const q = await newPage(browser);
    await q.page.goto(local(link.url));
    await expect(q.page.locator('#reset-new-password')).toBeVisible();

    // validation (same rules as registration), no reset request
    let resetCalls = 0;
    p.page.on('request', (r) => {
      if (r.url().endsWith('/api/auth/reset-password')) resetCalls += 1;
    });
    const submit = p.page.getByRole('button', { name: 'Reset password' });
    await submit.click();
    await expect(p.page.locator('#reset-new-password-error')).toBeVisible();
    await p.page.locator('#reset-new-password').fill('short');
    await p.page.locator('#reset-confirm-password').fill('short');
    await submit.click();
    await expect(p.page.locator('#reset-new-password-error')).toContainText(/8/);
    await p.page.locator('#reset-new-password').fill('x'.repeat(73));
    await submit.click();
    await expect(p.page.locator('#reset-new-password-error')).toContainText(/72/);
    await p.page.locator('#reset-new-password').fill(NEW_PASSWORD);
    await p.page.locator('#reset-confirm-password').fill(`${NEW_PASSWORD}x`);
    await submit.click();
    await expect(p.page.locator('#reset-confirm-password-error')).toHaveText('Passwords do not match');
    expect(resetCalls).toBe(0);
    // show / hide
    await p.page.getByRole('button', { name: 'Show password' }).first().click();
    await expect(p.page.locator('#reset-new-password')).toHaveAttribute('type', 'text');
    await shot(p.page, 'ac9-reset-validation-shown');
    await p.page.getByRole('button', { name: 'Hide password' }).first().click();
    await expect(p.page.locator('#reset-new-password')).toHaveAttribute('type', 'password');

    // submit (double-click → 1 request) → /login with the notice
    await p.page.locator('#reset-confirm-password').fill(NEW_PASSWORD);
    const done = p.page.waitForResponse((r) => r.url().endsWith('/api/auth/reset-password'));
    await submit.dblclick();
    const doneResp = await done;
    expect(doneResp.status()).toBe(204);
    const setCookie = (await doneResp.headersArray()).filter((h) => h.name.toLowerCase() === 'set-cookie').map((h) => h.value);
    expect(setCookie.some((c) => /^JSESSIONID=;/.test(c) || /^JSESSIONID=.*Max-Age=0/.test(c))).toBe(true);
    expect(setCookie.some((c) => /^remember-me=;.*Max-Age=0/.test(c) || /^remember-me=.*Max-Age=0/.test(c))).toBe(true);
    expect(setCookie.some((c) => /^XSRF-TOKEN=[^;]+/.test(c))).toBe(true);
    expect(resetCalls).toBe(1);
    await expect(p.page).toHaveURL(/\/login$/);
    await expect(p.page.getByTestId('password-reset')).toHaveText(RESET_DONE);
    await shot(p.page, 'ac9-login-after-reset');

    // old password fails, new password works (also after 5 failures: counter reset), email now confirmed
    await p.page.locator('#login-email').fill(mail);
    await p.page.locator('#login-password').fill(PASSWORD);
    await p.page.getByRole('button', { name: 'Sign in' }).click();
    await expect(p.page.getByText('Invalid email or password')).toBeVisible();
    await p.page.locator('#login-password').fill(NEW_PASSWORD);
    await p.page.getByRole('button', { name: 'Sign in' }).click();
    await expect(p.page).toHaveURL(/\/tasks$/);
    await expect(p.page.getByLabel('New task')).toBeVisible();
    await expect(p.page.getByTestId('verify-email-banner')).toHaveCount(0);
    expect((await me(p.page)).body.emailVerified).toBe(true);
    await shot(p.page, 'ac10-signed-in-new-password-verified');

    // AC10: S1 (normal session) and S2 (remember me) get the expired flow; S2 cannot sign back in by cookie
    await s1.page.goto('/tasks').catch(() => undefined);
    await expect(s1.page).toHaveURL(/\/login/);
    await s2.page.reload();
    await expect(s2.page).toHaveURL(/\/login/);
    const s2Reopen = await newPage(browser, s2Kept);
    await s2Reopen.page.goto('/tasks');
    await expect(s2Reopen.page).toHaveURL(/\/login/);
    await expect(s2Reopen.page.locator('#login-email')).toBeVisible();
    await shot(s2Reopen.page, 'ac10-remember-me-ended');

    // AC11 (used): the link again → invalid with the link back to /forgot-password (q still shows its form → submit)
    await q.page.locator('#reset-new-password').fill(PASSWORD);
    await q.page.locator('#reset-confirm-password').fill(PASSWORD);
    await q.page.getByRole('button', { name: 'Reset password' }).click();
    await expect(q.page.getByTestId('link-invalid')).toHaveText(LINK_INVALID);
    const back = q.page.getByRole('link', { name: 'Request a new link' });
    await expect(back).toHaveAttribute('href', '/forgot-password');
    await shot(q.page, 'ac11-used-link-on-submit');
    await q.page.goto(local(link.url));
    await expect(q.page.getByTestId('link-invalid')).toHaveText(LINK_INVALID);
    await back.click();
    await expect(q.page).toHaveURL(/\/forgot-password$/);

    if (DB) {
      expect(verifiedAt(mail)).not.toBe('NULL');
      expect(sql(`SELECT COUNT(*) FROM remember_me_tokens t JOIN users u ON u.id=t.user_id WHERE u.email='${mail}'`)).toBe('0');
      const rows = tokenRows(mail).filter((r) => r.purpose === 'RESET_PASSWORD');
      expect(rows.length).toBe(1);
      expect(rows[0].used).toBe(true);
    }
    for (const x of [p, q]) await assertTokensNotInResponses(x.w, [link.token]);
    if (LOG) {
      await expectLog(mail, /event=PASSWORD_RESET_COMPLETED/);
      await expectLog(mail, /event=EMAIL_TOKEN_REJECTED.*reason=/);
      expectNoTokensInLog([link.token]);
    }
    assertClean(p.w, 'AC9 p');
    assertClean(q.w, 'AC9 q');
    for (const x of [s1, s2, s2Reopen, lock, p, q]) await x.ctx.close();
  });

  test('AC10: a normal session in another browser gets "session expired" on its next action after a reset', async ({ browser }) => {
    const mail = email('ac10');
    const s1 = await newPage(browser);
    await registerUi(s1.page, mail);
    const p = await newPage(browser);
    expect((await post(p.ctx, p.page, '/api/auth/forgot-password', { email: mail })).status()).toBe(202);
    const [m] = await waitForMails(mail, RESET_SUBJECT, 1);
    const { token } = await readLink(m, 'reset-password');
    expect((await post(p.ctx, p.page, '/api/auth/reset-password', { token, newPassword: NEW_PASSWORD })).status()).toBe(204);
    await addTask(s1.page, `after reset ${uid()}`);
    await expect(s1.page).toHaveURL(/\/login/);
    await expect(s1.page.getByText(SESSION_EXPIRED)).toBeVisible();
    await shot(s1.page, 'ac10-other-session-expired');
    assertClean(s1.w, 'AC10 s1');
    for (const x of [s1, p]) await x.ctx.close();
  });

  test('reset while signed in as ANOTHER user: page works, this browser is signed out afterwards', async ({ browser }) => {
    const owner = email('rs-owner');
    const viewer = email('rs-viewer');
    const o = await newPage(browser);
    await registerApi(o.ctx, o.page, owner);
    const v = await newPage(browser);
    await registerUi(v.page, viewer);
    await v.page.goto('/forgot-password'); // works signed in
    await v.page.locator('#forgot-email').fill(owner);
    await v.page.getByRole('button', { name: 'Send reset link' }).click();
    await expect(v.page.getByTestId('reset-link-sent')).toHaveText(RESET_SENT);
    const [m] = await waitForMails(owner, RESET_SUBJECT, 1);
    const link = await readLink(m, 'reset-password');
    await v.page.goto(local(link.url));
    await v.page.locator('#reset-new-password').fill(NEW_PASSWORD);
    await v.page.locator('#reset-confirm-password').fill(NEW_PASSWORD);
    await v.page.getByRole('button', { name: 'Reset password' }).click();
    await expect(v.page.getByTestId('password-reset')).toHaveText(RESET_DONE);
    expect((await me(v.page)).status).toBe(401);
    assertClean(v.w, 'reset other');
    for (const x of [o, v]) await x.ctx.close();
  });

  test('AC11: a newer request replaces the older reset link (open AND submit); wrong-purpose, missing and garbage tokens', async ({
    browser,
  }) => {
    const mail = email('ac11');
    const a = await newPage(browser);
    await registerApi(a.ctx, a.page, mail);
    const [vMail] = await waitForMails(mail, VERIFY_SUBJECT, 1);
    const verifyLink = await readLink(vMail, 'verify-email');

    expect((await post(a.ctx, a.page, '/api/auth/forgot-password', { email: mail })).status()).toBe(202);
    const [m1] = await waitForMails(mail, RESET_SUBJECT, 1);
    const link1 = await readLink(m1, 'reset-password');
    // open link1 (form shows), then request a new one, then submit link1 → invalid
    const b = await newPage(browser);
    await b.page.goto(local(link1.url));
    await expect(b.page.locator('#reset-new-password')).toBeVisible();
    expect((await post(a.ctx, a.page, '/api/auth/forgot-password', { email: mail })).status()).toBe(202);
    const two = await waitForMails(mail, RESET_SUBJECT, 2);
    const link2 = await readLink(two[0], 'reset-password');
    expect(link2.token).not.toBe(link1.token);
    await b.page.locator('#reset-new-password').fill(NEW_PASSWORD);
    await b.page.locator('#reset-confirm-password').fill(NEW_PASSWORD);
    await b.page.getByRole('button', { name: 'Reset password' }).click();
    await expect(b.page.getByTestId('link-invalid')).toHaveText(LINK_INVALID);
    await expect(b.page.getByRole('link', { name: 'Request a new link' })).toHaveAttribute('href', '/forgot-password');
    // open link1 → invalid
    const c = await newPage(browser);
    await c.page.goto(local(link1.url));
    await expect(c.page.getByTestId('link-invalid')).toHaveText(LINK_INVALID);
    await shot(c.page, 'ac11-replaced-link');
    // a confirmation token on the reset page → invalid; a reset token on the verify page → invalid
    await c.page.goto(`/reset-password?token=${verifyLink.token}`);
    await expect(c.page.getByTestId('link-invalid')).toHaveText(LINK_INVALID);
    await c.page.goto(`/verify-email?token=${link2.token}`);
    await expect(c.page.getByTestId('link-invalid')).toHaveText(LINK_INVALID);
    // missing / garbage
    await c.page.goto('/reset-password');
    await expect(c.page.getByTestId('link-invalid')).toHaveText(LINK_INVALID);
    await c.page.goto('/verify-email');
    await expect(c.page.getByTestId('link-invalid')).toHaveText(LINK_INVALID);
    await c.page.goto('/reset-password?token=garbage%20%3Cscript%3E');
    await expect(c.page.getByTestId('link-invalid')).toHaveText(LINK_INVALID);
    await shot(c.page, 'ac11-garbage-token');
    // link2 still valid and the confirmation link was not used up by the wrong-purpose attempts
    await c.page.goto(local(link2.url));
    await expect(c.page.locator('#reset-new-password')).toBeVisible();
    await c.page.goto(local(verifyLink.url));
    await expect(c.page.getByTestId('email-confirmed')).toHaveText(CONFIRMED);
    if (LOG) await expectLog(mail, /event=EMAIL_TOKEN_REJECTED.*reason=/);
    assertClean(b.w, 'AC11 b');
    assertClean(c.w, 'AC11 c');
    for (const x of [a, b, c]) await x.ctx.close();
  });

  test('refresh mid-flow: the token is gone from the address bar, the emailed link still works', async ({ browser }) => {
    const mail = email('refresh');
    const a = await newPage(browser);
    await registerApi(a.ctx, a.page, mail);
    expect((await post(a.ctx, a.page, '/api/auth/forgot-password', { email: mail })).status()).toBe(202);
    const [m] = await waitForMails(mail, RESET_SUBJECT, 1);
    const link = await readLink(m, 'reset-password');
    const b = await newPage(browser);
    await b.page.goto(local(link.url));
    await expect(b.page.locator('#reset-new-password')).toBeVisible();
    await b.page.reload();
    const afterReload = (await b.page.getByTestId('link-invalid').count()) > 0 ? 'invalid' : 'form';
    console.log(`reset page after reload shows: ${afterReload}`);
    await shot(b.page, 'refresh-reset-page');
    await b.page.goto(local(link.url));
    await expect(b.page.locator('#reset-new-password')).toBeVisible();
    assertClean(b.w, 'refresh');
    for (const x of [a, b]) await x.ctx.close();
  });

  test('AC12: per-account silent limit (3/h, case variants share it): 4th request shows the normal message, no 4th email', async ({
    browser,
  }) => {
    const mail = email('ac12');
    const a = await newPage(browser);
    await registerApi(a.ctx, a.page, mail);
    for (const variant of [mail, mail.toUpperCase(), ` ${mail} `]) {
      expect((await post(a.ctx, a.page, '/api/auth/forgot-password', { email: variant })).status()).toBe(202);
    }
    await waitForMails(mail, RESET_SUBJECT, 3);
    await a.page.goto('/forgot-password');
    await a.page.locator('#forgot-email').fill(mail);
    const r = a.page.waitForResponse((x) => x.url().endsWith('/api/auth/forgot-password'));
    await a.page.getByRole('button', { name: 'Send reset link' }).click();
    expect((await r).status()).toBe(202);
    await expect(a.page.getByTestId('reset-link-sent')).toHaveText(RESET_SENT);
    await shot(a.page, 'ac12-account-limit-normal-message');
    await expectMailCountStays(mail, RESET_SUBJECT, 3);
    if (LOG) await expectLog(mail, /event=PASSWORD_RESET_REQUESTED.*outcome=account_limit/);
    assertClean(a.w, 'AC12');
    await a.ctx.close();
  });

  test('AC16 AC18: CSRF required on all 5 endpoints; resend needs a session; public endpoints never 401; responses match the contract', async ({
    browser,
  }) => {
    const a = await newPage(browser);
    await a.page.request.get('/api/auth/me'); // XSRF cookie, no session
    const endpoints: [string, unknown][] = [
      ['/api/auth/verify-email', { token: 'x' }],
      ['/api/auth/forgot-password', { email: 'a@example.com' }],
      ['/api/auth/check-reset-token', { token: 'x' }],
      ['/api/auth/reset-password', { token: 'x', newPassword: 'password123' }],
      ['/api/account/resend-verification', undefined],
    ];
    for (const [path, body] of endpoints) {
      const r = await a.page.request.post(path, { data: body }); // no X-XSRF-TOKEN
      expect(r.status(), `${path} without CSRF`).toBe(403);
      expect(r.headers()['content-type']).toContain('application/problem+json');
      expect((await r.json()).code).toBe('CSRF_INVALID');
    }
    // signed out, with CSRF: resend → 401; public ones answer normally
    const resend = await post(a.ctx, a.page, '/api/account/resend-verification');
    expect(resend.status()).toBe(401);
    expect((await resend.json()).code).toBe('UNAUTHENTICATED');
    // stale session + bogus remember-me cookie: still no 401 on the public endpoints
    await a.ctx.addCookies([
      { name: 'JSESSIONID', value: 'DEADBEEFDEADBEEFDEADBEEFDEADBEEF', url: BASE },
      { name: 'remember-me', value: 'bm90LWEtcmVhbC1jb29raWU', url: BASE },
    ]);
    const expectProblem = async (r: Awaited<ReturnType<typeof post>>, status: number, code: string) => {
      expect(r.status()).toBe(status);
      expect(r.headers()['content-type']).toContain('application/problem+json');
      const j = await r.json();
      expect(j.code).toBe(code);
      expect(j.status).toBe(status);
      return j;
    };
    const inv = await expectProblem(await post(a.ctx, a.page, '/api/auth/verify-email', { token: 'nope' }), 400, 'TOKEN_INVALID');
    expect(inv.detail).toBe('This link is invalid or has expired.');
    expect(inv.errors).toBeUndefined();
    await expectProblem(await post(a.ctx, a.page, '/api/auth/check-reset-token', { token: 'nope' }), 400, 'TOKEN_INVALID');
    await expectProblem(await post(a.ctx, a.page, '/api/auth/verify-email', {}), 400, 'TOKEN_INVALID');
    // reset: TOKEN_INVALID before VALIDATION_FAILED
    await expectProblem(await post(a.ctx, a.page, '/api/auth/reset-password', { token: 'nope', newPassword: 'x' }), 400, 'TOKEN_INVALID');
    expect((await post(a.ctx, a.page, '/api/auth/forgot-password', { email: 'nobody@example.com' })).status()).toBe(202);
    const val = await expectProblem(await post(a.ctx, a.page, '/api/auth/forgot-password', { email: 'not-an-email' }), 400, 'VALIDATION_FAILED');
    expect(val.errors[0]).toMatchObject({ field: 'email', code: 'INVALID_EMAIL' });
    const req = await expectProblem(await post(a.ctx, a.page, '/api/auth/forgot-password', {}), 400, 'VALIDATION_FAILED');
    expect(req.errors[0]).toMatchObject({ field: 'email', code: 'REQUIRED' });
    // malformed JSON
    const token = await xsrf(a.ctx, a.page.request, a.page);
    const mal = await a.page.request.post('/api/auth/forgot-password', {
      data: '{not json',
      headers: { 'X-XSRF-TOKEN': token, 'Content-Type': 'application/json' },
    });
    expect(mal.status()).toBe(400);
    expect((await mal.json()).code).toBe('MALFORMED_REQUEST');

    // with a valid reset token: VALIDATION_FAILED on newPassword, token NOT used up
    const mail = email('ac18');
    const b = await newPage(browser);
    await registerApi(b.ctx, b.page, mail);
    const reg = await me(b.page);
    expect(reg.body).toEqual({ id: expect.any(Number), email: mail, displayName: expect.any(String), emailVerified: false, hasPassword: true, connections: [], role: 'USER', reminderEmails: true, timeZone: null }); // 0.14.0 fields // 0.8.0 fields; role in 0.9.0
    expect((await post(b.ctx, b.page, '/api/auth/forgot-password', { email: mail })).status()).toBe(202);
    const [m] = await waitForMails(mail, RESET_SUBJECT, 1);
    const { token: rt } = await readLink(m, 'reset-password');
    const short = await expectProblem(await post(b.ctx, b.page, '/api/auth/reset-password', { token: rt, newPassword: 'short' }), 400, 'VALIDATION_FAILED');
    expect(short.errors[0]).toMatchObject({ field: 'newPassword', code: 'PASSWORD_LENGTH' });
    expect((await me(b.page)).status).toBe(200); // failed request keeps the session
    expect((await post(b.ctx, b.page, '/api/auth/check-reset-token', { token: rt })).status()).toBe(204);
    expect((await post(b.ctx, b.page, '/api/auth/check-reset-token', { token: rt })).status()).toBe(204); // repeatable
    // resend while signed in → 204, empty body
    const ok = await post(b.ctx, b.page, '/api/account/resend-verification');
    expect(ok.status()).toBe(204);
    expect(await ok.text()).toBe('');
    // login response carries emailVerified
    const c = await newPage(browser);
    const login = await post(c.ctx, c.page, '/api/auth/login', { email: mail, password: PASSWORD });
    expect(login.status()).toBe(200);
    expect((await login.json()).emailVerified).toBe(false);
    for (const x of [a, b, c]) await x.ctx.close();
  });

  test('AC19: loading states while checking / sending, and "Something went wrong" when the server is unreachable (then retry works)', async ({
    browser,
  }) => {
    const mail = email('ac19');
    const a = await newPage(browser);
    await registerUi(a.page, mail);
    const [vm] = await waitForMails(mail, VERIFY_SUBJECT, 1);
    const verifyLink = await readLink(vm, 'verify-email');

    // --- resend: loading, then unreachable → generic alert
    let release!: () => void;
    let gate = new Promise<void>((r) => (release = r));
    await a.page.route('**/api/account/resend-verification', async (route) => {
      await gate;
      await route.abort('connectionrefused');
    });
    await a.page.locator('#resend-verification').click();
    await expect(a.page.locator('#resend-verification')).toBeDisabled();
    await expect(a.page.locator('#resend-verification').getByTestId('spinner')).toBeVisible();
    await shot(a.page, 'ac19-resend-loading');
    release();
    await expect(a.page.getByTestId('verify-email-banner').getByRole('alert')).toHaveText(GENERIC);
    await expect(a.page.locator('#resend-verification')).toBeEnabled();
    await shot(a.page, 'ac19-resend-error');
    await a.page.unroute('**/api/account/resend-verification');

    // --- verify page: checking state, then unreachable → error + Try again → works
    const v = await newPage(browser);
    gate = new Promise<void>((r) => (release = r));
    let failVerify = true;
    await v.page.route('**/api/auth/verify-email', async (route) => {
      if (!failVerify) return route.continue();
      await gate;
      await route.abort('connectionrefused');
    });
    await v.page.goto(local(verifyLink.url));
    await expect(v.page.getByTestId('link-checking')).toBeVisible();
    await expect(v.page.getByTestId('link-checking').getByTestId('spinner')).toBeVisible();
    await shot(v.page, 'ac19-verify-checking');
    release();
    await expect(v.page.getByTestId('link-error')).toHaveText(GENERIC);
    await shot(v.page, 'ac19-verify-error');
    failVerify = false;
    await v.page.getByRole('button', { name: 'Try again' }).click();
    await expect(v.page.getByTestId('email-confirmed')).toHaveText(CONFIRMED);

    // --- forgot: loading, then 503 → generic banner, form kept, then success
    const f = await newPage(browser);
    await f.page.goto('/forgot-password');
    gate = new Promise<void>((r) => (release = r));
    let failForgot = true;
    await f.page.route('**/api/auth/forgot-password', async (route) => {
      if (!failForgot) return route.continue();
      await gate;
      await route.fulfill({ status: 503, body: '' });
    });
    await f.page.locator('#forgot-email').fill(mail);
    await f.page.getByRole('button', { name: 'Send reset link' }).click();
    await expect(f.page.getByRole('button', { name: 'Send reset link' })).toBeDisabled();
    await expect(f.page.getByTestId('spinner')).toBeVisible();
    await shot(f.page, 'ac19-forgot-loading');
    release();
    await expect(f.page.getByRole('alert')).toHaveText(GENERIC);
    await shot(f.page, 'ac19-forgot-error');
    failForgot = false;
    await f.page.getByRole('button', { name: 'Send reset link' }).click();
    await expect(f.page.getByTestId('reset-link-sent')).toHaveText(RESET_SENT);
    const [rm] = await waitForMails(mail, RESET_SUBJECT, 1);
    const resetLink = await readLink(rm, 'reset-password');

    // --- reset page: checking state, check unreachable → error + Try again; submit loading; submit unreachable
    const r = await newPage(browser);
    gate = new Promise<void>((res) => (release = res));
    let failCheck = true;
    await r.page.route('**/api/auth/check-reset-token', async (route) => {
      if (!failCheck) return route.continue();
      await gate;
      await route.abort('connectionrefused');
    });
    await r.page.goto(local(resetLink.url));
    await expect(r.page.getByTestId('link-checking')).toBeVisible();
    await shot(r.page, 'ac19-reset-checking');
    release();
    await expect(r.page.getByTestId('link-error')).toHaveText(GENERIC);
    await shot(r.page, 'ac19-reset-check-error');
    failCheck = false;
    await r.page.getByRole('button', { name: 'Try again' }).click();
    await expect(r.page.locator('#reset-new-password')).toBeVisible();

    gate = new Promise<void>((res) => (release = res));
    let failReset = true;
    await r.page.route('**/api/auth/reset-password', async (route) => {
      if (!failReset) return route.continue();
      await gate;
      await route.abort('connectionrefused');
    });
    await r.page.locator('#reset-new-password').fill(NEW_PASSWORD);
    await r.page.locator('#reset-confirm-password').fill(NEW_PASSWORD);
    await r.page.getByRole('button', { name: 'Reset password' }).click();
    await expect(r.page.getByRole('button', { name: 'Reset password' })).toBeDisabled();
    await expect(r.page.getByRole('button', { name: 'Reset password' }).getByTestId('spinner')).toBeVisible();
    await shot(r.page, 'ac19-reset-submit-loading');
    release();
    await expect(r.page.getByRole('alert')).toHaveText(GENERIC);
    await shot(r.page, 'ac19-reset-submit-error');
    failReset = false;
    await r.page.getByRole('button', { name: 'Reset password' }).click();
    await expect(r.page.getByTestId('password-reset')).toHaveText(RESET_DONE);

    // the only console errors allowed are the failures this test injected itself
    assertClean(a.w, 'AC19 a', false, true);
    assertClean(v.w, 'AC19 v', false, true);
    assertClean(f.w, 'AC19 f', true, true); // the 503 is injected
    assertClean(r.w, 'AC19 r', false, true);
    for (const x of [a, v, f, r]) await x.ctx.close();
  });

  test('AC14: the display name is HTML-escaped in the HTML email', async ({ browser }) => {
    const mail = email('xss');
    const a = await newPage(browser);
    const name = `<b>X</b> & "Q" ${uid()}`;
    const r = await post(a.ctx, a.page, '/api/auth/register', { displayName: name, email: mail, password: PASSWORD });
    expect(r.status(), await r.text()).toBe(201);
    const [m] = await waitForMails(mail, VERIFY_SUBJECT, 1);
    const msg = await message(m.ID);
    expect(msg.HTML).not.toContain('<b>X</b>');
    expect(msg.HTML).toContain('&lt;b&gt;X&lt;/b&gt; &amp;');
    expect(msg.Text).toContain(name);
    await a.ctx.close();
  });

  test('AC2: the banner on a narrow (phone) screen', async ({ browser }) => {
    const ctx = await browser.newContext({ baseURL: BASE, viewport: { width: 375, height: 740 } });
    const page = await ctx.newPage();
    const w = watch(page);
    const mail = email('narrow');
    await registerUi(page, mail);
    const banner = page.getByTestId('verify-email-banner');
    await expect(banner).toContainText(BANNER_TEXT(mail));
    await expect(page.locator('#resend-verification')).toBeInViewport();
    const box = await banner.boundingBox();
    expect(box!.width).toBeLessThanOrEqual(375);
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(375);
    await shot(page, 'ac2-banner-narrow');
    assertClean(w, 'narrow');
    await ctx.close();
  });

  test('429 messages on every new page (mocked, safe in the full suite)', async ({ browser }) => {
    const body = JSON.stringify({ type: 'about:blank', title: 'Too Many Requests', status: 429, detail: 'Too many attempts. Please try again later.', code: 'RATE_LIMITED' });
    const tooMany = { status: 429, headers: { 'Content-Type': 'application/problem+json', 'Retry-After': '60' }, body };
    const p = await newPage(browser);
    await p.page.route('**/api/auth/forgot-password', (r) => r.fulfill(tooMany));
    await p.page.route('**/api/auth/verify-email', (r) => r.fulfill(tooMany));
    await p.page.route('**/api/auth/check-reset-token', (r) => r.fulfill(tooMany));
    await p.page.goto('/forgot-password');
    await p.page.locator('#forgot-email').fill('someone@example.com');
    await p.page.getByRole('button', { name: 'Send reset link' }).click();
    await expect(p.page.getByRole('alert')).toHaveText(TOO_MANY_ATTEMPTS);
    await expect(p.page.locator('#forgot-email-error')).toHaveCount(0);
    await shot(p.page, 'ac12-forgot-429-mocked');
    await p.page.goto('/verify-email?token=abc');
    await expect(p.page.getByTestId('link-error')).toHaveText(TOO_MANY_ATTEMPTS);
    await p.page.goto('/reset-password?token=abc');
    await expect(p.page.getByTestId('link-error')).toHaveText(TOO_MANY_ATTEMPTS);
    assertClean(p.w, 'mocked 429');
    await p.ctx.close();
  });
});

// =============================================================================================================

test.describe('E2E_SHORT_LINKS=1: expired links (backend with 30s link lifetimes)', () => {
  test.skip(!SHORT, 'needs E2E_SHORT_LINKS=1 and VERIFY_EMAIL_LINK_LIFETIME=30s RESET_PASSWORD_LINK_LIFETIME=30s');
  test.setTimeout(150_000);

  test('AC4 AC11: expired confirmation link (with resend when signed in), expired reset link on open and on submit', async ({ browser }) => {
    const mail = email('exp');
    const a = await newPage(browser);
    await registerUi(a.page, mail);
    const [vm] = await waitForMails(mail, VERIFY_SUBJECT, 1);
    const verifyLink = await readLink(vm, 'verify-email');
    if (DB) expect(tokenRows(mail)[0].lifetimeS).toBe(30);
    expect((await post(a.ctx, a.page, '/api/auth/forgot-password', { email: mail })).status()).toBe(202);
    const [rm] = await waitForMails(mail, RESET_SUBJECT, 1);
    const resetLink = await readLink(rm, 'reset-password');
    // open the reset form while still valid
    const r = await newPage(browser);
    await r.page.goto(local(resetLink.url));
    await expect(r.page.locator('#reset-new-password')).toBeVisible();

    await new Promise((res) => setTimeout(res, 35_000));

    // reset: submit after expiry → invalid
    await r.page.locator('#reset-new-password').fill(NEW_PASSWORD);
    await r.page.locator('#reset-confirm-password').fill(NEW_PASSWORD);
    await r.page.getByRole('button', { name: 'Reset password' }).click();
    await expect(r.page.getByTestId('link-invalid')).toHaveText(LINK_INVALID);
    await shot(r.page, 'ac11-expired-on-submit');
    // reset: open after expiry → invalid
    await r.page.goto(local(resetLink.url));
    await expect(r.page.getByTestId('link-invalid')).toHaveText(LINK_INVALID);
    await expect(r.page.getByRole('link', { name: 'Request a new link' })).toHaveAttribute('href', '/forgot-password');
    await shot(r.page, 'ac11-expired-on-open');
    // verify: expired, signed in and unconfirmed → resend from there
    await a.page.goto(local(verifyLink.url));
    await expect(a.page.getByTestId('link-invalid')).toHaveText(LINK_INVALID);
    await expect(a.page.locator('#resend-verification')).toBeVisible();
    await shot(a.page, 'ac4-expired-link-signed-in');
    await a.page.locator('#resend-verification').click();
    await expect(a.page.getByTestId('resend-status')).toHaveText(EMAIL_SENT);
    const two = await waitForMails(mail, VERIFY_SUBJECT, 2);
    const fresh = await readLink(two[0], 'verify-email');
    await a.page.goto(local(fresh.url));
    await expect(a.page.getByTestId('email-confirmed')).toHaveText(CONFIRMED);
    // old password still works (expired reset changed nothing)
    const c = await newPage(browser);
    const login = await post(c.ctx, c.page, '/api/auth/login', { email: mail, password: PASSWORD });
    expect(login.status()).toBe(200);
    if (LOG) await expectLog(mail, /event=EMAIL_TOKEN_REJECTED.*reason=expired/);
    assertClean(a.w, 'expiry a');
    assertClean(r.w, 'expiry r');
    for (const x of [a, r, c]) await x.ctx.close();
  });
});

test.describe('E2E_RATE_LIMIT=1: real per-IP forgot-password limit (fresh backend, default limits)', () => {
  test.skip(!REAL_RL, 'needs E2E_RATE_LIMIT=1 and a freshly started backend with default limits');

  test('AC12: the 11th forgot-password request from this IP gets 429 and "Too many attempts, please try again later"', async ({ browser }) => {
    const a = await newPage(browser);
    await a.page.goto('/forgot-password');
    for (let i = 0; i < 10; i++) {
      const r = await post(a.ctx, a.page, '/api/auth/forgot-password', { email: `qa-ea-rl-${uid()}@example.com` });
      expect(r.status(), `request ${i + 1}`).toBe(202);
    }
    await a.page.locator('#forgot-email').fill(`qa-ea-rl-${uid()}@example.com`);
    const r = a.page.waitForResponse((x) => x.url().endsWith('/api/auth/forgot-password'));
    await a.page.getByRole('button', { name: 'Send reset link' }).click();
    const resp = await r;
    expect(resp.status()).toBe(429);
    expect(resp.headers()['retry-after']).toMatch(/^\d+$/);
    expect((await resp.json()).code).toBe('RATE_LIMITED');
    await expect(a.page.getByRole('alert')).toHaveText(TOO_MANY_ATTEMPTS);
    await expect(a.page.getByTestId('reset-link-sent')).toHaveCount(0);
    await expect(a.page.getByRole('button', { name: 'Send reset link' })).toBeEnabled();
    await shot(a.page, 'ac12-forgot-ip-429-real');
    if (LOG) {
      const log = readFileSync(LOG, 'utf8');
      expect(log).toMatch(/event=RATE_LIMITED.*limit=\S*forgot/i);
    }
    assertClean(a.w, 'AC12 real');
    await a.ctx.close();
  });
});

test.describe('E2E_MAIL_DOWN=1: mail server down (stops and restarts the Mailpit container)', () => {
  test.skip(!MAIL_DOWN, 'needs E2E_MAIL_DOWN=1; run alone');
  test.setTimeout(180_000);

  test('AC15: register and forgot still work, the failure is only logged, "Resend email" works once mail is back', async ({ browser }) => {
    const mail = email('down');
    const compose = (cmd: string) =>
      execFileSync('docker', ['compose', cmd, 'mail'], { cwd: REPO, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] });
    compose('stop');
    try {
      const a = await newPage(browser);
      const t0 = Date.now();
      await registerUi(a.page, mail);
      console.log(`register with mail down took ${Date.now() - t0} ms`);
      await expect(a.page.getByTestId('verify-email-banner')).toContainText(BANNER_TEXT(mail));
      await shot(a.page, 'ac15-registered-mail-down');

      const f = await newPage(browser);
      await f.page.goto('/forgot-password');
      await f.page.locator('#forgot-email').fill(mail);
      const r = f.page.waitForResponse((x) => x.url().endsWith('/api/auth/forgot-password'));
      await f.page.getByRole('button', { name: 'Send reset link' }).click();
      expect((await r).status()).toBe(202);
      await expect(f.page.getByTestId('reset-link-sent')).toHaveText(RESET_SENT);
      await shot(f.page, 'ac15-forgot-mail-down');

      if (LOG) {
        await expectLog(mail, /event=EMAIL_SEND_FAILED.*reason=smtp/);
        await expectLog(mail, /event=PASSWORD_RESET_REQUESTED.*outcome=failed/);
      }
      compose('start');
      await expect
        .poll(async () => (await fetch(`${MAILPIT}/api/v1/messages`).catch(() => null))?.status ?? 0, { timeout: 30_000 })
        .toBe(200);
      expect((await mailsTo(mail)).length).toBe(0);
      await a.page.locator('#resend-verification').click();
      await expect(a.page.getByTestId('resend-status')).toHaveText(EMAIL_SENT);
      const [m] = await waitForMails(mail, VERIFY_SUBJECT, 1);
      const link = await readLink(m, 'verify-email');
      await a.page.goto(local(link.url));
      await expect(a.page.getByTestId('email-confirmed')).toHaveText(CONFIRMED);
      assertClean(a.w, 'AC15 a');
      assertClean(f.w, 'AC15 f');
      for (const x of [a, f]) await x.ctx.close();
    } finally {
      compose('start');
    }
  });
});
