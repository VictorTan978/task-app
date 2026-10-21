import { Component, computed, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { Subscription } from 'rxjs';
import { ToastService } from '../../../shared/toast/toast.service';
import { ListPicker } from '../../lists/list-picker/list-picker';
import { isListNotFound } from '../../lists/lists.logic';
import { ListsStore } from '../../lists/lists.store';
import { isInvalidCursor, isNotFound, isUnauthenticated } from '../../tasks/task-form';
import {
  daysLeftText,
  DELETE_FOREVER_ERROR,
  deletedText,
  emptyTrashQuestion,
  EMPTY_TRASH_ERROR,
  NOT_IN_TRASH_NOTE,
  RESTORE_ERROR,
  RESTORED_MESSAGE,
  tasksText,
  TRASH_EMPTY,
  TRASH_LOAD_ERROR,
  TRASH_MORE_ERROR,
} from '../trash.logic';
import { TrashItem } from '../trash.models';
import { TrashService } from '../trash.service';
import { TrashConfirmDialog } from '../trash-confirm-dialog/trash-confirm-dialog';

/** What the open confirm dialog is about. */
type Confirming = { kind: 'forever'; item: TrashItem } | { kind: 'empty' };

/**
 * The trash of every list I am a member of (`/trash`), newest deleted first, 50 per page.
 * Any member can restore, delete forever or empty (spec AC2 to AC6, AC16).
 */
@Component({
  selector: 'app-trash-page',
  imports: [RouterLink, ListPicker, TrashConfirmDialog],
  templateUrl: './trash-page.html',
  styleUrl: './trash-page.scss',
})
export class TrashPage {
  private readonly api = inject(TrashService);
  private readonly lists = inject(ListsStore);
  private readonly toast = inject(ToastService);
  private loadSub: Subscription | null = null;
  private moreSub: Subscription | null = null;
  private readonly destroyRef = inject(DestroyRef);

  protected readonly emptyText = TRASH_EMPTY;
  protected readonly loadError = TRASH_LOAD_ERROR;
  protected readonly moreError = TRASH_MORE_ERROR;

  protected readonly listOptions = this.lists.lists;
  /** The list filter (null: every list). */
  protected readonly filterId = signal<number | null>(null);

  protected readonly items = signal<readonly TrashItem[]>([]);
  protected readonly total = signal(0);
  protected readonly nextCursor = signal<string | null>(null);
  protected readonly loading = signal(false);
  protected readonly loaded = signal(false);
  protected readonly loadFailed = signal(false);
  protected readonly loadingMore = signal(false);
  protected readonly moreFailed = signal(false);
  /** Time used for "2 days ago" and "28 days left"; refreshed with every load. */
  private readonly nowMs = signal(Date.now());

  protected readonly busy = signal<ReadonlySet<number>>(new Set());
  /** Last row action that failed (Restore / Delete forever), else null. */
  protected readonly rowError = signal<string | null>(null);
  /** A row that was already gone from the trash (404): "That task is no longer in the trash." */
  protected readonly note = signal<string | null>(null);

  protected readonly confirming = signal<Confirming | null>(null);
  protected readonly confirmPending = signal(false);
  protected readonly confirmError = signal<string | null>(null);

  protected readonly isEmpty = computed(
    () => this.loaded() && !this.loading() && this.items().length === 0,
  );
  protected readonly shownTotal = computed(() => Math.max(this.total(), this.items().length));
  protected readonly filterName = computed(() => {
    const id = this.filterId();
    return id === null ? null : (this.lists.byId().get(id)?.name ?? null);
  });
  protected readonly confirmHeading = computed(() =>
    this.confirming()?.kind === 'empty' ? 'Empty trash?' : 'Delete forever?',
  );
  protected readonly confirmMessage = computed(() => {
    const c = this.confirming();
    if (c === null) {
      return '';
    }
    return c.kind === 'empty'
      ? emptyTrashQuestion(this.shownTotal(), this.filterName())
      : `"${c.item.title}" will be deleted forever. This can't be undone.`;
  });
  protected readonly confirmLabel = computed(() =>
    this.confirming()?.kind === 'empty' ? 'Empty trash' : 'Delete forever',
  );

  constructor() {
    this.destroyRef.onDestroy(() => {
      this.loadSub?.unsubscribe();
      this.moreSub?.unsubscribe();
    });
    this.lists.load();
    this.load();
  }

  protected listName(item: TrashItem): string {
    return this.lists.byId().get(item.listId)?.name ?? 'Unknown list';
  }

  protected deleted(item: TrashItem): string {
    return deletedText(item, this.nowMs());
  }

  protected daysLeft(item: TrashItem): string {
    return daysLeftText(item.purgeAt, this.nowMs());
  }

  protected countText(): string {
    return tasksText(this.items().length);
  }

  protected isBusy(id: number): boolean {
    return this.busy().has(id);
  }

  protected onFilterChange(event: Event): void {
    const value = (event.target as HTMLSelectElement).value;
    this.filterId.set(value === '' ? null : Number(value));
    // Rows of the previous filter must not stay on screen while the new ones load.
    this.items.set([]);
    this.nextCursor.set(null);
    this.total.set(0);
    this.loaded.set(false);
    this.load();
  }

  /** (Re)loads the first page; the shown rows stay until it arrives. */
  protected load(): void {
    this.loadSub?.unsubscribe();
    this.moreSub?.unsubscribe();
    this.loadingMore.set(false);
    this.moreFailed.set(false);
    this.loadFailed.set(false);
    this.loading.set(true);
    this.loadSub = this.api.list(this.filterId()).subscribe({
      next: (page) => {
        this.loading.set(false);
        this.nowMs.set(Date.now());
        this.items.set(page.items);
        this.total.set(page.total);
        this.nextCursor.set(page.nextCursor);
        this.loaded.set(true);
      },
      error: (error: unknown) => {
        if (isUnauthenticated(error)) {
          this.loading.set(false);
          return;
        }
        if (this.filterId() !== null && isListNotFound(error)) {
          this.filterGone();
          return;
        }
        this.loading.set(false);
        this.loadFailed.set(true);
      },
    });
  }

  protected loadMore(): void {
    const cursor = this.nextCursor();
    if (cursor === null || this.loadingMore() || this.loading()) {
      return;
    }
    this.moreFailed.set(false);
    this.loadingMore.set(true);
    this.moreSub = this.api.list(this.filterId(), cursor).subscribe({
      next: (page) => {
        this.loadingMore.set(false);
        const known = new Set(this.items().map((i) => i.id));
        this.items.update((items) => [...items, ...page.items.filter((i) => !known.has(i.id))]);
        this.total.set(page.total);
        this.nextCursor.set(page.nextCursor);
      },
      error: (error: unknown) => {
        this.loadingMore.set(false);
        if (isUnauthenticated(error)) {
          return;
        }
        if (isInvalidCursor(error)) {
          this.load();
          return;
        }
        if (this.filterId() !== null && isListNotFound(error)) {
          this.filterGone();
          return;
        }
        this.moreFailed.set(true);
      },
    });
  }

  /** The filtered list is gone (deleted, or I left it): show every list again. */
  private filterGone(): void {
    this.filterId.set(null);
    this.lists.refresh();
    this.load();
  }

  protected restore(item: TrashItem): void {
    if (this.isBusy(item.id)) {
      return;
    }
    this.rowError.set(null);
    this.note.set(null);
    this.setBusy(item.id, true);
    this.api
      .restore(item.id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          this.setBusy(item.id, false);
          this.dropRow(item.id);
          this.toast.show({ message: RESTORED_MESSAGE });
        },
        error: (error: unknown) => {
          this.setBusy(item.id, false);
          this.rowFailed(item.id, error, RESTORE_ERROR);
        },
      });
  }

  protected askDelete(item: TrashItem): void {
    this.confirmError.set(null);
    this.confirming.set({ kind: 'forever', item });
  }

  protected askEmpty(): void {
    this.confirmError.set(null);
    this.confirming.set({ kind: 'empty' });
  }

  protected closeConfirm(): void {
    if (!this.confirmPending()) {
      this.confirming.set(null);
    }
  }

  protected confirm(): void {
    const c = this.confirming();
    if (c === null || this.confirmPending()) {
      return;
    }
    this.confirmError.set(null);
    this.confirmPending.set(true);
    this.rowError.set(null);
    this.note.set(null);
    if (c.kind === 'forever') {
      this.deleteForever(c.item);
    } else {
      this.emptyAll();
    }
  }

  private deleteForever(item: TrashItem): void {
    this.api
      .deleteForever(item.id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          this.confirmPending.set(false);
          this.confirming.set(null);
          this.dropRow(item.id);
        },
        error: (error: unknown) => {
          this.confirmPending.set(false);
          if (isUnauthenticated(error)) {
            return;
          }
          if (isNotFound(error)) {
            this.confirming.set(null);
            this.dropRow(item.id);
            this.note.set(NOT_IN_TRASH_NOTE);
            return;
          }
          this.confirmError.set(DELETE_FOREVER_ERROR);
        },
      });
  }

  private emptyAll(): void {
    this.api
      .empty(this.filterId())
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (result) => {
          this.confirmPending.set(false);
          this.confirming.set(null);
          this.toast.show({ message: `Deleted ${tasksText(result.deleted)} forever` });
          this.load();
        },
        error: (error: unknown) => {
          this.confirmPending.set(false);
          if (isUnauthenticated(error)) {
            return;
          }
          if (this.filterId() !== null && isListNotFound(error)) {
            this.confirming.set(null);
            this.filterGone();
            return;
          }
          this.confirmError.set(EMPTY_TRASH_ERROR);
        },
      });
  }

  /** A row action failed: a 404 means it is no longer in the trash, so the row goes. */
  private rowFailed(id: number, error: unknown, message: string): void {
    if (isUnauthenticated(error)) {
      return;
    }
    if (isNotFound(error)) {
      this.dropRow(id);
      this.note.set(NOT_IN_TRASH_NOTE);
      return;
    }
    this.rowError.set(message);
  }

  /** Removes a row; if that was the last loaded one but more exist, loads the first page again. */
  private dropRow(id: number): void {
    const had = this.items().some((i) => i.id === id);
    this.items.update((items) => items.filter((i) => i.id !== id));
    if (had) {
      this.total.update((n) => Math.max(0, n - 1));
    }
    if (this.items().length === 0 && this.nextCursor() !== null) {
      this.load();
    }
  }

  private setBusy(id: number, busy: boolean): void {
    this.busy.update((ids) => {
      const next = new Set(ids);
      if (busy) {
        next.add(id);
      } else {
        next.delete(id);
      }
      return next;
    });
  }
}
