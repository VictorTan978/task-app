import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
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
