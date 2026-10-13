import { Component, DestroyRef, inject, signal, viewChildren } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NonNullableFormBuilder, ReactiveFormsModule } from '@angular/forms';
import { finalize } from 'rxjs';
import { PasswordField } from '../../../shared/password-field/password-field';
import {
  CONFIRM_NEW_PASSWORD_MESSAGE,
  confirmMatches,
  confirmMessage,
  fieldMessage,
  PASSWORD_MAX,
  PASSWORD_MIN,
  passwordLength,
  requiredTrimmed,
} from '../../auth/auth-form';
import { accountErrorBanner, PASSWORD_CHANGED_MESSAGE } from '../account-form';
import { AccountService } from '../account.service';

@Component({
  selector: 'app-change-password-form',
  imports: [ReactiveFormsModule, PasswordField],
  templateUrl: './change-password-form.html',
  styleUrl: '../account-form.scss',
})
export class ChangePasswordForm {
  private readonly account = inject(AccountService);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly form = inject(NonNullableFormBuilder).group(
    {
      currentPassword: ['', [requiredTrimmed]],
      newPassword: ['', [requiredTrimmed, passwordLength]],
      // Browser-only check; never sent to the API.
      confirmPassword: [''],
    },
    { validators: confirmMatches('newPassword', 'confirmPassword') },
  );

  private readonly passwordFields = viewChildren(PasswordField);

  protected readonly hint = `${PASSWORD_MIN} to ${PASSWORD_MAX} characters`;
  protected readonly pending = signal(false);
  protected readonly submitted = signal(false);
  protected readonly banner = signal<string | null>(null);
  protected readonly status = signal<string | null>(null);

  protected error(field: 'currentPassword' | 'newPassword'): string | null {
    const control = this.form.controls[field];
    if (!this.submitted() && !control.touched) {
      return null;
    }
    return fieldMessage(field, control);
  }

  protected confirmError(): string | null {
    if (!this.submitted() && !this.form.controls.confirmPassword.touched) {
      return null;
    }
    return confirmMessage(this.form, CONFIRM_NEW_PASSWORD_MESSAGE);
  }

  protected submit(): void {
    if (this.pending()) {
      return;
    }
    this.submitted.set(true);
    this.banner.set(null);
    this.status.set(null);
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }

    // Passwords are sent as typed (not trimmed).
    const { currentPassword, newPassword } = this.form.getRawValue();
    this.pending.set(true);
    this.account
      .changePassword({ currentPassword, newPassword })
      .pipe(
        finalize(() => this.pending.set(false)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: () => {
          this.form.reset();
          this.passwordFields().forEach((field) => field.hide());
          this.submitted.set(false);
          this.status.set(PASSWORD_CHANGED_MESSAGE);
        },
        error: (error: unknown) => this.banner.set(accountErrorBanner(error, this.form.controls)),
      });
  }
}
