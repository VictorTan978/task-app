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
  let navigateByUrl: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    handleSessionLost = vi.fn();
    navigateByUrl = vi.fn().mockResolvedValue(true);
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([authErrorInterceptor])),
        provideHttpClientTesting(),
        { provide: AuthService, useValue: { handleSessionLost } },
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

  it.each(['/api/auth/me', '/api/auth/login', '/api/auth/register'])('ignores 401 from %s', (url) => {
    call(url, 401, 'UNAUTHENTICATED');
    expect(handleSessionLost).not.toHaveBeenCalled();
    expect(navigateByUrl).not.toHaveBeenCalled();
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
});
