import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, throwError } from 'rxjs';
import { AUTH_API } from './auth-api';
import { AuthService } from './auth.service';
import { readProblem } from './problem';

const IGNORED_URLS: readonly string[] = [AUTH_API.login, AUTH_API.register, AUTH_API.me];

/**
 * When any API call (other than login/register/me) returns 401 UNAUTHENTICATED,
 * the session is gone: clear the user and go to /login. The error is re-thrown.
 */
export const authErrorInterceptor: HttpInterceptorFn = (req, next) => {
  const auth = inject(AuthService);
  const router = inject(Router);
  return next(req).pipe(
    catchError((error: unknown) => {
      if (
        error instanceof HttpErrorResponse &&
        error.status === 401 &&
        readProblem(error)?.code === 'UNAUTHENTICATED' &&
        !IGNORED_URLS.some((url) => req.url.split('?')[0] === url)
      ) {
        auth.handleSessionLost();
        void router.navigateByUrl('/login');
      }
      return throwError(() => error);
    }),
  );
};
