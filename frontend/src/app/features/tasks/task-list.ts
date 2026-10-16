/**
 * The loaded part of a paged task list (pure functions; the page owns the signal).
 *
 * Pages come from `GET /api/tasks` in server order and are kept in that order. Local changes
 * (create, edit, tick, delete) are placed with the client comparator, deciding against the
 * boundary: the last task of the last loaded page, as the server sent it. The next page's cursor
 * starts right after that position, so a task that sorts after it will come back via "Load more"
 * and must not be shown yet (it would show twice or out of place).
 */
import { compareTasks, instantMicros, matchesView, titleOrderUncertain } from './tasks.logic';
import { Task, TaskListQuery, TaskPage, TaskSort } from './tasks.models';

export interface TaskListState {
  /** Shown tasks, in list order. */
  items: Task[];
  /** Matching tasks on all pages (from the last page, adjusted by local changes). */
  total: number;
  /** Cursor of the next page; null when everything is loaded. */
  nextCursor: string | null;
  /** Last task of the last non-empty page as received (the cursor's position); null if none. */
  boundary: Task | null;
  /** Ids deleted while paging this list; skipped when a later page still has them. */
  deleted: ReadonlySet<number>;
}

export function emptyList(): TaskListState {
  return { items: [], total: 0, nextCursor: null, boundary: null, deleted: new Set() };
}

export function hasMore(state: TaskListState): boolean {
  return state.nextCursor !== null;
}

/**
 * Appends a page: skips ids deleted in this paging session, de-duplicates by id (a task already
 * shown stays where it is unless the page has a newer version, which then takes the page's
 * place), and re-syncs `total` and the cursor from the page.
 */
export function appendPage(state: TaskListState, page: TaskPage): TaskListState {
  const items = [...state.items];
  for (const task of page.items) {
    if (state.deleted.has(task.id)) {
      continue;
    }
    const i = items.findIndex((t) => t.id === task.id);
    if (i >= 0) {
      if (instantMicros(task.updatedAt) <= instantMicros(items[i].updatedAt)) {
        continue;
      }
      items.splice(i, 1);
    }
    items.push(task);
  }
  return {
    items,
    total: page.total,
    nextCursor: page.nextCursor,
    boundary: page.items.length > 0 ? page.items[page.items.length - 1] : state.boundary,
    deleted: state.deleted,
  };
}

/** The first page of a new paging session. */
export function firstPage(page: TaskPage): TaskListState {
  return appendPage(emptyList(), page);
}

/** Inserts `task` before the first item that sorts after it (the rest keep the server order). */
export function insertSorted(items: readonly Task[], task: Task, sort: TaskSort): Task[] {
  const i = items.findIndex((t) => compareTasks(task, t, sort) < 0);
  const at = i < 0 ? items.length : i;
  return [...items.slice(0, at), task, ...items.slice(at)];
}

/**
 * True if `task` belongs in the loaded part: everything is loaded, or it sorts at or before the
 * boundary, or (title sort) its place next to the boundary is uncertain — then it is kept, since
 * a wrong drop would skip it while a wrong keep only misplaces a row.
 */
export function fitsLoaded(state: TaskListState, task: Task, sort: TaskSort): boolean {
  const boundary = state.boundary;
  if (!hasMore(state) || boundary === null) {
    return true;
  }
  return compareTasks(task, boundary, sort) <= 0 || titleOrderUncertain(task, boundary, sort);
}

export type TaskChange = 'created' | 'updated';

export interface ChangeResult {
  state: TaskListState;
  /** A created task that isn't shown: the page says "Task added". */
  addedHidden: boolean;
}

/**
 * Applies a created or updated task (as returned by the server):
 * - doesn't match tab / tag / search: removed (an update of a shown task: total − 1; a create:
 *   total unchanged, "Task added");
 * - matches and fits the loaded part: inserted or moved to its sorted place (total + 1 if it
 *   wasn't counted: a create, or an update of a task that wasn't shown);
 * - matches but sorts after the boundary: removed, it comes back via "Load more" (a create:
 *   total + 1 and "Task added"; an update: total unchanged).
 */
export function applyChange(
  state: TaskListState,
  task: Task,
  query: TaskListQuery,
  change: TaskChange,
): ChangeResult {
  const shown = state.items.some((t) => t.id === task.id);
  const others = state.items.filter((t) => t.id !== task.id);
  const created = change === 'created';

  if (!matchesView(task, query)) {
    const total = shown && !created ? Math.max(0, state.total - 1) : state.total;
    return { state: { ...state, items: others, total }, addedHidden: created };
  }

  const counted = shown && !created;
  const total = counted ? state.total : state.total + 1;
  if (fitsLoaded(state, task, query.sort)) {
    const items = insertSorted(others, task, query.sort);
    return { state: { ...state, items, total }, addedHidden: false };
  }
  return { state: { ...state, items: others, total }, addedHidden: created };
}

/** A deleted task: removed (total − 1 if it was shown) and skipped on later pages. */
export function removeTask(state: TaskListState, id: number): TaskListState {
  const shown = state.items.some((t) => t.id === id);
  const deleted = new Set(state.deleted);
  deleted.add(id);
  return {
    ...state,
    items: state.items.filter((t) => t.id !== id),
    total: shown ? Math.max(0, state.total - 1) : state.total,
    deleted,
  };
}

/** Number for "of N": never fewer than what is shown. */
export function shownTotal(state: TaskListState): number {
  return Math.max(state.total, state.items.length);
}
