import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActivatedRouteSnapshot, Router, RouterStateSnapshot, UrlTree } from '@angular/router';
import { authGuard, guestGuard } from './auth.guards';
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

  it('guestGuard allows signed-out users', () => {
    signedIn.set(false);
    expect(run(guestGuard)).toBe(true);
  });

  it('guestGuard redirects signed-in users to /tasks', () => {
    signedIn.set(true);
    expect(urlOf(run(guestGuard))).toBe('/tasks');
  });
});
