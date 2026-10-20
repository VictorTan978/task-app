import { Component, computed, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NavigationEnd, Router, RouterLink, RouterLinkActive } from '@angular/router';
import { distinctUntilChanged, filter, finalize, map, skip } from 'rxjs';
import { AuthService } from '../../core/auth/auth.service';
import { NotificationsStore } from '../../features/notifications/notifications.store';
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
  private readonly notifications = inject(NotificationsStore);

  protected readonly user = this.auth.currentUser;
  /** Admins and super admins see the "Admin" link (updated when the user is re-read). */
  protected readonly isAdmin = this.auth.isAdmin;
  protected readonly loggingOut = signal(false);
  /** My pending invitations (badge hidden at 0). */
  protected readonly invitationCount = this.invitations.count;
  protected readonly invitationsLabel = computed(() => invitationsLabel(this.invitationCount()));
  /** My unread notifications (badge hidden at 0). */
  protected readonly notificationCount = this.notifications.count;
  protected readonly notificationsLabel = computed(() => {
    const n = this.notificationCount();
    return n > 0 ? `Notifications, ${n} unread` : 'Notifications';
  });

  constructor() {
    // No live updates: the badge count is fetched when the shell opens and on every page change
    // (the invitations page also sets it after loading, accepting or declining).
    this.invitations.refresh();
    this.notifications.refresh();
    this.notifications.startPolling(inject(DestroyRef));
    this.router.events
      .pipe(
        filter((event): event is NavigationEnd => event instanceof NavigationEnd),
        map((event) => event.urlAfterRedirects.split(/[?#]/)[0]),
        distinctUntilChanged(),
        // The navigation that opened the shell was just counted above.
        skip(1),
        takeUntilDestroyed(),
      )
      .subscribe((path) => {
        // The invitations page sets the count from its own list.
        if (path !== '/invitations') {
          this.invitations.refresh();
        }
        // The notifications page sets the count from its own list.
        if (path !== '/notifications') {
          this.notifications.refresh();
        }
      });
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
