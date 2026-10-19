import {
  afterNextRender,
  Component,
  computed,
  DestroyRef,
  ElementRef,
  inject,
  input,
  OnInit,
  output,
  signal,
  viewChild,
} from '@angular/core';
import { Subscription } from 'rxjs';
import { isUnauthenticated } from '../../tasks/task-form';
import {
  deleteListQuestion,
  isListNotFound,
  LIST_COUNT_ERROR,
  LIST_DELETE_ERROR,
  problemDetail,
  hasListProblem,
} from '../lists.logic';
import { TaskList } from '../lists.models';
import { ListsService } from '../lists.service';

interface DialogError {
  message: string;
  retry: () => void;
}

/**
 * Confirms deleting a list. Reads `GET /api/lists/{id}` on open for the task count
 * ('Delete "Home" and its 12 tasks?'); Delete stays disabled until the count is known.
 */
@Component({
  selector: 'app-delete-list-dialog',
  templateUrl: './delete-list-dialog.html',
  styleUrl: '../../tasks/dialog.scss',
})
export class DeleteListDialog implements OnInit {
  private readonly api = inject(ListsService);
  private readonly destroyRef = inject(DestroyRef);
  private countSub: Subscription | null = null;
  private deleteSub: Subscription | null = null;

  readonly list = input.required<TaskList>();

  /** Emits the deleted list's id (also when it was already gone). */
  readonly deleted = output<number>();
  readonly closed = output<void>();
  /** The list was not found when reading its count. */
  readonly notFound = output<void>();

  protected readonly taskCount = signal<number | null>(null);
  protected readonly counting = signal(false);
  protected readonly pending = signal(false);
  protected readonly error = signal<DialogError | null>(null);
  protected readonly question = computed(() => {
    const count = this.taskCount();
    return count === null
      ? `Delete "${this.list().name}"?`
      : deleteListQuestion(this.list().name, count);
  });

  private readonly dialog = viewChild.required<ElementRef<HTMLDialogElement>>('dialog');

  constructor() {
    afterNextRender(() => this.dialog().nativeElement.showModal());
    this.destroyRef.onDestroy(() => {
      this.countSub?.unsubscribe();
      this.deleteSub?.unsubscribe();
    });
  }

  ngOnInit(): void {
    this.loadCount();
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
    if (this.pending() || this.taskCount() === null) {
      return;
    }
    const id = this.list().id;
    this.error.set(null);
    this.pending.set(true);
    this.deleteSub = this.api.delete(id).subscribe({
      next: () => {
        this.pending.set(false);
        this.deleted.emit(id);
      },
      error: (error: unknown) => {
        this.pending.set(false);
        if (isUnauthenticated(error)) {
          return;
        }
        if (isListNotFound(error)) {
          // Already gone (deleted in another tab): same outcome.
          this.deleted.emit(id);
          return;
        }
        const message = hasListProblem(error, 409, 'PERSONAL_LIST')
          ? (problemDetail(error) ?? LIST_DELETE_ERROR)
          : LIST_DELETE_ERROR;
        this.error.set({ message, retry: () => this.confirm() });
      },
    });
  }

  private loadCount(): void {
    this.error.set(null);
    this.counting.set(true);
    this.countSub?.unsubscribe();
    this.countSub = this.api.get(this.list().id).subscribe({
      next: (details) => {
        this.counting.set(false);
        this.taskCount.set(details.taskCount);
      },
      error: (error: unknown) => {
        this.counting.set(false);
        if (isUnauthenticated(error)) {
          return;
        }
        if (isListNotFound(error)) {
          this.notFound.emit();
          return;
        }
        this.error.set({ message: LIST_COUNT_ERROR, retry: () => this.loadCount() });
      },
    });
  }
}
