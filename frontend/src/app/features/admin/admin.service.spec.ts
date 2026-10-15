import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom, Observable } from 'rxjs';
import { AdminUser, AdminUserPage } from './admin.models';
import { AdminService } from './admin.service';

const ALICE: AdminUser = {
  id: 2,
  email: 'alice@example.com',
  displayName: 'Alice',
  createdAt: '2026-10-01T10:00:00Z',
  emailVerified: true,
  googleConnected: false,
  role: 'USER',
  disabled: false,
};

const PAGE: AdminUserPage = {
  content: [ALICE],
  page: 0,
  size: 20,
  totalElements: 1,
  totalPages: 1,
};

function problem(status: number, code: string) {
  return { type: 'about:blank', title: 'x', status, detail: 'x', instance: '/api', code };
}

describe('AdminService', () => {
  let service: AdminService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(AdminService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  describe('list', () => {
    it('GETs page and size, without q when blank', async () => {
      const result = firstValueFrom(service.list('   ', 0));
      const req = http.expectOne((r) => r.url === '/api/admin/users');
      expect(req.request.method).toBe('GET');
      expect(req.request.params.get('page')).toBe('0');
      expect(req.request.params.get('size')).toBe('20');
      expect(req.request.params.has('q')).toBe(false);
      req.flush(PAGE);
      expect(await result).toEqual(PAGE);
    });

    it('sends the trimmed q and the page', () => {
      service.list('  alice ', 3).subscribe();
      const req = http.expectOne((r) => r.url === '/api/admin/users');
      expect(req.request.params.get('q')).toBe('alice');
      expect(req.request.params.get('page')).toBe('3');
      req.flush(PAGE);
    });

    it('surfaces errors without retrying', async () => {
      const result = firstValueFrom(service.list('', 0));
      http
        .expectOne((r) => r.url === '/api/admin/users')
        .flush(problem(403, 'FORBIDDEN'), { status: 403, statusText: 'x' });
      await expect(result).rejects.toMatchObject({ status: 403 });
      http.expectNone('/api/auth/me');
    });
  });

  const changes: [string, () => Observable<unknown>, string, string, unknown][] = [
    ['disable', () => service.disable(2), 'POST', '/api/admin/users/2/disable', null],
    ['enable', () => service.enable(2), 'POST', '/api/admin/users/2/enable', null],
    [
      'revokeSessions',
      () => service.revokeSessions(2),
      'POST',
      '/api/admin/users/2/revoke-sessions',
      null,
    ],
    [
      'changeRole ADMIN',
      () => service.changeRole(2, 'ADMIN'),
      'PUT',
      '/api/admin/users/2/role',
      { role: 'ADMIN' },
    ],
    [
      'changeRole USER',
      () => service.changeRole(2, 'USER'),
      'PUT',
      '/api/admin/users/2/role',
      { role: 'USER' },
    ],
  ];

  it.each(changes)(
    '%s sends %s %s and returns the AdminUser',
    async (_n, call, method, url, body) => {
      const result = firstValueFrom(call());
      const req = http.expectOne(url);
      expect(req.request.method).toBe(method);
      expect(req.request.body).toEqual(body);
      req.flush(ALICE);
      expect(await result).toEqual(ALICE);
    },
  );

  it('delete sends DELETE and completes on 204', async () => {
    const result = firstValueFrom(service.delete(2), { defaultValue: 'none' });
    const req = http.expectOne('/api/admin/users/2');
    expect(req.request.method).toBe('DELETE');
    req.flush(null, { status: 204, statusText: 'No Content' });
    expect(await result).toBeUndefined();
  });

  it.each(changes)(
    '%s refreshes the CSRF token once on 403 CSRF_INVALID and retries',
    async (_n, call, _m, url) => {
      const result = firstValueFrom(call());
      http.expectOne(url).flush(problem(403, 'CSRF_INVALID'), { status: 403, statusText: 'x' });
      http
        .expectOne('/api/auth/me')
        .flush(problem(401, 'UNAUTHENTICATED'), { status: 401, statusText: 'x' });
      http.expectOne(url).flush(ALICE);
      expect(await result).toEqual(ALICE);
    },
  );

  it('gives up after one CSRF retry', async () => {
    const result = firstValueFrom(service.delete(2));
    const csrf = problem(403, 'CSRF_INVALID');
    http.expectOne('/api/admin/users/2').flush(csrf, { status: 403, statusText: 'x' });
    http.expectOne('/api/auth/me').flush(null, { status: 401, statusText: 'x' });
    http.expectOne('/api/admin/users/2').flush(csrf, { status: 403, statusText: 'x' });
    await expect(result).rejects.toMatchObject({ status: 403 });
    http.expectNone('/api/auth/me');
  });

  it.each([
    [403, 'INSUFFICIENT_ROLE'],
    [403, 'FORBIDDEN'],
    [404, 'USER_NOT_FOUND'],
    [409, 'CANNOT_MODIFY_SELF'],
    [429, 'RATE_LIMITED'],
  ])('surfaces %s %s without retrying', async (status, code) => {
    const result = firstValueFrom(service.disable(2));
    http
      .expectOne('/api/admin/users/2/disable')
      .flush(problem(status, code), { status, statusText: 'x' });
    await expect(result).rejects.toMatchObject({ status });
    http.expectNone('/api/auth/me');
    http.expectNone('/api/admin/users/2/disable');
  });
});
