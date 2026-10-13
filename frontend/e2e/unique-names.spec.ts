import { Browser, expect, Page, test } from '@playwright/test';

// specs/auth.md AC14: display names are unique after trimming, ignoring letter case and accents.
// Every test fails on app console errors or page errors (the browser's own 401/409 lines are OK).

const S = `${Date.now().toString(36)}${Math.floor(Math.random() * 1e4)}`;
const SHOTS = '../test-results/unique-names';
const PASSWORD = 'password123';
const TAKEN = 'This display name is already taken';
const EMAIL_TAKEN = 'An account with this email already exists';
let counter = 0;
const uniqEmail = (tag: string) => `qa-names-${S}-${tag}-${++counter}@example.com`;

function watch(page: Page) {
  const errors: string[] = [];
  page.on('console', (m) => {
    if (m.type() === 'error') errors.push(m.text());
  });
  page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`));
  return errors;
}
const appErrors = (errs: string[]) =>
  errs.filter((e) => !/^Failed to load resource: the server responded with a status of (401|409)/.test(e));

async function fillRegister(page: Page, name: string, email: string, pw = PASSWORD) {
  await page.locator('#register-display-name').fill(name);
  await page.locator('#register-email').fill(email);
  await page.locator('#register-password').fill(pw);
  await page.locator('#register-confirm-password').fill(pw);
}
async function submit(page: Page) {
  await page.getByRole('button', { name: 'Create account' }).click();
}

/** Registers the owner of `name` in a separate context so the test page stays signed out. */
async function registerOwner(browser: Browser, name: string, email: string) {
  const ctx = await browser.newContext({ baseURL: process.env['E2E_BASE_URL'] ?? 'http://localhost:4200' });
  const p = await ctx.newPage();
  const errors = watch(p);
  await p.goto('/register');
  await fillRegister(p, name, email);
  await submit(p);
  await expect(p).toHaveURL(/\/tasks$/);
  await expect(p.getByText(`Hi, ${name}`)).toBeVisible();
  await p.screenshot({ path: `${SHOTS}/ac14-owner-registered.png`, fullPage: true });
  expect(appErrors(errors)).toEqual([]);
  await ctx.close();
}

/** The account does not exist: logging in with it fails. */
async function expectNoAccount(page: Page, email: string) {
  await page.request.get('/api/auth/me');
  const token = (await page.context().cookies()).find((c) => c.name === 'XSRF-TOKEN')!.value;
  const res = await page.request.post('/api/auth/login', {
    data: { email, password: PASSWORD },
    headers: { 'X-XSRF-TOKEN': token },
  });
  expect(res.status(), `no account for ${email}`).toBe(401);
}

test.describe('AC14: unique display names (UI)', () => {
  test('"José", "jose", "JOSE", " José " are all taken; stays on /register; nothing created', async ({
    page,
    browser,
  }) => {
    const owner = `José ${S}`;
    await registerOwner(browser, owner, uniqEmail('owner'));

    const errors = watch(page);
    const statuses: number[] = [];
    page.on('response', (r) => {
      if (r.request().method() === 'POST' && r.url().endsWith('/api/auth/register')) statuses.push(r.status());
    });
    const variants = [`José ${S}`, `jose ${S}`, `JOSE ${S}`, ` José ${S} `];
    for (const [i, name] of variants.entries()) {
      const email = uniqEmail(`v${i}`);
      await page.goto('/register');
      await fillRegister(page, name, email);
      await submit(page);
      await expect(page.locator('#register-display-name-error'), JSON.stringify(name)).toHaveText(TAKEN);
      await expect(page.locator('#register-display-name')).toHaveAttribute('aria-invalid', 'true');
      await expect(page.locator('#register-email-error')).toHaveCount(0);
      await expect(page.getByRole('alert')).toHaveCount(0);
      await expect(page).toHaveURL(/\/register$/);
      await page.screenshot({ path: `${SHOTS}/ac14-variant-${i}-taken.png`, fullPage: true });
      await expectNoAccount(page, email);
    }
    expect(statuses).toEqual([409, 409, 409, 409]);
    console.log('AC14 console errors:', JSON.stringify(errors));
    expect(appErrors(errors)).toEqual([]);
  });

  test('email and name both taken: both field errors show', async ({ page, browser }) => {
    const owner = `Zoë Both ${S}`;
    const ownerEmail = uniqEmail('both');
    await registerOwner(browser, owner, ownerEmail);

    const errors = watch(page);
    await page.goto('/register');
    await fillRegister(page, `ZOE BOTH ${S}`, ownerEmail.toUpperCase());
    const resp = page.waitForResponse((r) => r.url().endsWith('/api/auth/register'));
    await submit(page);
    const body = await (await resp).json();
    expect(body.code).toBe('EMAIL_TAKEN');
    expect(body.errors.map((e: { field: string }) => e.field)).toEqual(['email', 'displayName']);
    await expect(page.locator('#register-email-error')).toHaveText(EMAIL_TAKEN);
    await expect(page.locator('#register-display-name-error')).toHaveText(TAKEN);
    await expect(page).toHaveURL(/\/register$/);
    await page.screenshot({ path: `${SHOTS}/ac14-both-taken.png`, fullPage: true });

    // editing the name clears the name error (email error is left as is)
    await page.locator('#register-display-name').fill(`ZOE BOTH ${S} x`);
    await expect(page.locator('#register-display-name-error')).toHaveCount(0);
    await expect(page.locator('#register-display-name')).not.toHaveAttribute('aria-invalid', 'true');
    console.log('AC14 email error after editing name:', await page.locator('#register-email-error').count());
    await page.screenshot({ path: `${SHOTS}/ac14-both-after-name-edit.png`, fullPage: true });
    expect(appErrors(errors)).toEqual([]);
  });

  test('editing the name clears its error; a different name succeeds', async ({ page, browser }) => {
    const owner = `Renée ${S}`;
    await registerOwner(browser, owner, uniqEmail('ren-owner'));

    const errors = watch(page);
    const email = uniqEmail('ren');
    await page.goto('/register');
    await fillRegister(page, `renee ${S}`, email);
    await submit(page);
    await expect(page.locator('#register-display-name-error')).toHaveText(TAKEN);

    // typing one character clears the server error
    await page.locator('#register-display-name').press('End');
    await page.locator('#register-display-name').pressSequentially('2');
    await expect(page.locator('#register-display-name-error')).toHaveCount(0);
    await page.screenshot({ path: `${SHOTS}/ac14-error-cleared-on-edit.png`, fullPage: true });

    // resubmitting the same taken name brings it back
    await page.locator('#register-display-name').fill(`RENEE ${S}`);
    await submit(page);
    await expect(page.locator('#register-display-name-error')).toHaveText(TAKEN);

    const different = `Renée Two ${S}`;
    await page.locator('#register-display-name').fill(different);
    await submit(page);
    await expect(page).toHaveURL(/\/tasks$/);
    await expect(page.getByText(`Hi, ${different}`)).toBeVisible();
    await page.screenshot({ path: `${SHOTS}/ac14-different-name-success.png`, fullPage: true });
    const me = await (await page.request.get('/api/auth/me')).json();
    expect(me).toMatchObject({ email, displayName: different });
    expect(appErrors(errors)).toEqual([]);
  });

  test('edge: double-click submit with a taken name sends one request; reload clears the form', async ({
    page,
    browser,
  }) => {
    const owner = `Dbl ${S}`;
    await registerOwner(browser, owner, uniqEmail('dbl-owner'));
    const errors = watch(page);
    let posts = 0;
    page.on('request', (r) => {
      if (r.method() === 'POST' && r.url().endsWith('/api/auth/register')) posts++;
    });
    await page.route('**/api/auth/register', async (route) => {
      await new Promise((r) => setTimeout(r, 500));
      await route.continue();
    });
    await page.goto('/register');
    await fillRegister(page, `dbl ${S}`, uniqEmail('dbl'));
    await page.getByRole('button', { name: 'Create account' }).dblclick();
    await expect(page.locator('#register-display-name-error')).toHaveText(TAKEN);
    expect(posts).toBe(1);
    await page.reload();
    await expect(page).toHaveURL(/\/register$/);
    await expect(page.locator('#register-display-name')).toHaveValue('');
    await expect(page.locator('#register-display-name-error')).toHaveCount(0);
    expect(appErrors(errors)).toEqual([]);
  });

  test('edge (API): decomposed accent "Jose\\u0301" also collides with "José"; 400 beats 409', async ({ page, browser }) => {
    const owner = `José NFD ${S}`;
    await registerOwner(browser, owner, uniqEmail('nfd-owner'));
    await page.request.get('/api/auth/me');
    const token = (await page.context().cookies()).find((c) => c.name === 'XSRF-TOKEN')!.value;
    const post = (data: object) =>
      page.request.post('/api/auth/register', { data, headers: { 'X-XSRF-TOKEN': token } });
    const nfd = await post({ displayName: `José NFD ${S}`, email: uniqEmail('nfd'), password: PASSWORD });
    expect(nfd.status()).toBe(409);
    expect(await nfd.json()).toMatchObject({
      type: 'about:blank',
      title: 'Conflict',
      status: 409,
      detail: TAKEN,
      instance: '/api/auth/register',
      code: 'DISPLAY_NAME_TAKEN',
      errors: [{ field: 'displayName', code: 'DISPLAY_NAME_TAKEN', message: TAKEN }],
    });
    const bad = await post({ displayName: owner, email: uniqEmail('nfd400'), password: 'short' });
    expect(bad.status()).toBe(400);
    expect((await bad.json()).code).toBe('VALIDATION_FAILED');
  });
});
