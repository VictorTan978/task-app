import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { User } from '../../core/auth/auth.models';
import { AuthService } from '../../core/auth/auth.service';
import { AccountService } from './account.service';

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

function problem(status: number, code: string, errors?: unknown[]) {
  return { type: 'about:blank', title: 'x', status, detail: 'x', instance: '/api', code, errors };
}

describe('AccountService', () => {
  let service: AccountService;
  let auth: AuthService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(AccountService);
    auth = TestBed.inject(AuthService);
    http = TestBed.inject(HttpTestingController);
    auth.loadMe().subscribe();
    http.expectOne('/api/auth/me').flush(BOB);
  });

  afterEach(() => http.verify());

  it('changeDisplayName sends PUT and updates the signed-in user', async () => {
    const result = firstValueFrom(service.changeDisplayName({ displayName: 'Bobby' }));
    const req = http.expectOne('/api/account/display-name');
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual({ displayName: 'Bobby' });
    req.flush({ ...BOB, displayName: 'Bobby' });
    expect((await result).displayName).toBe('Bobby');
    expect(auth.currentUser()?.displayName).toBe('Bobby');
  });

  it('changeDisplayName leaves the user unchanged on 409', async () => {
    const result = firstValueFrom(service.changeDisplayName({ displayName: 'jose' }));
    http
      .expectOne('/api/account/display-name')
      .flush(problem(409, 'DISPLAY_NAME_TAKEN'), { status: 409, statusText: 'Conflict' });
    await expect(result).rejects.toMatchObject({ status: 409 });
    expect(auth.currentUser()).toEqual(BOB);
  });

  it('changePassword sends PUT, completes on 204 and stays signed in', async () => {
    const body = { currentPassword: 'old-pass', newPassword: 'new-pass-1' };
    const result = firstValueFrom(service.changePassword(body), { defaultValue: 'none' });
    const req = http.expectOne('/api/account/password');
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual(body);
    req.flush(null, { status: 204, statusText: 'No Content' });
    expect(await result).toBeUndefined();
    expect(auth.isSignedIn()).toBe(true);
  });

  it('deleteAccount sends POST, clears the user and flags the deletion without calling /me', async () => {
    const result = firstValueFrom(service.deleteAccount({ password: 'secret12' }), {
      defaultValue: 'none',
    });
    const req = http.expectOne('/api/account/delete');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ password: 'secret12' });
    req.flush(null, { status: 204, statusText: 'No Content' });
    expect(await result).toBeUndefined();
    expect(auth.isSignedIn()).toBe(false);
    expect(auth.accountDeleted()).toBe(true);
    expect(auth.sessionExpired()).toBe(false);
    http.expectNone('/api/auth/me');
  });

  it('deleteAccount keeps the user on 400 WRONG_PASSWORD', async () => {
    const result = firstValueFrom(service.deleteAccount({ password: 'nope' }));
    http.expectOne('/api/account/delete').flush(problem(400, 'WRONG_PASSWORD'), {
      status: 400,
      statusText: 'Bad Request',
    });
    await expect(result).rejects.toMatchObject({ status: 400 });
    expect(auth.isSignedIn()).toBe(true);
    expect(auth.accountDeleted()).toBe(false);
  });

  it('does not retry on 403 CSRF_INVALID', async () => {
    const result = firstValueFrom(
      service.changePassword({ currentPassword: 'a', newPassword: 'b' }),
    );
    http
      .expectOne('/api/account/password')
      .flush(problem(403, 'CSRF_INVALID'), { status: 403, statusText: 'Forbidden' });
    await expect(result).rejects.toMatchObject({ status: 403 });
    http.expectNone('/api/auth/me');
    http.expectNone('/api/account/password');
  });

  it('logoutOtherSessions sends POST with no body, completes on 204 and stays signed in', async () => {
    const result = firstValueFrom(service.logoutOtherSessions(), { defaultValue: 'none' });
    const req = http.expectOne('/api/account/logout-other-sessions');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toBeNull();
    req.flush(null, { status: 204, statusText: 'No Content' });
    expect(await result).toBeUndefined();
    expect(auth.isSignedIn()).toBe(true);
    http.expectNone('/api/auth/me');
  });

  it('logoutOtherSessions does not retry on 403 CSRF_INVALID', async () => {
    const result = firstValueFrom(service.logoutOtherSessions());
    http
      .expectOne('/api/account/logout-other-sessions')
      .flush(problem(403, 'CSRF_INVALID'), { status: 403, statusText: 'Forbidden' });
    await expect(result).rejects.toMatchObject({ status: 403 });
    http.expectNone('/api/auth/me');
  });

  it.each([
    ['logoutOtherSessions', '/api/account/logout-other-sessions'],
    ['changePassword', '/api/account/password'],
  ] as const)('sessionRenewing is true only while %s is in flight', (method, url) => {
    expect(service.sessionRenewing()).toBe(false);
    const call =
      method === 'changePassword'
        ? service.changePassword({ currentPassword: 'a', newPassword: 'b' })
        : service.logoutOtherSessions();
    // Not before subscribe.
    expect(service.sessionRenewing()).toBe(false);
    call.subscribe({ error: () => undefined });
    expect(service.sessionRenewing()).toBe(true);
    http.expectOne(url).flush(problem(500, 'INTERNAL_ERROR'), { status: 500, statusText: 'x' });
    expect(service.sessionRenewing()).toBe(false);
  });

  it('sessionRenewing resets when the caller unsubscribes', () => {
    const sub = service.logoutOtherSessions().subscribe();
    expect(service.sessionRenewing()).toBe(true);
    sub.unsubscribe();
    expect(service.sessionRenewing()).toBe(false);
    http.expectOne('/api/account/logout-other-sessions');
  });

  it('changeDisplayName does not set sessionRenewing', () => {
    service.changeDisplayName({ displayName: 'Bobby' }).subscribe();
    expect(service.sessionRenewing()).toBe(false);
    http.expectOne('/api/account/display-name').flush(BOB);
  });

  it('resendVerification sends POST with no body and completes on 204', async () => {
    const result = firstValueFrom(service.resendVerification(), { defaultValue: 'none' });
    const req = http.expectOne('/api/account/resend-verification');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toBeNull();
    req.flush(null, { status: 204, statusText: 'No Content' });
    expect(await result).toBeUndefined();
    expect(auth.currentUser()).toEqual(BOB);
    expect(service.sessionRenewing()).toBe(false);
  });

  it.each([
    [409, 'ALREADY_VERIFIED'],
    [429, 'RATE_LIMITED'],
  ])('resendVerification surfaces %s %s as an error without retrying', async (status, code) => {
    const result = firstValueFrom(service.resendVerification());
    http
      .expectOne('/api/account/resend-verification')
      .flush(problem(status, code), { status, statusText: 'x' });
    await expect(result).rejects.toMatchObject({ status });
    http.expectNone('/api/account/resend-verification');
    http.expectNone('/api/auth/me');
  });

  describe('disconnectGoogle', () => {
    const URL = '/api/account/connections/google';

    it('sends DELETE with no body and updates the signed-in user', async () => {
      auth.updateCurrentUser({
        ...BOB,
        connections: [{ provider: 'google', email: 'b@gmail.com' }],
      });
      const result = firstValueFrom(service.disconnectGoogle());
      const req = http.expectOne(URL);
      expect(req.request.method).toBe('DELETE');
      expect(req.request.body).toBeNull();
      req.flush(BOB);
      expect(await result).toEqual(BOB);
      expect(auth.currentUser()?.connections).toEqual([]);
    });

    it.each([
      [404, 'NOT_FOUND'],
      [409, 'PASSWORD_REQUIRED'],
    ])('surfaces %s %s as an error and keeps the user', async (status, code) => {
      const before = {
        ...BOB,
        connections: [{ provider: 'google' as const, email: 'b@gmail.com' }],
      };
      auth.updateCurrentUser(before);
      const result = firstValueFrom(service.disconnectGoogle());
      http.expectOne(URL).flush(problem(status, code), { status, statusText: 'x' });
      await expect(result).rejects.toMatchObject({ status });
      expect(auth.currentUser()).toEqual(before);
    });
  });

  it('deleteAccount sends { email } for accounts without a password', async () => {
    const result = firstValueFrom(service.deleteAccount({ email: 'bob@gmail.com' }));
    const req = http.expectOne('/api/account/delete');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ email: 'bob@gmail.com' });
    req.flush(null, { status: 204, statusText: 'No Content' });
    await result;
    expect(auth.isSignedIn()).toBe(false);
  });
});

describe('AccountService time zone and reminder emails', () => {
  let service: AccountService;
  let http: HttpTestingController;
  let auth: AuthService;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(AccountService);
    http = TestBed.inject(HttpTestingController);
    auth = TestBed.inject(AuthService);
    auth.loadMe().subscribe();
    http.expectOne('/api/auth/me').flush(BOB);
  });

  afterEach(() => http.verify());

  it('PUTs the time zone and replaces the signed-in user', () => {
    service.setTimeZone({ timeZone: 'Asia/Tokyo' }).subscribe();
    const req = http.expectOne('/api/account/time-zone');
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual({ timeZone: 'Asia/Tokyo' });
    req.flush({ ...BOB, timeZone: 'Asia/Tokyo' });
    expect(auth.currentUser()?.timeZone).toBe('Asia/Tokyo');
  });

  it('PUTs reminder emails and replaces the signed-in user', () => {
    service.setReminderEmails({ enabled: false }).subscribe();
    const req = http.expectOne('/api/account/reminder-emails');
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual({ enabled: false });
    req.flush({ ...BOB, reminderEmails: false });
    expect(auth.currentUser()?.reminderEmails).toBe(false);
  });
});

describe('AccountService time zone and reminder emails', () => {
  let service: AccountService;
  let http: HttpTestingController;
  let auth: AuthService;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(AccountService);
    http = TestBed.inject(HttpTestingController);
    auth = TestBed.inject(AuthService);
    auth.loadMe().subscribe();
    http.expectOne('/api/auth/me').flush(BOB);
  });

  afterEach(() => http.verify());

  it('PUTs the time zone and replaces the signed-in user', () => {
    service.setTimeZone({ timeZone: 'Asia/Tokyo' }).subscribe();
    const req = http.expectOne('/api/account/time-zone');
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual({ timeZone: 'Asia/Tokyo' });
    req.flush({ ...BOB, timeZone: 'Asia/Tokyo' });
    expect(auth.currentUser()?.timeZone).toBe('Asia/Tokyo');
  });

  it('PUTs reminder emails and replaces the signed-in user', () => {
    service.setReminderEmails({ enabled: false }).subscribe();
    const req = http.expectOne('/api/account/reminder-emails');
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual({ enabled: false });
    req.flush({ ...BOB, reminderEmails: false });
    expect(auth.currentUser()?.reminderEmails).toBe(false);
  });
});
