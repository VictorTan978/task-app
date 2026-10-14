import { expect, Page, test } from '@playwright/test';

/**
 * Security hardening, section B, AC10: with the dev profile over plain http://localhost,
 * register / logout / login and CSRF work as before, cookies are NOT Secure, and nothing redirects to HTTPS.
 * (AC7-AC9 need production settings and are verified with curl against a separately started backend.)
 */
const RUN = `${Date.now()}-${Math.floor(Math.random() * 1e6)}`;
const SHOTS = '../test-results/security-https';
const PASSWORD = 'password123';

function watch(page: Page) {
  const errors: string[] = [];
  page.on('console', (msg) => {
    if (msg.type() === 'error' && !/^Failed to load resource: the server responded with a status of 401/.test(msg.text())) {
      errors.push(msg.text());
    }
  });
  page.on('pageerror', (err) => errors.push(err.message));
  return errors;
}

test('AC10: dev over plain HTTP: register, logout, login, CSRF; cookies not Secure; no HTTPS redirect', async ({ page, context }) => {
  const errors = watch(page);
  const redirects: string[] = [];
  page.on('response', (r) => {
    if (r.status() >= 300 && r.status() < 400) redirects.push(`${r.status()} ${r.url()} -> ${r.headers()['location']}`);
  });
  const setCookies: string[] = [];
  page.on('response', async (r) => {
    if (r.url().includes('/api/')) {
      const sc = (await r.headersArray()).filter((h) => h.name.toLowerCase() === 'set-cookie').map((h) => h.value);
      setCookies.push(...sc);
    }
  });

  const email = `qa-sec-b-${RUN}@example.com`;
  await page.goto('/register');
  await page.locator('#register-display-name').fill(`SecB ${RUN}`);
  await page.locator('#register-email').fill(email);
  await page.locator('#register-password').fill(PASSWORD);
  await page.locator('#register-confirm-password').fill(PASSWORD);
  await page.getByRole('button', { name: 'Create account' }).click();
  await expect(page).toHaveURL(/\/tasks$/);
  await expect(page.getByRole('heading', { name: 'Your tasks' })).toBeVisible();

  let cookies = await context.cookies();
  const session = cookies.find((c) => c.name === 'JSESSIONID');
  const xsrf = cookies.find((c) => c.name === 'XSRF-TOKEN');
  expect(session, 'JSESSIONID set').toBeTruthy();
  expect(xsrf, 'XSRF-TOKEN set').toBeTruthy();
  expect(session!.secure).toBe(false);
  expect(xsrf!.secure).toBe(false);
  expect(session!.httpOnly).toBe(true);
  await page.screenshot({ path: `${SHOTS}/ac10-registered.png`, fullPage: true });

  // CSRF still enforced: a state-changing request without the header is rejected.
  const noCsrf = await context.request.post('/api/tasks', { data: { title: 'x' } });
  expect(noCsrf.status()).toBe(403);

  await page.getByRole('button', { name: 'Log out', exact: true }).click();
  await expect(page).toHaveURL(/\/login/);
  cookies = await context.cookies();
  expect(cookies.find((c) => c.name === 'JSESSIONID')).toBeFalsy();
  await page.screenshot({ path: `${SHOTS}/ac10-logged-out.png`, fullPage: true });

  await page.locator('#login-email').fill(email);
  await page.locator('#login-password').fill(PASSWORD);
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page).toHaveURL(/\/tasks$/);
  await expect(page.getByRole('heading', { name: 'Your tasks' })).toBeVisible();
  await page.screenshot({ path: `${SHOTS}/ac10-logged-in.png`, fullPage: true });

  expect(setCookies.length).toBeGreaterThan(0);
  for (const sc of setCookies) expect(sc, sc).not.toMatch(/;\s*Secure/i);
  expect(redirects, redirects.join('\n')).toEqual([]);
  expect(errors, errors.join('\n')).toEqual([]);
});
