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
import { GENERIC_ERROR } from '../../auth/auth-form';
import { AccountService } from '../account.service';

@Component({
  selector: 'app-logout-others-dialog',
  templateUrl: './logout-others-dialog.html',
  styleUrls: ['../../tasks/dialog.scss', '../account-form.scss'],
})
export class LogoutOthersDialog {
  private readonly account = inject(AccountService);
  private sub: Subscription | null = null;

  /** Emits after all other sessions were logged out (204). */
  readonly loggedOut = output<void>();
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
      .logoutOtherSessions()
      .pipe(finalize(() => this.pending.set(false)))
      .subscribe({
        next: () => this.loggedOut.emit(),
        // 401 is handled by the global interceptor (session expired flow).
        error: (error: unknown) => {
          if (!(error instanceof HttpErrorResponse && error.status === 401)) {
            this.banner.set(GENERIC_ERROR);
          }
        },
      });
  }
}
