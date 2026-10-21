import { expect, Page, test } from '@playwright/test';

/*
 * Extra regression checks for the PostgreSQL 16 -> MySQL 8.4 switch (2026-10-09).
 * - Timestamps / due date round-trip unchanged across reload (no time-zone shift),
 *   with the browser in far-off time zones (UTC-10 and UTC+14).
 * - Same email with different letter case is rejected as "email taken" (UI + API).
 * Every test fails on browser console errors or page errors.
 */

const RUN = `${Date.now()}-${Math.floor(Math.random() * 1e6)}`;
const SHOTS = '../test-results/mysql-regression';
const PASSWORD = 'password123';
let counter = 0;
const uniq = (tag: string) => `qa-mysql-${RUN}-${tag}-${++counter}@example.com`;
// Display names are unique (ignoring case/accents) and the DB keeps data, so suffix per run.
const uname = (base: string) => `${base} ${RUN}-${++counter}`;

function watch(page: Page) {
  const errors: string[] = [];
  page.on('console', (m) => {
    if (m.type() === 'error') errors.push(m.text());
  });
  page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`));
  return errors;
}
// Only the browser's own log line for the expected 401 from /api/auth/me on a signed-out load
// and the expected 409 on duplicate registration are allowed.
const allowed = (e: string) =>
  /^Failed to load resource: the server responded with a status of (401|409)/.test(e);

async function xsrf(page: Page) {
  return (await page.context().cookies()).find((c) => c.name === 'XSRF-TOKEN')!.value;
}
async function api(page: Page, method: string, path: string, data?: unknown) {
  return page.request.fetch(path, { method, data, headers: { 'X-XSRF-TOKEN': await xsrf(page) } });
}

// Capture a request's JSON response in Node (route.fetch) instead of asking Chromium for the body
// afterwards; CDP sometimes drops response bodies. Register before the click that triggers it.
interface Captured {
  status: number;
  body: { id: number; createdAt: string; updatedAt: string; dueDate: string | null };
}
// Returns { result } (not the bare promise) so awaiting the registration does not wait for the response.
async function captureJson(
  page: Page,
  method: string,
  match: (url: URL) => boolean,
): Promise<{ result: Promise<Captured> }> {
  let resolve!: (v: Captured) => void;
  let reject!: (e: unknown) => void;
  const captured = new Promise<Captured>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  const handler = async (route: import('@playwright/test').Route) => {
    const req = route.request();
    if (req.method() !== method || !match(new URL(req.url()))) {
      await route.fallback();
      return;
    }
    try {
      const resp = await route.fetch();
      const body = (await resp.json()) as Captured['body'];
      await route.fulfill({ response: resp });
      resolve({ status: resp.status(), body });
    } catch (e) {
      // The request may already be sent: never fallback/continue (it would be sent twice).
      reject(e);
      await route.abort().catch(() => undefined);
    } finally {
      void page.unroute('**/api/**', handler).catch(() => undefined);
    }
  };
  await page.route('**/api/**', handler);
  return { result: captured };
}

async function registerUi(page: Page, name: string, email: string) {
  await page.goto('/register');
  await page.locator('#register-display-name').fill(name);
  await page.locator('#register-email').fill(email);
  await page.locator('#register-password').fill(PASSWORD);
  await page.locator('#register-confirm-password').fill(PASSWORD);
  await page.getByRole('button', { name: 'Create account' }).click();
}

for (const tz of ['Pacific/Honolulu', 'Pacific/Kiritimati']) {
  test.describe(`timestamps and due date, browser TZ ${tz}`, () => {
    test.use({ timezoneId: tz });

    test(`create via UI, set due date via dialog, reload: values unchanged (${tz})`, async ({ page }) => {
      const errors = watch(page);
      const title = `TZ check ${tz} ${RUN}`;
      await registerUi(page, uname('TZ'), uniq('tz'));
      await expect(page).toHaveURL(/\/tasks$/);

      // Create through the UI and capture the POST response
      const postResp = await captureJson(page, 'POST', (u) => u.pathname === '/api/tasks');
      await page.getByLabel('New task').fill(title);
      await page.getByRole('button', { name: 'Add', exact: true }).click();
      const created = (await postResp.result).body;
      expect(created.createdAt).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?Z$/);
      expect(created.createdAt).toBe(created.updatedAt);
      const skewMs = Math.abs(Date.parse(created.createdAt) - Date.now());
      expect(skewMs, 'createdAt within 2 min of real now (no hour shift)').toBeLessThan(120_000);

      // Edit: set a due date on a month/year boundary that is sensitive to day shifts
      const row = page
        .getByRole('list', { name: 'Tasks' })
        .getByRole('listitem')
        .filter({ has: page.getByRole('checkbox', { name: `Done: ${title}`, exact: true }) });
      await row.getByRole('button', { name: 'Edit' }).click();
      const dlg = page.getByRole('dialog', { name: 'Edit task' });
      await dlg.getByLabel('Due date').fill('2031-01-01');
      const putResp = await captureJson(page, 'PUT', (u) => /^\/api\/tasks\/\d+$/.test(u.pathname));
      await dlg.getByRole('button', { name: 'Save' }).click();
      const saved = (await putResp.result).body;
      await expect(dlg).toHaveCount(0);
      expect(saved.dueDate).toBe('2031-01-01');
      expect(saved.createdAt).toBe(created.createdAt);
      expect(Date.parse(saved.updatedAt)).toBeGreaterThanOrEqual(Date.parse(created.updatedAt));
      await expect(row).toContainText('2031-01-01');
      await page.screenshot({ path: `${SHOTS}/tz-${tz.replace('/', '_')}-before-reload.png`, fullPage: true });

      // Reload and compare UI + GET with what the writes returned
      await page.reload();
      await expect(row).toContainText('2031-01-01');
      await page.screenshot({ path: `${SHOTS}/tz-${tz.replace('/', '_')}-after-reload.png`, fullPage: true });
      const got = await (await api(page, 'GET', `/api/tasks/${created.id}`)).json();
      expect(got.createdAt).toBe(saved.createdAt);
      expect(got.updatedAt).toBe(saved.updatedAt);
      expect(got.dueDate).toBe('2031-01-01');
      // GET /api/tasks returns a TaskPage since contract 0.11.0 (this user has one task: one page)
      const listed = ((await (await api(page, 'GET', '/api/tasks')).json()) as { items: { id: number; createdAt: string; updatedAt: string; dueDate: string }[] }).items;
      const fromList = listed.find((t) => t.id === created.id)!;
      expect(fromList).toMatchObject({ createdAt: saved.createdAt, updatedAt: saved.updatedAt, dueDate: '2031-01-01' });

      // Toggle and confirm updatedAt round-trips too
      const patched = await (await api(page, 'PATCH', `/api/tasks/${created.id}`, { done: true })).json();
      const got2 = await (await api(page, 'GET', `/api/tasks/${created.id}`)).json();
      expect(got2.updatedAt).toBe(patched.updatedAt);
      expect(got2.createdAt).toBe(created.createdAt);
      expect(got2.dueDate).toBe('2031-01-01');

      console.log(`TZRESULT ${tz} id=${created.id} created=${created.createdAt} updated=${got2.updatedAt} due=${got2.dueDate}`);
      expect(errors.filter((e) => !allowed(e))).toEqual([]);
    });
  });
}

test('due date boundaries round-trip exactly via API (leap day, year ends)', async ({ page }) => {
  const errors = watch(page);
  await registerUi(page, uname('Dates'), uniq('dates'));
  await expect(page).toHaveURL(/\/tasks$/);
  for (const d of ['2028-02-29', '1999-12-31', '2000-01-01', '2038-01-20', '9999-12-31']) {
    const res = await api(page, 'POST', '/api/tasks', { title: `d ${d}`, dueDate: d });
    expect(res.status(), d).toBe(201);
    const t = await res.json();
    expect(t.dueDate).toBe(d);
    expect((await (await api(page, 'GET', `/api/tasks/${t.id}`)).json()).dueDate).toBe(d);
  }
  await page.reload();
  for (const d of ['2028-02-29', '1999-12-31', '2038-01-20']) {
    await expect(page.getByRole('list', { name: 'Tasks' })).toContainText(d);
  }
  expect(errors.filter((e) => !allowed(e))).toEqual([]);
});

test('same email in different letter case is rejected as "email taken" (UI)', async ({ page, browser }) => {
  const email = uniq('case');
  // first account in a separate context
  const other = await browser.newContext();
  const p2 = await other.newPage();
  await registerUi(p2, uname('Bob'), email);
  await expect(p2).toHaveURL(/\/tasks$/);
  await other.close();

  const errors = watch(page);
  const mixed = email.replace(/^qa-mysql/, 'QA-MySQL').replace('@example.com', '@Example.COM');
  expect(mixed).not.toBe(email);
  await registerUi(page, uname('Bob2'), mixed);
  await expect(page.getByText('An account with this email already exists')).toBeVisible();
  await expect(page).toHaveURL(/\/register$/);
  await page.screenshot({ path: `${SHOTS}/email-case-taken.png`, fullPage: true });

  // API: 409 EMAIL_TAKEN for upper case + surrounding spaces
  const res = await api(page, 'POST', '/api/auth/register', {
    displayName: uname('Bob3'),
    email: `  ${email.toUpperCase()}  `,
    password: PASSWORD,
  });
  expect(res.status()).toBe(409);
  expect((await res.json()).code).toBe('EMAIL_TAKEN');
  expect(errors.filter((e) => !allowed(e))).toEqual([]);
});
