import { Component, computed, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { finalize } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { isUnauthenticated } from '../../tasks/task-form';
import { AccountService } from '../account.service';

export const REMINDER_EMAILS_SAVED_MESSAGE = 'Saved';
export const REMINDER_EMAILS_ERROR = "Couldn't save the setting. Please try again.";

/** "Reminder emails" on/off; saves as soon as it is switched and reverts if saving fails. */
@Component({
  selector: 'app-reminder-emails-toggle',
  templateUrl: './reminder-emails-toggle.html',
  styleUrl: '../account-form.scss',
})
export class ReminderEmailsToggle {
  private readonly account = inject(AccountService);
  private readonly auth = inject(AuthService);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly enabled = computed(() => this.auth.currentUser()?.reminderEmails !== false);
  protected readonly pending = signal(false);
  protected readonly locked = this.account.sessionRenewing;
  protected readonly error = signal<string | null>(null);
  protected readonly status = signal<string | null>(null);

  protected toggle(event: Event): void {
    const input = event.target as HTMLInputElement;
    if (this.pending() || this.locked()) {
      input.checked = this.enabled();
      return;
    }
    this.error.set(null);
    this.status.set(null);
    this.pending.set(true);
    this.account
      .setReminderEmails({ enabled: input.checked })
      .pipe(
        finalize(() => this.pending.set(false)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: () => this.status.set(REMINDER_EMAILS_SAVED_MESSAGE),
        error: (error: unknown) => {
          input.checked = this.enabled();
          if (!isUnauthenticated(error)) {
            this.error.set(REMINDER_EMAILS_ERROR);
          }
        },
      });
  }
}
