import { expect, Locator, Page, Request, test } from '@playwright/test';

/**
 * Password fields (specs/account-settings.md, AC18–AC22): show/hide toggle on every
 * password input, confirm password on register and change password.
 * Needs the backend with the e2e IP rate-limit overrides.
 */

const RUN = `${Date.now()}-${Math.floor(Math.random() * 1e6)}`;
const SHOTS = '../test-results/account-settings';
const PASSWORD = 'password123';
const NEW_PASSWORD = 'n3w-passw0rd!';

let n = 0;
function uid(): string {
  n += 1;
  return `${RUN}-${n}`;
}
function email(tag: string): string {
  return `qa-pwf-${tag}-${uid()}@example.com`;
}

function watch(page: Page) {
  const consoleErrors: string[] = [];
  const pageErrors: string[] = [];
  page.on('console', (m) => {
    if (m.type() === 'error') consoleErrors.push(m.text());
  });
  page.on('pageerror', (e) => pageErrors.push(e.message));
  return { consoleErrors, pageErrors };
}
function assertClean(w: ReturnType<typeof watch>, label: string) {
  const app = w.consoleErrors.filter(
    (e) => !/^Failed to load resource: the server responded with a status of/.test(e),
  );
  console.log(`${label} console errors:`, JSON.stringify(w.consoleErrors));
  expect(app).toEqual([]);
  expect(w.pageErrors).toEqual([]);
}

/** Records every request to an API path (method + JSON body). */
function record(page: Page, path: string) {
  const reqs: { method: string; body: Record<string, unknown> | null }[] = [];
  page.on('request', (r: Request) => {
    if (!r.url().includes(path)) return;
    let body: Record<string, unknown> | null = null;
    try {
      body = r.postDataJSON() as Record<string, unknown>;
    } catch {
      body = null;
    }
    reqs.push({ method: r.method(), body });
  });
  return reqs;
}

function toggle(page: Page | Locator, inputId: string): Locator {
  return page.locator(`button[aria-controls="${inputId}"]`);
}

async function expectHidden(page: Page | Locator, inputId: string) {
  await expect(page.locator(`#${inputId}`)).toHaveAttribute('type', 'password');
  const t = toggle(page, inputId);
  await expect(t).toHaveAttribute('aria-label', 'Show password');
  await expect(t).toHaveAttribute('aria-pressed', 'false');
}
async function expectShown(page: Page | Locator, inputId: string) {
  await expect(page.locator(`#${inputId}`)).toHaveAttribute('type', 'text');
  const t = toggle(page, inputId);
  await expect(t).toHaveAttribute('aria-label', 'Hide password');
  await expect(t).toHaveAttribute('aria-pressed', 'true');
}

/** Full AC18 click cycle for one field: hidden -> shown (text visible) -> hidden. */
async function checkToggle(page: Page | Locator, inputId: string, value: string) {
  const input = page.locator(`#${inputId}`);
  const t = toggle(page, inputId);
  await expect(t).toHaveCount(1);
  await expect(t).toHaveAttribute('type', 'button');
  await expect(t).toHaveAttribute('data-testid', 'password-toggle');
  // toggle sits inside the input's box
  const ib = await input.boundingBox();
  const tb = await t.boundingBox();
  expect(ib && tb).toBeTruthy();
  if (ib && tb) {
    expect(tb.x).toBeGreaterThanOrEqual(ib.x - 1);
    expect(tb.x + tb.width).toBeLessThanOrEqual(ib.x + ib.width + 1);
    expect(tb.y).toBeGreaterThanOrEqual(ib.y - 1);
    expect(tb.y + tb.height).toBeLessThanOrEqual(ib.y + ib.height + 1);
  }
  await input.fill(value);
  await expectHidden(page, inputId);
  await t.click();
  await expectShown(page, inputId);
  await expect(input).toHaveValue(value);
  await t.click();
  await expectHidden(page, inputId);
  await expect(input).toHaveValue(value);
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

async function gotoAccount(page: Page) {
  await page.goto('/account');
  await expect(page.getByRole('heading', { name: 'Account', exact: true })).toBeVisible();
}

// ---------------------------------------------------------------- AC18

test.describe('AC18: show/hide toggle', () => {
  test('login and register fields toggle; shown and hidden screenshots', async ({ page }) => {
    const w = watch(page);
    await page.goto('/login');
    await checkToggle(page, 'login-password', 'secret-login-1');
    await page.screenshot({ path: `${SHOTS}/ac18-login-hidden.png`, fullPage: true });
    await toggle(page, 'login-password').click();
    await expectShown(page, 'login-password');
    await page.screenshot({ path: `${SHOTS}/ac18-login-shown.png`, fullPage: true });

    await page.goto('/register');
    await checkToggle(page, 'register-password', 'secret-reg-1');
    await checkToggle(page, 'register-confirm-password', 'secret-reg-2');
    await toggle(page, 'register-password').click();
    await expectShown(page, 'register-password');
    await expectHidden(page, 'register-confirm-password');
    await page.screenshot({ path: `${SHOTS}/ac18-register-one-shown.png`, fullPage: true });
    assertClean(w, 'AC18 login/register');
  });

  test('account fields and delete dialog toggle; each field independent', async ({ page }) => {
    const w = watch(page);
    await register(page, `Tog ${uid()}`, email('tog'));
    await gotoAccount(page);
    const ids = ['account-current-password', 'account-new-password', 'account-confirm-password'];
    for (const id of ids) await checkToggle(page, id, `val-${id}`);

    // independence: toggle each one alone, others stay hidden
    for (const id of ids) {
      await toggle(page, id).click();
      for (const other of ids) {
        if (other === id) await expectShown(page, other);
        else await expectHidden(page, other);
      }
      await toggle(page, id).click();
    }
    // two shown at once, then hide just one
    await toggle(page, ids[0]).click();
    await toggle(page, ids[2]).click();
    await expectShown(page, ids[0]);
    await expectHidden(page, ids[1]);
    await expectShown(page, ids[2]);
    await page.screenshot({ path: `${SHOTS}/ac18-account-two-shown.png`, fullPage: true });
    await toggle(page, ids[0]).click();
    await expectHidden(page, ids[0]);
    await expectShown(page, ids[2]);

    // delete dialog
    await page.getByRole('button', { name: 'Delete account' }).click();
    const dialog = page.getByRole('dialog');
    await expect(dialog).toBeVisible();
    await checkToggle(dialog, 'delete-account-password', 'dialog-secret');
    await toggle(dialog, 'delete-account-password').click();
    await expectShown(dialog, 'delete-account-password');
    // the account-page field is not affected by the dialog toggle
    await expectShown(page, ids[2]);
    await expectHidden(page, ids[0]);
    await page.screenshot({ path: `${SHOTS}/ac18-delete-dialog-shown.png`, fullPage: true });
    // clicking toggle did not close the dialog or submit
    await expect(dialog).toBeVisible();
    await expect(page).toHaveURL(/\/account$/);
    await dialog.getByRole('button', { name: 'Cancel' }).click();
    await expect(page.getByRole('dialog')).toHaveCount(0);
    // Re-open: dialog field starts hidden (informational about fresh state)
    await page.getByRole('button', { name: 'Delete account' }).click();
    const reopenedType = await page.locator('#delete-account-password').getAttribute('type');
    console.log('AC18 delete dialog field type after cancel+reopen:', reopenedType);
    await expectHidden(page.getByRole('dialog'), 'delete-account-password');
    await page.getByRole('dialog').getByRole('button', { name: 'Cancel' }).click();
    assertClean(w, 'AC18 account');
  });

  test('keyboard: Tab reaches toggle; Enter and Space toggle without submitting', async ({ page }) => {
    const w = watch(page);
    // Register: fully valid form so a submit would really go out
    const regReqs = record(page, '/api/auth/register');
    await page.goto('/register');
    await page.locator('#register-display-name').fill(`Kb ${uid()}`);
    await page.locator('#register-email').fill(email('kb'));
    await page.locator('#register-password').fill(PASSWORD);
    await page.locator('#register-confirm-password').fill(PASSWORD);

    await page.locator('#register-password').focus();
    await page.keyboard.press('Tab');
    await expect(toggle(page, 'register-password')).toBeFocused();
    await page.keyboard.press('Enter');
    await expectShown(page, 'register-password');
    await page.keyboard.press('Space');
    await expectHidden(page, 'register-password');
    await page.keyboard.press('Tab');
    await expect(page.locator('#register-confirm-password')).toBeFocused();
    await page.keyboard.press('Tab');
    await expect(toggle(page, 'register-confirm-password')).toBeFocused();
    await page.keyboard.press('Enter');
    await expectShown(page, 'register-confirm-password');
    await page.screenshot({ path: `${SHOTS}/ac18-keyboard-focus.png`, fullPage: true });
    await page.keyboard.press('Space');
    await expectHidden(page, 'register-confirm-password');
    await page.waitForTimeout(500);
    expect(regReqs).toEqual([]);
    await expect(page).toHaveURL(/\/register$/);

    // Login: Enter on the toggle does not sign in
    const loginReqs = record(page, '/api/auth/login');
    await page.goto('/login');
    await page.locator('#login-email').fill(email('kb-login'));
    await page.locator('#login-password').fill('whatever123');
    await page.locator('#login-password').focus();
    await page.keyboard.press('Tab');
    await expect(toggle(page, 'login-password')).toBeFocused();
    await page.keyboard.press('Enter');
    await expectShown(page, 'login-password');
    await page.keyboard.press('Space');
    await expectHidden(page, 'login-password');
    await page.waitForTimeout(500);
    expect(loginReqs).toEqual([]);
    await expect(page.getByTestId('form-error')).toHaveCount(0);
    // Enter inside the input still submits (login unchanged)
    await page.locator('#login-password').press('Enter');
    await expect(page.getByTestId('form-error')).toHaveText('Invalid email or password');
    expect(loginReqs.length).toBe(1);
    assertClean(w, 'AC18 keyboard');
  });

  test('keyboard on /account and delete dialog: toggles do not submit', async ({ page }) => {
    const w = watch(page);
    await register(page, `KbA ${uid()}`, email('kba'));
    await gotoAccount(page);
    const pwReqs = record(page, '/api/account/password');
    const delReqs = record(page, '/api/account/delete');
    await page.locator('#account-current-password').fill(PASSWORD);
    await page.locator('#account-new-password').fill(NEW_PASSWORD);
    await page.locator('#account-confirm-password').fill(NEW_PASSWORD);
    for (const id of ['account-current-password', 'account-new-password', 'account-confirm-password']) {
      await page.locator(`#${id}`).focus();
      await page.keyboard.press('Tab');
      await expect(toggle(page, id)).toBeFocused();
      await page.keyboard.press('Enter');
      await expectShown(page, id);
      await page.keyboard.press('Space');
      await expectHidden(page, id);
    }
    await page.getByRole('button', { name: 'Delete account' }).click();
    const dialog = page.getByRole('dialog');
    await dialog.locator('#delete-account-password').fill(PASSWORD);
    await dialog.locator('#delete-account-password').focus();
    await page.keyboard.press('Tab');
    await expect(toggle(dialog, 'delete-account-password')).toBeFocused();
    await page.keyboard.press('Enter');
    await expectShown(dialog, 'delete-account-password');
    await page.keyboard.press('Space');
    await expectHidden(dialog, 'delete-account-password');
    await page.waitForTimeout(500);
    expect(pwReqs).toEqual([]);
    expect(delReqs).toEqual([]);
    await expect(dialog).toBeVisible();
    await expect(page.getByText('Password changed')).toHaveCount(0);
    await dialog.getByRole('button', { name: 'Cancel' }).click();
    assertClean(w, 'AC18 keyboard account');
  });

  test('fields start hidden on page load and after a successful submit', async ({ page }) => {
    const w = watch(page);
    await register(page, `Rst ${uid()}`, email('rst'));
    await gotoAccount(page);
    const ids = ['account-current-password', 'account-new-password', 'account-confirm-password'];
    for (const id of ids) await toggle(page, id).click();
    for (const id of ids) await expectShown(page, id);
    await page.reload();
    await expect(page.getByRole('heading', { name: 'Account', exact: true })).toBeVisible();
    for (const id of ids) await expectHidden(page, id);

    // successful change: all shown before submit, all hidden and cleared after
    await page.locator('#account-current-password').fill(PASSWORD);
    await page.locator('#account-new-password').fill(NEW_PASSWORD);
    await page.locator('#account-confirm-password').fill(NEW_PASSWORD);
    for (const id of ids) await toggle(page, id).click();
    for (const id of ids) await expectShown(page, id);
    await page.getByRole('button', { name: 'Change password' }).click();
    await expect(page.getByText('Password changed')).toBeVisible();
    for (const id of ids) {
      await expectHidden(page, id);
      await expect(page.locator(`#${id}`)).toHaveValue('');
    }
    // no stale errors after reset
    await expect(page.locator('#account-confirm-password-error')).toHaveCount(0);
    await expect(page.locator('#account-new-password-error')).toHaveCount(0);
    await expect(page.locator('#account-current-password-error')).toHaveCount(0);
    await page.screenshot({ path: `${SHOTS}/ac18-after-password-changed-hidden.png`, fullPage: true });

    // login/register page load: shown state does not survive navigation
    await page.getByRole('button', { name: 'Log out' }).click();
    await expect(page).toHaveURL(/\/login/);
    await page.goto('/register');
    await toggle(page, 'register-password').click();
    await page.reload();
    await expectHidden(page, 'register-password');
    await expectHidden(page, 'register-confirm-password');
    assertClean(w, 'AC18 reset');
  });
});

// ---------------------------------------------------------------- AC19

test.describe('AC19: register confirm password', () => {
  test('mismatch and empty show errors; nothing is sent', async ({ page }) => {
    const w = watch(page);
    const reqs = record(page, '/api/auth/register');
    await page.goto('/register');
    // field order: Confirm password is directly below Password
    const pw = await page.locator('#register-password').boundingBox();
    const cf = await page.locator('#register-confirm-password').boundingBox();
    expect(pw && cf && cf.y > pw.y).toBeTruthy();
    await expect(page.getByLabel('Confirm password', { exact: true })).toHaveAttribute(
      'id',
      'register-confirm-password',
    );

    await page.locator('#register-display-name').fill(`Mm ${uid()}`);
    await page.locator('#register-email').fill(email('mm'));
    await page.locator('#register-password').fill(PASSWORD);
    await page.locator('#register-confirm-password').fill('password124');
    await page.getByRole('button', { name: 'Create account' }).click();
    const err = page.locator('#register-confirm-password-error');
    await expect(err).toHaveText('Passwords do not match');
    await expect(page.locator('#register-confirm-password')).toHaveAttribute('aria-invalid', 'true');
    await expect(page.locator('#register-password-error')).toHaveCount(0);
    await page.screenshot({ path: `${SHOTS}/ac19-register-mismatch.png`, fullPage: true });

    // case-only difference is a mismatch too
    await page.locator('#register-confirm-password').fill(PASSWORD.toUpperCase());
    await page.getByRole('button', { name: 'Create account' }).click();
    await expect(err).toHaveText('Passwords do not match');

    // trailing space is a mismatch (passwords are not trimmed)
    await page.locator('#register-confirm-password').fill(`${PASSWORD} `);
    await page.getByRole('button', { name: 'Create account' }).click();
    await expect(err).toHaveText('Passwords do not match');

    await page.locator('#register-confirm-password').fill('');
    await page.getByRole('button', { name: 'Create account' }).click();
    await expect(err).toHaveText('Confirm your password');
    await page.screenshot({ path: `${SHOTS}/ac19-register-empty.png`, fullPage: true });

    // double-click submit while mismatched: still nothing sent
    await page.locator('#register-confirm-password').fill('nope-nope');
    await page.getByRole('button', { name: 'Create account' }).dblclick();
    await expect(err).toHaveText('Passwords do not match');

    await page.waitForTimeout(500);
    expect(reqs).toEqual([]);
    await expect(page).toHaveURL(/\/register$/);

    // fix it: error disappears
    await page.locator('#register-confirm-password').fill(PASSWORD);
    await expect(err).toHaveCount(0);
    assertClean(w, 'AC19');
  });

  test('empty form: confirm shows "Confirm your password" alongside the other errors', async ({ page }) => {
    const reqs = record(page, '/api/auth/register');
    await page.goto('/register');
    await page.getByRole('button', { name: 'Create account' }).click();
    await expect(page.locator('#register-confirm-password-error')).toHaveText('Confirm your password');
    await expect(page.locator('#register-password-error')).toBeVisible();
    expect(reqs).toEqual([]);
  });
});

// ---------------------------------------------------------------- AC20

test.describe('AC20: change password confirm', () => {
  test('mismatch and empty show errors; nothing is sent', async ({ page }) => {
    const w = watch(page);
    const mail = email('cmm');
    await register(page, `Cmm ${uid()}`, mail);
    await gotoAccount(page);
    const reqs = record(page, '/api/account/password');
    const np = await page.locator('#account-new-password').boundingBox();
    const cf = await page.locator('#account-confirm-password').boundingBox();
    expect(np && cf && cf.y > np.y).toBeTruthy();
    await expect(page.getByLabel('Confirm new password', { exact: true })).toHaveAttribute(
      'id',
      'account-confirm-password',
    );

    await page.locator('#account-current-password').fill(PASSWORD);
    await page.locator('#account-new-password').fill(NEW_PASSWORD);
    await page.locator('#account-confirm-password').fill('something-else');
    await page.getByRole('button', { name: 'Change password' }).click();
    const err = page.locator('#account-confirm-password-error');
    await expect(err).toHaveText('Passwords do not match');
    await expect(page.locator('#account-confirm-password')).toHaveAttribute('aria-invalid', 'true');
    await page.screenshot({ path: `${SHOTS}/ac20-account-mismatch.png`, fullPage: true });

    await page.locator('#account-confirm-password').fill('');
    await page.getByRole('button', { name: 'Change password' }).click();
    await expect(err).toHaveText('Confirm your new password');
    await page.screenshot({ path: `${SHOTS}/ac20-account-empty.png`, fullPage: true });

    // confirm equal to the *current* password but not the new one: mismatch
    await page.locator('#account-confirm-password').fill(PASSWORD);
    await page.getByRole('button', { name: 'Change password' }).dblclick();
    await expect(err).toHaveText('Passwords do not match');

    await page.waitForTimeout(500);
    expect(reqs).toEqual([]);
    await expect(page.getByText('Password changed')).toHaveCount(0);

    // password unchanged on the server: still logs in with the old one
    await page.getByRole('button', { name: 'Log out' }).click();
    await expect(page).toHaveURL(/\/login/);
    await page.locator('#login-email').fill(mail);
    await page.locator('#login-password').fill(PASSWORD);
    await page.getByRole('button', { name: 'Sign in' }).click();
    await expect(page).toHaveURL(/\/tasks$/);
    assertClean(w, 'AC20');
  });
});

// ---------------------------------------------------------------- AC21

test.describe('AC21: matching passwords work; confirm never sent', () => {
  test('register body has no confirm key; change password body has no confirm key', async ({ page }) => {
    const w = watch(page);
    const mail = email('body');
    const name = `Body ${uid()}`;
    const regReqs = record(page, '/api/auth/register');
    await register(page, name, mail);
    expect(regReqs.length).toBe(1);
    expect(regReqs[0].method).toBe('POST');
    console.log('AC21 register body keys:', JSON.stringify(Object.keys(regReqs[0].body ?? {})));
    expect(Object.keys(regReqs[0].body ?? {}).sort()).toEqual(['displayName', 'email', 'password']);
    expect(regReqs[0].body).toEqual({ displayName: name, email: mail, password: PASSWORD });

    await gotoAccount(page);
    const pwReqs = record(page, '/api/account/password');
    await page.locator('#account-current-password').fill(PASSWORD);
    await page.locator('#account-new-password').fill(NEW_PASSWORD);
    await page.locator('#account-confirm-password').fill(NEW_PASSWORD);
    await page.getByRole('button', { name: 'Change password' }).click();
    await expect(page.getByText('Password changed')).toBeVisible();
    expect(pwReqs.length).toBe(1);
    expect(pwReqs[0].method).toBe('PUT');
    console.log('AC21 change-password body keys:', JSON.stringify(Object.keys(pwReqs[0].body ?? {})));
    expect(pwReqs[0].body).toEqual({ currentPassword: PASSWORD, newPassword: NEW_PASSWORD });
    for (const id of ['account-current-password', 'account-new-password', 'account-confirm-password']) {
      await expect(page.locator(`#${id}`)).toHaveValue('');
    }
    await expect(page).toHaveURL(/\/account$/);
    await page.screenshot({ path: `${SHOTS}/ac21-password-changed.png`, fullPage: true });

    // a second change right away works too (form is reusable after reset)
    await page.locator('#account-current-password').fill(NEW_PASSWORD);
    await page.locator('#account-new-password').fill(PASSWORD);
    await page.locator('#account-confirm-password').fill(PASSWORD);
    const second = page.waitForResponse((r) => r.url().includes('/api/account/password'));
    await page.getByRole('button', { name: 'Change password' }).click();
    expect((await second).status()).toBe(204);
    await expect(page.getByText('Password changed')).toBeVisible();
    expect(pwReqs.length).toBe(2);

    // signed in with the latest password
    await page.getByRole('button', { name: 'Log out' }).click();
    await expect(page).toHaveURL(/\/login/);
    await page.locator('#login-email').fill(mail);
    await page.locator('#login-password').fill(NEW_PASSWORD);
    await page.getByRole('button', { name: 'Sign in' }).click();
    await expect(page.getByTestId('form-error')).toHaveText('Invalid email or password');
    await page.locator('#login-password').fill(PASSWORD);
    await page.getByRole('button', { name: 'Sign in' }).click();
    await expect(page).toHaveURL(/\/tasks$/);
    assertClean(w, 'AC21');
  });

  test('server-side error still maps when passwords match (wrong current password)', async ({ page }) => {
    await register(page, `Wcp ${uid()}`, email('wcp'));
    await gotoAccount(page);
    const pwReqs = record(page, '/api/account/password');
    await page.locator('#account-current-password').fill('not-my-password');
    await page.locator('#account-new-password').fill(NEW_PASSWORD);
    await page.locator('#account-confirm-password').fill(NEW_PASSWORD);
    await page.getByRole('button', { name: 'Change password' }).click();
    await expect(page.locator('#account-current-password-error')).toHaveText('Current password is incorrect');
    expect(pwReqs.length).toBe(1);
    expect(pwReqs[0].body).toEqual({ currentPassword: 'not-my-password', newPassword: NEW_PASSWORD });
    // values kept so the user can retry
    await expect(page.locator('#account-confirm-password')).toHaveValue(NEW_PASSWORD);
  });
});

// ---------------------------------------------------------------- AC22

test.describe('AC22: login and delete dialog have no confirm field', () => {
  test('login has one password field with a toggle; body unchanged', async ({ page }) => {
    const w = watch(page);
    const mail = email('lg');
    await register(page, `Lg ${uid()}`, mail);
    await page.getByRole('button', { name: 'Log out' }).click();
    await expect(page).toHaveURL(/\/login/);
    await expect(page.locator('input[type=password]')).toHaveCount(1);
    await expect(page.getByTestId('password-toggle')).toHaveCount(1);
    await expect(page.getByLabel(/confirm/i)).toHaveCount(0);
    const reqs = record(page, '/api/auth/login');
    await page.locator('#login-email').fill(mail);
    await page.locator('#login-password').fill(PASSWORD);
    // shown state still submits the typed value
    await toggle(page, 'login-password').click();
    await page.getByRole('button', { name: 'Sign in' }).click();
    await expect(page).toHaveURL(/\/tasks$/);
    expect(reqs.length).toBe(1);
    expect(reqs[0].body).toEqual({ email: mail, password: PASSWORD });

    // delete dialog: one password field, one toggle, no confirm
    await gotoAccount(page);
    await page.getByRole('button', { name: 'Delete account' }).click();
    const dialog = page.getByRole('dialog');
    await expect(dialog.locator('input')).toHaveCount(1);
    await expect(dialog.getByTestId('password-toggle')).toHaveCount(1);
    await expect(dialog.getByLabel(/confirm/i)).toHaveCount(0);
    await page.screenshot({ path: `${SHOTS}/ac22-delete-dialog.png`, fullPage: true });

    // delete with password shown: body is just {password}
    const delReqs = record(page, '/api/account/delete');
    await dialog.locator('#delete-account-password').fill(PASSWORD);
    await toggle(dialog, 'delete-account-password').click();
    await dialog.getByRole('button', { name: 'Delete permanently' }).click();
    await expect(page).toHaveURL(/\/login/);
    expect(delReqs.length).toBe(1);
    expect(delReqs[0].body).toEqual({ password: PASSWORD });
    await expect(page.locator('#login-password')).toHaveAttribute('type', 'password');
    assertClean(w, 'AC22');
  });

  test('login screenshots: hidden and shown with a value', async ({ page }) => {
    await page.goto('/login');
    await page.locator('#login-email').fill('someone@example.com');
    await page.locator('#login-password').fill('visible-secret');
    await page.screenshot({ path: `${SHOTS}/ac22-login-hidden.png` });
    await toggle(page, 'login-password').click();
    await page.screenshot({ path: `${SHOTS}/ac22-login-shown.png` });
  });
});
