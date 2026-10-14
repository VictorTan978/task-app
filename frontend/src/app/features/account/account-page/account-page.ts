import { Component, computed, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { Router } from '@angular/router';
import { map, Observable, of, switchMap } from 'rxjs';
import { OAuthResult } from '../../../core/auth/auth.models';
import { AuthService } from '../../../core/auth/auth.service';
import { GoogleStart } from '../../auth/google-button/google-start';
import { takeOAuthResult } from '../../auth/oauth-result';
import {
  GOOGLE_CONNECT_FAILED_MESSAGE,
  GOOGLE_CONNECTED_MESSAGE,
  GOOGLE_DISCONNECTED_MESSAGE,
  GOOGLE_IN_USE_MESSAGE,
  LOGGED_OUT_OTHERS_MESSAGE,
  NO_PASSWORD_MESSAGE,
  SET_PASSWORD_FIRST_MESSAGE,
} from '../account-form';
import { AccountService } from '../account.service';
import { ChangePasswordForm } from '../change-password-form/change-password-form';
import { DeleteAccountDialog } from '../delete-account-dialog/delete-account-dialog';
import { DisconnectGoogleDialog } from '../disconnect-google-dialog/disconnect-google-dialog';
import { DisplayNameForm } from '../display-name-form/display-name-form';
import { LogoutOthersDialog } from '../logout-others-dialog/logout-others-dialog';

interface OAuthNotice {
  message: string;
  error: boolean;
}

/** `oauth` codes the server sends to /account after a Google connect, and their notices. */
const ACCOUNT_OAUTH_NOTICES: Partial<Record<OAuthResult, OAuthNotice>> = {
  google_connected: { message: GOOGLE_CONNECTED_MESSAGE, error: false },
  google_in_use: { message: GOOGLE_IN_USE_MESSAGE, error: true },
  google_connect_failed: { message: GOOGLE_CONNECT_FAILED_MESSAGE, error: true },
};

type ConnectOutcome = 'started' | 'signed-out' | 'failed';

@Component({
  selector: 'app-account-page',
  imports: [
    DisplayNameForm,
    ChangePasswordForm,
    DeleteAccountDialog,
    LogoutOthersDialog,
    DisconnectGoogleDialog,
  ],
  templateUrl: './account-page.html',
  styleUrls: ['../../tasks/dialog.scss', './account-page.scss'],
  host: { '(window:pageshow)': 'onPageShow($event)' },
})
export class AccountPage {
  private readonly router = inject(Router);
  private readonly auth = inject(AuthService);
  private readonly googleStart = inject(GoogleStart);
  private readonly destroyRef = inject(DestroyRef);

  /** The route is behind authGuard, so a user is always present while the page is shown. */
  protected readonly email = computed(() => this.auth.currentUser()?.email ?? '');
  /** False only for accounts created with Google that have no password yet. */
  protected readonly hasPassword = computed(() => this.auth.currentUser()?.hasPassword !== false);
  protected readonly googleConnection = computed(
    () => this.auth.currentUser()?.connections.find((c) => c.provider === 'google') ?? null,
  );
  /** Whether Google sign-in is configured; false while loading or on any error. */
  protected readonly googleEnabled = toSignal(
    this.auth.providers().pipe(map((providers) => providers.google)),
    { initialValue: false },
  );
  /** With Google off, existing connections are still listed (read-only); otherwise hidden. */
  protected readonly showConnections = computed(
    () => this.googleEnabled() || (this.auth.currentUser()?.connections.length ?? 0) > 0,
  );
  protected readonly deleting = signal(false);
  protected readonly loggingOutOthers = signal(false);
  protected readonly disconnectingGoogle = signal(false);
  protected readonly sessionsStatus = signal<string | null>(null);
  /** True from "Connect Google" until the browser leaves the page for Google. */
  protected readonly connecting = signal(false);
  protected readonly connectionsStatus = signal<string | null>(null);
  protected readonly connectionsError = signal<string | null>(null);
  /** Shown once after a Google connect round trip; the `oauth` parameter is removed. */
  protected readonly oauthNotice = signal<OAuthNotice | null>(this.takeOAuthNotice());
  protected readonly noPasswordMessage = NO_PASSWORD_MESSAGE;
  protected readonly setPasswordFirstMessage = SET_PASSWORD_FIRST_MESSAGE;
  /** Page-level busy lock: no request while this session's id is being renewed. */
  protected readonly locked = inject(AccountService).sessionRenewing;

  protected openLogoutOthers(): void {
    this.sessionsStatus.set(null);
    this.loggingOutOthers.set(true);
  }

  protected onLoggedOutOthers(): void {
    this.loggingOutOthers.set(false);
    this.sessionsStatus.set(LOGGED_OUT_OTHERS_MESSAGE);
  }

  protected onDeleted(): void {
    this.deleting.set(false);
    void this.router.navigateByUrl('/login');
  }

  /**
   * Checks the session first (GET /me may restore it from remember-me; 401 goes to /login),
   * then submits the hidden form with `intent=connect`.
   */
  protected connectGoogle(): void {
    if (this.connecting() || this.locked()) {
      return;
    }
    this.clearConnectionMessages();
    this.connecting.set(true);
    this.auth
      .confirmSession()
      .pipe(
        switchMap((signedIn): Observable<ConnectOutcome> =>
          signedIn
            ? this.googleStart
                .start('connect')
                .pipe(map((submitted) => (submitted ? 'started' : 'failed')))
            : of('signed-out'),
        ),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: (outcome) => {
          if (outcome === 'started') {
            return;
          }
          this.connecting.set(false);
          if (outcome === 'signed-out') {
            void this.router.navigateByUrl('/login');
          } else {
            this.connectionsError.set(GOOGLE_CONNECT_FAILED_MESSAGE);
          }
        },
        error: () => {
          this.connecting.set(false);
          this.connectionsError.set(GOOGLE_CONNECT_FAILED_MESSAGE);
        },
      });
  }

  protected openDisconnectGoogle(): void {
    if (this.locked() || !this.hasPassword()) {
      return;
    }
    this.clearConnectionMessages();
    this.disconnectingGoogle.set(true);
  }

  protected onGoogleDisconnected(): void {
    this.disconnectingGoogle.set(false);
    this.connectionsStatus.set(GOOGLE_DISCONNECTED_MESSAGE);
  }

  /** Back/forward cache restore (e.g. Back from Google): the page is usable again. */
  protected onPageShow(event: PageTransitionEvent): void {
    if (event.persisted) {
      this.connecting.set(false);
    }
  }

  private clearConnectionMessages(): void {
    this.connectionsStatus.set(null);
    this.connectionsError.set(null);
    this.oauthNotice.set(null);
  }

  private takeOAuthNotice(): OAuthNotice | null {
    const code = takeOAuthResult(['google_connected', 'google_in_use', 'google_connect_failed']);
    return code ? (ACCOUNT_OAUTH_NOTICES[code] ?? null) : null;
  }
}
