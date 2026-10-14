import { Component, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { AuthService } from '../../../core/auth/auth.service';
import { LOGGED_OUT_OTHERS_MESSAGE } from '../account-form';
import { AccountService } from '../account.service';
import { ChangePasswordForm } from '../change-password-form/change-password-form';
import { DeleteAccountDialog } from '../delete-account-dialog/delete-account-dialog';
import { DisplayNameForm } from '../display-name-form/display-name-form';
import { LogoutOthersDialog } from '../logout-others-dialog/logout-others-dialog';

@Component({
  selector: 'app-account-page',
  imports: [DisplayNameForm, ChangePasswordForm, DeleteAccountDialog, LogoutOthersDialog],
  templateUrl: './account-page.html',
  styleUrls: ['../../tasks/dialog.scss', './account-page.scss'],
})
export class AccountPage {
  private readonly router = inject(Router);
  private readonly auth = inject(AuthService);

  /** The route is behind authGuard, so a user is always present while the page is shown. */
  protected readonly email = computed(() => this.auth.currentUser()?.email ?? '');
  protected readonly deleting = signal(false);
  protected readonly loggingOutOthers = signal(false);
  protected readonly sessionsStatus = signal<string | null>(null);
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
}
