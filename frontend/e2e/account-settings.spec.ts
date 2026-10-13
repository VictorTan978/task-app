import { APIRequestContext, BrowserContext, expect, Page, Response, test } from '@playwright/test';
import * as fs from 'fs';

/**
 * Account settings (specs/account-settings.md, AC1–AC17 + constraints).
 * Needs the backend with the e2e IP rate-limit overrides; the per-user password-check
 * limit stays at its default (5 / 15 min), every test uses fresh users.
 * Optional: BACKEND_LOG=<path to backend console log> enables the AC14 audit-log checks.
 */

const RUN = `${Date.now()}-${Math.floor(Math.random() * 1e6)}`;
const SHOTS = '../test-results/account-settings';
const PASSWORD = 'password123';
const NEW_PASSWORD = 'n3w-passw0rd!';
const GENERIC = 'Something went wrong, please try again';
const RATE_LIMITED = 'Too many attempts, please try again later';
const SESSION_EXPIRED = 'Your session expired, please sign in again';

let n = 0;
function uid(): string {
  n += 1;
  return `${RUN}-${n}`;
}
function email(tag: string): string {
  return `qa-acct-${tag}-${uid()}@example.com`;
}

function watch(page: Page) {
  const consoleErrors: string[] = [];
  const pageErrors: string[] = [];
  const serverErrors: string[] = [];
  const bodies: string[] = [];
  page.on('console', (m) => {
    if (m.type() === 'error') consoleErrors.push(m.text());
  });
  page.on('pageerror', (e) => pageErrors.push(e.message));
  page.on('response', async (r: Response) => {
    if (!r.url().includes('/api/')) return;
    if (r.status() >= 500) serverErrors.push(`${r.status()} ${r.request().method()} ${r.url()}`);
    try {
      bodies.push(await r.text());
    } catch {
      /* body not available */
    }
  });
  return { consoleErrors, pageErrors, serverErrors, bodies };
}
type Watch = ReturnType<typeof watch>;

function appErrors(errors: string[]): string[] {
  return errors.filter((e) => !/^Failed to load resource: the server responded with a status of/.test(e));
}
function assertClean(w: Watch, label: string) {
  console.log(`${label} console errors:`, JSON.stringify(w.consoleErrors));
  expect(appErrors(w.consoleErrors)).toEqual([]);
  expect(w.pageErrors).toEqual([]);
  expect(w.serverErrors).toEqual([]);
}

async function register(page: Page, name: string, mail: string, password = PASSWORD) {
  await page.goto('/register');
  await page.locator('#register-display-name').fill(name);
  await page.locator('#register-email').fill(mail);
  await page.locator('#register-password').fill(password);
  await page.locator('#register-confirm-password').fill(password);
  await page.getByRole('button', { name: 'Create account' }).click();
  await expect(page).toHaveURL(/\/tasks$/);
}

async function login(page: Page, mail: string, password = PASSWORD) {
  await page.locator('#login-email').fill(mail);
  await page.locator('#login-password').fill(password);
  await page.getByRole('button', { name: 'Sign in' }).click();
}

async function logout(page: Page) {
  await page.getByRole('button', { name: 'Log out' }).click();
  await expect(page).toHaveURL(/\/login/);
}

async function gotoAccount(page: Page) {
  await page.goto('/account');
  await expect(page.getByRole('heading', { name: 'Account', exact: true })).toBeVisible();
}

async function addTask(page: Page, title: string) {
  await page.getByLabel('New task').fill(title);
  await page.getByRole('button', { name: 'Add', exact: true }).click();
  await expect(page.getByRole('checkbox', { name: `Done: ${title}`, exact: true })).toBeVisible();
}

function header(page: Page) {
  return page.locator('header');
}

async function xsrf(ctx: BrowserContext): Promise<string> {
  const c = (await ctx.cookies()).find((x) => x.name === 'XSRF-TOKEN');
  return c?.value ?? '';
}

async function apiCall(
  req: APIRequestContext,
  ctx: BrowserContext,
  method: 'PUT' | 'POST',
  path: string,
  data: unknown,
  csrf = true,
) {
  const headers: Record<string, string> = {};
  if (csrf) headers['X-XSRF-TOKEN'] = await xsrf(ctx);
  return req.fetch(path, { method, data, headers });
}

// ---------------------------------------------------------------- Account page

test.describe('Account page', () => {
  test('AC1: header name links to /account showing email and three sections', async ({ page }) => {
    const w = watch(page);
    const name = `Acct ${uid()}`;
    const mail = email('ac1');
    await register(page, name, mail);
    const link = header(page).getByRole('link', { name: `Hi, ${name}` });
    await expect(link).toBeVisible();
    await link.click();
    await expect(page).toHaveURL(/\/account$/);
    await expect(page).toHaveTitle('Account · Task App');
    const emailField = page.locator('#account-email');
    await expect(emailField).toHaveValue(mail);
    await expect(emailField).toHaveAttribute('readonly', '');
    for (const h of ['Display name', 'Change password', 'Delete account']) {
      await expect(page.getByRole('heading', { name: h, exact: true })).toBeVisible();
    }
    await expect(page.locator('#account-display-name')).toHaveValue(name);
    await page.screenshot({ path: `${SHOTS}/ac1-account-page.png`, fullPage: true });
    assertClean(w, 'AC1');
  });

  test('AC1 edge: refreshing /account keeps the page and the field values', async ({ page }) => {
    const w = watch(page);
    const name = `Refresh ${uid()}`;
    const mail = email('ac1r');
    await register(page, name, mail);
    await gotoAccount(page);
    await page.reload();
    await expect(page).toHaveURL(/\/account$/);
    await expect(page.locator('#account-email')).toHaveValue(mail);
    await expect(page.locator('#account-display-name')).toHaveValue(name);
    assertClean(w, 'AC1 refresh');
  });

  test('AC2: signed out, /account redirects to /login', async ({ page }) => {
    const w = watch(page);
    await page.goto('/account');
    await expect(page).toHaveURL(/\/login/);
    await expect(page.getByRole('heading', { name: 'Sign in' })).toBeVisible();
    await page.screenshot({ path: `${SHOTS}/ac2-redirect-login.png`, fullPage: true });
    assertClean(w, 'AC2');
  });
});

// ---------------------------------------------------------------- Display name

test.describe('Change display name', () => {
  test('AC3: save new name updates header without reload and persists', async ({ page }) => {
    const w = watch(page);
    const name = `Old ${uid()}`;
    const newName = `New ${uid()}`;
    await register(page, name, email('ac3'));
    await gotoAccount(page);
    await page.evaluate(() => ((window as unknown as { __noReload: boolean }).__noReload = true));
    await page.locator('#account-display-name').fill(`  ${newName}  `);
    await page.getByRole('button', { name: 'Save' }).click();
    await expect(page.getByText('Display name updated')).toBeVisible();
    await expect(header(page).getByRole('link', { name: `Hi, ${newName}` })).toBeVisible();
    expect(await page.evaluate(() => (window as unknown as { __noReload?: boolean }).__noReload)).toBe(true);
    await expect(page.locator('#account-display-name')).toHaveValue(newName);
    await page.screenshot({ path: `${SHOTS}/ac3-name-updated.png`, fullPage: true });
    await page.reload();
    await expect(header(page).getByRole('link', { name: `Hi, ${newName}` })).toBeVisible();
    await expect(page.locator('#account-display-name')).toHaveValue(newName);
    assertClean(w, 'AC3');
  });

  test('AC3 edge: exactly 50 characters after trimming is accepted', async ({ page }) => {
    const w = watch(page);
    await register(page, `Fifty ${uid()}`, email('ac3b'));
    await gotoAccount(page);
    const id = uid().replace(/-/g, '');
    const fifty = (id + 'x'.repeat(50)).slice(0, 50);
    await page.locator('#account-display-name').fill(`   ${fifty}   `);
    await page.getByRole('button', { name: 'Save' }).click();
    await expect(page.getByText('Display name updated')).toBeVisible();
    await expect(header(page).getByRole('link', { name: `Hi, ${fifty}` })).toBeVisible();
    assertClean(w, 'AC3 50');
  });

  test('AC4: name taken by another user (case/accents/spaces) is rejected', async ({ browser }) => {
    const id = uid();
    const ctxA = await browser.newContext();
    const pageA = await ctxA.newPage();
    await register(pageA, `José ${id}`, email('ac4a'));
    await ctxA.close();

    const ctx = await browser.newContext();
    const page = await ctx.newPage();
    const w = watch(page);
    const mine = `Mine ${uid()}`;
    await register(page, mine, email('ac4b'));
    await gotoAccount(page);
    for (const attempt of [`jose ${id}`, `JOSE ${id}`, ` José ${id} `]) {
      await page.locator('#account-display-name').fill(attempt);
      await page.getByRole('button', { name: 'Save' }).click();
      await expect(page.locator('#account-display-name-error')).toHaveText('This display name is already taken');
      await expect(page.getByText('Display name updated')).toHaveCount(0);
      await expect(header(page).getByRole('link', { name: `Hi, ${mine}` })).toBeVisible();
    }
    await page.screenshot({ path: `${SHOTS}/ac4-name-taken.png`, fullPage: true });
    await page.reload();
    await expect(header(page).getByRole('link', { name: `Hi, ${mine}` })).toBeVisible();
    assertClean(w, 'AC4');
    await ctx.close();
  });

  test('AC5: changing only case/accents of my own name is allowed', async ({ page }) => {
    const w = watch(page);
    const id = uid();
    await register(page, `Bob ${id}`, email('ac5'));
    await gotoAccount(page);
    await page.locator('#account-display-name').fill(`bob ${id}`);
    await page.getByRole('button', { name: 'Save' }).click();
    await expect(page.getByText('Display name updated')).toBeVisible();
    await expect(header(page).getByRole('link', { name: `Hi, bob ${id}` })).toBeVisible();
    await page.locator('#account-display-name').fill(`Böb ${id}`);
    await page.getByRole('button', { name: 'Save' }).click();
    await expect(header(page).getByRole('link', { name: `Hi, Böb ${id}` })).toBeVisible();
    await expect(page.locator('#account-display-name-error')).toHaveCount(0);
    // saving the exact same name again
    await page.getByRole('button', { name: 'Save' }).click();
    await expect(page.getByText('Display name updated')).toBeVisible();
    await page.reload();
    await expect(header(page).getByRole('link', { name: `Hi, Böb ${id}` })).toBeVisible();
    await page.screenshot({ path: `${SHOTS}/ac5-own-name-case.png`, fullPage: true });
    assertClean(w, 'AC5');
  });

  test('AC6: empty, whitespace-only and >50 chars show an error and change nothing', async ({ page }) => {
    const w = watch(page);
    const name = `Valid ${uid()}`;
    await register(page, name, email('ac6'));
    await gotoAccount(page);
    let puts = 0;
    page.on('request', (r) => {
      if (r.url().includes('/api/account/display-name')) puts++;
    });
    const msgs: string[] = [];
    for (const bad of ['', '    ', 'x'.repeat(51)]) {
      await page.locator('#account-display-name').fill(bad);
      await page.getByRole('button', { name: 'Save' }).click();
      const err = page.locator('#account-display-name-error');
      await expect(err).toBeVisible();
      msgs.push((await err.textContent())?.trim() ?? '');
      await expect(header(page).getByRole('link', { name: `Hi, ${name}` })).toBeVisible();
    }
    console.log('AC6 messages:', JSON.stringify(msgs), 'requests sent:', puts);
    await page.screenshot({ path: `${SHOTS}/ac6-name-invalid.png`, fullPage: true });
    await page.reload();
    await expect(page.locator('#account-display-name')).toHaveValue(name);
    assertClean(w, 'AC6');
  });

  test('AC6 edge: double-clicking Save sends one request', async ({ page }) => {
    const w = watch(page);
    await register(page, `Dbl ${uid()}`, email('ac6d'));
    await gotoAccount(page);
    let puts = 0;
    page.on('request', (r) => {
      if (r.url().includes('/api/account/display-name')) puts++;
    });
    await page.route('**/api/account/display-name', async (r) => {
      await new Promise((res) => setTimeout(res, 500));
      await r.continue();
    });
    const newName = `Dbl2 ${uid()}`;
    await page.locator('#account-display-name').fill(newName);
    await page.getByRole('button', { name: 'Save' }).dblclick();
    await expect(page.getByText('Display name updated')).toBeVisible();
    expect(puts).toBe(1);
    assertClean(w, 'AC6 dbl');
  });
});

// ---------------------------------------------------------------- Password

test.describe('Change password', () => {
  test('AC7: change password, stay signed in; old fails, new works', async ({ page }) => {
    const w = watch(page);
    const mail = email('ac7');
    await register(page, `Pw ${uid()}`, mail);
    await gotoAccount(page);
    await page.locator('#account-current-password').fill(PASSWORD);
    await page.locator('#account-new-password').fill(NEW_PASSWORD);
    await page.locator('#account-confirm-password').fill(NEW_PASSWORD);
    await page.getByRole('button', { name: 'Change password' }).click();
    await expect(page.getByText('Password changed')).toBeVisible();
    await expect(page.locator('#account-current-password')).toHaveValue('');
    await expect(page.locator('#account-new-password')).toHaveValue('');
    await expect(page).toHaveURL(/\/account$/);
    await page.screenshot({ path: `${SHOTS}/ac7-password-changed.png`, fullPage: true });
    await page.reload();
    await expect(page).toHaveURL(/\/account$/);
    await logout(page);
    await login(page, mail, PASSWORD);
    await expect(page.getByTestId('form-error')).toHaveText('Invalid email or password');
    await login(page, mail, NEW_PASSWORD);
    await expect(page).toHaveURL(/\/tasks$/);
    // no password in any API response
    for (const b of w.bodies) {
      expect(b).not.toContain(PASSWORD);
      expect(b).not.toContain(NEW_PASSWORD);
    }
    assertClean(w, 'AC7');
  });

  test('AC8: wrong current password shows error and changes nothing', async ({ page }) => {
    const w = watch(page);
    const mail = email('ac8');
    await register(page, `Wrong ${uid()}`, mail);
    await gotoAccount(page);
    await page.locator('#account-current-password').fill('not-my-password');
    await page.locator('#account-new-password').fill(NEW_PASSWORD);
    await page.locator('#account-confirm-password').fill(NEW_PASSWORD);
    await page.getByRole('button', { name: 'Change password' }).click();
    await expect(page.locator('#account-current-password-error')).toHaveText('Current password is incorrect');
    await expect(page.getByText('Password changed')).toHaveCount(0);
    await expect(page).toHaveURL(/\/account$/);
    await page.screenshot({ path: `${SHOTS}/ac8-wrong-current.png`, fullPage: true });
    await logout(page);
    await login(page, mail, NEW_PASSWORD);
    await expect(page.getByTestId('form-error')).toHaveText('Invalid email or password');
    await login(page, mail, PASSWORD);
    await expect(page).toHaveURL(/\/tasks$/);
    assertClean(w, 'AC8');
  });

  test('AC9: new password <8 or >72 shows error under new password', async ({ page }) => {
    const w = watch(page);
    const mail = email('ac9');
    await register(page, `Len ${uid()}`, mail);
    await gotoAccount(page);
    const msgs: string[] = [];
    for (const bad of ['short7!', 'a'.repeat(73)]) {
      await page.locator('#account-current-password').fill(PASSWORD);
      await page.locator('#account-new-password').fill(bad);
      await page.locator('#account-confirm-password').fill(bad);
      await page.getByRole('button', { name: 'Change password' }).click();
      const err = page.locator('#account-new-password-error');
      await expect(err).toBeVisible();
      msgs.push((await err.textContent())?.trim() ?? '');
      await expect(page.getByText('Password changed')).toHaveCount(0);
    }
    // 37 x 'é' = 37 chars but 74 UTF-8 bytes (constraint: max 72 bytes)
    await page.locator('#account-new-password').fill('é'.repeat(37));
    await page.locator('#account-confirm-password').fill('é'.repeat(37));
    await page.getByRole('button', { name: 'Change password' }).click();
    await expect(page.locator('#account-new-password-error')).toBeVisible();
    msgs.push((await page.locator('#account-new-password-error').textContent())?.trim() ?? '');
    console.log('AC9 messages:', JSON.stringify(msgs));
    await page.screenshot({ path: `${SHOTS}/ac9-new-password-invalid.png`, fullPage: true });
    await logout(page);
    await login(page, mail, PASSWORD);
    await expect(page).toHaveURL(/\/tasks$/);
    assertClean(w, 'AC9');
  });
});

// ---------------------------------------------------------------- Delete

test.describe('Delete account', () => {
  test('AC10: dialog explains and asks for password; Cancel changes nothing', async ({ page }) => {
    const w = watch(page);
    const mail = email('ac10');
    const name = `Cancel ${uid()}`;
    await register(page, name, mail);
    await gotoAccount(page);
    await page.getByRole('button', { name: 'Delete account' }).click();
    const dialog = page.getByRole('dialog');
    await expect(dialog).toBeVisible();
    await expect(dialog).toContainText('Your account and all your tasks will be permanently deleted');
    await expect(dialog.locator('#delete-account-password')).toBeVisible();
    await expect(dialog.getByRole('button', { name: 'Delete permanently' })).toBeVisible();
    await page.screenshot({ path: `${SHOTS}/ac10-delete-dialog.png`, fullPage: true });
    await dialog.getByRole('button', { name: 'Cancel' }).click();
    await expect(page.getByRole('dialog')).toHaveCount(0);
    await expect(page).toHaveURL(/\/account$/);
    // Escape also closes
    await page.getByRole('button', { name: 'Delete account' }).click();
    await expect(page.getByRole('dialog')).toBeVisible();
    await page.keyboard.press('Escape');
    await expect(page.getByRole('dialog')).toHaveCount(0);
    await page.reload();
    await expect(header(page).getByRole('link', { name: `Hi, ${name}` })).toBeVisible();
    assertClean(w, 'AC10');
  });

  test('AC11: delete with correct password signs out; creds fail; can re-register', async ({ page }) => {
    const w = watch(page);
    const mail = email('ac11');
    const name = `Gone ${uid()}`;
    await register(page, name, mail);
    await addTask(page, 'Task to be deleted');
    await gotoAccount(page);
    await page.getByRole('button', { name: 'Delete account' }).click();
    await page.locator('#delete-account-password').fill(PASSWORD);
    await page.getByRole('button', { name: 'Delete permanently' }).click();
    await expect(page).toHaveURL(/\/login/);
    await expect(page.getByTestId('account-deleted')).toHaveText('Your account has been deleted');
    await expect(page.getByText(SESSION_EXPIRED)).toHaveCount(0);
    await page.screenshot({ path: `${SHOTS}/ac11-account-deleted-login.png`, fullPage: true });
    // back to protected page is not possible
    await page.goto('/tasks');
    await expect(page).toHaveURL(/\/login/);
    await login(page, mail, PASSWORD);
    await expect(page.getByTestId('form-error')).toHaveText('Invalid email or password');
    await register(page, name, mail);
    await expect(header(page).getByRole('link', { name: `Hi, ${name}` })).toBeVisible();
    await expect(page.getByText('No tasks yet — add your first one above')).toBeVisible();
    await page.screenshot({ path: `${SHOTS}/ac11-reregistered-empty.png`, fullPage: true });
    for (const b of w.bodies) expect(b).not.toContain(PASSWORD);
    assertClean(w, 'AC11');
  });

  test('AC12: wrong password in dialog shows error and deletes nothing', async ({ page }) => {
    const w = watch(page);
    const mail = email('ac12');
    const name = `Stay ${uid()}`;
    await register(page, name, mail);
    await gotoAccount(page);
    await page.getByRole('button', { name: 'Delete account' }).click();
    await page.locator('#delete-account-password').fill('wrong-password');
    await page.getByRole('button', { name: 'Delete permanently' }).click();
    const dialog = page.getByRole('dialog');
    await expect(dialog.locator('#delete-account-password-error')).toHaveText('Password is incorrect');
    await expect(page).toHaveURL(/\/account$/);
    await page.screenshot({ path: `${SHOTS}/ac12-delete-wrong-password.png`, fullPage: true });
    await dialog.getByRole('button', { name: 'Cancel' }).click();
    await expect(page.locator('dialog[open]')).toHaveCount(0);
    await page.reload();
    await expect(header(page).getByRole('link', { name: `Hi, ${name}` })).toBeVisible();
    await logout(page);
    await login(page, mail, PASSWORD);
    await expect(page).toHaveURL(/\/tasks$/);
    assertClean(w, 'AC12');
  });

  test('AC13: deleting user A leaves user B and their tasks intact', async ({ browser }) => {
    const ctxB = await browser.newContext();
    const pageB = await ctxB.newPage();
    const wB = watch(pageB);
    const nameB = `KeepB ${uid()}`;
    await register(pageB, nameB, email('ac13b'));
    await addTask(pageB, 'B keeps this');

    const ctxA = await browser.newContext();
    const pageA = await ctxA.newPage();
    const wA = watch(pageA);
    await register(pageA, `DelA ${uid()}`, email('ac13a'));
    await addTask(pageA, 'A loses this');
    await gotoAccount(pageA);
    await pageA.getByRole('button', { name: 'Delete account' }).click();
    await pageA.locator('#delete-account-password').fill(PASSWORD);
    await pageA.getByRole('button', { name: 'Delete permanently' }).click();
    await expect(pageA.getByTestId('account-deleted')).toBeVisible();

    await pageB.reload();
    await expect(header(pageB).getByRole('link', { name: `Hi, ${nameB}` })).toBeVisible();
    await expect(pageB.getByRole('checkbox', { name: 'Done: B keeps this', exact: true })).toBeVisible();
    await addTask(pageB, 'B adds after');
    await pageB.screenshot({ path: `${SHOTS}/ac13-user-b-intact.png`, fullPage: true });
    assertClean(wA, 'AC13 A');
    assertClean(wB, 'AC13 B');
    await ctxA.close();
    await ctxB.close();
  });

  test('Constraint: other session of a deleted account goes to login (session expired), no 500', async ({
    browser,
  }) => {
    const mail = email('two');
    const ctx1 = await browser.newContext();
    const p1 = await ctx1.newPage();
    const w1 = watch(p1);
    await register(p1, `Two ${uid()}`, mail);
    await addTask(p1, 'Shared task');

    const ctx2 = await browser.newContext();
    const p2 = await ctx2.newPage();
    const w2 = watch(p2);
    await p2.goto('/login');
    await login(p2, mail);
    await expect(p2).toHaveURL(/\/tasks$/);
    await expect(p2.getByRole('checkbox', { name: 'Done: Shared task', exact: true })).toBeVisible();

    await gotoAccount(p1);
    await p1.getByRole('button', { name: 'Delete account' }).click();
    await p1.locator('#delete-account-password').fill(PASSWORD);
    await p1.getByRole('button', { name: 'Delete permanently' }).click();
    await expect(p1.getByTestId('account-deleted')).toBeVisible();

    // next action in the other context: add a task
    const statuses: number[] = [];
    p2.on('response', (r) => {
      if (r.url().includes('/api/tasks')) statuses.push(r.status());
    });
    await p2.getByLabel('New task').fill('Orphan attempt');
    await p2.getByRole('button', { name: 'Add', exact: true }).click();
    await expect(p2).toHaveURL(/\/login/);
    await expect(p2.getByText(SESSION_EXPIRED)).toBeVisible();
    await p2.screenshot({ path: `${SHOTS}/two-contexts-session-expired.png`, fullPage: true });
    console.log('two-context /api/tasks statuses:', JSON.stringify(statuses));
    expect(statuses).toContain(401);
    expect(statuses.filter((s) => s >= 500)).toEqual([]);
    assertClean(w1, 'two ctx1');
    assertClean(w2, 'two ctx2');
    await ctx1.close();
    await ctx2.close();
  });

  test('Constraint: rename in one session shows the new name in the other on next load', async ({ browser }) => {
    const mail = email('ren');
    const ctx1 = await browser.newContext();
    const p1 = await ctx1.newPage();
    await register(p1, `Ren ${uid()}`, mail);
    const ctx2 = await browser.newContext();
    const p2 = await ctx2.newPage();
    const w2 = watch(p2);
    await p2.goto('/login');
    await login(p2, mail);
    await expect(p2).toHaveURL(/\/tasks$/);
    const newName = `Renamed ${uid()}`;
    await gotoAccount(p1);
    await p1.locator('#account-display-name').fill(newName);
    await p1.getByRole('button', { name: 'Save' }).click();
    await expect(p1.getByText('Display name updated')).toBeVisible();
    await p2.reload();
    await expect(header(p2).getByRole('link', { name: `Hi, ${newName}` })).toBeVisible();
    assertClean(w2, 'rename ctx2');
    await ctx1.close();
    await ctx2.close();
  });
});

// ---------------------------------------------------------------- Security

test.describe('Security', () => {
  test('AC14: audit events in security.audit log, no passwords logged', async ({ page }) => {
    const logPath = process.env['BACKEND_LOG'];
    test.skip(!logPath, 'BACKEND_LOG not set');
    const mail = email('ac14');
    await register(page, `Audit ${uid()}`, mail);
    await gotoAccount(page);
    await page.locator('#account-display-name').fill(`Audit2 ${uid()}`);
    await page.getByRole('button', { name: 'Save' }).click();
    await expect(page.getByText('Display name updated')).toBeVisible();
    await page.locator('#account-current-password').fill('audit-wrong-pw');
    await page.locator('#account-new-password').fill(NEW_PASSWORD);
    await page.locator('#account-confirm-password').fill(NEW_PASSWORD);
    await page.getByRole('button', { name: 'Change password' }).click();
    await expect(page.locator('#account-current-password-error')).toBeVisible();
    await page.locator('#account-current-password').fill(PASSWORD);
    await page.locator('#account-new-password').fill(NEW_PASSWORD);
    await page.locator('#account-confirm-password').fill(NEW_PASSWORD);
    await page.getByRole('button', { name: 'Change password' }).click();
    await expect(page.getByText('Password changed')).toBeVisible();
    await page.getByRole('button', { name: 'Delete account' }).click();
    await page.locator('#delete-account-password').fill(NEW_PASSWORD);
    await page.getByRole('button', { name: 'Delete permanently' }).click();
    await expect(page.getByTestId('account-deleted')).toBeVisible();
    await page.waitForTimeout(500);
    const lines = fs
      .readFileSync(logPath!, 'utf8')
      .split('\n')
      .filter((l) => l.includes('security.audit') && l.includes(mail));
    console.log('AC14 audit lines:\n' + lines.join('\n'));
    const events = lines.map((l) => /event=(\S+)/.exec(l)?.[1]);
    expect(events).toEqual(
      expect.arrayContaining(['DISPLAY_NAME_CHANGED', 'PASSWORD_CHECK_FAILURE', 'PASSWORD_CHANGED', 'ACCOUNT_DELETED']),
    );
    const whole = fs.readFileSync(logPath!, 'utf8');
    for (const pw of [PASSWORD, NEW_PASSWORD, 'audit-wrong-pw']) expect(whole).not.toContain(pw);
  });

  test('AC15: 5 wrong passwords across both endpoints, then 429 with the account-page text', async ({ page }) => {
    const w = watch(page);
    const mail = email('ac15');
    await register(page, `Limit ${uid()}`, mail);
    await gotoAccount(page);
    // 3 wrong on change password
    for (let i = 0; i < 3; i++) {
      await page.locator('#account-current-password').fill(`wrong-${i}-pw`);
      await page.locator('#account-new-password').fill(NEW_PASSWORD);
      await page.locator('#account-confirm-password').fill(NEW_PASSWORD);
      await page.getByRole('button', { name: 'Change password' }).click();
      await expect(page.locator('#account-current-password-error')).toHaveText('Current password is incorrect');
    }
    // 2 wrong on delete
    await page.getByRole('button', { name: 'Delete account' }).click();
    for (let i = 0; i < 2; i++) {
      await page.locator('#delete-account-password').fill(`wrong-del-${i}`);
      await page.getByRole('button', { name: 'Delete permanently' }).click();
      await expect(page.locator('#delete-account-password-error')).toHaveText('Password is incorrect');
    }
    // 6th: correct password on delete -> limited, not deleted
    await page.locator('#delete-account-password').fill(PASSWORD);
    const resp = page.waitForResponse('**/api/account/delete');
    await page.getByRole('button', { name: 'Delete permanently' }).click();
    const r = await resp;
    expect(r.status()).toBe(429);
    expect(Number(r.headers()['retry-after'])).toBeGreaterThanOrEqual(1);
    await expect(page.getByRole('dialog')).toContainText(RATE_LIMITED);
    await page.screenshot({ path: `${SHOTS}/ac15-rate-limited-dialog.png`, fullPage: true });
    await page.getByRole('dialog').getByRole('button', { name: 'Cancel' }).click();
    await expect(page.locator('dialog[open]')).toHaveCount(0);
    // correct password on change password -> also limited
    await page.locator('#account-current-password').fill(PASSWORD);
    await page.locator('#account-new-password').fill(NEW_PASSWORD);
    await page.locator('#account-confirm-password').fill(NEW_PASSWORD);
    await page.getByRole('button', { name: 'Change password' }).click();
    await expect(page.getByText(RATE_LIMITED)).toBeVisible();
    await expect(page.getByText('Password changed')).toHaveCount(0);
    await page.screenshot({ path: `${SHOTS}/ac15-rate-limited-password.png`, fullPage: true });
    // still signed in, account exists, password unchanged
    await page.reload();
    await expect(page).toHaveURL(/\/account$/);
    await logout(page);
    await login(page, mail, PASSWORD);
    await expect(page).toHaveURL(/\/tasks$/);
    assertClean(w, 'AC15');
  });

  test('AC16: new endpoints return 401 signed out and 403 without CSRF', async ({ page, context }) => {
    const req = context.request;
    expect((await req.get('/api/auth/me')).status()).toBe(401); // sets the XSRF cookie
    expect(await xsrf(context)).not.toBe('');
    const calls: ['PUT' | 'POST', string, unknown][] = [
      ['PUT', '/api/account/display-name', { displayName: 'Nobody' }],
      ['PUT', '/api/account/password', { currentPassword: PASSWORD, newPassword: NEW_PASSWORD }],
      ['POST', '/api/account/delete', { password: PASSWORD }],
    ];
    for (const [m, p, d] of calls) {
      const r = await apiCall(req, context, m, p, d);
      expect(r.status(), `${m} ${p} signed out`).toBe(401);
      expect((await r.json()).code).toBe('UNAUTHENTICATED');
    }
    await register(page, `Csrf ${uid()}`, email('ac16'));
    for (const [m, p, d] of calls) {
      const r = await apiCall(req, context, m, p, d, false);
      expect(r.status(), `${m} ${p} no csrf`).toBe(403);
      expect((await r.json()).code).toBe('CSRF_INVALID');
    }
    // still signed in, nothing deleted
    const me = await req.get('/api/auth/me');
    expect(me.status()).toBe(200);
  });
});

// ---------------------------------------------------------------- States

test.describe('States', () => {
  test('AC17: buttons are disabled with a spinner while pending', async ({ page }) => {
    const w = watch(page);
    await register(page, `Busy ${uid()}`, email('ac17'));
    await gotoAccount(page);
    let release: () => void = () => undefined;
    const gate = new Promise<void>((res) => (release = res));
    await page.route('**/api/account/**', async (r) => {
      await gate;
      await r.continue();
    });

    await page.locator('#account-display-name').fill(`Busy2 ${uid()}`);
    const save = page.getByRole('button', { name: 'Save' });
    await save.click();
    await expect(save).toBeDisabled();
    await expect(save.getByTestId('spinner')).toBeVisible();
    await page.screenshot({ path: `${SHOTS}/ac17-saving.png`, fullPage: true });

    await page.locator('#account-current-password').fill('wrong-pw-busy');
    await page.locator('#account-new-password').fill(NEW_PASSWORD);
    await page.locator('#account-confirm-password').fill(NEW_PASSWORD);
    const change = page.getByRole('button', { name: 'Change password' });
    await change.click();
    await expect(change).toBeDisabled();
    await expect(change.getByTestId('spinner')).toBeVisible();

    await page.getByRole('button', { name: 'Delete account' }).click();
    await page.locator('#delete-account-password').fill('wrong-pw-busy');
    const del = page.getByRole('button', { name: 'Delete permanently' });
    await del.click();
    await expect(del).toBeDisabled();
    await expect(del.getByTestId('spinner')).toBeVisible();
    await page.screenshot({ path: `${SHOTS}/ac17-deleting.png`, fullPage: true });

    release();
    await expect(page.locator('#delete-account-password-error')).toHaveText('Password is incorrect');
    await expect(del).toBeEnabled();
    await page.getByRole('dialog').getByRole('button', { name: 'Cancel' }).click();
    await expect(page.getByText('Display name updated')).toBeVisible();
    await expect(page.locator('#account-current-password-error')).toBeVisible();
    await expect(save).toBeEnabled();
    await expect(change).toBeEnabled();
    assertClean(w, 'AC17 pending');
  });

  test('AC17: server unreachable shows the error banner in each section', async ({ page }) => {
    const w = watch(page);
    const name = `Down ${uid()}`;
    await register(page, name, email('ac17b'));
    await gotoAccount(page);
    await page.route('**/api/account/**', (r) => r.abort('connectionrefused'));

    await page.locator('#account-display-name').fill(`Down2 ${uid()}`);
    await page.getByRole('button', { name: 'Save' }).click();
    const nameSection = page.locator('section', { has: page.locator('#account-display-name') });
    await expect(nameSection.getByRole('alert')).toHaveText(GENERIC);
    await expect(page.getByRole('button', { name: 'Save' })).toBeEnabled();
    await expect(header(page).getByRole('link', { name: `Hi, ${name}` })).toBeVisible();

    await page.locator('#account-current-password').fill(PASSWORD);
    await page.locator('#account-new-password').fill(NEW_PASSWORD);
    await page.locator('#account-confirm-password').fill(NEW_PASSWORD);
    await page.getByRole('button', { name: 'Change password' }).click();
    const pwSection = page.locator('section', { has: page.locator('#account-new-password') });
    await expect(pwSection.getByRole('alert')).toHaveText(GENERIC);
    await page.screenshot({ path: `${SHOTS}/ac17-unreachable.png`, fullPage: true });

    await page.getByRole('button', { name: 'Delete account' }).click();
    await page.locator('#delete-account-password').fill(PASSWORD);
    await page.getByRole('button', { name: 'Delete permanently' }).click();
    await expect(page.getByRole('dialog').getByRole('alert')).toHaveText(GENERIC);
    await page.screenshot({ path: `${SHOTS}/ac17-unreachable-dialog.png`, fullPage: true });
    await expect(page).toHaveURL(/\/account$/);
    console.log('AC17 unreachable console errors:', JSON.stringify(w.consoleErrors));
    // aborted requests log net::ERR_* in the console; those are the browser's own network log
    expect(appErrors(w.consoleErrors).filter((e) => !/net::ERR_/.test(e))).toEqual([]);
    expect(w.pageErrors).toEqual([]);
  });

  test('AC17: server 500 shows the generic banner', async ({ page }) => {
    const w = watch(page);
    await register(page, `Five ${uid()}`, email('ac17c'));
    await gotoAccount(page);
    await page.route('**/api/account/display-name', (r) =>
      r.fulfill({
        status: 500,
        contentType: 'application/problem+json',
        body: JSON.stringify({ type: 'about:blank', title: 'Internal Server Error', status: 500, code: 'INTERNAL_ERROR' }),
      }),
    );
    await page.locator('#account-display-name').fill(`Five2 ${uid()}`);
    await page.getByRole('button', { name: 'Save' }).click();
    await expect(page.getByRole('alert')).toHaveText(GENERIC);
    expect(w.pageErrors).toEqual([]);
  });
});
