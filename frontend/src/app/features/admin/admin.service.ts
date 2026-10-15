import { HttpClient, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { catchError, map, Observable, switchMap, throwError } from 'rxjs';
import { AuthService } from '../../core/auth/auth.service';
import { hasProblemCode } from '../../core/auth/problem';
import { ADMIN_API } from './admin-api';
import { AdminUser, AdminUserPage, AssignableRole, ChangeRoleRequest } from './admin.models';

export const ADMIN_PAGE_SIZE = 20;
/** Longest search the API accepts (longer is 400 VALIDATION_FAILED). */
export const ADMIN_QUERY_MAX = 100;

/**
 * Typed HTTP access to `/api/admin/users`. Stateless: the page owns the list state.
 * Changes refresh the CSRF token once and retry on 403 CSRF_INVALID; every other error
 * (403 FORBIDDEN / INSUFFICIENT_ROLE, 404 USER_NOT_FOUND, 409, 429, ...) surfaces to the caller.
 */
@Injectable({ providedIn: 'root' })
export class AdminService {
  private readonly http = inject(HttpClient);
  private readonly auth = inject(AuthService);

  /** GET /api/admin/users. `q` is trimmed and left out when blank. */
  list(q: string, page: number, size = ADMIN_PAGE_SIZE): Observable<AdminUserPage> {
    let params = new HttpParams().set('page', page).set('size', size);
    const query = q.trim();
    if (query) {
      params = params.set('q', query);
    }
    return this.http.get<AdminUserPage>(ADMIN_API.users, { params });
  }

  disable(id: number): Observable<AdminUser> {
    return this.withCsrfRetry(() => this.http.post<AdminUser>(ADMIN_API.disable(id), null));
  }

  enable(id: number): Observable<AdminUser> {
    return this.withCsrfRetry(() => this.http.post<AdminUser>(ADMIN_API.enable(id), null));
  }

  revokeSessions(id: number): Observable<AdminUser> {
    return this.withCsrfRetry(() => this.http.post<AdminUser>(ADMIN_API.revokeSessions(id), null));
  }

  changeRole(id: number, role: AssignableRole): Observable<AdminUser> {
    const body: ChangeRoleRequest = { role };
    return this.withCsrfRetry(() => this.http.put<AdminUser>(ADMIN_API.role(id), body));
  }

  /** DELETE /api/admin/users/{id}: 204, no body. */
  delete(id: number): Observable<void> {
    return this.withCsrfRetry(() => this.http.delete<unknown>(ADMIN_API.user(id))).pipe(
      map(() => undefined),
    );
  }

  /** On 403 CSRF_INVALID: fetch a fresh XSRF-TOKEN via GET /me once, then retry once. */
  private withCsrfRetry<T>(request: () => Observable<T>): Observable<T> {
    return request().pipe(
      catchError((error: unknown) =>
        hasProblemCode(error, 403, 'CSRF_INVALID')
          ? this.auth.refreshMe().pipe(switchMap(() => request()))
          : throwError(() => error),
      ),
    );
  }
}
