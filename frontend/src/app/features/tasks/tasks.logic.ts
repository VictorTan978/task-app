import { hasTag } from './task-tags';
import { Priority, Task, TaskFilter, TaskListQuery, TaskSort, TaskStatus } from './tasks.models';

const FILTERS: readonly TaskFilter[] = ['all', 'active', 'in_progress', 'done'];
const SORTS: readonly TaskSort[] = ['newest', 'oldest', 'due', 'priority', 'title'];

/** Longest search the API accepts (UTF-16 code units, after trimming). */
export const SEARCH_MAX = 100;

/** Exact, case-sensitive match; anything else (missing, empty, unknown) is `all`. */
export function normaliseFilter(value: string | null | undefined): TaskFilter {
  return FILTERS.find((f) => f === value) ?? 'all';
}

/** `?tag=` value: missing, empty or blank means no tag filter. Otherwise the raw value. */
export function normaliseTagParam(value: string | null | undefined): string | null {
  return value && value.trim() ? value : null;
}

/** Exact, case-sensitive match; anything else (missing, empty, unknown) is `newest`. */
export function normaliseSort(value: string | null | undefined): TaskSort {
  return SORTS.find((s) => s === value) ?? 'newest';
}

/**
 * `?q=` value or typed search: trimmed; blank means no search (null). Longer than 100 characters
 * (e.g. a hand-edited URL) is cut to 100 so the API never answers `TOO_LONG`; a cut never splits a
 * surrogate pair (a lone surrogate can't be URL-encoded).
 */
export function normaliseQuery(value: string | null | undefined): string | null {
  let q = (value ?? '').trim();
  if (q.length > SEARCH_MAX) {
    const end = /[\uD800-\uDBFF]/.test(q.charAt(SEARCH_MAX - 1)) ? SEARCH_MAX - 1 : SEARCH_MAX;
    q = q.slice(0, end).trim();
  }
  return q ? q : null;
}

const MARKS = /\p{M}/gu;

/** Search matching key: accents removed, lower case. A client-side hint only. */
export function searchKey(value: string): string {
  return value.normalize('NFD').replace(MARKS, '').toLowerCase();
}

/**
 * No search, or the title or description contains the text (ignoring case and accents). A hint:
 * the server's collation decides (e.g. it also treats `ß` as `ss`).
 */
export function matchesQuery(task: Task, q: string | null): boolean {
  if (q === null) {
    return true;
  }
  const key = searchKey(q);
  return (
    searchKey(task.title).includes(key) ||
    (task.description !== null && searchKey(task.description).includes(key))
  );
}

export function isDone(task: Task): boolean {
  return task.status === 'DONE';
}

export function matchesFilter(task: Task, filter: TaskFilter): boolean {
  switch (filter) {
    case 'all':
      return true;
    case 'active':
      return !isDone(task);
    case 'in_progress':
      return task.status === 'IN_PROGRESS';
    case 'done':
      return isDone(task);
  }
}

/** No tag filter, or the task has the tag (ignoring case and accents, a client-side hint). */
export function matchesTag(task: Task, tag: string | null): boolean {
  return tag === null || hasTag(task.tags, tag);
}

const INSTANT = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,9}))?Z$/;

/**
 * Microseconds since the epoch for a UTC `...Z` timestamp with 0–9 fractional digits.
 * Parsed by hand so 6-digit fractions keep their precision in every browser.
 */
export function instantMicros(value: string): number {
  const m = INSTANT.exec(value);
  if (!m) {
    const ms = Date.parse(value);
    return Number.isNaN(ms) ? 0 : ms * 1000;
  }
  const [, y, mo, d, h, mi, s, frac = ''] = m;
  const ms = Date.UTC(+y, +mo - 1, +d, +h, +mi, +s);
  const micros = Number((frac + '000000').slice(0, 6));
  return ms * 1000 + micros;
}

/** True if the task matches the list's tab, tag and search (client-side hint). */
export function matchesView(task: Task, query: TaskListQuery): boolean {
  return (
    matchesFilter(task, query.filter) && matchesTag(task, query.tag) && matchesQuery(task, query.q)
  );
}

/** `newest`: createdAt desc, then id desc. Also the tie-break of due, priority and title. */
export function compareNewest(a: Task, b: Task): number {
  const byCreated = instantMicros(b.createdAt) - instantMicros(a.createdAt);
  return byCreated !== 0 ? byCreated : b.id - a.id;
}

const PRIORITY_RANK: Record<Priority, number> = { HIGH: 0, MEDIUM: 1, LOW: 2, NONE: 3 };

/** Ignores case and accents like the server's `utf8mb4_0900_ai_ci` (close, not identical). */
const TITLE_COLLATOR = new Intl.Collator('en', { sensitivity: 'base' });

/** Due date ascending (`YYYY-MM-DD` compares as text); no date last. */
function compareDue(a: Task, b: Task): number {
  if (a.dueDate === b.dueDate) {
    return 0;
  }
  if (a.dueDate === null || b.dueDate === null) {
    return a.dueDate === null ? 1 : -1;
  }
  return a.dueDate < b.dueDate ? -1 : 1;
}

/** Order inside one group by the sort key only (0 = tie). */
function compareKey(a: Task, b: Task, sort: TaskSort): number {
  switch (sort) {
    case 'newest':
      return compareNewest(a, b);
    case 'oldest':
      return -compareNewest(a, b);
    case 'due':
      return compareDue(a, b);
    case 'priority':
      return PRIORITY_RANK[a.priority] - PRIORITY_RANK[b.priority];
    case 'title':
      return TITLE_COLLATOR.compare(a.title, b.title);
  }
}

/** Not done (To do and In progress mixed) first, then done. */
function compareGroup(a: Task, b: Task): number {
  return isDone(a) === isDone(b) ? 0 : isDone(a) ? 1 : -1;
}

/**
 * Server order (contract `GET /api/tasks` "Order"): the done group last, then the sort key, then
 * ties newest first. Exact for every sort except `title`, where the collation is a close hint.
 */
export function compareTasks(a: Task, b: Task, sort: TaskSort = 'newest'): number {
  return compareGroup(a, b) || compareKey(a, b, sort) || compareNewest(a, b);
}

/**
 * True if the browser can't be sure how `a` and `b` order on the server: same group, title sort,
 * and the titles are equal for the collator or the collator and a plain folded comparison
 * disagree (punctuation, special letters).
 */
export function titleOrderUncertain(a: Task, b: Task, sort: TaskSort): boolean {
  if (sort !== 'title' || compareGroup(a, b) !== 0) {
    return false;
  }
  const collated = Math.sign(TITLE_COLLATOR.compare(a.title, b.title));
  const ka = searchKey(a.title);
  const kb = searchKey(b.title);
  const folded = ka === kb ? 0 : ka < kb ? -1 : 1;
  return collated === 0 || collated !== folded;
}

export function sortTasks(tasks: readonly Task[], sort: TaskSort = 'newest'): Task[] {
  return [...tasks].sort((a, b) => compareTasks(a, b, sort));
}

/**
 * Inserts or replaces `task`, drops it if it no longer matches `filter` and `tag`, and re-sorts.
 */
export function applyTask(
  tasks: readonly Task[],
  task: Task,
  filter: TaskFilter,
  tag: string | null = null,
): Task[] {
  const others = tasks.filter((t) => t.id !== task.id);
  const keep = matchesFilter(task, filter) && matchesTag(task, tag);
  return sortTasks(keep ? [...others, task] : others);
}

/** Today's date in the browser's local time zone as `YYYY-MM-DD`. */
export function todayLocal(now: Date = new Date()): string {
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`;
}

/** Not done and due strictly before today. Compares `YYYY-MM-DD` strings (no Date parsing). */
export function isOverdue(task: Task, today: string): boolean {
  return !isDone(task) && task.dueDate !== null && task.dueDate < today;
}

export const PRIORITY_LABELS: Record<Priority, string> = {
  NONE: 'None',
  LOW: 'Low',
  MEDIUM: 'Medium',
  HIGH: 'High',
};

export const STATUS_LABELS: Record<TaskStatus, string> = {
  TODO: 'To do',
  IN_PROGRESS: 'In progress',
  DONE: 'Done',
};

export const PRIORITIES: readonly Priority[] = ['NONE', 'LOW', 'MEDIUM', 'HIGH'];
export const STATUSES: readonly TaskStatus[] = ['TODO', 'IN_PROGRESS', 'DONE'];
