import { Component, signal } from '@angular/core';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { NotificationsStore } from '../../features/notifications/notifications.store';
import { AuthService } from '../../core/auth/auth.service';
import { ShellLayout } from './shell-layout';

/** The bell is tested in notifications.store.spec; here it must not call the API. */
const NOTIFICATIONS_STUB = {
  count: signal(0),
  refresh: () => undefined,
  startPolling: () => undefined,
};

@Component({ template: 'page' })
class Page {}

const BOB = {
  id: 1,
  email: 'bob@example.com',
  displayName: 'Bob',
  emailVerified: true,
  hasPassword: true,
  role: 'USER' as const,
  timeZone: 'Europe/Bucharest',
  reminderEmails: true,
  connections: [],
};

function problem(status: number, code: string) {
  return { type: 'about:blank', title: 'x', status, detail: 'x', instance: '/api', code };
}

describe('ShellLayout', () => {
  it('shows the verify-email banner right under the header for an unconfirmed user', async () => {
    TestBed.configureTestingModule({
      imports: [ShellLayout],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: NotificationsStore, useValue: NOTIFICATIONS_STUB },
      ],
    });
    const http = TestBed.inject(HttpTestingController);
    TestBed.inject(AuthService).loadMe().subscribe();
    http.expectOne('/api/auth/me').flush({
      id: 1,
      email: 'bob@example.com',
      displayName: 'Bob',
      emailVerified: false,
      hasPassword: true,
      role: 'USER',
      timeZone: 'Europe/Bucharest',
      reminderEmails: true,
      connections: [],
    });

    const fixture = TestBed.createComponent(ShellLayout);
    http.expectOne('/api/invitations/count').flush({ count: 0 });
    await fixture.whenStable();
    const el: HTMLElement = fixture.nativeElement;
    const header = el.querySelector('app-header')!;
    expect(header.nextElementSibling?.tagName.toLowerCase()).toBe('app-verify-email-banner');
    expect(el.querySelector('[data-testid=verify-email-banner]')).not.toBeNull();
    http.verify();
  });
  describe('refresh on navigation', () => {
    let http: HttpTestingController;
    let harness: RouterTestingHarness;
    let auth: AuthService;

    beforeEach(async () => {
      TestBed.configureTestingModule({
        providers: [
          provideRouter([
            { path: 'login', component: Page },
            {
              path: '',
              component: ShellLayout,
              children: [
                { path: 'tasks', component: Page },
                { path: 'account', component: Page },
                { path: 'admin/users', component: Page },
              ],
            },
          ]),
          provideHttpClient(),
          provideHttpClientTesting(),
          { provide: NotificationsStore, useValue: NOTIFICATIONS_STUB },
        ],
      });
      http = TestBed.inject(HttpTestingController);
      auth = TestBed.inject(AuthService);
      auth.loadMe().subscribe();
      http.expectOne('/api/auth/me').flush(BOB);
      harness = await RouterTestingHarness.create();
      await harness.navigateByUrl('/tasks');
      http.expectOne('/api/invitations/count').flush({ count: 0 });
    });

    /** The header recounts invitations on every page change (no live updates). */
    function flushCount(): void {
      http.expectOne('/api/invitations/count').flush({ count: 0 });
    }

    afterEach(() => http.verify());

    it('does not re-read the user for the navigation that opened the shell', () => {
      http.expectNone('/api/auth/me');
    });

    it('re-reads the user when moving to another page and applies a new role', async () => {
      await harness.navigateByUrl('/account');
      flushCount();
      http.expectOne('/api/auth/me').flush({ ...BOB, role: 'ADMIN' });
      expect(auth.isAdmin()).toBe(true);
    });

    it('does not re-read for a query-only change or for /admin pages (the guard does)', async () => {
      await harness.navigateByUrl('/tasks?filter=done');
      http.expectNone('/api/auth/me');
      await harness.navigateByUrl('/admin/users');
      flushCount();
      http.expectNone('/api/auth/me');
    });

    it('goes to /login when the refresh finds the session gone', async () => {
      await harness.navigateByUrl('/account');
      flushCount();
      http
        .expectOne('/api/auth/me')
        .flush(problem(401, 'ACCOUNT_DISABLED'), { status: 401, statusText: 'x' });
      await harness.fixture.whenStable();
      expect(TestBed.inject(Router).url).toBe('/login');
      expect(auth.accountDisabled()).toBe(true);
    });
  });
});
