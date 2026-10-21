import { Component, inject } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NavigationEnd, Router, RouterOutlet } from '@angular/router';
import { distinctUntilChanged, filter, map, skip, switchMap } from 'rxjs';
import { TimeZoneSync } from '../../core/time/time-zone-sync';
import { AuthService } from '../../core/auth/auth.service';
import { ToastHost } from '../../shared/toast/toast-host';
import { AppHeader } from '../app-header/app-header';
import { VerifyEmailBanner } from '../verify-email-banner/verify-email-banner';

/** Path of a URL without query string or fragment. */
function pathOf(url: string): string {
  return url.split(/[?#]/)[0];
}

@Component({
  selector: 'app-shell-layout',
  imports: [AppHeader, VerifyEmailBanner, RouterOutlet, ToastHost],
  template: `
    <app-header />
    <app-verify-email-banner />
    <main class="shell-main">
      <router-outlet />
    </main>
    <app-toast-host />
  `,
  styles: `
    .shell-main {
      max-width: 960px;
      margin: 0 auto;
      padding: 2rem 1.5rem;
    }
  `,
})
export class ShellLayout {
  constructor() {
    const router = inject(Router);
    const auth = inject(AuthService);
    // Saves the browser's time zone when the account has none (best-effort, silent).
    inject(TimeZoneSync);
    // Re-read the user (GET /me) when moving to another page, so a role changed by a super admin
    // shows or hides the "Admin" link on the next navigation (spec AC13). Skipped for the
    // navigation that created the shell (the user was just loaded) and for /admin pages
    // (adminGuard already re-read it).
    router.events
      .pipe(
        filter((event): event is NavigationEnd => event instanceof NavigationEnd),
        map((event) => pathOf(event.urlAfterRedirects)),
        distinctUntilChanged(),
        skip(1),
        filter((path) => !path.startsWith('/admin')),
        switchMap(() => auth.syncSession()),
        takeUntilDestroyed(),
      )
      .subscribe(() => {
        if (!auth.isSignedIn()) {
          void router.navigateByUrl('/login');
        }
      });
  }
}
