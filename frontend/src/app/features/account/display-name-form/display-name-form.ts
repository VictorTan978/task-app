import { Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NonNullableFormBuilder, ReactiveFormsModule } from '@angular/forms';
import { finalize } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { DISPLAY_NAME_MAX, fieldMessage, maxTrimmed, requiredTrimmed } from '../../auth/auth-form';
import { accountErrorBanner, DISPLAY_NAME_UPDATED_MESSAGE } from '../account-form';
import { AccountService } from '../account.service';

@Component({
  selector: 'app-display-name-form',
  imports: [ReactiveFormsModule],
  templateUrl: './display-name-form.html',
  styleUrl: '../account-form.scss',
})
export class DisplayNameForm {
  private readonly account = inject(AccountService);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly form = inject(NonNullableFormBuilder).group({
    displayName: [
      inject(AuthService).currentUser()?.displayName ?? '',
      [requiredTrimmed, maxTrimmed(DISPLAY_NAME_MAX)],
    ],
  });

  protected readonly pending = signal(false);
  protected readonly submitted = signal(false);
  protected readonly banner = signal<string | null>(null);
  protected readonly status = signal<string | null>(null);

  protected error(): string | null {
    const control = this.form.controls.displayName;
    if (!this.submitted() && !control.touched) {
      return null;
    }
    return fieldMessage('displayName', control);
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

    const displayName = this.form.controls.displayName.value.trim();
    this.pending.set(true);
    this.account
      .changeDisplayName({ displayName })
      .pipe(
        finalize(() => this.pending.set(false)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: (user) => {
          this.form.reset({ displayName: user.displayName });
          this.submitted.set(false);
          this.status.set(DISPLAY_NAME_UPDATED_MESSAGE);
        },
        error: (error: unknown) => this.banner.set(accountErrorBanner(error, this.form.controls)),
      });
  }
}
