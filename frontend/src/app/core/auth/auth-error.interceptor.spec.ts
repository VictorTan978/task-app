import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { authErrorInterceptor } from './auth-error.interceptor';
import { AuthService } from './auth.service';

function problem(status: number, code: string) {
  return { type: 'about:blank', title: 'x', status, detail: 'x', instance: '/api', code };
}

describe('authErrorInterceptor', () => {
  let http: HttpClient;
  let controller: HttpTestingController;
  let handleSessionLost: ReturnType<typeof vi.fn>;
  let handleAccountDisabled: ReturnType<typeof vi.fn>;
  let navigateByUrl: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    handleSessionLost = vi.fn();
    handleAccountDisabled = vi.fn();
    navigateByUrl = vi.fn().mockResolvedValue(true);
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([authErrorInterceptor])),
        provideHttpClientTesting(),
        { provide: AuthService, useValue: { handleSessionLost, handleAccountDisabled } },
        { provide: Router, useValue: { navigateByUrl } },
      ],
    });
    http = TestBed.inject(HttpClient);
    controller = TestBed.inject(HttpTestingController);
  });

  afterEach(() => controller.verify());

  function call(url: string, status: number, code: string): unknown {
    let received: unknown;
    http.get(url).subscribe({ error: (e: unknown) => (received = e) });
    controller.expectOne(url).flush(problem(status, code), { status, statusText: 'x' });
    return received;
  }

  it('on 401 UNAUTHENTICATED from another API: clears session, goes to /login, rethrows', () => {
    const error = call('/api/tasks', 401, 'UNAUTHENTICATED');
    expect(handleSessionLost).toHaveBeenCalledTimes(1);
    expect(navigateByUrl).toHaveBeenCalledWith('/login');
    expect(error).toMatchObject({ status: 401 });
  });

  it.each([
    '/api/auth/me',
    '/api/auth/login',
    '/api/auth/register',
    '/api/auth/verify-email',
    '/api/auth/forgot-password',
    '/api/auth/check-reset-token',
    '/api/auth/reset-password',
    '/api/auth/providers',
  ])('ignores 401 from %s', (url) => {
    call(url, 401, 'UNAUTHENTICATED');
    expect(handleSessionLost).not.toHaveBeenCalled();
    expect(navigateByUrl).not.toHaveBeenCalled();
  });

  it('treats 401 UNAUTHENTICATED from the Google disconnect as signed out', () => {
    let received: unknown;
    http
      .delete('/api/account/connections/google')
      .subscribe({ error: (e: unknown) => (received = e) });
    controller
      .expectOne('/api/account/connections/google')
      .flush(problem(401, 'UNAUTHENTICATED'), { status: 401, statusText: 'x' });
    expect(handleSessionLost).toHaveBeenCalledTimes(1);
    expect(navigateByUrl).toHaveBeenCalledWith('/login');
    expect(received).toMatchObject({ status: 401 });
  });

  it('treats 401 UNAUTHENTICATED from resend-verification as signed out', () => {
    const error = call('/api/account/resend-verification', 401, 'UNAUTHENTICATED');
    expect(handleSessionLost).toHaveBeenCalledTimes(1);
    expect(navigateByUrl).toHaveBeenCalledWith('/login');
    expect(error).toMatchObject({ status: 401 });
  });

  it.each(['/api/auth/login', '/api/auth/register', '/api/tasks'])(
    'never treats 429 RATE_LIMITED from %s as signed out, and rethrows it',
    (url) => {
      const error = call(url, 429, 'RATE_LIMITED');
      expect(handleSessionLost).not.toHaveBeenCalled();
      expect(navigateByUrl).not.toHaveBeenCalled();
      expect(error).toMatchObject({ status: 429 });
    },
  );

  it('ignores 401 with another code', () => {
    call('/api/tasks', 401, 'INVALID_CREDENTIALS');
    expect(handleSessionLost).not.toHaveBeenCalled();
  });

  it('never treats 403 CSRF_INVALID as signed out', () => {
    call('/api/tasks', 403, 'CSRF_INVALID');
    expect(handleSessionLost).not.toHaveBeenCalled();
    expect(navigateByUrl).not.toHaveBeenCalled();
  });
  it.each(['/api/tasks', '/api/admin/users', '/api/account/display-name'])(
    'on 401 ACCOUNT_DISABLED from %s: flags the disabled account, goes to /login, rethrows',
    (url) => {
      const error = call(url, 401, 'ACCOUNT_DISABLED');
      expect(handleAccountDisabled).toHaveBeenCalledTimes(1);
      expect(handleSessionLost).not.toHaveBeenCalled();
      expect(navigateByUrl).toHaveBeenCalledWith('/login');
      expect(error).toMatchObject({ status: 401 });
    },
  );

  it('on 401 ACCOUNT_DISABLED from GET /me: flags it but leaves navigation to the caller', () => {
    call('/api/auth/me', 401, 'ACCOUNT_DISABLED');
    expect(handleAccountDisabled).toHaveBeenCalledTimes(1);
    expect(navigateByUrl).not.toHaveBeenCalled();
  });

  it('never treats 403 ACCOUNT_DISABLED at login as a lost session', () => {
    let received: unknown;
    http.post('/api/auth/login', {}).subscribe({ error: (e: unknown) => (received = e) });
    controller
      .expectOne('/api/auth/login')
      .flush(problem(403, 'ACCOUNT_DISABLED'), { status: 403, statusText: 'x' });
    expect(handleAccountDisabled).not.toHaveBeenCalled();
    expect(handleSessionLost).not.toHaveBeenCalled();
    expect(navigateByUrl).not.toHaveBeenCalled();
    expect(received).toMatchObject({ status: 403 });
  });
});
