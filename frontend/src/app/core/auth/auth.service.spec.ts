import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { User } from './auth.models';
import { AuthService } from './auth.service';

const BOB: User = { id: 1, email: 'bob@example.com', displayName: 'Bob' };

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
    const result = firstValueFrom(service.login({ email: 'bob@example.com', password: 'secret12' }));
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
});
