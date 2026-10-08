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
import { finalize, Subscription } from 'rxjs';
import { DELETE_ERROR, isNotFound, isUnauthenticated } from '../task-form';
import { Task } from '../tasks.models';
import { TasksService } from '../tasks.service';

@Component({
  selector: 'app-confirm-delete-dialog',
  templateUrl: './confirm-delete-dialog.html',
  styleUrl: '../dialog.scss',
})
export class ConfirmDeleteDialog {
  private readonly tasks = inject(TasksService);
  private sub: Subscription | null = null;

  readonly task = input.required<Task>();

  /** Emits the deleted task's id. */
  readonly deleted = output<number>();
  readonly closed = output<void>();
  readonly notFound = output<void>();

  protected readonly pending = signal(false);
  protected readonly error = signal<string | null>(null);

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
    const id = this.task().id;
    this.error.set(null);
    this.pending.set(true);
    this.sub = this.tasks
      .delete(id)
      .pipe(finalize(() => this.pending.set(false)))
      .subscribe({
        next: () => this.deleted.emit(id),
        error: (error: unknown) => {
          if (isUnauthenticated(error)) {
            return;
          }
          if (isNotFound(error)) {
            this.notFound.emit();
            return;
          }
          this.error.set(DELETE_ERROR);
        },
      });
  }
}
