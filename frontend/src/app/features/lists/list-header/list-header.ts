import {
  afterNextRender,
  Component,
  computed,
  DestroyRef,
  ElementRef,
  inject,
  Injector,
  input,
  linkedSignal,
  output,
  signal,
  viewChild,
} from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { finalize, Subscription } from 'rxjs';
import { isUnauthenticated } from '../../tasks/task-form';
import { DeleteListDialog } from '../delete-list-dialog/delete-list-dialog';
import { ListMenu } from '../list-menu/list-menu';
import {
  applyListNameError,
  isListNotFound,
  LIST_RENAME_ERROR,
  listNameMessage,
  listNameValidators,
} from '../lists.logic';
import { RenameListRequest, TaskList } from '../lists.models';
import { ListsService } from '../lists.service';
import { ListsStore } from '../lists.store';

/**
 * The tasks page heading: the open list's name with its "⋯" menu (Rename inline, Delete with a
 * confirm dialog). After a delete it goes to "My tasks" (`/tasks`).
 */
@Component({
  selector: 'app-list-header',
  imports: [ReactiveFormsModule, ListMenu, DeleteListDialog],
  templateUrl: './list-header.html',
  styleUrl: './list-header.scss',
})
export class ListHeader {
  private readonly api = inject(ListsService);
  private readonly store = inject(ListsStore);
  private readonly router = inject(Router);
  private readonly injector = inject(Injector);
  private sub: Subscription | null = null;

  /** The open list; null while unknown (lists not loaded yet). */
  readonly list = input<TaskList | null>(null);
  /** Heading while `list` is null. */
  readonly fallbackName = input('My tasks');
  /** Id of the h1, for the page's `aria-labelledby`. */
  readonly headingId = input('tasks-heading');

  /** The list turned out to be gone (404 on rename or when counting its tasks). */
  readonly notFound = output<void>();

  protected readonly name = computed(() => this.list()?.name ?? this.fallbackName());
  private readonly listId = computed(() => this.list()?.id ?? null);

  /** Both reset when another list opens. */
  protected readonly renaming = linkedSignal({ source: this.listId, computation: () => false });
  protected readonly deleting = linkedSignal<number | null, TaskList | null>({
    source: this.listId,
    computation: () => null,
  });

  protected readonly pending = signal(false);
  protected readonly submitted = signal(false);
  protected readonly renameError = signal<string | null>(null);
  protected readonly form = inject(NonNullableFormBuilder).group({
    name: ['', listNameValidators],
  });
  private lastRequest: RenameListRequest | null = null;

  private readonly nameInput = viewChild<ElementRef<HTMLInputElement>>('nameInput');

  constructor() {
    inject(DestroyRef).onDestroy(() => this.sub?.unsubscribe());
  }

  protected nameError(): string | null {
    const control = this.form.controls.name;
    if (!this.submitted() && !control.touched) {
      return null;
    }
    return listNameMessage(control);
  }

  protected startRename(): void {
    const list = this.list();
    if (!list) {
      return;
    }
    this.sub?.unsubscribe();
    this.pending.set(false);
    this.form.reset({ name: list.name });
    this.submitted.set(false);
    this.renameError.set(null);
    this.renaming.set(true);
    afterNextRender(
      () => {
        const input = this.nameInput()?.nativeElement;
        input?.focus();
        input?.select();
      },
      { injector: this.injector },
    );
  }

  protected cancelRename(): void {
    if (!this.pending()) {
      this.renaming.set(false);
    }
  }

  protected onRenameKeydown(event: KeyboardEvent): void {
    if (event.key === 'Escape') {
      event.preventDefault();
      this.cancelRename();
    }
  }

  protected submitRename(): void {
    if (this.pending()) {
      return;
    }
    this.submitted.set(true);
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    this.sendRename({ name: this.form.controls.name.value.trim() });
  }

  protected retryRename(): void {
    if (this.lastRequest) {
      this.sendRename(this.lastRequest);
    }
  }

  protected startDelete(): void {
    const list = this.list();
    if (list && !list.personal) {
      this.renaming.set(false);
      this.deleting.set(list);
    }
  }

  protected onDeleted(id: number): void {
    this.deleting.set(null);
    this.store.remove(id);
    this.store.refresh();
    void this.router.navigateByUrl('/tasks');
  }

  protected onDeleteNotFound(): void {
    this.deleting.set(null);
    this.notFound.emit();
  }

  private sendRename(request: RenameListRequest): void {
    const list = this.list();
    if (this.pending() || !list) {
      return;
    }
    this.lastRequest = request;
    this.renameError.set(null);
    this.pending.set(true);
    this.sub = this.api
      .rename(list.id, request)
      .pipe(finalize(() => this.pending.set(false)))
      .subscribe({
        next: (renamed) => {
          this.store.upsert(renamed);
          this.store.refresh();
          this.renaming.set(false);
        },
        error: (error: unknown) => {
          if (isUnauthenticated(error)) {
            return;
          }
          if (applyListNameError(error, this.form.controls.name)) {
            return;
          }
          if (isListNotFound(error)) {
            this.renaming.set(false);
            this.notFound.emit();
            return;
          }
          this.renameError.set(LIST_RENAME_ERROR);
        },
      });
  }
}
