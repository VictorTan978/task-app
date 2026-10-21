import {
  Component,
  computed,
  DestroyRef,
  ElementRef,
  HostListener,
  inject,
  Injector,
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
import { ToastService } from '../../../shared/toast/toast.service';
import { BulkBar } from '../bulk-bar/bulk-bar';
import { BulkDeleteDialog } from '../bulk-delete-dialog/bulk-delete-dialog';
import {
  addCapped,
  BULK_ERROR,
  BULK_MAX,
  BulkListChoice,
  bulkFailure,
  BulkRequest,
  RELOAD_LABEL,
  rangeIds,
  tagChoices,
} from '../bulk.logic';
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
  NEXT_SCHEDULED_PREFIX,
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
import { RowSelect, TaskRow } from '../task-row/task-row';
import { findTag } from '../task-tags';
import {
  formatDue,
  normaliseQuery,
  normaliseSort,
  reminderJustPassed,
  SEARCH_MAX,
} from '../tasks.logic';
import {
  BulkActionRequest,
  BulkResult,
  Task,
  TaskFilter,
  TaskListQuery,
  TaskResult,
  TaskSort,
} from '../tasks.models';
import { TasksService } from '../tasks.service';
import { BulkUndoPlan, isBulkPlan, UndoPlan } from '../undo.messages';
import {
  ListRef,
  listParam,
  PageRoute,
  PERSONAL,
  readPageRoute,
  sameListRef,
  samePageRoute,
} from './page-route';

type UndoLogic = typeof import('../undo.logic');
type UndoFailure = import('../task-undo').UndoFailure;

/** Delay between the last keystroke in the search box and the search. */
export const SEARCH_DEBOUNCE_MS = 300;
/** How long "Task added" stays on screen. */
export const ADDED_NOTICE_MS = 4000;

interface Banner {
  message: string;
  retry: (() => void) | null;
  /** Text of the retry button; "Retry" when missing. */
  retryLabel?: string;
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
    BulkBar,
    BulkDeleteDialog,
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
  private readonly toast = inject(ToastService);
  private readonly injector = inject(Injector);
  /** Bumped by every new undo offer; a late-loading older one is dropped. */
  private undoOffer = 0;
  private undoSub: Subscription | null = null;
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
  /** "Next one scheduled for <date>" after completing a repeating task. */
  protected readonly nextNotice = signal<string | null>(null);
  protected readonly busyIds = signal<ReadonlySet<number>>(new Set());
  /** Today and the current time in the account zone (the browser's while it has none). */
  protected readonly today = this.now.today;
  protected readonly nowTime = this.now.time;

  protected readonly editing = signal<Task | null>(null);
  protected readonly deleting = signal<Task | null>(null);

  /** Selection mode (0.17.0 bulk actions): ids ticked among the loaded tasks. */
  protected readonly selecting = signal(false);
  private readonly selectedIds = signal<ReadonlySet<number>>(new Set());
  /** Last ticked task, the start of a Shift+click range. */
  private anchorId: number | null = null;
  /** A bulk request is running: the bar is disabled. */
  protected readonly bulkBusy = signal(false);
  protected readonly confirmingBulkDelete = signal(false);
  /** The selected tasks that are loaded, in list order. */
  protected readonly selectedTasks = computed(() => {
    const ids = this.selectedIds();
    return this.tasks().filter((t) => ids.has(t.id));
  });
  protected readonly selectedTags = computed(() => tagChoices(this.selectedTasks()));
  protected readonly atBulkMax = computed(() => this.selectedTasks().length >= BULK_MAX);
  /** Lists a selected task can move to: all but the open one. */
  protected readonly moveTargets = computed<BulkListChoice[]>(() =>
    this.lists
      .lists()
      .filter((l) => l.id !== this.activeListId())
      .map((l) => ({ id: l.id, name: l.name })),
  );

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
      this.undoSub?.unsubscribe();
      this.cancelSearchTimer();
      if (this.addedTimer !== null) {
        clearTimeout(this.addedTimer);
      }
    });
  }

  private onRoute({ list, query }: PageRoute): void {
    // The list, filter, sort or search changed: the selection no longer matches what is shown.
    this.exitSelection();
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
    this.bulkBusy.set(false);
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
          this.applyNext(updated.nextTask);
          this.offerUndo(
            (m) => (done ? m.COMPLETED_MESSAGE : m.REOPENED_MESSAGE),
            (m) => m.planUndo(task, updated),
          );
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

  protected onSaved(task: TaskResult): void {
    const before = this.editing();
    // A reminder that just turned out to be in the past: the dialog stays open and says so.
    const keepOpen =
      before !== null && before.listId === task.listId && reminderJustPassed(before, task);
    this.editing.set(keepOpen ? task : null);
    const moved = before !== null && before.listId !== task.listId;
    if (moved) {
      // Moved to another list: it leaves this view.
      this.list.update((state) => moveOut(state, task.id));
    } else {
      this.applyOrMoveOut(task);
      this.applyNext(task.nextTask);
    }
    if (before !== null) {
      const list = this.lists.byId().get(task.listId)?.name ?? null;
      this.offerUndo(
        (m) => (moved ? m.movedMessage(list) : m.UPDATED_MESSAGE),
        (m) => m.planUndo(before, task),
      );
    }
  }

  protected onDeleted(id: number): void {
    this.deleting.set(null);
    this.list.update((state) => removeTask(state, id));
    this.offerUndo(
      (m) => m.TRASHED_MESSAGE,
      () => ({ kind: 'restore', id }),
    );
  }

  /**
   * The toast with "Undo" for a save or a tick. The undo code is loaded on first use (it stays
   * out of the initial bundle); no plan (the save changed nothing) means no toast.
   */
  private offerUndo(
    message: (logic: UndoLogic) => string,
    plan: (logic: UndoLogic) => UndoPlan | BulkUndoPlan | null,
    alwaysShow = false,
  ): void {
    const offer = ++this.undoOffer;
    const generation = this.generation;
    void import('../undo.logic').then((logic) => {
      const found = plan(logic);
      if (offer !== this.undoOffer) {
        return;
      }
      if (found !== null) {
        this.toast.show({
          message: message(logic),
          action: { label: logic.UNDO_LABEL, run: () => this.undo(found, generation) },
        });
      } else if (alwaysShow) {
        this.toast.show({ message: message(logic) });
      }
    });
  }

  /** Undo: back to how it was before; the list is reloaded when it can't be applied. */
  private undo(plan: UndoPlan | BulkUndoPlan, generation: number): void {
    this.undoSub?.unsubscribe();
    void Promise.all([import('../task-undo'), import('../undo.logic')]).then(
      ([{ TaskUndo }, m]) => {
        const undo = this.injector.get(TaskUndo);
        const run = isBulkPlan(plan)
          ? undo.runBulk(plan)
          : undo.run(plan).pipe(
              map((r) => ({
                tasks: [r.task],
                removedIds: r.removedId === null ? [] : [r.removedId],
                message: r.message,
              })),
            );
        this.undoSub = run.subscribe({
          next: (result) => {
            if (generation !== this.generation) {
              return;
            }
            for (const task of result.tasks) {
              this.list.update((state) => m.reinstate(state, task.id));
              this.applyOrMoveOut(task);
              // Undoing a reopen completes the task again; a repeating one makes a new copy.
              this.applyNext((task as Partial<TaskResult>).nextTask ?? null);
            }
            for (const removed of result.removedIds) {
              this.list.update((state) => removeTask(state, removed));
            }
            if (result.removedIds.length > 0) {
              this.nextNotice.set(null);
            }
            this.toast.show({ message: result.message });
          },
          error: (failure: UndoFailure) => {
            if (failure.message !== null) {
              this.toast.show({ message: failure.message });
              if (generation === this.generation) {
                this.load(false);
              }
            }
          },
        });
      },
    );
  }

  // ----- Bulk actions (0.17.0) -----

  protected startSelecting(): void {
    this.selecting.set(true);
  }

  /** Leaves selection mode and forgets the selection. */
  protected exitSelection(): void {
    this.selecting.set(false);
    this.selectedIds.set(new Set());
    this.anchorId = null;
    this.confirmingBulkDelete.set(false);
  }

  protected isSelected(id: number): boolean {
    return this.selectedIds().has(id);
  }

  /** A row checkbox (or title click in selection mode); Shift extends from the last one. */
  protected onRowSelect(task: Task, { checked, shift }: RowSelect): void {
    if (this.bulkBusy()) {
      return;
    }
    this.selecting.set(true);
    const ids =
      shift && this.anchorId !== null ? rangeIds(this.tasks(), this.anchorId, task.id) : [task.id];
    this.selectedIds.update((current) => {
      if (checked) {
        return addCapped(current, ids);
      }
      const next = new Set(current);
      ids.forEach((id) => next.delete(id));
      return next;
    });
    this.anchorId = task.id;
  }

  /** Every loaded task (never unloaded pages), up to 200. */
  protected selectAllLoaded(): void {
    this.selectedIds.set(
      addCapped(
        new Set(),
        this.tasks().map((t) => t.id),
      ),
    );
  }

  @HostListener('document:keydown.escape')
  protected onEscape(): void {
    if (this.selecting() && !this.bulkBusy() && !this.editing() && !this.deleting()) {
      this.exitSelection();
    }
  }

  protected onBulk(request: BulkRequest): void {
    if (request.action === 'DELETE') {
      this.confirmingBulkDelete.set(true);
      return;
    }
    this.sendBulk(request);
  }

  protected confirmBulkDelete(): void {
    this.sendBulk({ action: 'DELETE' });
  }

  /** One request for the whole selection; on success the list is updated and Undo offered. */
  private sendBulk(request: BulkRequest): void {
    const before = this.selectedTasks();
    if (this.bulkBusy() || before.length === 0) {
      return;
    }
    this.banner.set(null);
    this.bulkBusy.set(true);
    const generation = this.generation;
    const body: BulkActionRequest = {
      ids: before.map((t) => t.id),
      action: request.action,
      ...(request.value === undefined ? {} : { value: request.value }),
    };
    this.tasksApi
      .bulk(body)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (result) => {
          if (generation !== this.generation) {
            return;
          }
          this.bulkBusy.set(false);
          this.applyBulk(request, before, result);
          this.exitSelection();
          const listName =
            typeof request.value === 'number'
              ? (this.lists.byId().get(request.value)?.name ?? null)
              : null;
          this.offerUndo(
            (m) => m.bulkMessage(request.action, before.length, request.value, listName),
            (m) => m.planBulkUndo(request.action, request.value, before, result.tasks),
            true,
          );
        },
        error: (error: unknown) => {
          if (generation !== this.generation) {
            return;
          }
          this.bulkBusy.set(false);
          this.onBulkError(error, request);
        },
      });
  }

  private applyBulk(request: BulkRequest, before: readonly Task[], result: BulkResult): void {
    if (request.action === 'DELETE' || request.action === 'MOVE') {
      // Gone from this view (the trash, or another list; "My tasks" accepts any list id, so
      // a move is removed by id rather than by `belongsHere`).
      for (const task of before) {
        this.list.update((state) =>
          request.action === 'DELETE' ? removeTask(state, task.id) : moveOut(state, task.id),
        );
      }
      return;
    }
    for (const task of result.tasks) {
      this.applyOrMoveOut(task);
      this.applyNext(task.nextTask);
    }
  }

  /** All or nothing: nothing changed, the selection stays, the message says why. */
  private onBulkError(error: unknown, request: BulkRequest): void {
    const failure = bulkFailure(error);
    if (failure.silent) {
      return;
    }
    this.confirmingBulkDelete.set(false);
    if (failure.refreshLists) {
      this.lists.refresh();
    }
    this.banner.set(
      failure.reload
        ? { message: failure.message, retry: () => this.load(), retryLabel: RELOAD_LABEL }
        : {
            message: failure.message,
            retry: failure.message === BULK_ERROR ? () => this.sendBulk(request) : null,
          },
    );
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

  /** The copy a completion created (null: nothing to do). Shown in its place, with a status note. */
  private applyNext(next: Task | null): void {
    if (!next || !this.belongsHere(next)) {
      return;
    }
    const result = applyChange(this.list(), next, this.listQuery(), 'created');
    this.list.set(result.state);
    this.showNext(next);
  }

  private showNext(next: Task): void {
    this.clearAdded();
    if (next.dueDate !== null) {
      this.nextNotice.set(NEXT_SCHEDULED_PREFIX + formatDue(next.dueDate, next.dueTime));
      this.addedTimer = setTimeout(() => {
        this.addedTimer = null;
        this.nextNotice.set(null);
      }, ADDED_NOTICE_MS);
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
    this.nextNotice.set(null);
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
