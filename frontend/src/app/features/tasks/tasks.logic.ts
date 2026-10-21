import { hasTag } from './task-tags';
import {
  Priority,
  Reminder,
  RepeatFrequency,
  RepeatRule,
  Task,
  TaskFilter,
  TaskListQuery,
  TaskSort,
  TaskStatus,
  Weekday,
} from './tasks.models';

const FILTERS: readonly TaskFilter[] = ['all', 'active', 'in_progress', 'done'];
const SORTS: readonly TaskSort[] = ['newest', 'oldest', 'due', 'priority', 'title', 'manual'];

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

/**
 * Due date ascending (`YYYY-MM-DD` compares as text); within a date timed tasks by time first,
 * then date-only; no date last.
 */
function compareDue(a: Task, b: Task): number {
  if (a.dueDate === b.dueDate) {
    if (a.dueTime === b.dueTime) {
      return 0;
    }
    if (a.dueTime === null || b.dueTime === null) {
      return a.dueTime === null ? 1 : -1;
    }
    return a.dueTime < b.dueTime ? -1 : 1;
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
    case 'manual':
      // The shared position is hidden from the client; the page places rows itself.
      return 0;
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

/**
 * Not done and past due: a date-only task when its date is before `today`, a timed task when its
 * date + time is before `today` + `time` (`HH:mm`, both in the account zone). Compares strings
 * (no Date parsing). Without `time` a timed task is overdue from the next day.
 */
export function isOverdue(task: Task, today: string, time = ''): boolean {
  if (isDone(task) || task.dueDate === null) {
    return false;
  }
  if (task.dueDate !== today) {
    return task.dueDate < today;
  }
  return task.dueTime !== null && time !== '' && task.dueTime < time;
}

export const REMINDER_LABELS: Record<Reminder, string> = {
  AT_DUE: 'At due time',
  TEN_MINUTES: '10 minutes before',
  ONE_HOUR: '1 hour before',
  ONE_DAY: '1 day before',
  ON_THE_DAY: 'On the day at 09:00',
};

/** Date-only label of `ONE_DAY` differs: it fires at 09:00 the day before. */
export const DATE_ONLY_ONE_DAY_LABEL = '1 day before at 09:00';

export const TIMED_REMINDERS: readonly Reminder[] = [
  'AT_DUE',
  'TEN_MINUTES',
  'ONE_HOUR',
  'ONE_DAY',
];
export const DATE_ONLY_REMINDERS: readonly Reminder[] = ['ON_THE_DAY', 'ONE_DAY'];

/** Reminder choices (without "None") for a task with or without a due time. */
export function reminderOptions(timed: boolean): { value: Reminder; label: string }[] {
  return (timed ? TIMED_REMINDERS : DATE_ONLY_REMINDERS).map((value) => ({
    value,
    label: !timed && value === 'ONE_DAY' ? DATE_ONLY_ONE_DAY_LABEL : REMINDER_LABELS[value],
  }));
}

/** The task's date, with the time when it has one (`2026-10-21 09:30`). */
export function formatDue(dueDate: string, dueTime: string | null): string {
  return dueTime ? `${dueDate} ${dueTime}` : dueDate;
}

/**
 * My reminder is set and in the past after a save that changed it (or the date or time), or it
 * just became past: the dialog stays open to say so.
 */
export function reminderJustPassed(before: Task, after: Task): boolean {
  if (after.myReminder === null || !after.myReminderPassed) {
    return false;
  }
  return (
    !before.myReminderPassed ||
    before.myReminder !== after.myReminder ||
    before.dueDate !== after.dueDate ||
    before.dueTime !== after.dueTime
  );
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

/** Weekdays in the order the picker shows them (Monday first). */
export const WEEKDAYS: readonly { value: Weekday; label: string }[] = [
  { value: 'MON', label: 'Mon' },
  { value: 'TUE', label: 'Tue' },
  { value: 'WED', label: 'Wed' },
  { value: 'THU', label: 'Thu' },
  { value: 'FRI', label: 'Fri' },
  { value: 'SAT', label: 'Sat' },
  { value: 'SUN', label: 'Sun' },
];

export const REPEAT_OPTIONS: readonly { value: RepeatFrequency | 'NEVER'; label: string }[] = [
  { value: 'NEVER', label: 'Never' },
  { value: 'DAILY', label: 'Daily' },
  { value: 'WEEKLY', label: 'Weekly' },
  { value: 'MONTHLY', label: 'Monthly' },
  { value: 'YEARLY', label: 'Yearly' },
];

const FREQUENCY_ADVERB: Record<RepeatFrequency, string> = {
  DAILY: 'daily',
  WEEKLY: 'weekly',
  MONTHLY: 'monthly',
  YEARLY: 'yearly',
};

/** Accessible text of the row icon: "Repeats daily" / "Repeats weekly (Mon, Wed)". */
export function repeatSummary(rule: RepeatRule): string {
  const base = `Repeats ${FREQUENCY_ADVERB[rule.frequency]}`;
  if (rule.frequency !== 'WEEKLY' || rule.weekdays.length === 0) {
    return base;
  }
  const days = WEEKDAYS.filter((d) => rule.weekdays.includes(d.value)).map((d) => d.label);
  return `${base} (${days.join(', ')})`;
}

/** The weekday of a `YYYY-MM-DD` calendar date, or null if it isn't one. */
export function weekdayOf(date: string): Weekday | null {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(date);
  if (!m) {
    return null;
  }
  const d = new Date(Date.UTC(Number(m[1]), Number(m[2]) - 1, Number(m[3])));
  // getUTCDay: 0 = Sunday.
  return WEEKDAYS[(d.getUTCDay() + 6) % 7].value;
}

const MONTH_NAMES = [
  'January',
  'February',
  'March',
  'April',
  'May',
  'June',
  'July',
  'August',
  'September',
  'October',
  'November',
  'December',
];

function ordinal(n: number): string {
  const rem = n % 100;
  if (rem >= 11 && rem <= 13) {
    return `${n}th`;
  }
  return `${n}${({ 1: 'st', 2: 'nd', 3: 'rd' } as Record<number, string>)[n % 10] ?? 'th'}`;
}

/** Hint under the Repeat select for monthly and yearly rules (null for the others). */
export function repeatHint(frequency: RepeatFrequency | 'NEVER', dueDate: string): string | null {
  const m = /^\d{4}-(\d{2})-(\d{2})$/.exec(dueDate);
  if (!m || (frequency !== 'MONTHLY' && frequency !== 'YEARLY')) {
    return null;
  }
  const month = Number(m[1]);
  const day = Number(m[2]);
  if (frequency === 'MONTHLY') {
    return day > 28
      ? `Repeats on the ${ordinal(day)}; shorter months use the last day`
      : `Repeats on the ${ordinal(day)} of every month`;
  }
  if (month === 2 && day === 29) {
    return 'Repeats every February 29; in other years on February 28';
  }
  return `Repeats every ${MONTH_NAMES[month - 1]} ${day}`;
}
