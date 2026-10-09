import { APIRequestContext, BrowserContext, expect, Page, test } from '@playwright/test';

// Email matching ignores letter case AND accents (V4, 2026-10-09; reverses V3):
// jose@ = josé@ = JOSÉ@ (specs/auth.md AC2, AC4).
//
// NOTE: the Angular register and login forms use Validators.email, which rejects a non-ASCII
// local part ("josé"). The user chose to keep that (Decisions log, option A). So the accent
// steps go through the API from the browser context ("verified via API"), and the UI block is
// asserted explicitly in its own test.

const N = `${Date.now()}${Math.floor(Math.random() * 1e4)}`;
const SHOTS = '../test-results/unique-names/accent-email';
const JOSE_PW = 'josePassword1';
const OTHER_PW = 'joseAccentPw2';

const jose = `jose${N}@example.com`;
const joseAccent = `josé${N}@example.com`; // NFC é
const joseName = `Jose ${N}`;

function watch(page: Page) {
  const consoleErrors: string[] = [];
  const pageErrors: string[] = [];
  page.on('console', (m) => {
    if (m.type() === 'error') consoleErrors.push(m.text());
  });
  page.on('pageerror', (e) => pageErrors.push(e.message));
  return { consoleErrors, pageErrors };
}
const appErrors = (errs: string[]) =>
  errs.filter((e) => !/^Failed to load resource: the server responded with a status of (401|409)/.test(e));

async function registerUi(page: Page, name: string, email: string, pw: string) {
  await page.goto('/register');
  await page.locator('#register-display-name').fill(name);
  await page.locator('#register-email').fill(email);
  await page.locator('#register-password').fill(pw);
  await page.getByRole('button', { name: 'Create account' }).click();
}
async function loginUi(page: Page, email: string, pw: string) {
  await page.goto('/login');
  await page.locator('#login-email').fill(email);
  await page.locator('#login-password').fill(pw);
  await page.getByRole('button', { name: 'Sign in' }).click();
}
async function logout(page: Page) {
  await page.getByRole('button', { name: 'Log out' }).click();
  await expect(page).toHaveURL(/\/login$/);
}
async function api(context: BrowserContext, req: APIRequestContext, path: string, data: object) {
  await req.get('/api/auth/me');
  const token = (await context.cookies()).find((c) => c.name === 'XSRF-TOKEN')!.value;
  return req.post(path, { data, headers: { 'X-XSRF-TOKEN': token } });
}

test.describe.serial('accent- and case-insensitive email', () => {
  const all: { consoleErrors: string[]; pageErrors: string[] }[] = [];
  let joseId = 0;

  test('1. UI: register jose, sign out', async ({ page }) => {
    const w = watch(page);
    all.push(w);
    await registerUi(page, joseName, jose, JOSE_PW);
    await expect(page).toHaveURL(/\/tasks$/);
    await expect(page.getByText(`Hi, ${joseName}`)).toBeVisible();
    joseId = (await (await page.request.get('/api/auth/me')).json()).id;
    expect(joseId).toBeGreaterThan(0);
    await page.screenshot({ path: `${SHOTS}/01-jose-registered.png`, fullPage: true });
    await logout(page);
  });

  test('2. UI (by design): josé is blocked client-side on register and login, no request sent', async ({ page }) => {
    const w = watch(page);
    all.push(w);
    let authPosts = 0;
    page.on('request', (r) => {
      if (r.method() === 'POST' && /\/api\/auth\/(register|login)$/.test(r.url())) authPosts++;
    });
    await registerUi(page, `José Accent ${N}`, joseAccent, OTHER_PW);
    await expect(page.getByText('Enter a valid email address')).toBeVisible();
    await expect(page).toHaveURL(/\/register$/);
    await page.screenshot({ path: `${SHOTS}/02a-ui-register-jose-accent-blocked.png`, fullPage: true });
    await loginUi(page, joseAccent, JOSE_PW);
    await expect(page.getByText('Enter a valid email address')).toBeVisible();
    await expect(page.getByText('Invalid email or password')).toHaveCount(0);
    await page.screenshot({ path: `${SHOTS}/02b-ui-login-jose-accent-blocked.png`, fullPage: true });
    expect(authPosts).toBe(0);
  });

  test('3. API (AC2): register josé@ / JOSÉ@ collides with jose@ -> 409 EMAIL_TAKEN, nothing created', async ({ context }) => {
    const req = context.request;
    for (const email of [joseAccent, joseAccent.toUpperCase(), `  JOSÉ${N}@Example.com  `]) {
      const reg = await api(context, req, '/api/auth/register', {
        displayName: `José Accent ${N} ${email.length}${email.charCodeAt(0)}`,
        email,
        password: OTHER_PW,
      });
      expect(reg.status(), email).toBe(409);
      const body = await reg.json();
      expect(body).toMatchObject({
        status: 409,
        code: 'EMAIL_TAKEN',
        detail: 'An account with this email already exists',
        errors: [{ field: 'email', code: 'EMAIL_TAKEN', message: 'An account with this email already exists' }],
      });
      expect(body.errors).toHaveLength(1);
    }
    // nothing was created: the attempted password does not sign anyone in
    const bad = await api(context, req, '/api/auth/login', { email: joseAccent, password: OTHER_PW });
    expect(bad.status()).toBe(401);
    expect((await bad.json()).code).toBe('INVALID_CREDENTIALS');
  });

  test('3b. API (AC2 example): böb@ collides with bob@', async ({ context }) => {
    const req = context.request;
    const bob = `bob${N}@example.com`;
    const ok = await api(context, req, '/api/auth/register', { displayName: `Bob ${N}`, email: bob, password: JOSE_PW });
    expect(ok.status()).toBe(201);
    await api(context, req, '/api/auth/logout', {});
    const dup = await api(context, req, '/api/auth/register', {
      displayName: `Böb Other ${N}`,
      email: `böb${N}@example.com`,
      password: JOSE_PW,
    });
    expect(dup.status()).toBe(409);
    expect((await dup.json()).code).toBe('EMAIL_TAKEN');
  });

  test('4. API (AC4): login as josé@ / JOSÉ@ with jose password signs in as jose', async ({ page, context }) => {
    const w = watch(page);
    all.push(w);
    const req = context.request;
    for (const email of [joseAccent, `  JOSÉ${N}@EXAMPLE.COM `]) {
      const ok = await api(context, req, '/api/auth/login', { email, password: JOSE_PW });
      expect(ok.status(), email).toBe(200);
      expect(await ok.json()).toEqual({ id: joseId, email: jose, displayName: joseName });
    }
    // the browser page shares the session: header shows jose
    await page.goto('/tasks');
    await expect(page.getByText(`Hi, ${joseName}`)).toBeVisible();
    await page.screenshot({ path: `${SHOTS}/04-login-jose-accent-as-jose.png`, fullPage: true });
    await logout(page);
    const wrong = await api(context, req, '/api/auth/login', { email: joseAccent, password: OTHER_PW });
    expect(wrong.status()).toBe(401);
  });

  test('5. UI (AC2): register JOSE<N>@EXAMPLE.COM rejected as taken', async ({ page }) => {
    const w = watch(page);
    all.push(w);
    await registerUi(page, `Upper Jose ${N}`, jose.toUpperCase(), 'whatever123');
    await expect(page.locator('#register-email-error')).toHaveText('An account with this email already exists');
    await expect(page.locator('#register-display-name-error')).toHaveCount(0);
    await expect(page).toHaveURL(/\/register$/);
    await page.screenshot({ path: `${SHOTS}/05-upper-email-taken.png`, fullPage: true });
  });

  test('6. UI (AC4): login with upper-case email signs in as jose', async ({ page }) => {
    const w = watch(page);
    all.push(w);
    await loginUi(page, jose.toUpperCase(), JOSE_PW);
    await expect(page).toHaveURL(/\/tasks$/);
    await expect(page.getByText(`Hi, ${joseName}`)).toBeVisible();
    await page.screenshot({ path: `${SHOTS}/06-upper-login-as-jose.png`, fullPage: true });
    const body = await (await page.request.get('/api/auth/me')).json();
    expect(body.email).toBe(jose);
    await logout(page);
  });

  test('7. UI: wrong password for jose shows "Invalid email or password"', async ({ page }) => {
    const w = watch(page);
    all.push(w);
    await loginUi(page, jose, OTHER_PW);
    await expect(page.getByText('Invalid email or password')).toBeVisible();
    await expect(page).toHaveURL(/\/login$/);
    await page.screenshot({ path: `${SHOTS}/07-jose-wrong-pw.png`, fullPage: true });
  });

  test('console: no app console errors / page errors across flow', async () => {
    const consoleErrors = all.flatMap((w) => w.consoleErrors);
    const pageErrors = all.flatMap((w) => w.pageErrors);
    console.log('ALL console errors:', JSON.stringify(consoleErrors));
    expect(appErrors(consoleErrors)).toEqual([]);
    expect(pageErrors).toEqual([]);
  });
});
