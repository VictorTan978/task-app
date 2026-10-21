import { expect, Page, test } from '@playwright/test';
import { execFileSync } from 'node:child_process';
import { resolve } from 'node:path';

/**
 * Security hardening, section D (AC13, AC14, AC16): least-privilege database users.
 * Walks the full data path through the UI (register, create, edit, toggle, delete, logout, login),
 * which every one of SELECT / INSERT / UPDATE / DELETE must allow for the restricted app user.
 *
 * The DB-level checks (who the app's connections belong to, grants, DDL refused) need the local
 * docker-compose "db" service. They are skipped when docker is not reachable. Read-only for every
 * user except taskapp_app, and the DDL attempts run ONLY as taskapp_app (which must be refused).
 */
const RUN = `${Date.now()}-${Math.floor(Math.random() * 1e6)}`;
const SHOTS = '../test-results/security-db-users';
const PASSWORD = 'password123';
const REPO = resolve(__dirname, '..', '..');

function dockerMysql(args: string[], sql: string): { ok: boolean; out: string } {
  try {
    const out = execFileSync('docker', ['compose', 'exec', '-T', 'db', ...args, '-N', '-B', '-e', sql], {
      cwd: REPO,
      encoding: 'utf8',
      stdio: ['ignore', 'pipe', 'pipe'],
    });
    return { ok: true, out };
  } catch (e) {
    const err = e as { stdout?: string; stderr?: string };
    return { ok: false, out: `${err.stdout ?? ''}${err.stderr ?? ''}` };
  }
}
const asRoot = (sql: string) => dockerMysql(['env', 'MYSQL_PWD=taskapp-root', 'mysql', '-uroot'], sql);
const asApp = (sql: string) => dockerMysql(['env', 'MYSQL_PWD=taskapp_app', 'mysql', '-utaskapp_app', 'taskapp'], sql);
const dockerAvailable = asRoot('SELECT 1').ok;

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

test('AC16: full data path works through the UI with the restricted app user', async ({ page }) => {
  const errors = watch(page);
  const email = `qa-sec-d-${RUN}@example.com`;

  await page.goto('/register');
  await page.locator('#register-display-name').fill(`SecD ${RUN}`);
  await page.locator('#register-email').fill(email);
  await page.locator('#register-password').fill(PASSWORD);
  await page.locator('#register-confirm-password').fill(PASSWORD);
  await page.getByRole('button', { name: 'Create account' }).click();
  await expect(page).toHaveURL(/\/tasks$/);
  await expect(page.getByRole('heading', { name: 'My tasks' })).toBeVisible();

  // INSERT
  await page.getByLabel('New task').fill('D first');
  await page.getByRole('button', { name: 'Add', exact: true }).click();
  await expect(page.getByRole('checkbox', { name: 'Done: D first' })).toBeVisible();
  await page.getByLabel('New task').fill('D second');
  await page.getByRole('button', { name: 'Add', exact: true }).click();
  const list = page.getByRole('list', { name: 'Tasks' });
  await expect(list.getByRole('listitem')).toHaveCount(2);

  // UPDATE (toggle + edit)
  await page.getByRole('checkbox', { name: 'Done: D first' }).check();
  await expect(page.getByRole('checkbox', { name: 'Done: D first' })).toBeChecked();
  const row = list.getByRole('listitem').filter({ has: page.getByRole('checkbox', { name: 'Done: D second', exact: true }) });
  await row.getByRole('button', { name: 'Edit' }).click();
  const dlg = page.getByRole('dialog', { name: 'Edit task' });
  await dlg.getByLabel('Title').fill('D renamed');
  await dlg.getByRole('button', { name: 'Save' }).click();
  await expect(dlg).toHaveCount(0);
  await expect(page.getByRole('checkbox', { name: 'Done: D renamed' })).toBeVisible();

  // DELETE
  const row1 = list.getByRole('listitem').filter({ has: page.getByRole('checkbox', { name: 'Done: D first', exact: true }) });
  await row1.getByRole('button', { name: 'Delete' }).click();
  const del = page.getByRole('dialog', { name: "Delete 'D first'?" });
  await del.getByRole('button', { name: 'Delete' }).click();
  await expect(del).toHaveCount(0);
  await expect(list.getByRole('listitem')).toHaveCount(1);

  // SELECT after reload, then logout / login round trip
  await page.reload();
  await expect(list.getByRole('listitem')).toHaveCount(1);
  await page.screenshot({ path: `${SHOTS}/ac16-tasks.png`, fullPage: true });
  await page.getByRole('button', { name: 'Log out', exact: true }).click();
  await expect(page).toHaveURL(/\/login/);
  await page.locator('#login-email').fill(email);
  await page.locator('#login-password').fill(PASSWORD);
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page).toHaveURL(/\/tasks$/);
  await expect(page.getByRole('checkbox', { name: 'Done: D renamed' })).toBeVisible();

  if (dockerAvailable) {
    // The data written above really went to MySQL. T5a: delete is a soft delete, so the
    // deleted task is still a row, marked with deleted_at (an UPDATE by the restricted user).
    const q = (extra: string) =>
      asRoot(`SELECT COUNT(*) FROM taskapp.tasks t JOIN taskapp.users u ON u.id = t.owner_id WHERE u.email = '${email}'${extra}`);
    const n = q('');
    expect(n.ok, n.out).toBe(true);
    expect(n.out.trim()).toBe('2');
    expect(q(' AND t.deleted_at IS NOT NULL').out.trim()).toBe('1');
  }

  // DELETE FOR GOOD through the Trash page: a real DELETE by the restricted user
  await page.goto('/trash');
  await expect(page.getByTestId('trash-row')).toHaveCount(1);
  await expect(page.getByTestId('trash-title')).toHaveText('D first');
  await page.getByTestId('trash-delete').click();
  await page.getByTestId('trash-confirm').click();
  await expect(page.getByTestId('trash-empty')).toBeVisible();

  expect(errors).toEqual([]);

  if (dockerAvailable) {
    const n = asRoot(`SELECT COUNT(*) FROM taskapp.tasks t JOIN taskapp.users u ON u.id = t.owner_id WHERE u.email = '${email}'`);
    expect(n.ok, n.out).toBe(true);
    expect(n.out.trim()).toBe('1');
  }
});

test.describe('AC13/AC14: database users (needs the docker-compose db)', () => {
  test.skip(!dockerAvailable, 'docker compose db not reachable');

  test('AC13: the running app connects as taskapp_app only; Flyway history installed_by taskapp', async ({ request }) => {
    expect((await request.get('/api/health')).status()).toBe(200);
    // host <> 'localhost' keeps only the app's TCP connections (host = gateway IP:port); other specs' `docker compose exec db mysql` sessions use the in-container socket (host = 'localhost').
    const users = asRoot(
      "SELECT DISTINCT user FROM information_schema.processlist WHERE db = 'taskapp' AND host <> 'localhost' AND user NOT IN ('root','event_scheduler') ORDER BY user",
    );
    expect(users.ok, users.out).toBe(true);
    expect(users.out.trim().split('\n')).toEqual(['taskapp_app']);

    const grants = asRoot('SHOW GRANTS FOR taskapp_app');
    expect(grants.out.trim().split('\n').sort()).toEqual([
      'GRANT SELECT, INSERT, UPDATE, DELETE ON `taskapp`.* TO `taskapp_app`@`%`',
      'GRANT USAGE ON *.* TO `taskapp_app`@`%`',
    ]);

    const installedBy = asRoot('SELECT DISTINCT installed_by FROM taskapp.flyway_schema_history');
    expect(installedBy.out.trim().split('\n')).toEqual(['taskapp']);
  });

  test('AC14: taskapp_app is refused DROP TABLE tasks and CREATE TABLE x', () => {
    const drop = asApp('DROP TABLE tasks');
    expect(drop.ok).toBe(false);
    expect(drop.out).toMatch(/ERROR 1142 .*DROP command denied to user 'taskapp_app'/);

    const create = asApp('CREATE TABLE x (id int)');
    expect(create.ok).toBe(false);
    expect(create.out).toMatch(/ERROR 1142 .*CREATE command denied to user 'taskapp_app'/);

    // Still intact, and normal data access still works for the app user.
    const sel = asApp('SELECT COUNT(*) FROM tasks');
    expect(sel.ok, sel.out).toBe(true);
    const tables = asRoot("SELECT table_name FROM information_schema.tables WHERE table_schema = 'taskapp' AND table_name IN ('tasks','x')");
    expect(tables.out.trim().split('\n')).toEqual(['tasks']);
  });
});
