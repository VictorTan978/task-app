import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { map } from 'rxjs';
import { AuthService } from './auth.service';
import {
  INVITATIONS_PATH,
  rememberInvitationsAfterSignIn,
  takeAfterSignInPath,
} from './post-sign-in';

/** Path of a URL without query string or fragment. */
function pathOf(url: string | undefined): string {
  return (url ?? '').split(/[?#]/)[0];
}

/**
 * Allows signed-in users; sends everyone else to /login.
 *
 * Invitation link (Gate 6): a signed-out visit to `/invitations` is remembered, and the first
 * signed-in entry afterwards (the shell guard runs once per sign-in) goes to `/invitations`
 * once. No other path is remembered or followed.
 */
export const authGuard: CanActivateFn = (_route, state) => {
  const auth = inject(AuthService);
  const router = inject(Router);
  const path = pathOf(state?.url);
  if (!auth.isSignedIn()) {
    if (path === INVITATIONS_PATH) {
      rememberInvitationsAfterSignIn();
    }
    return router.createUrlTree(['/login']);
  }
  const next = takeAfterSignInPath();
  return next && path !== next ? router.createUrlTree([next]) : true;
};

/** Allows signed-out users; sends signed-in users to /tasks. */
export const guestGuard: CanActivateFn = () => {
  const auth = inject(AuthService);
  return auth.isSignedIn() ? inject(Router).createUrlTree(['/tasks']) : true;
};

/**
 * Allows admins and super admins. Re-reads the user first (GET /api/auth/me), so a role changed
 * since the last load counts. Signed-out users go to /login, normal users to /tasks.
 * Convenience only: the backend answers 403 to non-admins whatever the frontend does.
 */
export const adminGuard: CanActivateFn = () => {
  const auth = inject(AuthService);
  const router = inject(Router);
  return auth.syncSession().pipe(
    map(() => {
      if (!auth.isSignedIn()) {
        return router.createUrlTree(['/login']);
      }
      return auth.isAdmin() ? true : router.createUrlTree(['/tasks']);
    }),
  );
};
