import { expect, test } from '@playwright/test';

test('app loads at / (signed out -> /login) without app console errors', async ({ page }) => {
  const errors: string[] = [];
  page.on('console', (msg) => {
    if (msg.type() === 'error') errors.push(msg.text());
  });
  page.on('pageerror', (err) => errors.push(err.message));

  await page.goto('/');

  await expect(page).toHaveURL(/\/login$/);
  await expect(page).toHaveTitle('Sign in · Task App');
  await expect(page.locator('app-root')).toBeVisible();
  // The browser logs the expected 401 from GET /api/auth/me as a network error; ignore only that.
  const appErrors = errors.filter(
    (e) => !/^Failed to load resource: the server responded with a status of 401/.test(e),
  );
  expect(appErrors).toEqual([]);
});
