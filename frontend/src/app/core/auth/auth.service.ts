import { HttpClient } from '@angular/common/http';
import { computed, inject, Injectable, signal } from '@angular/core';
import { catchError, map, Observable, of, switchMap, tap, throwError } from 'rxjs';
import { AUTH_API } from './auth-api';
import { LoginRequest, RegisterRequest, User } from './auth.models';
import { hasProblemCode } from './problem';

@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);

  private readonly user = signal<User | null>(null);
  private readonly expired = signal(false);
  private signedInThisTab = false;

  /** The signed-in user, or null when signed out. */
  readonly currentUser = this.user.asReadonly();
  readonly isSignedIn = computed(() => this.user() !== null);
  /** True when the session expired after the user had signed in during this tab. */
  readonly sessionExpired = this.expired.asReadonly();

  /** True once a user was signed in at any point during this tab's lifetime. */
  get wasSignedInThisTab(): boolean {
    return this.signedInThisTab;
  }

  /**
   * Loads the current user (also obtains the XSRF-TOKEN cookie).
   * Never errors: 401 or a network/server error leaves the app signed out.
   */
  loadMe(): Observable<User | null> {
    return this.http.get<User>(AUTH_API.me).pipe(
      tap((user) => this.setUser(user)),
      catchError(() => {
        this.user.set(null);
        return of(null);
      }),
    );
  }

  register(request: RegisterRequest): Observable<User> {
    return this.withCsrfRetry(() => this.http.post<User>(AUTH_API.register, request)).pipe(
      tap((user) => this.setUser(user)),
    );
  }

  login(request: LoginRequest): Observable<User> {
    return this.withCsrfRetry(() => this.http.post<User>(AUTH_API.login, request)).pipe(
      tap((user) => this.setUser(user)),
    );
  }

  /** Signs out on the server. The local user is cleared whether or not the call succeeds. */
  logout(): Observable<void> {
    return this.withCsrfRetry(() => this.http.post<void>(AUTH_API.logout, null)).pipe(
      map(() => undefined),
      tap({
        next: () => this.clearLocal(),
        error: () => this.clearLocal(),
      }),
    );
  }

  /** Called when an API call reports the session is gone (401 UNAUTHENTICATED). */
  handleSessionLost(): void {
    if (this.signedInThisTab) {
      this.expired.set(true);
    }
    this.signedInThisTab = false;
    this.user.set(null);
  }

  clearSessionExpired(): void {
    this.expired.set(false);
  }

  private setUser(user: User): void {
    this.user.set(user);
    this.signedInThisTab = true;
    this.expired.set(false);
  }

  private clearLocal(): void {
    this.user.set(null);
    this.signedInThisTab = false;
    this.expired.set(false);
  }

  /** On 403 CSRF_INVALID: fetch a fresh XSRF-TOKEN via GET /me once, then retry once. */
  private withCsrfRetry<T>(request: () => Observable<T>): Observable<T> {
    return request().pipe(
      catchError((error: unknown) => {
        if (!hasProblemCode(error, 403, 'CSRF_INVALID')) {
          return throwError(() => error);
        }
        return this.http.get<User>(AUTH_API.me).pipe(
          catchError(() => of(null)),
          switchMap(() => request()),
        );
      }),
    );
  }
}
