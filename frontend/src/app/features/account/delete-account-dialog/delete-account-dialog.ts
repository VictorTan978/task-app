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
import { AuthService } from '../../../core/auth/auth.service';
import { PasswordField } from '../../../shared/password-field/password-field';
import { fieldMessage, requiredTrimmed } from '../../auth/auth-form';
import { accountErrorBanner } from '../account-form';
import { AccountService } from '../account.service';

@Component({
  selector: 'app-delete-account-dialog',
  imports: [ReactiveFormsModule, PasswordField],
  templateUrl: './delete-account-dialog.html',
  styleUrls: ['../../tasks/dialog.scss', '../account-form.scss'],
})
export class DeleteAccountDialog {
  private readonly account = inject(AccountService);
  private sub: Subscription | null = null;

  /** Emits after the account was deleted and the local session cleared. */
  readonly deleted = output<void>();
  readonly closed = output<void>();

  /**
   * Accounts without a password (created with Google) confirm with their email instead
   * (`DeleteAccountRequest.email`). Fixed for the dialog's lifetime.
   */
  protected readonly usesEmail = inject(AuthService).currentUser()?.hasPassword === false;

  protected readonly form = inject(NonNullableFormBuilder).group({
    password: ['', this.usesEmail ? [] : [requiredTrimmed]],
    email: ['', this.usesEmail ? [requiredTrimmed] : []],
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
    const field = this.usesEmail ? 'email' : 'password';
    const control = this.form.controls[field];
    if (!this.submitted() && !control.touched) {
      return null;
    }
    return fieldMessage(field, control);
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

    const { password, email } = this.form.getRawValue();
    this.pending.set(true);
    this.sub = this.account
      .deleteAccount(this.usesEmail ? { email: email.trim() } : { password })
      .pipe(finalize(() => this.pending.set(false)))
      .subscribe({
        next: () => this.deleted.emit(),
        error: (error: unknown) => this.banner.set(accountErrorBanner(error, this.form.controls)),
      });
  }
}
