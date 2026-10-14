import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { computed, inject, Injectable, signal } from '@angular/core';
import { catchError, map, Observable, of, switchMap, tap, throwError } from 'rxjs';
import { AUTH_API } from './auth-api';
import {
  ForgotPasswordRequest,
  LoginRequest,
  Providers,
  RegisterRequest,
  ResetPasswordRequest,
  TokenRequest,
  User,
} from './auth.models';
import { hasProblemCode } from './problem';

@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);

  private readonly user = signal<User | null>(null);
  private readonly expired = signal(false);
  private readonly deleted = signal(false);
  private readonly reset = signal(false);
  private signedInThisTab = false;

  /** The signed-in user, or null when signed out. */
  readonly currentUser = this.user.asReadonly();
  readonly isSignedIn = computed(() => this.user() !== null);
  /** True when the session expired after the user had signed in during this tab. */
  readonly sessionExpired = this.expired.asReadonly();
  /** True after this tab deleted the signed-in account (shown once on the login page). */
  readonly accountDeleted = this.deleted.asReadonly();
  /** True after this tab reset the password by email (shown once on the login page). */
  readonly passwordReset = this.reset.asReadonly();

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

  /**
   * Refetches the signed-in user (e.g. to pick up a new `emailVerified`). Never errors and never
   * signs anyone in: it only replaces the user when this tab is already signed in.
   */
  refreshMe(): Observable<User | null> {
    return this.http.get<User>(AUTH_API.me).pipe(
      tap((user) => this.updateCurrentUser(user)),
      catchError(() => of(null)),
    );
  }

  /**
   * GET /api/auth/providers: which external sign-in providers are configured. Never errors: any
   * failure counts as "not configured" (`google: false`), so the Google buttons stay hidden.
   */
  providers(): Observable<Providers> {
    return this.http.get<Providers>(AUTH_API.providers).pipe(
      map((providers) => ({ google: providers?.google === true })),
      catchError(() => of({ google: false })),
    );
  }

  /**
   * Checks the session right before a Google connect (GET /api/auth/me also restores an expired
   * session from the remember-me cookie). Emits true when signed in (the user is refreshed),
   * false on 401 (the tab is signed out via `handleSessionLost`; the caller goes to /login).
   * Any other failure (network, 5xx) is re-thrown.
   */
  confirmSession(): Observable<boolean> {
    return this.http.get<User>(AUTH_API.me).pipe(
      tap((user) => this.updateCurrentUser(user)),
      map(() => true),
      catchError((error: unknown) => {
        if (error instanceof HttpErrorResponse && error.status === 401) {
          this.handleSessionLost();
          return of(false);
        }
        return throwError(() => error);
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

  /** POST /api/auth/verify-email. Completes on 204; 400 TOKEN_INVALID surfaces as an error. */
  verifyEmail(request: TokenRequest): Observable<void> {
    return this.withCsrfRetry(() => this.http.post<unknown>(AUTH_API.verifyEmail, request)).pipe(
      map(() => undefined),
    );
  }

  /** POST /api/auth/forgot-password. Completes on 202 (always the same for a well-formed email). */
  forgotPassword(request: ForgotPasswordRequest): Observable<void> {
    return this.withCsrfRetry(() => this.http.post<unknown>(AUTH_API.forgotPassword, request)).pipe(
      map(() => undefined),
    );
  }

  /** POST /api/auth/check-reset-token. Completes on 204 without using the token up. */
  checkResetToken(request: TokenRequest): Observable<void> {
    return this.withCsrfRetry(() =>
      this.http.post<unknown>(AUTH_API.checkResetToken, request),
    ).pipe(map(() => undefined));
  }

  /**
   * POST /api/auth/reset-password. On 204 the server signed this browser out and set a fresh
   * XSRF-TOKEN, so the local user is cleared (no GET /me) and the reset is flagged for /login.
   */
  resetPassword(request: ResetPasswordRequest): Observable<void> {
    return this.withCsrfRetry(() => this.http.post<unknown>(AUTH_API.resetPassword, request)).pipe(
      map(() => undefined),
      tap(() => this.handlePasswordReset()),
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

  /** Replaces the signed-in user with fresh data from the server (e.g. after a display name change). */
  updateCurrentUser(user: User): void {
    if (this.user() !== null) {
      this.user.set(user);
    }
  }

  /**
   * Called after a successful account delete: the server already ended the session and set a
   * fresh XSRF-TOKEN, so only the local state is cleared (no GET /me needed).
   */
  handleAccountDeleted(): void {
    this.clearLocal();
    this.deleted.set(true);
  }

  clearAccountDeleted(): void {
    this.deleted.set(false);
  }

  /** Called after a successful password reset: clears the local user and flags the reset. */
  handlePasswordReset(): void {
    this.clearLocal();
    this.reset.set(true);
  }

  clearPasswordReset(): void {
    this.reset.set(false);
  }

  private setUser(user: User): void {
    this.user.set(user);
    this.signedInThisTab = true;
    this.expired.set(false);
    this.deleted.set(false);
    this.reset.set(false);
  }

  private clearLocal(): void {
    this.user.set(null);
    this.signedInThisTab = false;
    this.expired.set(false);
    this.deleted.set(false);
    this.reset.set(false);
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
