import { Task, TaskFilter } from './tasks.models';

const FILTERS: readonly TaskFilter[] = ['all', 'active', 'done'];

/** Exact, case-sensitive match; anything else (missing, empty, unknown) is `all`. */
export function normaliseFilter(value: string | null | undefined): TaskFilter {
  return FILTERS.find((f) => f === value) ?? 'all';
}

export function matchesFilter(task: Task, filter: TaskFilter): boolean {
  return filter === 'all' || (filter === 'done') === task.done;
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

/** Server order: not done first, then done; each group createdAt desc, then id desc. */
export function compareTasks(a: Task, b: Task): number {
  if (a.done !== b.done) {
    return a.done ? 1 : -1;
  }
  const byCreated = instantMicros(b.createdAt) - instantMicros(a.createdAt);
  return byCreated !== 0 ? byCreated : b.id - a.id;
}

export function sortTasks(tasks: readonly Task[]): Task[] {
  return [...tasks].sort(compareTasks);
}

/**
 * Inserts or replaces `task`, drops it if it no longer matches `filter`, and re-sorts.
 */
export function applyTask(tasks: readonly Task[], task: Task, filter: TaskFilter): Task[] {
  const others = tasks.filter((t) => t.id !== task.id);
  return sortTasks(matchesFilter(task, filter) ? [...others, task] : others);
}

/** Today's date in the browser's local time zone as `YYYY-MM-DD`. */
export function todayLocal(now: Date = new Date()): string {
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`;
}

/** Not done and due strictly before today. Compares `YYYY-MM-DD` strings (no Date parsing). */
export function isOverdue(task: Task, today: string): boolean {
  return !task.done && task.dueDate !== null && task.dueDate < today;
}
