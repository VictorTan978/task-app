import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { map } from 'rxjs';
import { AuthService } from './auth.service';

/** Allows signed-in users; sends everyone else to /login. */
export const authGuard: CanActivateFn = () => {
  const auth = inject(AuthService);
  return auth.isSignedIn() ? true : inject(Router).createUrlTree(['/login']);
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
