import { BrowserContext, expect, Page, test } from '@playwright/test';

const RUN = `${Date.now()}-${Math.floor(Math.random() * 1e6)}`;
const SHOTS = '../test-results/auth';
const PASSWORD = 'password123';

let counter = 0;
let nameCounter = 0;
/** Display names are unique (ignoring case/accents) and the DB keeps data, so suffix per run. */
function uniqueName(base: string): string {
  nameCounter += 1;
  return `${base} ${RUN}-${nameCounter}`;
}
function uniqueEmail(tag: string): string {
  counter += 1;
  return `qa-e2e-${RUN}-${tag}-${counter}@example.com`;
}

/** Collects console errors and failed responses for a page. */
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

async function registerViaUi(page: Page, name: string, email: string, password = PASSWORD) {
  await page.goto('/register');
  await page.locator('#register-display-name').fill(name);
  await page.locator('#register-email').fill(email);
  await page.locator('#register-password').fill(password);
  await page.locator('#register-confirm-password').fill(password);
  await page.getByRole('button', { name: 'Create account' }).click();
}

async function loginViaUi(page: Page, email: string, password = PASSWORD) {
  await page.locator('#login-email').fill(email);
  await page.locator('#login-password').fill(password);
  await page.getByRole('button', { name: 'Sign in' }).click();
}

/** Creates an account through the API in a separate request context (does not sign the page in). */
async function registerViaApi(context: BrowserContext, name: string, email: string) {
  const req = context.request;
  const me = await req.get('/api/auth/me');
  expect(me.status()).toBe(401);
  const cookies = await context.cookies();
  const token = cookies.find((c) => c.name === 'XSRF-TOKEN')!.value;
  const res = await req.post('/api/auth/register', {
    data: { displayName: name, email, password: PASSWORD },
    headers: { 'X-XSRF-TOKEN': token },
  });
  expect(res.status()).toBe(201);
  // sign this context back out so the page starts signed out
  const cookies2 = await context.cookies();
  const token2 = cookies2.find((c) => c.name === 'XSRF-TOKEN')!.value;
  const out = await req.post('/api/auth/logout', { headers: { 'X-XSRF-TOKEN': token2 } });
  expect(out.status()).toBe(204);
}

test.describe('Registration', () => {
  test('AC1: register signs in, lands on /tasks, header shows display name', async ({ page }) => {
    const w = watch(page);
    const email = uniqueEmail('ac1');
    const nameAliceQA = uniqueName('Alice QA');
    await registerViaUi(page, nameAliceQA, email);
    await expect(page).toHaveURL(/\/tasks$/);
    await expect(page.getByText(`Hi, ${nameAliceQA}`)).toBeVisible();
    await expect(page.getByText('Task App')).toBeVisible();
    await expect(page.getByRole('button', { name: 'Log out' })).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Your tasks' })).toBeVisible();
    await page.screenshot({ path: `${SHOTS}/ac1-tasks-after-register.png`, fullPage: true });
    console.log('AC1 console errors:', JSON.stringify(w.consoleErrors));
    expect(appErrors(w.consoleErrors)).toEqual([]);
    expect(w.pageErrors).toEqual([]);
  });

  test('AC2: duplicate email (different case) shows error under email', async ({ page, context }) => {
    const w = watch(page);
    const email = uniqueEmail('ac2');
    await registerViaApi(context, uniqueName('Bob'), email);
    await registerViaUi(page, uniqueName('Bob Again'), email.toUpperCase().replace('@EXAMPLE.COM', '@Example.com'));
    await expect(page.locator('#register-email-error')).toHaveText(
      'An account with this email already exists',
    );
    await expect(page.locator('#register-display-name-error')).toHaveCount(0);
    await expect(page).toHaveURL(/\/register$/);
    await page.screenshot({ path: `${SHOTS}/ac2-duplicate-email.png`, fullPage: true });
    console.log('AC2 console errors:', JSON.stringify(w.consoleErrors));
    expect(appErrors(w.consoleErrors)).toEqual([]);
  });

  test('AC3: invalid fields show errors under each field and nothing is created', async ({ page }) => {
    const w = watch(page);
    let posted = 0;
    page.on('request', (r) => {
      if (r.url().includes('/api/auth/register') && r.method() === 'POST') posted++;
    });
    await page.goto('/register');
    await page.locator('#register-display-name').fill('   ');
    await page.locator('#register-email').fill('not-an-email');
    await page.locator('#register-password').fill('short');
    await page.locator('#register-confirm-password').fill('short');
    await page.getByRole('button', { name: 'Create account' }).click();
    await expect(page.locator('#register-display-name-error')).toBeVisible();
    await expect(page.locator('#register-email-error')).toBeVisible();
    await expect(page.locator('#register-password-error')).toBeVisible();
    await page.screenshot({ path: `${SHOTS}/ac3-invalid-short.png`, fullPage: true });
    console.log(
      'AC3 messages:',
      await page.locator('#register-display-name-error').textContent(),
      '|',
      await page.locator('#register-email-error').textContent(),
      '|',
      await page.locator('#register-password-error').textContent(),
    );

    // Too long password (73 chars), valid other fields
    await page.locator('#register-display-name').fill(uniqueName('Long'));
    await page.locator('#register-email').fill(uniqueEmail('ac3long'));
    await page.locator('#register-password').fill('a'.repeat(73));
    await page.locator('#register-confirm-password').fill('a'.repeat(73));
    await page.getByRole('button', { name: 'Create account' }).click();
    await expect(page.locator('#register-password-error')).toBeVisible();
    await expect(page.locator('#register-display-name-error')).toHaveCount(0);
    await expect(page.locator('#register-email-error')).toHaveCount(0);
    await expect(page).toHaveURL(/\/register$/);
    await page.screenshot({ path: `${SHOTS}/ac3-invalid-long-password.png`, fullPage: true });
    expect(posted).toBe(0);

    // Exactly 72 chars is accepted
    await page.locator('#register-password').fill('a'.repeat(72));
    await page.locator('#register-confirm-password').fill('a'.repeat(72));
    await page.getByRole('button', { name: 'Create account' }).click();
    await expect(page).toHaveURL(/\/tasks$/);
    expect(appErrors(w.consoleErrors)).toEqual([]);
  });

  test('AC3 (server side): server field errors mapped when client validation is bypassed', async ({
    page,
  }) => {
    // multibyte password: 40 chars but 80 UTF-8 bytes -> server rejects with PASSWORD_LENGTH
    await registerViaUi(page, uniqueName('Multi'), uniqueEmail('ac3mb'), 'é'.repeat(40));
    await expect(page.locator('#register-password-error')).toBeVisible();
    await expect(page).toHaveURL(/\/register$/);
    console.log('AC3 multibyte msg:', await page.locator('#register-password-error').textContent());
  });
});

test.describe('Login', () => {
  test('AC4: login with any-case email lands on /tasks', async ({ page, context }) => {
    const w = watch(page);
    const email = uniqueEmail('ac4');
    const nameCarol = uniqueName('Carol');
    await registerViaApi(context, nameCarol, email);
    await page.goto('/login');
    await loginViaUi(page, `  ${email.toUpperCase()}  `);
    await expect(page).toHaveURL(/\/tasks$/);
    await expect(page.getByText(`Hi, ${nameCarol}`)).toBeVisible();
    await page.screenshot({ path: `${SHOTS}/ac4-login-success.png`, fullPage: true });
    console.log('AC4 console errors:', JSON.stringify(w.consoleErrors));
    expect(appErrors(w.consoleErrors)).toEqual([]);
  });

  test('AC5: wrong password and unknown email show the same message', async ({ page, context }) => {
    const w = watch(page);
    const email = uniqueEmail('ac5');
    await registerViaApi(context, uniqueName('Dave'), email);
    await page.goto('/login');

    await loginViaUi(page, email, 'wrong-password');
    const err = page.locator('[data-testid=form-error]');
    await expect(err).toHaveText('Invalid email or password');
    await expect(page).toHaveURL(/\/login$/);
    await page.screenshot({ path: `${SHOTS}/ac5-wrong-password.png`, fullPage: true });

    await loginViaUi(page, uniqueEmail('ac5-unknown'), 'whatever123');
    await expect(err).toHaveText('Invalid email or password');
    await expect(page).toHaveURL(/\/login$/);
    await page.screenshot({ path: `${SHOTS}/ac5-unknown-email.png`, fullPage: true });
    console.log('AC5 console errors:', JSON.stringify(w.consoleErrors));
    expect(appErrors(w.consoleErrors)).toEqual([]);
  });
});

test.describe('Session and logout', () => {
  test('AC6: refresh keeps me signed in', async ({ page }) => {
    const w = watch(page);
    const nameEve = uniqueName('Eve');
    await registerViaUi(page, nameEve, uniqueEmail('ac6'));
    await expect(page).toHaveURL(/\/tasks$/);
    await page.reload();
    await expect(page).toHaveURL(/\/tasks$/);
    await expect(page.getByText(`Hi, ${nameEve}`)).toBeVisible();
    await page.screenshot({ path: `${SHOTS}/ac6-after-refresh.png`, fullPage: true });
    expect(appErrors(w.consoleErrors)).toEqual([]);
  });

  test('AC7: log out lands on /login; /tasks afterwards goes to /login', async ({ page }) => {
    const w = watch(page);
    await registerViaUi(page, uniqueName('Frank'), uniqueEmail('ac7'));
    await expect(page).toHaveURL(/\/tasks$/);
    await page.getByRole('button', { name: 'Log out' }).click();
    await expect(page).toHaveURL(/\/login$/);
    // no session-expired notice after a normal logout
    await expect(page.getByRole('status')).toHaveCount(0);
    await page.screenshot({ path: `${SHOTS}/ac7-after-logout.png`, fullPage: true });
    // in-app navigation
    await page.goto('/tasks');
    await expect(page).toHaveURL(/\/login$/);
    // server agrees
    const me = await page.request.get('/api/auth/me');
    expect(me.status()).toBe(401);
    console.log('AC7 console errors:', JSON.stringify(w.consoleErrors));
    expect(appErrors(w.consoleErrors)).toEqual([]);
  });

  test('AC7 edge: double-click Log out sends one request and lands on /login', async ({ page }) => {
    await registerViaUi(page, uniqueName('Gina'), uniqueEmail('ac7dbl'));
    await expect(page).toHaveURL(/\/tasks$/);
    let logouts = 0;
    page.on('request', (r) => {
      if (r.url().includes('/api/auth/logout')) logouts++;
    });
    await page.route('**/api/auth/logout', async (route) => {
      await new Promise((r) => setTimeout(r, 500));
      await route.continue();
    });
    await page.getByRole('button', { name: 'Log out' }).dblclick();
    await expect(page).toHaveURL(/\/login$/);
    expect(logouts).toBe(1);
  });

  test('AC8: session expires (JSESSIONID removed) then an API call -> /login with message; sign in returns to /tasks', async ({
    page,
    context,
  }) => {
    const w = watch(page);
    const email = uniqueEmail('ac8');
    const nameHank = uniqueName('Hank');
    await registerViaUi(page, nameHank, email);
    await expect(page).toHaveURL(/\/tasks$/);

    // Simulate server-side expiry: drop the session cookie from the browser.
    await context.clearCookies({ name: 'JSESSIONID' });

    // The app has no non-auth API call yet; make one through the app's own HttpClient
    // (so it passes through the app's interceptors). Uses dev-mode `ng` debugging API.
    const fired = await page.evaluate(() => {
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      const ngApi = (window as any).ng;
      const header = document.querySelector('app-header');
      if (!ngApi || !header) return 'no-ng';
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      const cmp: any = ngApi.getComponent(header);
      const http = cmp?.auth?.http;
      if (!http) return 'no-http';
      http.get('/api/tasks').subscribe({ error: () => undefined });
      return 'ok';
    });
    expect(fired).toBe('ok');

    await expect(page).toHaveURL(/\/login$/);
    await expect(page.getByRole('status')).toHaveText('Your session expired, please sign in again');
    await page.screenshot({ path: `${SHOTS}/ac8-session-expired.png`, fullPage: true });

    await loginViaUi(page, email);
    await expect(page).toHaveURL(/\/tasks$/);
    await expect(page.getByText(`Hi, ${nameHank}`)).toBeVisible();
    console.log('AC8 console errors:', JSON.stringify(w.consoleErrors));
    expect(appErrors(w.consoleErrors)).toEqual([]);
  });

  test('AC8 decision: stale session + fresh load -> /login silently (no message)', async ({
    page,
    context,
  }) => {
    await registerViaUi(page, uniqueName('Ivy'), uniqueEmail('ac8b'));
    await expect(page).toHaveURL(/\/tasks$/);
    await context.clearCookies({ name: 'JSESSIONID' });
    await page.reload();
    await expect(page).toHaveURL(/\/login$/);
    await expect(page.getByRole('status')).toHaveCount(0);
    await page.screenshot({ path: `${SHOTS}/ac8-reload-stale-silent.png`, fullPage: true });
  });

  test('AC8 variant: session killed server-side (logout out of band) then in-app logout click', async ({
    page,
    context,
  }) => {
    // Out-of-band invalidation with the same cookies, then click Log out in the app.
    await registerViaUi(page, uniqueName('Jack'), uniqueEmail('ac8c'));
    await expect(page).toHaveURL(/\/tasks$/);
    const token = (await context.cookies()).find((c) => c.name === 'XSRF-TOKEN')!.value;
    const out = await context.request.post('/api/auth/logout', {
      headers: { 'X-XSRF-TOKEN': token },
    });
    expect(out.status()).toBe(204);
    await page.getByRole('button', { name: 'Log out' }).click();
    await expect(page).toHaveURL(/\/login$/);
  });
});

test.describe('Route protection', () => {
  test('AC9: signed out, / and /tasks go to /login', async ({ page }) => {
    const w = watch(page);
    await page.goto('/');
    await expect(page).toHaveURL(/\/login$/);
    await expect(page.getByRole('status')).toHaveCount(0);
    await page.screenshot({ path: `${SHOTS}/ac9-login-page.png`, fullPage: true });
    await page.goto('/tasks');
    await expect(page).toHaveURL(/\/login$/);
    console.log('AC9 signed-out console errors:', JSON.stringify(w.consoleErrors));
  });

  test('AC9: signed in, /, /login, /register go to /tasks', async ({ page }) => {
    await registerViaUi(page, uniqueName('Kim'), uniqueEmail('ac9'));
    await expect(page).toHaveURL(/\/tasks$/);
    for (const path of ['/', '/login', '/register']) {
      await page.goto(path);
      await expect(page, `visiting ${path}`).toHaveURL(/\/tasks$/);
    }
  });
});

test.describe('States', () => {
  test('AC13: login button disabled with spinner while pending; banner when unreachable', async ({
    page,
  }) => {
    await page.goto('/login');
    await page.route('**/api/auth/login', async (route) => {
      await new Promise((r) => setTimeout(r, 1500));
      await route.continue();
    });
    let posts = 0;
    page.on('request', (r) => {
      if (r.url().includes('/api/auth/login')) posts++;
    });
    await page.locator('#login-email').fill(uniqueEmail('ac13'));
    await page.locator('#login-password').fill('whatever123');
    const btn = page.getByRole('button', { name: 'Sign in' });
    await btn.click();
    await expect(btn).toBeDisabled();
    await expect(page.locator('[data-testid=spinner]')).toBeVisible();
    await page.screenshot({ path: `${SHOTS}/ac13-login-loading.png`, fullPage: true });
    await btn.click({ force: true }); // double-submit attempt
    await expect(page.locator('[data-testid=form-error]')).toHaveText('Invalid email or password');
    await expect(btn).toBeEnabled();
    expect(posts).toBe(1);

    await page.unroute('**/api/auth/login');
    await page.route('**/api/auth/login', (route) => route.abort('connectionrefused'));
    await btn.click();
    await expect(page.getByRole('alert')).toHaveText('Something went wrong, please try again');
    await expect(page).toHaveURL(/\/login$/);
    await page.screenshot({ path: `${SHOTS}/ac13-login-unreachable.png`, fullPage: true });
  });

  test('AC13: register button disabled with spinner while pending; banner when unreachable', async ({
    page,
  }) => {
    await page.goto('/register');
    await page.route('**/api/auth/register', (route) => route.abort('connectionrefused'));
    await page.locator('#register-display-name').fill(uniqueName('Net'));
    await page.locator('#register-email').fill(uniqueEmail('ac13r'));
    await page.locator('#register-password').fill(PASSWORD);
    await page.locator('#register-confirm-password').fill(PASSWORD);
    await page.getByRole('button', { name: 'Create account' }).click();
    await expect(page.getByRole('alert')).toHaveText('Something went wrong, please try again');
    await page.screenshot({ path: `${SHOTS}/ac13-register-unreachable.png`, fullPage: true });

    await page.unroute('**/api/auth/register');
    await page.route('**/api/auth/register', async (route) => {
      await new Promise((r) => setTimeout(r, 1500));
      await route.continue();
    });
    const btn = page.getByRole('button', { name: 'Create account' });
    await btn.click();
    await expect(btn).toBeDisabled();
    await expect(page.locator('[data-testid=spinner]')).toBeVisible();
    await expect(page.getByRole('alert')).toHaveCount(0);
    await page.screenshot({ path: `${SHOTS}/ac13-register-loading.png`, fullPage: true });
    await expect(page).toHaveURL(/\/tasks$/);
  });

  test('Edge: backend unreachable on startup (/me aborted) -> /login, form still usable', async ({
    page,
  }) => {
    const w = watch(page);
    await page.route('**/api/auth/me', (route) => route.abort('connectionrefused'));
    await page.goto('/tasks');
    await expect(page).toHaveURL(/\/login$/);
    await expect(page.locator('#login-email')).toBeVisible();
    console.log('Edge /me aborted console errors:', JSON.stringify(w.consoleErrors));
  });

  test('Edge: 500 from login shows generic banner', async ({ page }) => {
    await page.goto('/login');
    await page.route('**/api/auth/login', (route) =>
      route.fulfill({
        status: 500,
        contentType: 'application/problem+json',
        body: JSON.stringify({
          type: 'about:blank',
          title: 'Internal Server Error',
          status: 500,
          detail: 'x',
          code: 'INTERNAL_ERROR',
        }),
      }),
    );
    await loginViaUi(page, uniqueEmail('e500'), 'whatever123');
    await expect(page.getByRole('alert')).toHaveText('Something went wrong, please try again');
  });

  test('Edge: CSRF cookie missing before login -> app recovers (retry) and signs in', async ({
    page,
    context,
  }) => {
    const email = uniqueEmail('csrf');
    await registerViaApi(context, uniqueName('Csrf'), email);
    await page.goto('/login');
    await context.clearCookies({ name: 'XSRF-TOKEN' });
    await loginViaUi(page, email);
    await expect(page).toHaveURL(/\/tasks$/);
  });
});

test.describe('AC10-12 via browser request context', () => {
  test('AC12: no API response contains password or hash', async ({ page }) => {
    const bodies: string[] = [];
    page.on('response', async (r) => {
      if (r.url().includes('/api/')) {
        try {
          bodies.push(await r.text());
        } catch {
          /* no body */
        }
      }
    });
    const email = uniqueEmail('ac12');
    const namePat = uniqueName('Pat');
    await registerViaUi(page, namePat, email);
    await expect(page).toHaveURL(/\/tasks$/);
    await page.reload();
    await expect(page.getByText(`Hi, ${namePat}`)).toBeVisible();
    await page.getByRole('button', { name: 'Log out' }).click();
    await expect(page).toHaveURL(/\/login$/);
    await loginViaUi(page, email);
    await expect(page).toHaveURL(/\/tasks$/);
    const all = bodies.join('\n');
    expect(all).not.toContain(PASSWORD);
    expect(all).not.toMatch(/\$2[aby]\$/);
    expect(all.toLowerCase()).not.toContain('password_hash');
    expect(all.toLowerCase()).not.toContain('passwordhash');
    console.log('AC12 email used:', email, 'responses captured:', bodies.length);
  });
});
