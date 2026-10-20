import { effect, inject, Injectable, untracked } from '@angular/core';
import { AccountService } from '../../features/account/account.service';
import { AuthService } from '../auth/auth.service';
import { browserTimeZone } from './now.service';

/**
 * Saves the browser's time zone on the account when the signed-in user has none (set-if-empty,
 * `ifUnset: true`; the server never overwrites a stored zone). Best-effort and silent: one
 * attempt per user per sign-in, so a failure isn't repeated on every navigation.
 */
@Injectable({ providedIn: 'root' })
export class TimeZoneSync {
  private readonly auth = inject(AuthService);
  private readonly account = inject(AccountService);
  private attemptedFor: number | null = null;

  constructor() {
    effect(() => {
      const user = this.auth.currentUser();
      if (user === null) {
        this.attemptedFor = null;
        return;
      }
      if (user.timeZone !== null || this.attemptedFor === user.id) {
        return;
      }
      const zone = browserTimeZone();
      if (!zone) {
        return;
      }
      this.attemptedFor = user.id;
      untracked(() =>
        this.account
          .setTimeZone({ timeZone: zone, ifUnset: true })
          .subscribe({ error: () => undefined }),
      );
    });
  }
}
