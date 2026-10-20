import {
  Component,
  computed,
  DestroyRef,
  ElementRef,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, NavigationEnd, Params, Router, RouterLink } from '@angular/router';
import { distinctUntilChanged, filter, map, merge, of, Subscription } from 'rxjs';
import { NowService } from '../../../core/time/now.service';
import { ListHeader } from '../../lists/list-header/list-header';
import { ListPicker } from '../../lists/list-picker/list-picker';
import { isListNotFound, LIST_NOT_FOUND_MESSAGE } from '../../lists/lists.logic';
import { ListsStore } from '../../lists/lists.store';
import { ConfirmDeleteDialog } from '../confirm-delete-dialog/confirm-delete-dialog';
import { AddFailure, TaskAddForm } from '../task-add-form/task-add-form';
import { TaskEditDialog } from '../task-edit-dialog/task-edit-dialog';
import {
  ADD_ERROR,
  isInvalidCursor,
  isNotFound,
  isUnauthenticated,
  LOAD_ERROR,
  LOAD_MORE_ERROR,
  TASK_ADDED_MESSAGE,
  TASK_NOT_FOUND_MESSAGE,
  TOGGLE_ERROR,
} from '../task-form';
import {
  appendPage,
  applyChange,
  emptyList,
  firstPage,
  hasMore,
  moveOut,
  removeTask,
  shownTotal,
  TaskChange,
  TaskListState,
} from '../task-list';
import { TaskRow } from '../task-row/task-row';
import { findTag } from '../task-tags';
import { normaliseQuery, normaliseSort, reminderJustPassed, SEARCH_MAX } from '../tasks.logic';
import { Task, TaskFilter, TaskListQuery, TaskSort } from '../tasks.models';
import { TasksService } from '../tasks.service';
import {
  ListRef,
  listParam,
  PageRoute,
  PERSONAL,
  readPageRoute,
  sameListRef,
  samePageRoute,
} from './page-route';

/** Delay between the last keystroke in the search box and the search. */
export const SEARCH_DEBOUNCE_MS = 300;
/** How long "Task added" stays on screen. */
export const ADDED_NOTICE_MS = 4000;

interface Banner {
  message: string;
  retry: (() => void) | null;
}

interface FilterTab {
  filter: TaskFilter;
  label: string;
  /** `null` removes the param, so "All" is plain `/tasks`. */
  param: TaskFilter | null;
}

interface SortOption {
  value: TaskSort;
  label: string;
}

const EMPTY_TEXT: Record<TaskFilter, string> = {
  all: 'No tasks yet — add your first one above',
  active: 'Nothing left to do',
  in_progress: 'Nothing in progress',
  done: 'Nothing done yet',
};

/** Empty text on every tab while a tag filter is set. */
const NO_TAGGED_TASKS = 'No tasks with this tag';

const DEFAULT_QUERY: TaskListQuery = { filter: 'all', tag: null, q: null, sort: 'newest' };

/**
 * "My tasks" (`/tasks`) or one of my lists (`/lists/:id`), with the list picker beside it.
 * The same component serves both routes; moving between two `/lists/:id` reuses it, so a list
 * change resets paging, search, banners and dialogs here.
 */
@Component({
  selector: 'app-tasks-page',
  imports: [
    RouterLink,
    TaskAddForm,
    TaskRow,
    TaskEditDialog,
    ConfirmDeleteDialog,
    ListPicker,
    ListHeader,
  ],
  templateUrl: './tasks-page.html',
  styleUrl: './tasks-page.scss',
})
export class TasksPage {
  private readonly tasksApi = inject(TasksService);
  private readonly lists = inject(ListsStore);
  private readonly now = inject(NowService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private loadSub: Subscription | null = null;
  private moreSub: Subscription | null = null;
  private searchTimer: ReturnType<typeof setTimeout> | null = null;
  private addedTimer: ReturnType<typeof setTimeout> | null = null;
  /** Bumped on every list change; late answers for the previous list are ignored. */
  private generation = 0;

  private readonly searchInput = viewChild.required<ElementRef<HTMLInputElement>>('searchInput');

  protected readonly searchMax = SEARCH_MAX;
  protected readonly loadMoreError = LOAD_MORE_ERROR;
  protected readonly addedMessage = TASK_ADDED_MESSAGE;
  protected readonly listNotFoundMessage = LIST_NOT_FOUND_MESSAGE;

  protected readonly tabs: readonly FilterTab[] = [
    { filter: 'all', label: 'All', param: null },
    { filter: 'active', label: 'Active', param: 'active' },
    { filter: 'in_progress', label: 'In progress', param: 'in_progress' },
    { filter: 'done', label: 'Done', param: 'done' },
  ];

  protected readonly sortOptions: readonly SortOption[] = [
    { value: 'newest', label: 'Newest' },
    { value: 'oldest', label: 'Oldest' },
    { value: 'due', label: 'Due date' },
    { value: 'priority', label: 'Priority' },
    { value: 'title', label: 'Title A–Z' },
  ];

  /** List selected in the URL. */
  protected readonly listRef = signal<ListRef>(PERSONAL);
  /** `list` for requests (null on `/tasks`). */
  protected readonly listId = computed(() => listParam(this.listRef()));
  /** The open list from the store; null until the lists are loaded (or if it isn't mine). */
  protected readonly currentList = computed(() => {
    const ref = this.listRef();
    if (ref.kind === 'personal') {
      return this.lists.personal();
    }
    return ref.kind === 'list' ? (this.lists.byId().get(ref.id) ?? null) : null;
  });
  /** Real id of the open list, for the picker's highlight. */
  /** The open list has 2 or more members: tasks show "Added by …". */
  protected readonly shared = computed(() => (this.currentList()?.memberCount ?? 1) >= 2);
  protected readonly activeListId = computed(() => this.currentList()?.id ?? this.listId());
  protected readonly headingFallback = computed(() =>
    this.listRef().kind === 'personal' ? 'My tasks' : 'List',
  );
  /** "My tasks" by its current name, for the "List not found" link. */
  protected readonly personalName = computed(() => this.lists.personal()?.name ?? 'My tasks');
  /** The list doesn't exist or isn't mine (bad id, or the API said 404 `LIST_NOT_FOUND`). */
  protected readonly listNotFound = signal(false);
  /** Query selected in the URL (normalised). */
  protected readonly query = signal<TaskListQuery>(DEFAULT_QUERY);
  /** Query the shown list was loaded with (differs from `query` only after a failed load). */
  private readonly listQuery = signal<TaskListQuery>(DEFAULT_QUERY);
  protected readonly filter = computed(() => this.query().filter);
  protected readonly tag = computed(() => this.query().tag);
  /** Text in the search box (may differ from `query().q` while typing). */
  protected readonly searchText = signal('');

  private readonly list = signal<TaskListState>(emptyList());
  protected readonly tasks = computed(() => this.list().items);
  protected readonly hasMore = computed(() => hasMore(this.list()));
  protected readonly total = computed(() => shownTotal(this.list()));
  protected readonly loading = signal(false);
  /** True once any list load succeeded. */
  protected readonly loaded = signal(false);
  protected readonly loadingMore = signal(false);
  protected readonly loadMoreFailed = signal(false);
  protected readonly banner = signal<Banner | null>(null);
  /** "Task added" for a created task that isn't shown. */
  protected readonly addedNotice = signal(false);
  protected readonly busyIds = signal<ReadonlySet<number>>(new Set());
  /** Today and the current time in the account zone (the browser's while it has none). */
  protected readonly today = this.now.today;
  protected readonly nowTime = this.now.time;

  protected readonly editing = signal<Task | null>(null);
  protected readonly deleting = signal<Task | null>(null);

  protected readonly emptyText = computed(() => {
    const { filter, tag, q } = this.listQuery();
    if (q !== null) {
      return `No tasks match "${q}"`;
    }
    return tag === null ? EMPTY_TEXT[filter] : NO_TAGGED_TASKS;
  });

  /** "N tasks" once everything is loaded. */
  protected readonly countText = computed(() => {
    const n = this.tasks().length;
    return n === 1 ? '1 task' : `${n} tasks`;
  });

  /** The tag bar shows the stored spelling if a loaded task has the tag, else the raw value. */
  protected readonly tagLabel = computed(() => {
    const tag = this.tag();
    if (tag === null) {
      return null;
    }
    return (
      findTag(
        this.tasks().flatMap((t) => t.tags),
        tag,
      ) ?? tag
    );
  });

  constructor() {
    this.lists.load();
    // One stream for the list (path) and the query (query params), read once per navigation, so
    // a navigation changing both gives one load.
    merge(of(null), this.router.events.pipe(filter((e) => e instanceof NavigationEnd)))
      .pipe(
        map(() => readPageRoute(this.route.snapshot.paramMap, this.route.snapshot.queryParamMap)),
        distinctUntilChanged(samePageRoute),
        takeUntilDestroyed(),
      )
      .subscribe((route) => this.onRoute(route));
    this.destroyRef.onDestroy(() => {
      this.loadSub?.unsubscribe();
      this.moreSub?.unsubscribe();
      this.cancelSearchTimer();
      if (this.addedTimer !== null) {
        clearTimeout(this.addedTimer);
      }
    });
  }

  private onRoute({ list, query }: PageRoute): void {
    if (!sameListRef(list, this.listRef())) {
      this.resetForList();
    }
    this.listRef.set(list);
    this.query.set(query);
    // Back / refresh / a link changed the search: show it (unless it's what is typed).
    if (normaliseQuery(this.searchText()) !== query.q) {
      this.cancelSearchTimer();
      this.searchText.set(query.q ?? '');
    }
    if (list.kind === 'invalid') {
      this.cancelLoads();
      this.loading.set(false);
      this.listNotFound.set(true);
      return;
    }
    this.load();
  }

  /** Another list opened: start from scratch (first page, no banners, dialogs closed). */
  private resetForList(): void {
    this.generation++;
    this.cancelLoads();
    this.cancelSearchTimer();
    this.clearAdded();
    this.list.set(emptyList());
    this.listQuery.set(DEFAULT_QUERY);
    this.loaded.set(false);
    this.listNotFound.set(false);
    this.banner.set(null);
    this.busyIds.set(new Set());
    this.editing.set(null);
    this.deleting.set(null);
  }

  /** The open list is gone or not mine: show "List not found" and refetch the picker. */
  protected listGone(): void {
    this.cancelLoads();
    this.loading.set(false);
    this.editing.set(null);
    this.deleting.set(null);
    this.banner.set(null);
    this.clearAdded();
    this.listNotFound.set(true);
    this.lists.refresh();
  }

  /** True if a task from the server belongs in the open list. */
  private belongsHere(task: Task): boolean {
    const ref = this.listRef();
    return ref.kind === 'personal' || (ref.kind === 'list' && task.listId === ref.id);
  }

  protected isBusy(id: number): boolean {
    return this.busyIds().has(id);
  }

  /** (Re)loads the first page for the URL query; the shown list stays until it arrives. */
  protected load(clearBanner = true): void {
    if (clearBanner) {
      this.banner.set(null);
    }
    const ref = this.listRef();
    if (ref.kind === 'invalid') {
      return;
    }
    const query = this.query();
    this.cancelLoads();
    this.loading.set(true);
    this.loadSub = this.tasksApi.list(query, null, listParam(ref)).subscribe({
      next: (page) => {
        this.loading.set(false);
        this.now.refresh();
        this.listQuery.set(query);
        this.list.set(firstPage(page));
        this.loaded.set(true);
        this.listNotFound.set(false);
        // A list of mine the picker doesn't know yet (e.g. created in another tab).
        if (ref.kind === 'list' && this.lists.loaded() && !this.lists.byId().has(ref.id)) {
          this.lists.refresh();
        }
      },
      error: (error: unknown) => {
        this.loading.set(false);
        if (isUnauthenticated(error)) {
          return;
        }
        if (isListNotFound(error)) {
          this.listGone();
          return;
        }
        this.banner.set({ message: LOAD_ERROR, retry: () => this.load() });
      },
    });
  }

  /** Appends the next page; on failure the loaded tasks stay and a Retry appears. */
  protected loadMore(): void {
    const cursor = this.list().nextCursor;
    if (this.loadingMore() || this.loading() || cursor === null) {
      return;
    }
    this.loadMoreFailed.set(false);
    this.loadingMore.set(true);
    this.moreSub = this.tasksApi.list(this.listQuery(), cursor, this.listId()).subscribe({
      next: (page) => {
        this.loadingMore.set(false);
        this.now.refresh();
        this.list.update((state) => appendPage(state, page));
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
        if (isListNotFound(error)) {
          this.listGone();
          return;
        }
        this.loadMoreFailed.set(true);
      },
    });
  }

  protected onSearchInput(event: Event): void {
    this.searchText.set((event.target as HTMLInputElement).value);
    this.cancelSearchTimer();
    this.searchTimer = setTimeout(() => this.searchNow(), SEARCH_DEBOUNCE_MS);
  }

  /** Enter, the debounce timer, or "×": puts the search in the URL (replacing the entry). */
  protected searchNow(): void {
    this.cancelSearchTimer();
    const q = normaliseQuery(this.searchText());
    if (q === this.query().q) {
      return;
    }
    void this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { q },
      queryParamsHandling: 'merge',
      replaceUrl: true,
    });
  }

  protected clearSearch(): void {
    this.searchText.set('');
    this.searchNow();
    this.searchInput().nativeElement.focus();
  }

  /** A new sort adds a history entry; Newest removes the param. */
  protected onSortChange(event: Event): void {
    const sort = normaliseSort((event.target as HTMLSelectElement).value);
    if (sort === this.query().sort) {
      return;
    }
    const params: Params = { sort: sort === 'newest' ? null : sort };
    void this.router.navigate([], {
      relativeTo: this.route,
      queryParams: params,
      queryParamsHandling: 'merge',
    });
  }

  protected onAddStarted(): void {
    this.banner.set(null);
    this.addedNotice.set(false);
  }

  protected onCreated(task: Task): void {
    if (this.belongsHere(task)) {
      this.apply(task, 'created');
    }
  }

  protected onAddFailed(failure: AddFailure): void {
    if (failure.listNotFound) {
      this.listGone();
      return;
    }
    this.banner.set({ message: ADD_ERROR, retry: failure.retry });
  }

  /** Removes `?tag`, keeping the tab, search and sort. */
  protected clearTag(): void {
    void this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { tag: null },
      queryParamsHandling: 'merge',
    });
  }

  /** Checkbox: ticked → status Done, unticked → To do (also from In progress). */
  protected toggle(task: Task, done: boolean): void {
    if (this.isBusy(task.id)) {
      return;
    }
    this.banner.set(null);
    this.setBusy(task.id, true);
    const generation = this.generation;
    this.tasksApi
      .setStatus(task.id, done ? 'DONE' : 'TODO')
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (updated) => {
          if (generation !== this.generation) {
            return;
          }
          this.setBusy(task.id, false);
          this.applyOrMoveOut(updated);
        },
        error: (error: unknown) => {
          if (generation !== this.generation) {
            return;
          }
          this.setBusy(task.id, false);
          if (isUnauthenticated(error)) {
            return;
          }
          if (isNotFound(error)) {
            this.taskGone();
            return;
          }
          this.banner.set({ message: TOGGLE_ERROR, retry: () => this.toggle(task, done) });
        },
      });
  }

  protected openEdit(task: Task): void {
    this.deleting.set(null);
    this.editing.set(task);
  }

  protected openDelete(task: Task): void {
    this.editing.set(null);
    this.deleting.set(task);
  }

  protected onSaved(task: Task): void {
    const before = this.editing();
    // A reminder that just turned out to be in the past: the dialog stays open and says so.
    const keepOpen =
      before !== null && before.listId === task.listId && reminderJustPassed(before, task);
    this.editing.set(keepOpen ? task : null);
    if (before !== null && before.listId !== task.listId) {
      // Moved to another list: it leaves this view.
      this.list.update((state) => moveOut(state, task.id));
      return;
    }
    this.applyOrMoveOut(task);
  }

  protected onDeleted(id: number): void {
    this.deleting.set(null);
    this.list.update((state) => removeTask(state, id));
  }

  /** 404 on toggle, edit or delete: the task is gone (deleted elsewhere). Restart the list. */
  protected taskGone(): void {
    this.editing.set(null);
    this.deleting.set(null);
    this.banner.set({ message: TASK_NOT_FOUND_MESSAGE, retry: null });
    this.load(false);
  }

  /** An updated task: placed in the view, or dropped if it is now in another list. */
  private applyOrMoveOut(task: Task): void {
    if (this.belongsHere(task)) {
      this.apply(task, 'updated');
    } else {
      this.list.update((state) => moveOut(state, task.id));
    }
  }

  private apply(task: Task, change: TaskChange): void {
    const result = applyChange(this.list(), task, this.listQuery(), change);
    this.list.set(result.state);
    if (result.addedHidden) {
      this.showAdded();
    }
  }

  private showAdded(): void {
    this.clearAdded();
    this.addedNotice.set(true);
    this.addedTimer = setTimeout(() => {
      this.addedTimer = null;
      this.addedNotice.set(false);
    }, ADDED_NOTICE_MS);
  }

  private clearAdded(): void {
    if (this.addedTimer !== null) {
      clearTimeout(this.addedTimer);
      this.addedTimer = null;
    }
    this.addedNotice.set(false);
  }

  /** Cancels the first-page and "Load more" requests in flight. */
  private cancelLoads(): void {
    this.loadSub?.unsubscribe();
    this.moreSub?.unsubscribe();
    this.loadSub = null;
    this.moreSub = null;
    this.loadingMore.set(false);
    this.loadMoreFailed.set(false);
  }

  private cancelSearchTimer(): void {
    if (this.searchTimer !== null) {
      clearTimeout(this.searchTimer);
      this.searchTimer = null;
    }
  }

  private setBusy(id: number, busy: boolean): void {
    this.busyIds.update((ids) => {
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
