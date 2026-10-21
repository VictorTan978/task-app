import { APIRequestContext, BrowserContext, expect, Page, test } from '@playwright/test';

/**
 * Security hardening, Section A (rate limiting), AC5: the login and register pages show the
 * rate-limit banner on a 429, with no field errors, and the button is usable again.
 *
 * Two groups:
 * - "mocked 429": the 429 is faked with page.route, so these never touch the real limiter and
 *   always run (safe in the full suite, whatever the backend limits are).
 * - "real backend 429": these hit the real limits (default values: register 5/hour per IP,
 *   login 5 failures per email). They exhaust the per-IP register budget, which would break other
 *   specs running from 127.0.0.1, so they only run when E2E_RATE_LIMIT=1 and expect a freshly
 *   restarted backend with DEFAULT limits:
 *     E2E_RATE_LIMIT=1 npx playwright test e2e/rate-limit.spec.ts
 */

const RUN = `${Date.now()}-${Math.floor(Math.random() * 1e6)}`;
const SHOTS = '../test-results/rate-limit';
const MESSAGE = 'Too many attempts. Please wait a few minutes and try again.';
const PASSWORD = 'password123';
const REAL = process.env['E2E_RATE_LIMIT'] === '1';

const RATE_LIMITED_BODY = {
  type: 'about:blank',
  title: 'Too Many Requests',
  status: 429,
  detail: 'Too many attempts. Please try again later.',
  instance: '/api/auth/login',
  code: 'RATE_LIMITED',
};

function watch(page: Page) {
  const consoleErrors: string[] = [];
  const pageErrors: string[] = [];
  page.on('console', (msg) => {
    if (msg.type() === 'error') consoleErrors.push(msg.text());
  });
  page.on('pageerror', (err) => pageErrors.push(err.message));
  return { consoleErrors, pageErrors };
}

/** Console errors that are NOT the browser's own network log of an HTTP error status. */
function appErrors(errors: string[]): string[] {
  return errors.filter((e) => !/^Failed to load resource: the server responded with a status of/.test(e));
}

async function expectRateLimitedState(page: Page, button: string) {
  const alerts = page.getByRole('alert');
  await expect(alerts).toHaveCount(1);
  await expect(alerts.first()).toHaveText(MESSAGE);
  await expect(page.locator('.field-error')).toHaveCount(0);
  await expect(page.locator('[aria-invalid="true"]')).toHaveCount(0);
  await expect(page.getByText('Invalid email or password')).toHaveCount(0);
  await expect(page.getByRole('button', { name: button })).toBeEnabled();
  await expect(page.getByTestId('spinner')).toHaveCount(0);
}

async function csrf(context: BrowserContext, req: APIRequestContext): Promise<string> {
  await req.get('/api/health');
  return (await context.cookies()).find((c) => c.name === 'XSRF-TOKEN')!.value;
}

test.describe('AC5 rate-limit banner (mocked 429)', () => {
  test('login: 429 with problem body shows banner, no field errors, button enabled', async ({ page }) => {
    const w = watch(page);
    await page.route('**/api/auth/login', (route) =>
      route.fulfill({
        status: 429,
        contentType: 'application/problem+json',
        headers: { 'Retry-After': '840' },
        body: JSON.stringify(RATE_LIMITED_BODY),
      }),
    );
    await page.goto('/login');
    await page.locator('#login-email').fill('someone@example.com');
    await page.locator('#login-password').fill(PASSWORD);
    await page.getByRole('button', { name: 'Sign in' }).click();
    await expectRateLimitedState(page, 'Sign in');
    await expect(page).toHaveURL(/\/login/);
    await page.screenshot({ path: `${SHOTS}/mocked-login-429.png`, fullPage: true });
    expect(appErrors(w.consoleErrors)).toEqual([]);
    expect(w.pageErrors).toEqual([]);
  });

  test('register: 429 without a body still shows the banner', async ({ page }) => {
    const w = watch(page);
    await page.route('**/api/auth/register', (route) =>
      route.fulfill({ status: 429, contentType: 'text/plain', body: 'slow down' }),
    );
    await page.goto('/register');
    await page.locator('#register-display-name').fill('Mock User');
    await page.locator('#register-email').fill('mock@example.com');
    await page.locator('#register-password').fill(PASSWORD);
    await page.locator('#register-confirm-password').fill(PASSWORD);
    await page.getByRole('button', { name: 'Create account' }).click();
    await expectRateLimitedState(page, 'Create account');
    await expect(page).toHaveURL(/\/register/);
    await page.screenshot({ path: `${SHOTS}/mocked-register-429-nobody.png`, fullPage: true });
    expect(appErrors(w.consoleErrors)).toEqual([]);
    expect(w.pageErrors).toEqual([]);
  });
  test('register: 409 field error, fix the email, then 429 shows only the banner', async ({
    page,
  }) => {
    const w = watch(page);
    let calls = 0;
    await page.route('**/api/auth/register', (route) => {
      calls += 1;
      if (calls === 1) {
        return route.fulfill({
          status: 409,
          contentType: 'application/problem+json',
          body: JSON.stringify({
            type: 'about:blank',
            title: 'Conflict',
            status: 409,
            detail: 'x',
            instance: '/api/auth/register',
            code: 'EMAIL_TAKEN',
            errors: [{ field: 'email', code: 'EMAIL_TAKEN', message: 'An account with this email already exists' }],
          }),
        });
      }
      return route.fulfill({
        status: 429,
        contentType: 'application/problem+json',
        headers: { 'Retry-After': '3000' },
        body: JSON.stringify({ ...RATE_LIMITED_BODY, instance: '/api/auth/register' }),
      });
    });
    await page.goto('/register');
    await page.locator('#register-display-name').fill('Mock Taken');
    await page.locator('#register-email').fill('taken@example.com');
    await page.locator('#register-password').fill(PASSWORD);
    await page.locator('#register-confirm-password').fill(PASSWORD);
    const button = page.getByRole('button', { name: 'Create account' });
    await button.click();
    await expect(page.locator('.field-error')).toHaveCount(1);
    // The form blocks resubmitting the same taken email (existing behaviour), so change it.
    await page.locator('#register-email').fill('fresh@example.com');
    await button.click();
    expect(calls).toBe(2);
    await expectRateLimitedState(page, 'Create account');
    await page.screenshot({ path: `${SHOTS}/mocked-register-409-then-429.png`, fullPage: true });
    expect(appErrors(w.consoleErrors)).toEqual([]);
    expect(w.pageErrors).toEqual([]);
  });
});

test.describe('AC5 rate-limit banner (real backend 429)', () => {
  test.skip(!REAL, 'Set E2E_RATE_LIMIT=1 and restart the backend with default limits to run');
  test.describe.configure({ mode: 'serial' });

  test('login: 6th attempt for an email gets the banner instead of "Invalid email or password"', async ({
    page,
  }) => {
    const w = watch(page);
    const email = `qa-rl-ui-${RUN}@example.com`;
    await page.goto('/login');
    await page.locator('#login-email').fill(email);
    await page.locator('#login-password').fill('wrongpass1');
    const button = page.getByRole('button', { name: 'Sign in' });

    for (let i = 1; i <= 5; i++) {
      const res = page.waitForResponse('**/api/auth/login');
      await button.click();
      expect((await res).status()).toBe(401);
      await expect(page.getByTestId('form-error')).toHaveText('Invalid email or password');
    }
    await page.screenshot({ path: `${SHOTS}/real-login-401-before-limit.png`, fullPage: true });

    // read the body as soon as the response arrives; the page may re-render before a later read
    const res6 = page
      .waitForResponse('**/api/auth/login')
      .then(async (r) => ({ status: r.status(), headers: r.headers(), body: await r.json() }));
    await button.click();
    const r6 = await res6;
    expect(r6.status).toBe(429);
    expect(Number(r6.headers['retry-after'])).toBeGreaterThanOrEqual(1);
    expect(r6.body.code).toBe('RATE_LIMITED');
    await expectRateLimitedState(page, 'Sign in');
    await page.screenshot({ path: `${SHOTS}/real-login-429.png`, fullPage: true });

    // Edge: double-click while limited -> still one banner, no crash, button usable.
    await button.dblclick();
    await expectRateLimitedState(page, 'Sign in');

    // Edge: refresh mid-flow -> clean form, no stale banner.
    await page.reload();
    await expect(page.getByRole('alert')).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Sign in' })).toBeEnabled();

    expect(appErrors(w.consoleErrors)).toEqual([]);
    expect(w.pageErrors).toEqual([]);
  });

  test('register: after 5 register requests from this IP the UI shows the banner and clears field errors', async ({
    page,
    browser,
  }) => {
    const w = watch(page);
    // 1: create an account in a separate context (counts 1 of 5).
    const other = await browser.newContext({ baseURL: process.env['E2E_BASE_URL'] ?? 'http://localhost:4200' });
    const takenEmail = `qa-rl-taken-${RUN}@example.com`;
    const created = await other.request.post('/api/auth/register', {
      data: { displayName: `RL Taken ${RUN}`, email: takenEmail, password: PASSWORD },
      headers: { 'X-XSRF-TOKEN': await csrf(other, other.request) },
    });
    expect(created.status()).toBe(201);
    await other.close();

    // 2: UI register with the taken email -> 409 field error (counts 2 of 5).
    await page.goto('/register');
    await page.locator('#register-display-name').fill(`RL UI ${RUN}`);
    await page.locator('#register-email').fill(takenEmail);
    await page.locator('#register-password').fill(PASSWORD);
    await page.locator('#register-confirm-password').fill(PASSWORD);
    const button = page.getByRole('button', { name: 'Create account' });
    const r409 = page.waitForResponse('**/api/auth/register');
    await button.click();
    expect((await r409).status()).toBe(409);
    await expect(page.locator('.field-error')).not.toHaveCount(0);
    await page.screenshot({ path: `${SHOTS}/real-register-409-before-limit.png`, fullPage: true });

    // 3-5: invalid bodies count too.
    const token = await csrf(page.context(), page.request);
    for (let i = 3; i <= 5; i++) {
      const bad = await page.request.post('/api/auth/register', {
        data: { displayName: '', email: 'bad', password: 'x' },
        headers: { 'X-XSRF-TOKEN': token },
      });
      expect(bad.status()).toBe(400);
    }

    // 6: valid new email in the UI -> 429 banner, the old field error is gone.
    await page.locator('#register-email').fill(`qa-rl-new-${RUN}@example.com`);
    const r429 = page
      .waitForResponse('**/api/auth/register')
      .then(async (x) => ({ status: x.status(), headers: x.headers(), body: await x.json() }));
    await button.click();
    const r = await r429;
    expect(r.status).toBe(429);
    expect(Number(r.headers['retry-after'])).toBeGreaterThanOrEqual(1);
    expect(r.body.code).toBe('RATE_LIMITED');
    await expectRateLimitedState(page, 'Create account');
    await expect(page).toHaveURL(/\/register/);
    await page.screenshot({ path: `${SHOTS}/real-register-429.png`, fullPage: true });

    // Edge: double-click while limited.
    await button.dblclick();
    await expectRateLimitedState(page, 'Create account');

    expect(appErrors(w.consoleErrors)).toEqual([]);
    expect(w.pageErrors).toEqual([]);
  });
});
