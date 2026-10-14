import { HttpErrorResponse } from '@angular/common/http';
import {
  afterNextRender,
  Component,
  DestroyRef,
  ElementRef,
  inject,
  output,
  signal,
  viewChild,
} from '@angular/core';
import { finalize, Subscription } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { hasProblemCode } from '../../../core/auth/problem';
import { GENERIC_ERROR } from '../../auth/auth-form';
import { SET_PASSWORD_FIRST_MESSAGE } from '../account-form';
import { AccountService } from '../account.service';

/** Confirms and runs `DELETE /api/account/connections/google`. */
@Component({
  selector: 'app-disconnect-google-dialog',
  templateUrl: './disconnect-google-dialog.html',
  styleUrls: ['../../tasks/dialog.scss', '../account-form.scss'],
})
export class DisconnectGoogleDialog {
  private readonly account = inject(AccountService);
  private readonly auth = inject(AuthService);
  private sub: Subscription | null = null;

  /** Emits after Google was disconnected (200; the signed-in user is already updated). */
  readonly disconnected = output<void>();
  readonly closed = output<void>();

  protected readonly pending = signal(false);
  protected readonly banner = signal<string | null>(null);

  private readonly dialog = viewChild.required<ElementRef<HTMLDialogElement>>('dialog');

  constructor() {
    afterNextRender(() => this.dialog().nativeElement.showModal());
    inject(DestroyRef).onDestroy(() => this.sub?.unsubscribe());
  }

  /** Escape key: close unless the request is running. */
  protected onCancel(event: Event): void {
    event.preventDefault();
    this.cancel();
  }

  protected cancel(): void {
    if (!this.pending()) {
      this.closed.emit();
    }
  }

  protected confirm(): void {
    if (this.pending()) {
      return;
    }
    this.banner.set(null);
    this.pending.set(true);
    this.sub = this.account
      .disconnectGoogle()
      .pipe(finalize(() => this.pending.set(false)))
      .subscribe({
        next: () => this.disconnected.emit(),
        error: (error: unknown) => this.handleError(error),
      });
  }

  private handleError(error: unknown): void {
    if (error instanceof HttpErrorResponse && error.status === 401) {
      // The auth error interceptor already signed this tab out and went to /login.
      return;
    }
    if (hasProblemCode(error, 409, 'PASSWORD_REQUIRED')) {
      this.banner.set(SET_PASSWORD_FIRST_MESSAGE);
      return;
    }
    if (error instanceof HttpErrorResponse && error.status === 404) {
      // Already disconnected (e.g. in another tab) or Google turned off: refresh the card.
      this.auth.refreshMe().subscribe();
    }
    this.banner.set(GENERIC_ERROR);
  }
}
