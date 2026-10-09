import { Component, inject, signal } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { finalize } from 'rxjs';
import { FieldName } from '../../../core/auth/auth.models';
import { AuthService } from '../../../core/auth/auth.service';
import {
  applyServerFieldErrors,
  DISPLAY_NAME_MAX,
  EMAIL_MAX,
  emailTrimmed,
  fieldMessage,
  fieldProblem,
  GENERIC_ERROR,
  isRateLimited,
  maxTrimmed,
  passwordLength,
  RATE_LIMITED_MESSAGE,
  requiredTrimmed,
} from '../auth-form';

@Component({
  selector: 'app-register-page',
  imports: [ReactiveFormsModule, RouterLink],
  templateUrl: './register-page.html',
})
export class RegisterPage {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  protected readonly form = inject(NonNullableFormBuilder).group({
    displayName: ['', [requiredTrimmed, maxTrimmed(DISPLAY_NAME_MAX)]],
    email: ['', [requiredTrimmed, emailTrimmed, maxTrimmed(EMAIL_MAX)]],
    password: ['', [requiredTrimmed, passwordLength]],
  });

  protected readonly pending = signal(false);
  protected readonly submitted = signal(false);
  protected readonly banner = signal<string | null>(null);

  protected error(field: FieldName): string | null {
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
    this.banner.set(null);
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }

    const { displayName, email, password } = this.form.getRawValue();
    this.pending.set(true);
    this.auth
      .register({ displayName: displayName.trim(), email: email.trim(), password })
      .pipe(finalize(() => this.pending.set(false)))
      .subscribe({
        next: () => void this.router.navigateByUrl('/tasks'),
        error: (error: unknown) => {
          if (isRateLimited(error)) {
            this.banner.set(RATE_LIMITED_MESSAGE);
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
