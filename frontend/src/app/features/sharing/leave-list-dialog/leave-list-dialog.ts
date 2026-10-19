import {
  afterNextRender,
  Component,
  DestroyRef,
  ElementRef,
  inject,
  input,
  output,
  signal,
  viewChild,
} from '@angular/core';
import { Subscription } from 'rxjs';
import { isListNotFound } from '../../lists/lists.logic';
import { TaskList } from '../../lists/lists.models';
import { isUnauthenticated } from '../../tasks/task-form';
import { LEAVE_ERROR, ownerCannotLeaveMessage } from '../sharing.logic';
import { SharingService } from '../sharing.service';

interface DialogError {
  message: string;
  /** Null when trying again can't help (the owner can't leave). */
  retry: (() => void) | null;
}

/** Confirms leaving a shared list (members only). The tasks I added stay in the list. */
@Component({
  selector: 'app-leave-list-dialog',
  templateUrl: './leave-list-dialog.html',
  styleUrl: '../../tasks/dialog.scss',
})
export class LeaveListDialog {
  private readonly api = inject(SharingService);
  private sub: Subscription | null = null;

  readonly list = input.required<TaskList>();

  /** Emits the list's id once I'm no longer a member (also when I already wasn't). */
  readonly left = output<number>();
  readonly closed = output<void>();

  protected readonly pending = signal(false);
  protected readonly error = signal<DialogError | null>(null);

  private readonly dialog = viewChild.required<ElementRef<HTMLDialogElement>>('dialog');

  constructor() {
    afterNextRender(() => this.dialog().nativeElement.showModal());
    inject(DestroyRef).onDestroy(() => this.sub?.unsubscribe());
  }

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
    const id = this.list().id;
    this.error.set(null);
    this.pending.set(true);
    this.sub = this.api.leave(id).subscribe({
      next: () => {
        this.pending.set(false);
        this.left.emit(id);
      },
      error: (error: unknown) => {
        this.pending.set(false);
        if (isUnauthenticated(error)) {
          return;
        }
        if (isListNotFound(error)) {
          // Already not a member (removed, left in another tab, or the list was deleted).
          this.left.emit(id);
          return;
        }
        const ownerMessage = ownerCannotLeaveMessage(error);
        this.error.set(
          ownerMessage
            ? { message: ownerMessage, retry: null }
            : { message: LEAVE_ERROR, retry: () => this.confirm() },
        );
      },
    });
  }
}
