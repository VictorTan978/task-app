import { Component, inject, signal } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { finalize } from 'rxjs';
import { FieldName } from '../../../core/auth/auth.models';
import { AuthService } from '../../../core/auth/auth.service';
import { hasProblemCode } from '../../../core/auth/problem';
import { OAuthResult } from '../../../core/auth/auth.models';
import { PasswordField } from '../../../shared/password-field/password-field';
import {
  ACCOUNT_DELETED_MESSAGE,
  applyServerFieldErrors,
  emailTrimmed,
  fieldMessage,
  fieldProblem,
  GENERIC_ERROR,
  GOOGLE_FAILED_MESSAGE,
  GOOGLE_UNVERIFIED_MESSAGE,
  INVALID_CREDENTIALS_MESSAGE,
  isRateLimited,
  PASSWORD_RESET_MESSAGE,
  RATE_LIMITED_MESSAGE,
  requiredTrimmed,
  SESSION_EXPIRED_MESSAGE,
} from '../auth-form';
import { GoogleButton } from '../google-button/google-button';
import { takeOAuthResult } from '../oauth-result';

/** `oauth` codes the server sends to /login after a Google round trip, and their notices. */
const LOGIN_OAUTH_NOTICES: Partial<Record<OAuthResult, string>> = {
  google_failed: GOOGLE_FAILED_MESSAGE,
  google_unverified: GOOGLE_UNVERIFIED_MESSAGE,
};

@Component({
  selector: 'app-login-page',
  imports: [ReactiveFormsModule, RouterLink, PasswordField, GoogleButton],
  templateUrl: './login-page.html',
  styleUrl: './login-page.scss',
})
export class LoginPage {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  protected readonly form = inject(NonNullableFormBuilder).group({
    email: ['', [requiredTrimmed, emailTrimmed]],
    password: ['', [requiredTrimmed]],
    rememberMe: [false],
  });

  protected readonly pending = signal(false);
  protected readonly submitted = signal(false);
  protected readonly formError = signal<string | null>(null);
  protected readonly banner = signal<string | null>(null);
  protected readonly sessionExpired = this.auth.sessionExpired;
  protected readonly sessionExpiredMessage = SESSION_EXPIRED_MESSAGE;
  protected readonly accountDeleted = this.auth.accountDeleted;
  protected readonly accountDeletedMessage = ACCOUNT_DELETED_MESSAGE;
  protected readonly passwordReset = this.auth.passwordReset;
  protected readonly passwordResetMessage = PASSWORD_RESET_MESSAGE;
  /** Shown once after a Google round trip; the `oauth` parameter is removed from the URL. */
  protected readonly oauthNotice = signal<string | null>(this.takeOAuthNotice());

  protected error(field: Extract<FieldName, 'email' | 'password'>): string | null {
    const control = this.form.controls[field];
    if (!this.submitted() && !control.touched) {
      return null;
    }
    return fieldMessage(field, control);
  }

  protected submit(): void {
    if (this.pending()) {
      return;
    }
    this.submitted.set(true);
    this.formError.set(null);
    this.banner.set(null);
    this.auth.clearSessionExpired();
    this.auth.clearAccountDeleted();
    this.auth.clearPasswordReset();
    this.oauthNotice.set(null);
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }

    const { email, password, rememberMe } = this.form.getRawValue();
    this.pending.set(true);
    this.auth
      .login({ email: email.trim(), password, rememberMe })
      .pipe(finalize(() => this.pending.set(false)))
      .subscribe({
        next: () => void this.router.navigateByUrl('/tasks'),
        error: (error: unknown) => this.handleError(error),
      });
  }

  private takeOAuthNotice(): string | null {
    const code = takeOAuthResult(['google_failed', 'google_unverified']);
    return code ? (LOGIN_OAUTH_NOTICES[code] ?? null) : null;
  }

  private handleError(error: unknown): void {
    if (isRateLimited(error)) {
      this.banner.set(RATE_LIMITED_MESSAGE);
      return;
    }
    if (hasProblemCode(error, 401, 'INVALID_CREDENTIALS')) {
      this.formError.set(INVALID_CREDENTIALS_MESSAGE);
      return;
    }
    const problem = fieldProblem(error);
    if (problem && applyServerFieldErrors(problem, this.form.controls)) {
      return;
    }
    this.banner.set(GENERIC_ERROR);
  }
}
