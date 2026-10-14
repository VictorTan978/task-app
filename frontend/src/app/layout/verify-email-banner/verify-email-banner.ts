import { Component, computed, inject } from '@angular/core';
import { AuthService } from '../../core/auth/auth.service';
import { ResendVerification } from '../../features/account/resend-verification/resend-verification';

/** Slim bar under the header while the signed-in user's email is not confirmed. */
@Component({
  selector: 'app-verify-email-banner',
  imports: [ResendVerification],
  templateUrl: './verify-email-banner.html',
  styleUrl: './verify-email-banner.scss',
})
export class VerifyEmailBanner {
  private readonly auth = inject(AuthService);

  /** The email to confirm, or null when the banner is hidden. */
  protected readonly unverifiedEmail = computed(() => {
    const user = this.auth.currentUser();
    return user?.emailVerified === false ? user.email : null;
  });
}
