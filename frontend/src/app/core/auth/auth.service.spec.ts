import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { User } from './auth.models';
import { AuthService } from './auth.service';

const BOB: User = {
  id: 1,
  email: 'bob@example.com',
  displayName: 'Bob',
  emailVerified: true,
  hasPassword: true,
  role: 'USER',
  timeZone: 'Europe/Bucharest',
  reminderEmails: true,
  connections: [],
};

function problem(status: number, code: string) {
  return { type: 'about:blank', title: 'x', status, detail: 'x', instance: '/api', code };
}

describe('AuthService', () => {
  let service: AuthService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(AuthService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('loadMe sets the user on 200', async () => {
    const result = firstValueFrom(service.loadMe());
    const req = http.expectOne('/api/auth/me');
    expect(req.request.method).toBe('GET');
    req.flush(BOB);
    expect(await result).toEqual(BOB);
    expect(service.currentUser()).toEqual(BOB);
    expect(service.isSignedIn()).toBe(true);
    expect(service.wasSignedInThisTab).toBe(true);
  });

  it('loadMe resolves null silently on 401', async () => {
    const result = firstValueFrom(service.loadMe());
    http
      .expectOne('/api/auth/me')
      .flush(problem(401, 'UNAUTHENTICATED'), { status: 401, statusText: 'Unauthorized' });
    expect(await result).toBeNull();
    expect(service.currentUser()).toBeNull();
    expect(service.wasSignedInThisTab).toBe(false);
  });

  it('loadMe resolves null on a network error', async () => {
    const result = firstValueFrom(service.loadMe());
    http.expectOne('/api/auth/me').error(new ProgressEvent('error'));
    expect(await result).toBeNull();
    expect(service.isSignedIn()).toBe(false);
  });

  it('login posts the credentials and sets the user', async () => {
    const result = firstValueFrom(
      service.login({ email: 'bob@example.com', password: 'secret12' }),
    );
    const req = http.expectOne('/api/auth/login');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ email: 'bob@example.com', password: 'secret12' });
    req.flush(BOB);
    expect(await result).toEqual(BOB);
    expect(service.currentUser()).toEqual(BOB);
  });

  it('register posts the request and sets the user', async () => {
    const body = { displayName: 'Bob', email: 'bob@example.com', password: 'secret12' };
    const result = firstValueFrom(service.register(body));
    const req = http.expectOne('/api/auth/register');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual(body);
    req.flush(BOB, { status: 201, statusText: 'Created' });
    expect(await result).toEqual(BOB);
    expect(service.isSignedIn()).toBe(true);
  });

  it('login error propagates and leaves the user signed out', async () => {
    const result = firstValueFrom(service.login({ email: 'a@b.co', password: 'x' }));
    http
      .expectOne('/api/auth/login')
      .flush(problem(401, 'INVALID_CREDENTIALS'), { status: 401, statusText: 'Unauthorized' });
    await expect(result).rejects.toMatchObject({ status: 401 });
    expect(service.isSignedIn()).toBe(false);
  });

  it('on 403 CSRF_INVALID refreshes via GET /me once and retries', async () => {
    const result = firstValueFrom(service.login({ email: 'a@b.co', password: 'x' }));
    http
      .expectOne('/api/auth/login')
      .flush(problem(403, 'CSRF_INVALID'), { status: 403, statusText: 'Forbidden' });
    http
      .expectOne('/api/auth/me')
      .flush(problem(401, 'UNAUTHENTICATED'), { status: 401, statusText: 'Unauthorized' });
    http.expectOne('/api/auth/login').flush(BOB);
    expect(await result).toEqual(BOB);
  });

  it('gives up after one CSRF retry', async () => {
    const result = firstValueFrom(service.login({ email: 'a@b.co', password: 'x' }));
    const csrf = problem(403, 'CSRF_INVALID');
    http.expectOne('/api/auth/login').flush(csrf, { status: 403, statusText: 'Forbidden' });
    http.expectOne('/api/auth/me').flush(BOB);
    http.expectOne('/api/auth/login').flush(csrf, { status: 403, statusText: 'Forbidden' });
    await expect(result).rejects.toMatchObject({ status: 403 });
    http.expectNone('/api/auth/me');
  });

  it('logout clears the user on success', async () => {
    service.loadMe().subscribe();
    http.expectOne('/api/auth/me').flush(BOB);
    const result = firstValueFrom(service.logout(), { defaultValue: undefined });
    const req = http.expectOne('/api/auth/logout');
    expect(req.request.method).toBe('POST');
    req.flush(null, { status: 204, statusText: 'No Content' });
    await result;
    expect(service.isSignedIn()).toBe(false);
    expect(service.wasSignedInThisTab).toBe(false);
  });

  it('logout clears the user even when the call fails', async () => {
    service.loadMe().subscribe();
    http.expectOne('/api/auth/me').flush(BOB);
    const result = firstValueFrom(service.logout());
    http.expectOne('/api/auth/logout').error(new ProgressEvent('error'));
    await expect(result).rejects.toBeTruthy();
    expect(service.isSignedIn()).toBe(false);
  });

  it('handleSessionLost flags expiry only if signed in during this tab', () => {
    service.handleSessionLost();
    expect(service.sessionExpired()).toBe(false);

    service.loadMe().subscribe();
    http.expectOne('/api/auth/me').flush(BOB);
    service.handleSessionLost();
    expect(service.sessionExpired()).toBe(true);
    expect(service.isSignedIn()).toBe(false);

    service.clearSessionExpired();
    expect(service.sessionExpired()).toBe(false);
  });

  it('updateCurrentUser replaces the signed-in user', () => {
    service.loadMe().subscribe();
    http.expectOne('/api/auth/me').flush(BOB);
    service.updateCurrentUser({ ...BOB, displayName: 'Bobby' });
    expect(service.currentUser()?.displayName).toBe('Bobby');
    expect(service.isSignedIn()).toBe(true);
  });

  it('updateCurrentUser does nothing when signed out', () => {
    service.updateCurrentUser(BOB);
    expect(service.isSignedIn()).toBe(false);
  });

  it('handleAccountDeleted signs out locally and flags the deletion, not an expiry', () => {
    service.loadMe().subscribe();
    http.expectOne('/api/auth/me').flush(BOB);
    service.handleAccountDeleted();
    expect(service.isSignedIn()).toBe(false);
    expect(service.wasSignedInThisTab).toBe(false);
    expect(service.accountDeleted()).toBe(true);
    expect(service.sessionExpired()).toBe(false);

    service.clearAccountDeleted();
    expect(service.accountDeleted()).toBe(false);
  });

  it('signing in again clears the deleted flag', async () => {
    service.handleAccountDeleted();
    const result = firstValueFrom(service.login({ email: 'a@b.co', password: 'x' }));
    http.expectOne('/api/auth/login').flush(BOB);
    await result;
    expect(service.accountDeleted()).toBe(false);
  });

  describe('email links', () => {
    const cases = [
      ['verifyEmail', '/api/auth/verify-email', { token: 'tok-1' }],
      ['forgotPassword', '/api/auth/forgot-password', { email: 'bob@example.com' }],
      ['checkResetToken', '/api/auth/check-reset-token', { token: 'tok-2' }],
      ['resetPassword', '/api/auth/reset-password', { token: 'tok-3', newPassword: 'secret12' }],
    ] as const;

    function callMethod(method: (typeof cases)[number][0], body: object) {
      switch (method) {
        case 'verifyEmail':
          return service.verifyEmail(body as { token: string });
        case 'forgotPassword':
          return service.forgotPassword(body as { email: string });
        case 'checkResetToken':
          return service.checkResetToken(body as { token: string });
        case 'resetPassword':
          return service.resetPassword(body as { token: string; newPassword: string });
      }
    }

    it.each(cases)('%s POSTs the body to %s and completes', async (method, url, body) => {
      const result = firstValueFrom(callMethod(method, body), { defaultValue: 'none' });
      const req = http.expectOne(url);
      expect(req.request.method).toBe('POST');
      expect(req.request.body).toEqual(body);
      const status = method === 'forgotPassword' ? 202 : 204;
      req.flush(null, { status, statusText: 'x' });
      expect(await result).toBeUndefined();
    });

    it.each(cases)(
      '%s refreshes the CSRF token once on 403 and retries',
      async (method, url, body) => {
        const result = firstValueFrom(callMethod(method, body), { defaultValue: 'none' });
        http.expectOne(url).flush(problem(403, 'CSRF_INVALID'), { status: 403, statusText: 'x' });
        http
          .expectOne('/api/auth/me')
          .flush(problem(401, 'UNAUTHENTICATED'), { status: 401, statusText: 'x' });
        http.expectOne(url).flush(null, { status: 204, statusText: 'x' });
        expect(await result).toBeUndefined();
      },
    );

    it.each(cases.filter(([m]) => m !== 'forgotPassword'))(
      '%s surfaces 400 TOKEN_INVALID as an error',
      async (method, url, body) => {
        const result = firstValueFrom(callMethod(method, body));
        http.expectOne(url).flush(problem(400, 'TOKEN_INVALID'), { status: 400, statusText: 'x' });
        await expect(result).rejects.toMatchObject({ status: 400 });
        http.expectNone('/api/auth/me');
      },
    );

    it('verifyEmail and checkResetToken leave the signed-in user unchanged', async () => {
      service.loadMe().subscribe();
      http.expectOne('/api/auth/me').flush({ ...BOB, emailVerified: false });
      const verify = firstValueFrom(service.verifyEmail({ token: 't' }), { defaultValue: 0 });
      http.expectOne('/api/auth/verify-email').flush(null, { status: 204, statusText: 'x' });
      await verify;
      const check = firstValueFrom(service.checkResetToken({ token: 't' }), { defaultValue: 0 });
      http.expectOne('/api/auth/check-reset-token').flush(null, { status: 204, statusText: 'x' });
      await check;
      expect(service.currentUser()).toEqual({ ...BOB, emailVerified: false });
    });

    it('resetPassword on 204 signs out locally and flags the reset, without calling /me', async () => {
      service.loadMe().subscribe();
      http.expectOne('/api/auth/me').flush(BOB);
      const result = firstValueFrom(
        service.resetPassword({ token: 't', newPassword: 'secret12' }),
        { defaultValue: 'none' },
      );
      http.expectOne('/api/auth/reset-password').flush(null, { status: 204, statusText: 'x' });
      await result;
      expect(service.isSignedIn()).toBe(false);
      expect(service.wasSignedInThisTab).toBe(false);
      expect(service.passwordReset()).toBe(true);
      expect(service.sessionExpired()).toBe(false);
      expect(service.accountDeleted()).toBe(false);
      http.expectNone('/api/auth/me');
    });

    it('resetPassword keeps the user and sets no flag on 400', async () => {
      service.loadMe().subscribe();
      http.expectOne('/api/auth/me').flush(BOB);
      const result = firstValueFrom(service.resetPassword({ token: 't', newPassword: 'x' }));
      http
        .expectOne('/api/auth/reset-password')
        .flush(problem(400, 'VALIDATION_FAILED'), { status: 400, statusText: 'x' });
      await expect(result).rejects.toMatchObject({ status: 400 });
      expect(service.isSignedIn()).toBe(true);
      expect(service.passwordReset()).toBe(false);
    });
  });

  it('handlePasswordReset flags the reset; clearPasswordReset and signing in clear it', async () => {
    service.handlePasswordReset();
    expect(service.passwordReset()).toBe(true);
    service.clearPasswordReset();
    expect(service.passwordReset()).toBe(false);

    service.handlePasswordReset();
    const result = firstValueFrom(service.login({ email: 'a@b.co', password: 'x' }));
    http.expectOne('/api/auth/login').flush(BOB);
    await result;
    expect(service.passwordReset()).toBe(false);
  });

  it('refreshMe replaces the signed-in user with the fresh one', async () => {
    service.loadMe().subscribe();
    http.expectOne('/api/auth/me').flush({ ...BOB, emailVerified: false });
    const result = firstValueFrom(service.refreshMe());
    http.expectOne('/api/auth/me').flush(BOB);
    expect(await result).toEqual(BOB);
    expect(service.currentUser()?.emailVerified).toBe(true);
  });

  it('refreshMe never signs anyone in', async () => {
    const result = firstValueFrom(service.refreshMe());
    http.expectOne('/api/auth/me').flush(BOB);
    await result;
    expect(service.isSignedIn()).toBe(false);
  });

  it('refreshMe resolves null on an error and keeps the current user', async () => {
    service.loadMe().subscribe();
    http.expectOne('/api/auth/me').flush({ ...BOB, emailVerified: false });
    const result = firstValueFrom(service.refreshMe());
    http.expectOne('/api/auth/me').error(new ProgressEvent('error'));
    expect(await result).toBeNull();
    expect(service.currentUser()).toEqual({ ...BOB, emailVerified: false });
  });

  describe('providers', () => {
    it.each([true, false])(
      'returns { google: %s } from GET /api/auth/providers',
      async (google) => {
        const result = firstValueFrom(service.providers());
        const req = http.expectOne('/api/auth/providers');
        expect(req.request.method).toBe('GET');
        req.flush({ google });
        expect(await result).toEqual({ google });
      },
    );

    it.each([
      ['network error', 0],
      ['server error', 500],
      ['not found', 404],
    ])('counts a %s as google: false', async (_label, status) => {
      const result = firstValueFrom(service.providers());
      const req = http.expectOne('/api/auth/providers');
      if (status === 0) {
        req.error(new ProgressEvent('error'));
      } else {
        req.flush(problem(status, 'INTERNAL_ERROR'), { status, statusText: 'x' });
      }
      expect(await result).toEqual({ google: false });
    });

    it('counts a malformed body as google: false', async () => {
      const result = firstValueFrom(service.providers());
      http.expectOne('/api/auth/providers').flush({});
      expect(await result).toEqual({ google: false });
    });
  });

  describe('confirmSession', () => {
    beforeEach(() => {
      service.loadMe().subscribe();
      http.expectOne('/api/auth/me').flush(BOB);
    });

    it('emits true on 200 and refreshes the signed-in user', async () => {
      const result = firstValueFrom(service.confirmSession());
      const req = http.expectOne('/api/auth/me');
      expect(req.request.method).toBe('GET');
      const updated = {
        ...BOB,
        connections: [{ provider: 'google' as const, email: 'b@gmail.com' }],
      };
      req.flush(updated);
      expect(await result).toBe(true);
      expect(service.currentUser()).toEqual(updated);
    });

    it('emits false on 401 and signs the tab out (session expired)', async () => {
      const result = firstValueFrom(service.confirmSession());
      http
        .expectOne('/api/auth/me')
        .flush(problem(401, 'UNAUTHENTICATED'), { status: 401, statusText: 'Unauthorized' });
      expect(await result).toBe(false);
      expect(service.isSignedIn()).toBe(false);
      expect(service.sessionExpired()).toBe(true);
    });

    it.each([
      ['network error', 0],
      ['server error', 500],
    ])('re-throws a %s and keeps the user', async (_label, status) => {
      const result = firstValueFrom(service.confirmSession());
      const req = http.expectOne('/api/auth/me');
      if (status === 0) {
        req.error(new ProgressEvent('error'));
      } else {
        req.flush(problem(500, 'INTERNAL_ERROR'), { status, statusText: 'x' });
      }
      await expect(result).rejects.toMatchObject({ status });
      expect(service.currentUser()).toEqual(BOB);
      expect(service.sessionExpired()).toBe(false);
    });
  });
  describe('roles and disabled accounts', () => {
    it.each([
      ['USER', false],
      ['ADMIN', true],
      ['SUPER_ADMIN', true],
    ] as const)('isAdmin is %s -> %s', (role, expected) => {
      service.loadMe().subscribe();
      http.expectOne('/api/auth/me').flush({ ...BOB, role });
      expect(service.isAdmin()).toBe(expected);
    });

    it('isAdmin is false when signed out', () => {
      expect(service.isAdmin()).toBe(false);
    });

    it('handleAccountDisabled signs out locally and flags it (not an expiry)', () => {
      service.loadMe().subscribe();
      http.expectOne('/api/auth/me').flush(BOB);
      service.handleAccountDisabled();
      expect(service.isSignedIn()).toBe(false);
      expect(service.accountDisabled()).toBe(true);
      expect(service.sessionExpired()).toBe(false);
      expect(service.wasSignedInThisTab).toBe(false);

      // A later plain session loss does not hide the disabled message or add an expiry.
      service.handleSessionLost();
      expect(service.accountDisabled()).toBe(true);
      expect(service.sessionExpired()).toBe(false);

      service.clearAccountDisabled();
      expect(service.accountDisabled()).toBe(false);
    });

    it('signing in again clears the disabled flag', async () => {
      service.handleAccountDisabled();
      const result = firstValueFrom(service.login({ email: 'a@b.co', password: 'x' }));
      http.expectOne('/api/auth/login').flush(BOB);
      await result;
      expect(service.accountDisabled()).toBe(false);
    });

    it('login surfaces 403 ACCOUNT_DISABLED without a CSRF retry', async () => {
      const result = firstValueFrom(service.login({ email: 'a@b.co', password: 'x' }));
      http
        .expectOne('/api/auth/login')
        .flush(problem(403, 'ACCOUNT_DISABLED'), { status: 403, statusText: 'Forbidden' });
      await expect(result).rejects.toMatchObject({ status: 403 });
      http.expectNone('/api/auth/me');
      http.expectNone('/api/auth/login');
      expect(service.isSignedIn()).toBe(false);
    });

    it('confirmSession flags a disabled account on 401 ACCOUNT_DISABLED', async () => {
      service.loadMe().subscribe();
      http.expectOne('/api/auth/me').flush(BOB);
      const result = firstValueFrom(service.confirmSession());
      http
        .expectOne('/api/auth/me')
        .flush(problem(401, 'ACCOUNT_DISABLED'), { status: 401, statusText: 'x' });
      expect(await result).toBe(false);
      expect(service.accountDisabled()).toBe(true);
      expect(service.sessionExpired()).toBe(false);
    });
  });

  describe('syncSession', () => {
    beforeEach(() => {
      service.loadMe().subscribe();
      http.expectOne('/api/auth/me').flush(BOB);
    });

    it('replaces the user with the fresh one (e.g. a new role)', async () => {
      const result = firstValueFrom(service.syncSession(), { defaultValue: 'none' });
      const req = http.expectOne('/api/auth/me');
      expect(req.request.method).toBe('GET');
      req.flush({ ...BOB, role: 'ADMIN' });
      expect(await result).toBeUndefined();
      expect(service.currentUser()?.role).toBe('ADMIN');
      expect(service.isAdmin()).toBe(true);
    });

    it('signs the tab out as expired on 401 UNAUTHENTICATED', async () => {
      const result = firstValueFrom(service.syncSession(), { defaultValue: 'none' });
      http
        .expectOne('/api/auth/me')
        .flush(problem(401, 'UNAUTHENTICATED'), { status: 401, statusText: 'x' });
      await result;
      expect(service.isSignedIn()).toBe(false);
      expect(service.sessionExpired()).toBe(true);
      expect(service.accountDisabled()).toBe(false);
    });

    it('signs the tab out as disabled on 401 ACCOUNT_DISABLED', async () => {
      const result = firstValueFrom(service.syncSession(), { defaultValue: 'none' });
      http
        .expectOne('/api/auth/me')
        .flush(problem(401, 'ACCOUNT_DISABLED'), { status: 401, statusText: 'x' });
      await result;
      expect(service.isSignedIn()).toBe(false);
      expect(service.accountDisabled()).toBe(true);
      expect(service.sessionExpired()).toBe(false);
    });

    it.each([
      ['network error', 0],
      ['server error', 500],
    ])('keeps the user on a %s and never errors', async (_label, status) => {
      const result = firstValueFrom(service.syncSession(), { defaultValue: 'none' });
      const req = http.expectOne('/api/auth/me');
      if (status === 0) {
        req.error(new ProgressEvent('error'));
      } else {
        req.flush(problem(500, 'INTERNAL_ERROR'), { status, statusText: 'x' });
      }
      expect(await result).toBeUndefined();
      expect(service.currentUser()).toEqual(BOB);
    });
  });

  it('syncSession never signs anyone in', async () => {
    const result = firstValueFrom(service.syncSession(), { defaultValue: 'none' });
    http.expectOne('/api/auth/me').flush(BOB);
    await result;
    expect(service.isSignedIn()).toBe(false);
  });
});
