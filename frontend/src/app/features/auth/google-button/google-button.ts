import { Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { map } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { GENERIC_ERROR } from '../auth-form';
import { GoogleStart } from './google-start';

/**
 * "or" divider and "Continue with Google" button for /login and /register. Renders only when
 * Google sign-in is configured on the server (`GET /api/auth/providers`).
 */
@Component({
  selector: 'app-google-button',
  templateUrl: './google-button.html',
  styleUrl: './google-button.scss',
  host: { '(window:pageshow)': 'onPageShow($event)' },
})
export class GoogleButton {
  private readonly googleStart = inject(GoogleStart);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly enabled = toSignal(
    inject(AuthService)
      .providers()
      .pipe(map((providers) => providers.google)),
    { initialValue: false },
  );
  /** True from the click until the browser leaves the page for Google. */
  protected readonly pending = signal(false);
  protected readonly error = signal<string | null>(null);

  protected start(): void {
    if (this.pending()) {
      return;
    }
    this.error.set(null);
    this.pending.set(true);
    this.googleStart
      .start('login')
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (submitted) => {
          if (!submitted) {
            this.fail();
          }
        },
        error: () => this.fail(),
      });
  }

  /** Back/forward cache restore (e.g. Back from Google): the page is usable again. */
  protected onPageShow(event: PageTransitionEvent): void {
    if (event.persisted) {
      this.pending.set(false);
    }
  }

  private fail(): void {
    this.pending.set(false);
    this.error.set(GENERIC_ERROR);
  }
}
