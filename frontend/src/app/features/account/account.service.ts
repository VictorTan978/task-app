import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { map, Observable, tap } from 'rxjs';
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

  /** PUT /api/account/display-name. On 200 the signed-in user is replaced with the response. */
  changeDisplayName(request: ChangeDisplayNameRequest): Observable<User> {
    return this.http
      .put<User>(ACCOUNT_API.displayName, request)
      .pipe(tap((user) => this.auth.updateCurrentUser(user)));
  }

  /** PUT /api/account/password. 204, the session stays signed in. */
  changePassword(request: ChangePasswordRequest): Observable<void> {
    return this.http.put<unknown>(ACCOUNT_API.password, request).pipe(map(() => undefined));
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
}
