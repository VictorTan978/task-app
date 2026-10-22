import { Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NonNullableFormBuilder, ReactiveFormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { finalize } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import {
  applyServerFieldErrors,
  EMAIL_MAX,
  emailTrimmed,
  fieldMessage,
  fieldProblem,
  GENERIC_ERROR,
  isRateLimited,
  maxTrimmed,
  requiredTrimmed,
  RESET_LINK_SENT_MESSAGE,
  TOO_MANY_ATTEMPTS_MESSAGE,
} from '../auth-form';
import { AuthBrand } from '../../../shared/auth-brand/auth-brand';

/** `/forgot-password`: asks for a reset link. Public (signed in or not). */
@Component({
  selector: 'app-forgot-password-page',
  imports: [ReactiveFormsModule, RouterLink, AuthBrand],
  templateUrl: './forgot-password-page.html',
})
export class ForgotPasswordPage {
  private readonly auth = inject(AuthService);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly form = inject(NonNullableFormBuilder).group({
    email: ['', [requiredTrimmed, emailTrimmed, maxTrimmed(EMAIL_MAX)]],
  });

  protected readonly pending = signal(false);
  protected readonly submitted = signal(false);
  protected readonly sent = signal(false);
  protected readonly banner = signal<string | null>(null);
  protected readonly sentMessage = RESET_LINK_SENT_MESSAGE;

  protected error(): string | null {
    const control = this.form.controls.email;
    if (!this.submitted() && !control.touched) {
      return null;
    }
    return fieldMessage('email', control);
  }

  protected submit(): void {
    if (this.pending()) {
      return;
    }
    this.submitted.set(true);
    this.banner.set(null);
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }

    const email = this.form.getRawValue().email.trim();
    this.pending.set(true);
    this.auth
      .forgotPassword({ email })
      .pipe(
        finalize(() => this.pending.set(false)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: () => this.sent.set(true),
        error: (error: unknown) => {
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
}
