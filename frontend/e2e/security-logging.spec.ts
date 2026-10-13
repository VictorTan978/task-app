import { expect, Page, test } from '@playwright/test';
import { readFileSync } from 'node:fs';

/**
 * Security hardening, Section C (security event logging), AC11-AC12.
 *
 * Drives a full auth flow through the UI (register, logout, wrong password, unknown email,
 * login, logout). The UI part always runs. The log assertions only run when E2E_BACKEND_LOG
 * points at the log file of the backend the frontend proxies to, e.g.
 *   E2E_BACKEND_LOG=/path/to/backend.log npx playwright test e2e/security-logging.spec.ts
 */

const RUN = `${Date.now()}-${Math.floor(Math.random() * 1e6)}`;
const SHOTS = '../test-results/security-logging';
const PASSWORD = `LogPw-${RUN}`;
const LOG = process.env['E2E_BACKEND_LOG'];

function watch(page: Page) {
  const errors: string[] = [];
  page.on('console', (m) => {
    if (m.type() === 'error' && !/^Failed to load resource: the server responded with a status of/.test(m.text())) {
      errors.push(m.text());
    }
  });
  page.on('pageerror', (e) => errors.push(e.message));
  return errors;
}

async function login(page: Page, email: string, password: string) {
  await page.goto('/login');
  await page.locator('#login-email').fill(email);
  await page.locator('#login-password').fill(password);
  await page.getByRole('button', { name: 'Sign in' }).click();
}

test('AC11/AC12: auth flow writes one SECURITY line per event and no secrets', async ({ page, context }) => {
  const errors = watch(page);
  const email = `qa-log-${RUN}@example.com`;
  const unknown = `qa-log-nobody-${RUN}@example.com`;

  await page.goto('/register');
  await page.locator('#register-display-name').fill(`Log QA ${RUN}`);
  await page.locator('#register-email').fill(email);
  await page.locator('#register-password').fill(PASSWORD);
  await page.locator('#register-confirm-password').fill(PASSWORD);
  await page.getByRole('button', { name: 'Create account' }).click();
  await expect(page).toHaveURL(/\/tasks$/);
  const sessionCookies = (await context.cookies()).filter((c) => ['JSESSIONID', 'XSRF-TOKEN'].includes(c.name));

  await page.getByRole('button', { name: 'Log out' }).click();
  await expect(page).toHaveURL(/\/login/);

  await login(page, email, `Wrong-${RUN}`);
  await expect(page.getByText('Invalid email or password')).toBeVisible();
  await login(page, unknown, `Wrong-${RUN}`);
  await expect(page.getByText('Invalid email or password')).toBeVisible();
  await page.screenshot({ path: `${SHOTS}/login-failure.png`, fullPage: true });

  await login(page, email, PASSWORD);
  await expect(page).toHaveURL(/\/tasks$/);
  sessionCookies.push(...(await context.cookies()).filter((c) => ['JSESSIONID', 'XSRF-TOKEN'].includes(c.name)));
  await page.getByRole('button', { name: 'Log out' }).click();
  await expect(page).toHaveURL(/\/login/);

  expect(errors).toEqual([]);

  test.skip(!LOG, 'E2E_BACKEND_LOG not set; log assertions skipped');
  await page.waitForTimeout(500);
  const log = readFileSync(LOG!, 'utf8');
  const mine = log
    .split('\n')
    .filter((l) => l.includes('SECURITY event=') && (l.includes(`email=${email} `) || l.includes(`email=${unknown} `)))
    .map((l) => l.slice(l.indexOf('SECURITY event=')).replace(/ ip=\S+/, ''));
  expect(mine).toEqual([
    `SECURITY event=REGISTER_SUCCESS email=${email} path=/api/auth/register`,
    `SECURITY event=LOGOUT email=${email} path=/api/auth/logout`,
    `SECURITY event=LOGIN_FAILURE reason=bad_password email=${email} path=/api/auth/login`,
    `SECURITY event=LOGIN_FAILURE reason=unknown_email email=${unknown} path=/api/auth/login`,
    `SECURITY event=LOGIN_SUCCESS email=${email} path=/api/auth/login`,
    `SECURITY event=LOGOUT email=${email} path=/api/auth/logout`,
  ]);
  expect(log).not.toContain(PASSWORD);
  expect(log).not.toContain(`Wrong-${RUN}`);
  expect(log).not.toMatch(/\$2[aby]\$/);
  expect(sessionCookies.length).toBeGreaterThan(0);
  for (const c of sessionCookies) expect(log).not.toContain(c.value);
});
