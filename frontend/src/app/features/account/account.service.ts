import { HttpClient } from '@angular/common/http';
import { computed, inject, Injectable, signal } from '@angular/core';
import { defer, finalize, map, Observable, tap } from 'rxjs';
import { User } from '../../core/auth/auth.models';
import { AuthService } from '../../core/auth/auth.service';
import { ACCOUNT_API } from './account-api';
import {
  ChangeDisplayNameRequest,
  ChangePasswordRequest,
  DeleteAccountRequest,
} from './account.models';

/**
 * Typed HTTP access to `/api/account/*`. Keeps the signed-in user in AuthService up to date.
 * No CSRF retry (same as TasksService): a 403 surfaces as an error.
 */
@Injectable({ providedIn: 'root' })
export class AccountService {
  private readonly http = inject(HttpClient);
  private readonly auth = inject(AuthService);
  private readonly renewing = signal(0);

  /**
   * True while a call that gives this session a new id is in flight (change password, log out
   * other sessions). The account page sends no other request until it ends: a request still
   * carrying the old session id would get 401.
   */
  readonly sessionRenewing = computed(() => this.renewing() > 0);

  /** PUT /api/account/display-name. On 200 the signed-in user is replaced with the response. */
  changeDisplayName(request: ChangeDisplayNameRequest): Observable<User> {
    return this.http
      .put<User>(ACCOUNT_API.displayName, request)
      .pipe(tap((user) => this.auth.updateCurrentUser(user)));
  }

  /**
   * PUT /api/account/password. On 204 this device stays signed in, its session gets a new
   * JSESSIONID (the XSRF-TOKEN is unchanged) and all other sessions and remember-me logins of
   * the user are ended.
   */
  changePassword(request: ChangePasswordRequest): Observable<void> {
    return this.renewingSession(
      this.http.put<unknown>(ACCOUNT_API.password, request).pipe(map(() => undefined)),
    );
  }

  /**
   * POST /api/account/logout-other-sessions (no body). On 204 every other session and
   * remember-me login of the user is ended; this device stays signed in with a new JSESSIONID
   * (the XSRF-TOKEN is unchanged).
   */
  logoutOtherSessions(): Observable<void> {
    return this.renewingSession(
      this.http.post<unknown>(ACCOUNT_API.logoutOtherSessions, null).pipe(map(() => undefined)),
    );
  }

  /**
   * POST /api/account/delete. On 204 the server has ended the session and set a fresh
   * XSRF-TOKEN, so the local user is cleared without calling GET /me.
   */
  deleteAccount(request: DeleteAccountRequest): Observable<void> {
    return this.http.post<unknown>(ACCOUNT_API.delete, request).pipe(
      map(() => undefined),
      tap(() => this.auth.handleAccountDeleted()),
    );
  }

  /**
   * POST /api/account/resend-verification (no body). 204 means a new confirmation email is
   * queued. 409 ALREADY_VERIFIED and 429 surface as errors; the caller decides what to show.
   */
  resendVerification(): Observable<void> {
    return this.http.post<unknown>(ACCOUNT_API.resendVerification, null).pipe(map(() => undefined));
  }

  /** Marks `sessionRenewing` while the request runs (from subscribe until it ends). */
  private renewingSession<T>(request: Observable<T>): Observable<T> {
    return defer(() => {
      this.renewing.update((n) => n + 1);
      return request.pipe(finalize(() => this.renewing.update((n) => n - 1)));
    });
  }
}
