import { Component, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { AuthService } from '../../../core/auth/auth.service';
import { ChangePasswordForm } from '../change-password-form/change-password-form';
import { DeleteAccountDialog } from '../delete-account-dialog/delete-account-dialog';
import { DisplayNameForm } from '../display-name-form/display-name-form';

@Component({
  selector: 'app-account-page',
  imports: [DisplayNameForm, ChangePasswordForm, DeleteAccountDialog],
  templateUrl: './account-page.html',
  styleUrls: ['../../tasks/dialog.scss', './account-page.scss'],
})
export class AccountPage {
  private readonly router = inject(Router);
  private readonly auth = inject(AuthService);

  /** The route is behind authGuard, so a user is always present while the page is shown. */
  protected readonly email = computed(() => this.auth.currentUser()?.email ?? '');
  protected readonly deleting = signal(false);

  protected onDeleted(): void {
    this.deleting.set(false);
    void this.router.navigateByUrl('/login');
  }
}
