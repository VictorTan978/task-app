import { Component, computed, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Router } from '@angular/router';
import { finalize } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { hasProblemCode } from '../../../core/auth/problem';
import { ResendVerification } from '../../account/resend-verification/resend-verification';
import {
  EMAIL_CONFIRMED_MESSAGE,
  GENERIC_ERROR,
  isRateLimited,
  LINK_INVALID_MESSAGE,
  TOO_MANY_ATTEMPTS_MESSAGE,
} from '../auth-form';
import { takeLinkToken } from '../link-token';

type VerifyState = 'checking' | 'success' | 'invalid' | 'error';

/**
 * `/verify-email?token=...`: confirms the email from the link. Public (signed in or not).
 *
 * The app initializer's GET /api/auth/me (which also sets the XSRF-TOKEN cookie) completes
 * before the first navigation, so this page always POSTs with a CSRF token.
 */
@Component({
  selector: 'app-verify-email-page',
  imports: [ResendVerification],
  templateUrl: './verify-email-page.html',
})
export class VerifyEmailPage {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);
  private readonly token = takeLinkToken();

  protected readonly state = signal<VerifyState>('checking');
  protected readonly pending = signal(false);
  protected readonly errorMessage = signal(GENERIC_ERROR);
  protected readonly confirmedMessage = EMAIL_CONFIRMED_MESSAGE;
  protected readonly invalidMessage = LINK_INVALID_MESSAGE;
  /** Signed in with an unconfirmed email: the invalid state offers "Resend email". */
  protected readonly canResend = computed(() => this.auth.currentUser()?.emailVerified === false);

  constructor() {
    this.verify();
  }

  protected retry(): void {
    if (this.state() === 'error' && !this.pending()) {
      this.verify();
    }
  }

  /** Continue: to /tasks when signed in (after refetching the user), otherwise to /login. */
  protected continue(): void {
    if (this.pending()) {
      return;
    }
    if (!this.auth.isSignedIn()) {
      void this.router.navigateByUrl('/login');
      return;
    }
    this.pending.set(true);
    this.auth
      .refreshMe()
      .pipe(
        finalize(() => this.pending.set(false)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe(
        () => void this.router.navigateByUrl(this.auth.isSignedIn() ? '/tasks' : '/login'),
      );
  }

  private verify(): void {
    if (!this.token) {
      this.state.set('invalid');
      return;
    }
    this.state.set('checking');
    this.pending.set(true);
    this.auth
      .verifyEmail({ token: this.token })
      .pipe(
        finalize(() => this.pending.set(false)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: () => this.state.set('success'),
        error: (error: unknown) => {
          if (hasProblemCode(error, 400, 'TOKEN_INVALID')) {
            this.state.set('invalid');
            return;
          }
          this.errorMessage.set(isRateLimited(error) ? TOO_MANY_ATTEMPTS_MESSAGE : GENERIC_ERROR);
          this.state.set('error');
        },
      });
  }
}
