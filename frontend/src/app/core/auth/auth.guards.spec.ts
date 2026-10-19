import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActivatedRouteSnapshot, Router, RouterStateSnapshot, UrlTree } from '@angular/router';
import { firstValueFrom, Observable, of } from 'rxjs';
import { adminGuard, authGuard, guestGuard } from './auth.guards';
import { AuthService } from './auth.service';

describe('auth guards', () => {
  const signedIn = signal(false);

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [{ provide: AuthService, useValue: { isSignedIn: signedIn } }],
    });
  });

  function run(guard: typeof authGuard) {
    return TestBed.runInInjectionContext(() =>
      guard({} as ActivatedRouteSnapshot, {} as RouterStateSnapshot),
    );
  }

  function urlOf(result: unknown): string {
    expect(result).toBeInstanceOf(UrlTree);
    return TestBed.inject(Router).serializeUrl(result as UrlTree);
  }

  it('authGuard allows signed-in users', () => {
    signedIn.set(true);
    expect(run(authGuard)).toBe(true);
  });

  it('authGuard redirects signed-out users to /login', () => {
    signedIn.set(false);
    expect(urlOf(run(authGuard))).toBe('/login');
  });

  describe('invitation link (sign-in redirect to /invitations only)', () => {
    const KEY = 'taskapp.afterSignIn';
    const runAt = (url: string) =>
      TestBed.runInInjectionContext(() =>
        authGuard({} as ActivatedRouteSnapshot, { url } as RouterStateSnapshot),
      );

    beforeEach(() => sessionStorage.clear());
    afterEach(() => sessionStorage.clear());

    it('a signed-out visit to /invitations is remembered; other pages are not', () => {
      signedIn.set(false);
      expect(urlOf(runAt('/tasks'))).toBe('/login');
      expect(sessionStorage.getItem(KEY)).toBeNull();
      expect(urlOf(runAt('/invitations'))).toBe('/login');
      expect(sessionStorage.getItem(KEY)).toBe('/invitations');
    });

    it('the first signed-in entry goes to /invitations once, then normally', () => {
      signedIn.set(false);
      runAt('/invitations');
      signedIn.set(true);
      expect(urlOf(runAt('/tasks'))).toBe('/invitations');
      expect(sessionStorage.getItem(KEY)).toBeNull();
      expect(runAt('/tasks')).toBe(true);
    });

    it('landing on /invitations itself just clears it', () => {
      sessionStorage.setItem(KEY, '/invitations');
      signedIn.set(true);
      expect(runAt('/invitations?x=1')).toBe(true);
      expect(sessionStorage.getItem(KEY)).toBeNull();
    });

    it('ignores any other stored path (no open redirect)', () => {
      sessionStorage.setItem(KEY, '//evil.example.com');
      signedIn.set(true);
      expect(runAt('/tasks')).toBe(true);
      expect(sessionStorage.getItem(KEY)).toBeNull();
    });
  });

  it('guestGuard allows signed-out users', () => {
    signedIn.set(false);
    expect(run(guestGuard)).toBe(true);
  });

  it('guestGuard redirects signed-in users to /tasks', () => {
    signedIn.set(true);
    expect(urlOf(run(guestGuard))).toBe('/tasks');
  });
});

describe('adminGuard', () => {
  const signedIn = signal(true);
  const admin = signal(false);
  let syncSession: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    signedIn.set(true);
    admin.set(false);
    syncSession = vi.fn(() => of(undefined));
    TestBed.configureTestingModule({
      providers: [
        { provide: AuthService, useValue: { isSignedIn: signedIn, isAdmin: admin, syncSession } },
      ],
    });
  });

  async function run(): Promise<unknown> {
    const result = TestBed.runInInjectionContext(() =>
      adminGuard({} as ActivatedRouteSnapshot, {} as RouterStateSnapshot),
    );
    return firstValueFrom(result as Observable<unknown>);
  }

  function urlOf(result: unknown): string {
    expect(result).toBeInstanceOf(UrlTree);
    return TestBed.inject(Router).serializeUrl(result as UrlTree);
  }

  it('re-reads the user, then allows admins', async () => {
    admin.set(true);
    expect(await run()).toBe(true);
    expect(syncSession).toHaveBeenCalledTimes(1);
  });

  it('checks the role after the refresh (a role raised meanwhile counts)', async () => {
    syncSession.mockImplementation(() => {
      admin.set(true);
      return of(undefined);
    });
    expect(await run()).toBe(true);
  });

  it('sends normal users to /tasks', async () => {
    expect(urlOf(await run())).toBe('/tasks');
  });

  it('sends users signed out by the refresh to /login', async () => {
    syncSession.mockImplementation(() => {
      signedIn.set(false);
      return of(undefined);
    });
    expect(urlOf(await run())).toBe('/login');
  });
});
