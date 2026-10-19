import { Component, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NavigationEnd, Router, RouterLink, RouterLinkActive } from '@angular/router';
import { distinctUntilChanged, filter, finalize, map, skip } from 'rxjs';
import { AuthService } from '../../core/auth/auth.service';
import { InvitationsStore } from '../../features/sharing/invitations.store';
import { invitationsLabel } from '../../features/sharing/sharing.logic';

@Component({
  selector: 'app-header',
  imports: [RouterLink, RouterLinkActive],
  templateUrl: './app-header.html',
  styleUrl: './app-header.scss',
})
export class AppHeader {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly invitations = inject(InvitationsStore);

  protected readonly user = this.auth.currentUser;
  /** Admins and super admins see the "Admin" link (updated when the user is re-read). */
  protected readonly isAdmin = this.auth.isAdmin;
  protected readonly loggingOut = signal(false);
  /** My pending invitations (badge hidden at 0). */
  protected readonly invitationCount = this.invitations.count;
  protected readonly invitationsLabel = computed(() => invitationsLabel(this.invitationCount()));

  constructor() {
    // No live updates: the badge count is fetched when the shell opens and on every page change
    // (the invitations page also sets it after loading, accepting or declining).
    this.invitations.refresh();
    this.router.events
      .pipe(
        filter((event): event is NavigationEnd => event instanceof NavigationEnd),
        map((event) => event.urlAfterRedirects.split(/[?#]/)[0]),
        distinctUntilChanged(),
        // The navigation that opened the shell was just counted above.
        skip(1),
        // The invitations page sets the count from its own list.
        filter((path) => path !== '/invitations'),
        takeUntilDestroyed(),
      )
      .subscribe(() => this.invitations.refresh());
  }

  protected logout(): void {
    if (this.loggingOut()) {
      return;
    }
    this.loggingOut.set(true);
    this.auth
      .logout()
      .pipe(finalize(() => this.loggingOut.set(false)))
      .subscribe({
        // Go to /login whether or not the server call succeeded; the local user is cleared either way.
        next: () => void this.router.navigateByUrl('/login'),
        error: () => void this.router.navigateByUrl('/login'),
      });
  }
}
