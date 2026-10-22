import { Component, computed, DestroyRef, inject, signal, WritableSignal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { Subscription } from 'rxjs';
import { ListsStore } from '../../lists/lists.store';
import { isNotFound, isUnauthenticated } from '../../tasks/task-form';
import { TrashService } from '../../trash/trash.service';
import {
  isAvailable,
  isTrashed,
  notificationLink,
  notificationText,
  NOTIFICATIONS_ACTION_ERROR,
  NOTIFICATIONS_EMPTY,
  NOTIFICATIONS_LOAD_ERROR,
  NOTIFICATIONS_MORE_ERROR,
  RESTORE_ERROR,
  TASK_IN_TRASH,
  TASK_UNAVAILABLE,
} from '../notifications.logic';
import { AppNotification } from '../notifications.models';
import { NotificationsService } from '../notifications.service';
import { NotificationsStore } from '../notifications.store';

@Component({
  selector: 'app-notifications-page',
  imports: [RouterLink],
  templateUrl: './notifications-page.html',
  styleUrl: './notifications-page.scss',
})
export class NotificationsPage {
  private readonly api = inject(NotificationsService);
  private readonly store = inject(NotificationsStore);
  private readonly trash = inject(TrashService);
  private readonly lists = inject(ListsStore);
  private readonly router = inject(Router);
  private loadSub: Subscription | null = null;
  private moreSub: Subscription | null = null;
  private allSub: Subscription | null = null;

  protected readonly items = signal<readonly AppNotification[]>([]);
  protected readonly nextCursor = signal<string | null>(null);
  protected readonly loading = signal(true);
  protected readonly loaded = signal(false);
  protected readonly loadFailed = signal(false);
  protected readonly loadingMore = signal(false);
  protected readonly moreFailed = signal(false);
  protected readonly markingAll = signal(false);
  protected readonly actionError = signal(false);
  protected readonly busy = signal<ReadonlySet<number>>(new Set());

  protected readonly isEmpty = computed(() => this.loaded() && this.items().length === 0);
  protected readonly hasUnread = computed(() => this.items().some((n) => !n.read));

  protected readonly loadError = NOTIFICATIONS_LOAD_ERROR;
  protected readonly moreError = NOTIFICATIONS_MORE_ERROR;
  protected readonly actionErrorText = NOTIFICATIONS_ACTION_ERROR;
  protected readonly emptyText = NOTIFICATIONS_EMPTY;
  protected readonly unavailableText = TASK_UNAVAILABLE;
  protected readonly trashedText = TASK_IN_TRASH;
  protected readonly restoreErrorText = RESTORE_ERROR;
  /** Restore failed for these notifications' tasks (retry with the button). */
  protected readonly restoreFailed = signal<ReadonlySet<number>>(new Set());
  /** Notifications whose Restore is running. */
  protected readonly restoring = signal<ReadonlySet<number>>(new Set());

  constructor() {
    inject(DestroyRef).onDestroy(() => {
      this.loadSub?.unsubscribe();
      this.moreSub?.unsubscribe();
      this.allSub?.unsubscribe();
    });
    this.lists.load();
    this.load();
  }

  protected text(n: AppNotification): string {
    return notificationText(n);
  }

  protected available(n: AppNotification): boolean {
    return isAvailable(n);
  }

  protected received(n: AppNotification): string {
    const date = new Date(n.createdAt);
    return Number.isNaN(date.getTime()) ? n.createdAt : date.toLocaleString();
  }

  /** (Re)loads the first page; the shown list stays until it arrives. */
  protected load(): void {
    this.loadSub?.unsubscribe();
    this.moreSub?.unsubscribe();
    this.loadingMore.set(false);
    this.moreFailed.set(false);
    this.loading.set(true);
    this.loadFailed.set(false);
    this.loadSub = this.api.list(null).subscribe({
      next: (page) => {
        this.loading.set(false);
        this.items.set(page.items);
        this.nextCursor.set(page.nextCursor);
        this.loaded.set(true);
        this.syncCount();
      },
      error: (error: unknown) => {
        this.loading.set(false);
        if (!isUnauthenticated(error)) {
          this.loadFailed.set(true);
        }
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
    this.moreSub = this.api.list(cursor).subscribe({
      next: (page) => {
        this.loadingMore.set(false);
        const known = new Set(this.items().map((n) => n.id));
        this.items.update((items) => [...items, ...page.items.filter((n) => !known.has(n.id))]);
        this.nextCursor.set(page.nextCursor);
      },
      error: (error: unknown) => {
        this.loadingMore.set(false);
        if (!isUnauthenticated(error)) {
          this.moreFailed.set(true);
        }
      },
    });
  }

  protected markAllRead(): void {
    if (this.markingAll()) {
      return;
    }
    this.actionError.set(false);
    this.markingAll.set(true);
    this.allSub = this.api.markAllRead().subscribe({
      next: () => {
        this.markingAll.set(false);
        this.items.update((items) => items.map((n) => ({ ...n, read: true })));
        this.store.set(0);
      },
      error: (error: unknown) => {
        this.markingAll.set(false);
        if (!isUnauthenticated(error)) {
          this.actionError.set(true);
        }
      },
    });
  }

  /** Marks the notification read (if unread), then opens its list when the task is available. */
  protected open(n: AppNotification): void {
    if (this.busy().has(n.id)) {
      return;
    }
    if (n.read) {
      this.navigate(n);
      return;
    }
    this.actionError.set(false);
    this.busy.update((set) => new Set(set).add(n.id));
    this.api.markRead(n.id).subscribe({
      next: (updated) => {
        this.release(n.id);
        this.items.update((items) =>
          items.map((i) => (i.id === n.id ? { ...i, read: updated.read } : i)),
        );
        this.syncCount();
        this.navigate(n);
      },
      error: (error: unknown) => {
        this.release(n.id);
        if (!isUnauthenticated(error)) {
          this.actionError.set(true);
          // Still open the list: marking read is a nicety.
          this.navigate(n);
        }
      },
    });
  }

  protected trashed(n: AppNotification): boolean {
    return isTrashed(n);
  }

  protected isRestoring(id: number): boolean {
    return this.restoring().has(id);
  }

  /** Restores the trashed task; every notification of that task stops saying it is in the trash. */
  protected restore(n: AppNotification): void {
    const taskId = n.taskId;
    if (taskId === null || this.restoring().has(n.id)) {
      return;
    }
    this.setIn(this.restoreFailed, n.id, false);
    this.setIn(this.restoring, n.id, true);
    this.trash.restore(taskId).subscribe({
      next: () => {
        this.setIn(this.restoring, n.id, false);
        this.items.update((items) =>
          items.map((i) => (i.taskId === taskId ? { ...i, taskTrashed: false } : i)),
        );
      },
      error: (error: unknown) => {
        this.setIn(this.restoring, n.id, false);
        if (isUnauthenticated(error)) {
          return;
        }
        if (isNotFound(error)) {
          // Deleted for good, or no longer in my lists.
          this.items.update((items) =>
            items.map((i) =>
              i.taskId === taskId ? { ...i, taskId: null, listId: null, taskTrashed: false } : i,
            ),
          );
          return;
        }
        this.setIn(this.restoreFailed, n.id, true);
      },
    });
  }

  private setIn(target: WritableSignal<ReadonlySet<number>>, id: number, on: boolean): void {
    target.update((set) => {
      const next = new Set(set);
      if (on) {
        next.add(id);
      } else {
        next.delete(id);
      }
      return next;
    });
  }

  protected isBusy(id: number): boolean {
    return this.busy().has(id);
  }

  private navigate(n: AppNotification): void {
    // A trashed task can't be opened: the row offers Restore instead.
    if (n.listId === null || n.taskTrashed) {
      return;
    }
    const personal = this.lists.personal()?.id ?? null;
    void this.router.navigateByUrl(notificationLink(n.listId, personal));
  }

  private release(id: number): void {
    this.busy.update((set) => {
      const next = new Set(set);
      next.delete(id);
      return next;
    });
  }

  /** The loaded pages may not hold every unread one, so ask the server for the count. */
  private syncCount(): void {
    this.store.refresh();
  }
}
