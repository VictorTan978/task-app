import { APIRequestContext, BrowserContext, expect, Page, test } from '@playwright/test';

// Regression for MySQL collation bug (V3 migration): jose@ and josé@ must be distinct accounts,
// while email matching stays case-insensitive (specs/auth.md AC2, AC4).
//
// NOTE: the Angular register and login forms use Validators.email, which rejects a non-ASCII
// local part ("josé"). So the josé account cannot be created/used through the UI; the josé
// steps below go through the API from the browser context, and the UI block is asserted
// explicitly in its own test so the gap is visible.

const N = `${Date.now()}${Math.floor(Math.random() * 1e4)}`;
const SHOTS = '../test-results/accent-fix';
const JOSE_PW = 'josePassword1';
const JOSE_ACCENT_PW = 'joseAccentPw2';

const jose = `jose${N}@example.com`;
const joseAccent = `josé${N}@example.com`; // NFC é

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
  errs.filter((e) => !/^Failed to load resource: the server responded with a status of/.test(e));

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

test.describe.serial('accent-sensitive, case-insensitive email', () => {
  const all: { consoleErrors: string[]; pageErrors: string[] }[] = [];

  test('1. UI: register jose, sign out', async ({ page }) => {
    const w = watch(page);
    all.push(w);
    await registerUi(page, `Jose ${N}`, jose, JOSE_PW);
    await expect(page).toHaveURL(/\/tasks$/);
    await expect(page.getByText(`Hi, Jose ${N}`)).toBeVisible();
    await page.screenshot({ path: `${SHOTS}/01-jose-registered.png`, fullPage: true });
    await logout(page);
  });

  test('2. UI (observed): josé is blocked client-side on register and login, no request sent', async ({ page }) => {
    const w = watch(page);
    all.push(w);
    let authPosts = 0;
    page.on('request', (r) => {
      if (r.method() === 'POST' && /\/api\/auth\/(register|login)$/.test(r.url())) authPosts++;
    });
    await registerUi(page, `José Accent ${N}`, joseAccent, JOSE_ACCENT_PW);
    await expect(page.getByText('Enter a valid email address')).toBeVisible();
    await expect(page).toHaveURL(/\/register$/);
    await page.screenshot({ path: `${SHOTS}/02a-ui-register-jose-accent-blocked.png`, fullPage: true });
    await loginUi(page, joseAccent, JOSE_PW);
    await expect(page.getByText('Enter a valid email address')).toBeVisible();
    await expect(page.getByText('Invalid email or password')).toHaveCount(0);
    await page.screenshot({ path: `${SHOTS}/02b-ui-login-jose-accent-blocked.png`, fullPage: true });
    expect(authPosts).toBe(0);
  });

  test('3. API: register josé succeeds as a separate user; cross-password logins fail', async ({ page, context }) => {
    const w = watch(page);
    all.push(w);
    const req = context.request;
    const reg = await api(context, req, '/api/auth/register', {
      displayName: `José Accent ${N}`, email: joseAccent, password: JOSE_ACCENT_PW,
    });
    expect(reg.status()).toBe(201);
    const regBody = await reg.json();
    expect(regBody.email).toBe(joseAccent);
    expect(regBody.displayName).toBe(`José Accent ${N}`);
    // session from register: page shows josé's name
    await page.goto('/tasks');
    await expect(page.getByText(`Hi, José Accent ${N}`)).toBeVisible();
    await page.screenshot({ path: `${SHOTS}/03-jose-accent-signed-in.png`, fullPage: true });
    await logout(page);

    const bad1 = await api(context, req, '/api/auth/login', { email: joseAccent, password: JOSE_PW });
    expect(bad1.status()).toBe(401);
    expect((await bad1.json()).code).toBe('INVALID_CREDENTIALS');
    const bad2 = await api(context, req, '/api/auth/login', { email: jose, password: JOSE_ACCENT_PW });
    expect(bad2.status()).toBe(401);
    const ok = await api(context, req, '/api/auth/login', { email: joseAccent, password: JOSE_ACCENT_PW });
    expect(ok.status()).toBe(200);
    expect((await ok.json()).id).toBe(regBody.id);
  });

  test('4. UI: register JOSE<N>@EXAMPLE.COM still rejected as taken (AC2)', async ({ page }) => {
    const w = watch(page);
    all.push(w);
    await registerUi(page, 'Upper Jose', jose.toUpperCase(), 'whatever123');
    await expect(page.locator('#register-email-error')).toHaveText(
      'An account with this email already exists',
    );
    await expect(page).toHaveURL(/\/register$/);
    await page.screenshot({ path: `${SHOTS}/04-upper-email-taken.png`, fullPage: true });
  });

  test('5. UI: login with upper-case email signs in as jose (AC4)', async ({ page }) => {
    const w = watch(page);
    all.push(w);
    await loginUi(page, jose.toUpperCase(), JOSE_PW);
    await expect(page).toHaveURL(/\/tasks$/);
    await expect(page.getByText(`Hi, Jose ${N}`)).toBeVisible();
    await page.screenshot({ path: `${SHOTS}/05-upper-login-as-jose.png`, fullPage: true });
    const body = await (await page.request.get('/api/auth/me')).json();
    expect(body.email).toBe(jose);
    await logout(page);
  });

  test('6. UI: wrong password for jose shows "Invalid email or password"', async ({ page }) => {
    const w = watch(page);
    all.push(w);
    await loginUi(page, jose, JOSE_ACCENT_PW);
    await expect(page.getByText('Invalid email or password')).toBeVisible();
    await expect(page).toHaveURL(/\/login$/);
    await page.screenshot({ path: `${SHOTS}/06-jose-wrong-pw.png`, fullPage: true });
  });

  test('console: no app console errors / page errors across flow', async () => {
    const consoleErrors = all.flatMap((w) => w.consoleErrors);
    const pageErrors = all.flatMap((w) => w.pageErrors);
    console.log('ALL console errors:', JSON.stringify(consoleErrors));
    expect(appErrors(consoleErrors)).toEqual([]);
    expect(pageErrors).toEqual([]);
  });
});
