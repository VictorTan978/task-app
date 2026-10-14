import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { finalize } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { hasProblemCode } from '../../../core/auth/problem';
import {
  EMAIL_SENT_MESSAGE,
  GENERIC_ERROR,
  isRateLimited,
  TOO_MANY_EMAILS_MESSAGE,
} from '../../auth/auth-form';
import { AccountService } from '../account.service';

/**
 * "Resend email" button for the signed-in user's confirmation email, with its result message.
 * Used by the verify-email banner and the verify-email page.
 */
@Component({
  selector: 'app-resend-verification',
  templateUrl: './resend-verification.html',
  styleUrl: './resend-verification.scss',
})
export class ResendVerification {
  private readonly account = inject(AccountService);
  private readonly auth = inject(AuthService);
  private readonly destroyRef = inject(DestroyRef);

  private readonly sending = signal(false);
  private readonly refreshing = signal(false);
  /** True while the resend request or the follow-up GET /me runs. */
  protected readonly pending = computed(() => this.sending() || this.refreshing());
  protected readonly status = signal<string | null>(null);
  protected readonly error = signal<string | null>(null);

  protected resend(): void {
    if (this.pending()) {
      return;
    }
    this.status.set(null);
    this.error.set(null);
    this.sending.set(true);
    this.account
      .resendVerification()
      .pipe(
        finalize(() => this.sending.set(false)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: () => this.status.set(EMAIL_SENT_MESSAGE),
        error: (error: unknown) => this.handleError(error),
      });
  }

  private handleError(error: unknown): void {
    if (hasProblemCode(error, 409, 'ALREADY_VERIFIED')) {
      // Confirmed elsewhere: refetch the user so the banner / button disappears.
      this.refreshing.set(true);
      this.auth
        .refreshMe()
        .pipe(
          finalize(() => this.refreshing.set(false)),
          takeUntilDestroyed(this.destroyRef),
        )
        .subscribe();
      return;
    }
    if (isRateLimited(error)) {
      this.error.set(TOO_MANY_EMAILS_MESSAGE);
      return;
    }
    if (error instanceof HttpErrorResponse && error.status === 401) {
      // The auth error interceptor already signed this tab out and went to /login.
      return;
    }
    this.error.set(GENERIC_ERROR);
  }
}
