import {
  afterNextRender,
  Component,
  computed,
  DestroyRef,
  ElementRef,
  inject,
  input,
  output,
  signal,
  viewChild,
} from '@angular/core';
import { finalize, Observable, Subscription } from 'rxjs';
import { adminActionFailure, EMAIL_DOESNT_MATCH_MESSAGE } from '../admin-errors';
import { AdminAction, emailMatches, isDestructive } from '../admin-permissions';
import { AdminUser } from '../admin.models';
import { AdminService } from '../admin.service';

interface DialogText {
  title: string;
  /** Text after the email; the email is shown in bold before it. */
  before: string;
  after: string;
  confirm: string;
}

const TEXTS: Record<AdminAction, DialogText> = {
  disable: {
    title: 'Disable user?',
    before: 'Disable',
    after: "? They will be signed out everywhere and can't sign in until enabled.",
    confirm: 'Disable',
  },
  enable: {
    title: 'Enable user?',
    before: 'Enable',
    after: '? They can sign in again.',
    confirm: 'Enable',
  },
  revokeSessions: {
    title: 'Sign out everywhere?',
    before: 'Sign out',
    after: ' on every device? Their account stays active and they can sign in again.',
    confirm: 'Sign out everywhere',
  },
  makeAdmin: {
    title: 'Make admin?',
    before: 'Make',
    after: " an admin? They can disable, sign out and delete normal users' accounts.",
    confirm: 'Make admin',
  },
  removeAdmin: {
    title: 'Remove admin?',
    before: 'Remove admin rights from',
    after: '? They become a normal user.',
    confirm: 'Remove admin',
  },
  delete: {
    title: 'Delete user?',
    before: 'Delete',
    after:
      "? Their account and all their tasks will be permanently deleted. This can't be undone. Type their email address to confirm.",
    confirm: 'Delete permanently',
  },
};

/** Confirmation dialog for one admin action on one account. Errors are shown inside it. */
@Component({
  selector: 'app-admin-action-dialog',
  templateUrl: './admin-action-dialog.html',
  styleUrls: ['../../tasks/dialog.scss', './admin-action-dialog.scss'],
})
export class AdminActionDialog {
  private readonly admin = inject(AdminService);
  private sub: Subscription | null = null;

  readonly user = input.required<AdminUser>();
  readonly action = input.required<AdminAction>();

  /** Emits the action after it succeeded. */
  readonly done = output<AdminAction>();
  readonly closed = output<void>();
  /** The table is out of date (403 INSUFFICIENT_ROLE, 404): the page reloads it. */
  readonly stale = output<void>();
  /** 403 FORBIDDEN: the signed-in user is no longer an admin. */
  readonly forbidden = output<void>();

  protected readonly text = computed(() => TEXTS[this.action()]);
  protected readonly destructive = computed(() => isDestructive(this.action()));
  protected readonly needsEmail = computed(() => this.action() === 'delete');
  protected readonly typedEmail = signal('');
  protected readonly emailOk = computed(() => emailMatches(this.typedEmail(), this.user().email));
  /** "Email doesn't match" as soon as something non-blank and wrong is typed. */
  protected readonly emailError = computed(() =>
    this.typedEmail().trim() && !this.emailOk() ? EMAIL_DOESNT_MATCH_MESSAGE : null,
  );
  protected readonly canConfirm = computed(
    () => !this.pending() && (!this.needsEmail() || this.emailOk()),
  );

  protected readonly pending = signal(false);
  protected readonly error = signal<string | null>(null);

  private readonly dialog = viewChild.required<ElementRef<HTMLDialogElement>>('dialog');

  constructor() {
    afterNextRender(() => this.dialog().nativeElement.showModal());
    inject(DestroyRef).onDestroy(() => this.sub?.unsubscribe());
  }

  protected onEmailInput(event: Event): void {
    this.typedEmail.set((event.target as HTMLInputElement).value);
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

  protected onSubmit(event: Event): void {
    event.preventDefault();
    this.confirm();
  }

  protected confirm(): void {
    if (!this.canConfirm()) {
      return;
    }
    const action = this.action();
    this.error.set(null);
    this.pending.set(true);
    this.sub = this.request(action, this.user().id)
      .pipe(finalize(() => this.pending.set(false)))
      .subscribe({
        next: () => this.done.emit(action),
        error: (error: unknown) => {
          const failure = adminActionFailure(error);
          this.error.set(failure.message);
          if (failure.lostAdmin) {
            this.forbidden.emit();
          } else if (failure.refresh) {
            this.stale.emit();
          }
        },
      });
  }

  private request(action: AdminAction, id: number): Observable<unknown> {
    switch (action) {
      case 'disable':
        return this.admin.disable(id);
      case 'enable':
        return this.admin.enable(id);
      case 'revokeSessions':
        return this.admin.revokeSessions(id);
      case 'makeAdmin':
        return this.admin.changeRole(id, 'ADMIN');
      case 'removeAdmin':
        return this.admin.changeRole(id, 'USER');
      case 'delete':
        return this.admin.delete(id);
    }
  }
}
