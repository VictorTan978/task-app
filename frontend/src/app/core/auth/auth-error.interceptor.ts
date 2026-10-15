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
 *
 * 401 ACCOUNT_DISABLED (the session ended because the account was disabled) is handled on every
 * URL: the user is cleared and the login page will say so. It navigates to /login too, except
 * for GET /api/auth/me, whose callers decide (at startup the route guards redirect).
 */
export const authErrorInterceptor: HttpInterceptorFn = (req, next) => {
  const auth = inject(AuthService);
  const router = inject(Router);
  return next(req).pipe(
    catchError((error: unknown) => {
      if (error instanceof HttpErrorResponse && error.status === 401) {
        const code = readProblem(error)?.code;
        const path = req.url.split('?')[0];
        if (code === 'ACCOUNT_DISABLED') {
          auth.handleAccountDisabled();
          if (path !== AUTH_API.me) {
            void router.navigateByUrl('/login');
          }
        } else if (code === 'UNAUTHENTICATED' && !IGNORED_URLS.some((url) => path === url)) {
          auth.handleSessionLost();
          void router.navigateByUrl('/login');
        }
      }
      return throwError(() => error);
    }),
  );
};
