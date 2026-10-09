import { Component, inject, signal } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { finalize } from 'rxjs';
import { FieldName } from '../../../core/auth/auth.models';
import { AuthService } from '../../../core/auth/auth.service';
import { hasProblemCode } from '../../../core/auth/problem';
import {
  applyServerFieldErrors,
  emailTrimmed,
  fieldMessage,
  fieldProblem,
  GENERIC_ERROR,
  INVALID_CREDENTIALS_MESSAGE,
  isRateLimited,
  RATE_LIMITED_MESSAGE,
  requiredTrimmed,
  SESSION_EXPIRED_MESSAGE,
} from '../auth-form';

@Component({
  selector: 'app-login-page',
  imports: [ReactiveFormsModule, RouterLink],
  templateUrl: './login-page.html',
})
export class LoginPage {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  protected readonly form = inject(NonNullableFormBuilder).group({
    email: ['', [requiredTrimmed, emailTrimmed]],
    password: ['', [requiredTrimmed]],
  });

  protected readonly pending = signal(false);
  protected readonly submitted = signal(false);
  protected readonly formError = signal<string | null>(null);
  protected readonly banner = signal<string | null>(null);
  protected readonly sessionExpired = this.auth.sessionExpired;
  protected readonly sessionExpiredMessage = SESSION_EXPIRED_MESSAGE;

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
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }

    const { email, password } = this.form.getRawValue();
    this.pending.set(true);
    this.auth
      .login({ email: email.trim(), password })
      .pipe(finalize(() => this.pending.set(false)))
      .subscribe({
        next: () => void this.router.navigateByUrl('/tasks'),
        error: (error: unknown) => this.handleError(error),
      });
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
