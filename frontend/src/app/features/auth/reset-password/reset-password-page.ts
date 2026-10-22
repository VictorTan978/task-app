import { Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NonNullableFormBuilder, ReactiveFormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { finalize } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { hasProblemCode } from '../../../core/auth/problem';
import { PasswordField } from '../../../shared/password-field/password-field';
import {
  applyServerFieldErrors,
  CONFIRM_NEW_PASSWORD_MESSAGE,
  confirmMatches,
  confirmMessage,
  fieldMessage,
  fieldProblem,
  GENERIC_ERROR,
  isRateLimited,
  LINK_INVALID_MESSAGE,
  passwordLength,
  requiredTrimmed,
  TOO_MANY_ATTEMPTS_MESSAGE,
} from '../auth-form';
import { takeLinkToken } from '../link-token';
import { AuthBrand } from '../../../shared/auth-brand/auth-brand';

type ResetState = 'checking' | 'form' | 'invalid' | 'check-error';

/**
 * `/reset-password?token=...`: checks the link, then sets a new password. Public (signed in or
 * not). The app initializer's GET /api/auth/me (which also sets the XSRF-TOKEN cookie) completes
 * before the first navigation, so the check always POSTs with a CSRF token.
 */
@Component({
  selector: 'app-reset-password-page',
  imports: [ReactiveFormsModule, RouterLink, PasswordField, AuthBrand],
  templateUrl: './reset-password-page.html',
})
export class ResetPasswordPage {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);
  private readonly token = takeLinkToken();

  protected readonly form = inject(NonNullableFormBuilder).group(
    {
      newPassword: ['', [requiredTrimmed, passwordLength]],
      // Browser-only check; never sent to the API.
      confirmPassword: [''],
    },
    { validators: confirmMatches('newPassword', 'confirmPassword') },
  );

  protected readonly state = signal<ResetState>('checking');
  protected readonly pending = signal(false);
  protected readonly submitted = signal(false);
  /** Error while checking the link (429 or generic), shown with "Try again". */
  protected readonly checkError = signal(GENERIC_ERROR);
  /** Error after submitting the form (429 or generic). */
  protected readonly banner = signal<string | null>(null);
  protected readonly invalidMessage = LINK_INVALID_MESSAGE;

  constructor() {
    this.check();
  }

  protected error(): string | null {
    const control = this.form.controls.newPassword;
    if (!this.submitted() && !control.touched) {
      return null;
    }
    return fieldMessage('newPassword', control);
  }

  protected confirmError(): string | null {
    if (!this.submitted() && !this.form.controls.confirmPassword.touched) {
      return null;
    }
    return confirmMessage(this.form, CONFIRM_NEW_PASSWORD_MESSAGE);
  }

  protected retryCheck(): void {
    if (this.state() === 'check-error' && !this.pending()) {
      this.check();
    }
  }

  protected submit(): void {
    if (this.pending() || this.state() !== 'form' || !this.token) {
      return;
    }
    this.submitted.set(true);
    this.banner.set(null);
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }

    const { newPassword } = this.form.getRawValue();
    this.pending.set(true);
    this.auth
      .resetPassword({ token: this.token, newPassword })
      .pipe(
        finalize(() => this.pending.set(false)),
        takeUntilDestroyed(this.destroyRef),
      )
      // On 204 AuthService has cleared the local user and flagged the reset for /login.
      .subscribe({
        next: () => void this.router.navigateByUrl('/login'),
        error: (error: unknown) => {
          if (hasProblemCode(error, 400, 'TOKEN_INVALID')) {
            this.state.set('invalid');
            return;
          }
          if (isRateLimited(error)) {
            this.banner.set(TOO_MANY_ATTEMPTS_MESSAGE);
            return;
          }
          const problem = fieldProblem(error);
          if (problem && applyServerFieldErrors(problem, this.form.controls)) {
            return;
          }
          this.banner.set(GENERIC_ERROR);
        },
      });
  }

  private check(): void {
    if (!this.token) {
      this.state.set('invalid');
      return;
    }
    this.state.set('checking');
    this.pending.set(true);
    this.auth
      .checkResetToken({ token: this.token })
      .pipe(
        finalize(() => this.pending.set(false)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: () => this.state.set('form'),
        error: (error: unknown) => {
          if (hasProblemCode(error, 400, 'TOKEN_INVALID')) {
            this.state.set('invalid');
            return;
          }
          this.checkError.set(isRateLimited(error) ? TOO_MANY_ATTEMPTS_MESSAGE : GENERIC_ERROR);
          this.state.set('check-error');
        },
      });
  }
}
