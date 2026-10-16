import { hasTag } from './task-tags';
import { Priority, Task, TaskFilter, TaskStatus } from './tasks.models';

const FILTERS: readonly TaskFilter[] = ['all', 'active', 'in_progress', 'done'];

/** Exact, case-sensitive match; anything else (missing, empty, unknown) is `all`. */
export function normaliseFilter(value: string | null | undefined): TaskFilter {
  return FILTERS.find((f) => f === value) ?? 'all';
}

/** `?tag=` value: missing, empty or blank means no tag filter. Otherwise the raw value. */
export function normaliseTagParam(value: string | null | undefined): string | null {
  return value && value.trim() ? value : null;
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

/**
 * Server order: not done (To do and In progress mixed) first, then done; each group createdAt
 * desc, then id desc. Priority never changes the order.
 */
export function compareTasks(a: Task, b: Task): number {
  if (isDone(a) !== isDone(b)) {
    return isDone(a) ? 1 : -1;
  }
  const byCreated = instantMicros(b.createdAt) - instantMicros(a.createdAt);
  return byCreated !== 0 ? byCreated : b.id - a.id;
}

export function sortTasks(tasks: readonly Task[]): Task[] {
  return [...tasks].sort(compareTasks);
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
