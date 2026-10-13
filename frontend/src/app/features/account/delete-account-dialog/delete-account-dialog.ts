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
import { NonNullableFormBuilder, ReactiveFormsModule } from '@angular/forms';
import { finalize, Subscription } from 'rxjs';
import { fieldMessage, requiredTrimmed } from '../../auth/auth-form';
import { accountErrorBanner } from '../account-form';
import { AccountService } from '../account.service';

@Component({
  selector: 'app-delete-account-dialog',
  imports: [ReactiveFormsModule],
  templateUrl: './delete-account-dialog.html',
  styleUrls: ['../../tasks/dialog.scss', '../account-form.scss'],
})
export class DeleteAccountDialog {
  private readonly account = inject(AccountService);
  private sub: Subscription | null = null;

  /** Emits after the account was deleted and the local session cleared. */
  readonly deleted = output<void>();
  readonly closed = output<void>();

  protected readonly form = inject(NonNullableFormBuilder).group({
    password: ['', [requiredTrimmed]],
  });

  protected readonly pending = signal(false);
  protected readonly submitted = signal(false);
  protected readonly banner = signal<string | null>(null);

  private readonly dialog = viewChild.required<ElementRef<HTMLDialogElement>>('dialog');

  constructor() {
    afterNextRender(() => this.dialog().nativeElement.showModal());
    inject(DestroyRef).onDestroy(() => this.sub?.unsubscribe());
  }

  protected error(): string | null {
    const control = this.form.controls.password;
    if (!this.submitted() && !control.touched) {
      return null;
    }
    return fieldMessage('password', control);
  }

  /** Escape key: close unless a delete is running. */
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
    this.submitted.set(true);
    this.banner.set(null);
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }

    const { password } = this.form.getRawValue();
    this.pending.set(true);
    this.sub = this.account
      .deleteAccount({ password })
      .pipe(finalize(() => this.pending.set(false)))
      .subscribe({
        next: () => this.deleted.emit(),
        error: (error: unknown) => this.banner.set(accountErrorBanner(error, this.form.controls)),
      });
  }
}
