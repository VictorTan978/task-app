import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { User } from '../../core/auth/auth.models';
import { AuthService } from '../../core/auth/auth.service';
import { AccountService } from './account.service';

const BOB: User = { id: 1, email: 'bob@example.com', displayName: 'Bob' };

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
});
