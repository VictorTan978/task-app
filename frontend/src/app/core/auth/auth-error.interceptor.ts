import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, throwError } from 'rxjs';
import { AUTH_API } from './auth-api';
import { AuthService } from './auth.service';
import { readProblem } from './problem';

/**
 * Public endpoints whose 401 (never expected; the contract says they never answer 401) must not
 * be treated as "session lost". Resend verification is a signed-in endpoint and is NOT listed.
 */
const IGNORED_URLS: readonly string[] = [
  AUTH_API.login,
  AUTH_API.register,
  AUTH_API.me,
  AUTH_API.verifyEmail,
  AUTH_API.forgotPassword,
  AUTH_API.checkResetToken,
  AUTH_API.resetPassword,
  AUTH_API.providers,
];

/**
 * When any API call (other than the public auth endpoints above) returns 401 UNAUTHENTICATED,
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
