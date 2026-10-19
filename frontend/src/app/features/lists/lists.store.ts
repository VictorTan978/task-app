import { computed, effect, inject, Injectable, signal, untracked } from '@angular/core';
import { Subscription } from 'rxjs';
import { AuthService } from '../../core/auth/auth.service';
import { isUnauthenticated } from '../tasks/task-form';
import { compareLists } from './lists.logic';
import { TaskList } from './lists.models';
import { ListsService } from './lists.service';

interface StoreState {
  /** User the lists belong to; null when nothing was loaded for anyone. */
  userId: number | null;
  /** Null until the first successful load for `userId`. */
  lists: readonly TaskList[] | null;
  loading: boolean;
  /** True if the last load failed (older lists may still be shown). */
  error: boolean;
}

const EMPTY: StoreState = { userId: null, lists: null, loading: false, error: false };

/**
 * The signed-in user's lists for the picker, the list header and the edit dialog's "List" select.
 *
 * Loaded once per sign-in (`load()`), refetched with `refresh()` after create / rename / delete,
 * on `LIST_NOT_FOUND` and on Retry — never per navigation. Everything is tied to the user it was
 * loaded for: another (or no) signed-in user sees nothing of it, and signing out clears it, so
 * nothing leaks between users in the same tab.
 */
@Injectable({ providedIn: 'root' })
export class ListsStore {
  private readonly api = inject(ListsService);
  private readonly auth = inject(AuthService);
  private readonly state = signal<StoreState>(EMPTY);
  private sub: Subscription | null = null;

  private readonly userId = computed(() => this.auth.currentUser()?.id ?? null);
  /** The state, only if it belongs to the signed-in user. */
  private readonly mine = computed(() => {
    const state = this.state();
    const userId = this.userId();
    return userId !== null && state.userId === userId ? state : EMPTY;
  });

  /** My lists in picker order; empty until loaded. */
  readonly lists = computed(() => this.mine().lists ?? []);
  /** True once a load succeeded for the signed-in user. */
  readonly loaded = computed(() => this.mine().lists !== null);
  readonly loading = computed(() => this.mine().loading);
  readonly error = computed(() => this.mine().error);
  /** "My tasks" (whatever its current name), once loaded. */
  readonly personal = computed(() => this.lists().find((l) => l.personal) ?? null);
  readonly byId = computed(() => new Map(this.lists().map((l) => [l.id, l])));

  constructor() {
    // Sign-out (or a lost session): drop everything, so the next sign-in loads afresh.
    effect(() => {
      if (this.userId() === null) {
        untracked(() => this.clear());
      }
    });
  }

  /** Loads the lists unless they are loaded (or loading) for the signed-in user. */
  load(): void {
    const userId = this.userId();
    const state = this.state();
    if (userId === null) {
      return;
    }
    if (state.userId === userId && (state.lists !== null || state.loading)) {
      return;
    }
    this.refresh();
  }

  /** Refetches the lists; the current ones stay shown until the answer arrives. */
  refresh(): void {
    const userId = this.userId();
    if (userId === null) {
      return;
    }
    this.sub?.unsubscribe();
    this.state.update((s) => ({
      userId,
      lists: s.userId === userId ? s.lists : null,
      loading: true,
      error: false,
    }));
    this.sub = this.api.list().subscribe({
      next: (lists) => {
        if (this.userId() === userId) {
          this.state.set({ userId, lists, loading: false, error: false });
        }
      },
      error: (error: unknown) => {
        if (this.userId() !== userId) {
          return;
        }
        this.state.update((s) => ({ ...s, loading: false, error: !isUnauthenticated(error) }));
      },
    });
  }

  /** Adds or replaces a list right away (after create / rename), keeping the picker order. */
  upsert(list: TaskList): void {
    this.updateLists((lists) =>
      [...lists.filter((l) => l.id !== list.id), list].sort(compareLists),
    );
  }

  /** Removes a deleted list right away. */
  remove(id: number): void {
    this.updateLists((lists) => lists.filter((l) => l.id !== id));
  }

  /** Forgets everything (sign-out). */
  clear(): void {
    this.sub?.unsubscribe();
    this.sub = null;
    this.state.set(EMPTY);
  }

  private updateLists(change: (lists: readonly TaskList[]) => TaskList[]): void {
    const userId = this.userId();
    this.state.update((s) =>
      userId !== null && s.userId === userId && s.lists !== null
        ? { ...s, lists: change(s.lists) }
        : s,
    );
  }
}
