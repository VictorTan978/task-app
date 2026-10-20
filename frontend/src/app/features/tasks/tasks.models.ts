/** Models matching the task schemas in `contracts/openapi.yaml` (v0.15.0) exactly. */
import { Problem } from '../../core/auth/auth.models';

/** `Priority`: `NONE` is the default (no badge). */
export type Priority = 'NONE' | 'LOW' | 'MEDIUM' | 'HIGH';

/** `Status`: `DONE` if and only if `done` is true. */
export type TaskStatus = 'TODO' | 'IN_PROGRESS' | 'DONE';

/** `Task.addedBy` (0.13.0). */
export interface TaskAddedBy {
  displayName: string;
}

/** `Reminder` (0.14.0): my reminder offset. Timed tasks use the first four, date-only the last two. */
export type Reminder = 'AT_DUE' | 'TEN_MINUTES' | 'ONE_HOUR' | 'ONE_DAY' | 'ON_THE_DAY';

/** `Weekday` (0.15.0). */
export type Weekday = 'MON' | 'TUE' | 'WED' | 'THU' | 'FRI' | 'SAT' | 'SUN';

export type RepeatFrequency = 'DAILY' | 'WEEKLY' | 'MONTHLY' | 'YEARLY';

/** `RepeatRule` (0.15.0). In responses `weekdays` is always present (Monday first, `[]` unless WEEKLY). */
export interface RepeatRule {
  frequency: RepeatFrequency;
  weekdays: Weekday[];
}

export interface Task {
  id: number;
  /** The list the task is in (0.12.0; always present). */
  listId: number;
  /**
   * Who added the task (0.13.0; always present): their current display name, or null when that
   * account was deleted. Shown only on lists with 2 or more members.
   */
  addedBy: TaskAddedBy | null;
  title: string;
  /** Never an empty string; null when absent. */
  description: string | null;
  priority: Priority;
  status: TaskStatus;
  /** Tag names in the stored spelling, sorted by the server; `[]` when none. Show as returned. */
  tags: string[];
  /** Read-only mirror of `status === 'DONE'`. The UI reads `status` instead. */
  done: boolean;
  /** Calendar date `YYYY-MM-DD`, or null. */
  dueDate: string | null;
  /** Wall-clock `HH:mm` (0.14.0), only with `dueDate`; shown as stored, no conversion. */
  dueTime: string | null;
  /** My own reminder on this task (0.14.0), or null. */
  myReminder: Reminder | null;
  /** True when my reminder time is already in the past (0.14.0). */
  myReminderPassed: boolean;
  /** The repeat rule shared by all members (0.15.0), or null. */
  repeat: RepeatRule | null;
  /** UTC instant with `Z` suffix and 0–6 fractional digits. */
  createdAt: string;
  updatedAt: string;
}

export interface CreateTaskRequest {
  title: string;
  description?: string | null;
  dueDate?: string | null;
  /** One of my lists (0.12.0); missing or null → "My tasks". */
  listId?: number | null;
}

/**
 * `PUT /api/tasks/{id}`. The UI always sends title, description, dueDate, priority, status and
 * tags. `done` is also accepted by the contract but this client never sends it (PM decision).
 * Missing or null `priority` / `status` / `tags` leave the stored value unchanged.
 */
export interface ReplaceTaskRequest {
  title: string;
  description: string | null;
  dueDate: string | null;
  priority?: Priority | null;
  status?: TaskStatus | null;
  tags?: string[] | null;
  /** `HH:mm` or null (no time). Always sent; the server clears it with the date. */
  dueTime?: string | null;
  /** Missing or null leaves my reminder unchanged; `NONE` clears it. */
  reminder?: Reminder | 'NONE' | null;
  /**
   * Another of my lists moves the task there (0.12.0). Missing, null or the current list → it
   * stays. The UI sends it only when the user picked another list.
   */
  listId?: number | null;
  /** Missing or null leaves the rule unchanged (0.15.0); `NEVER` removes it; a rule sets it. Needs `dueDate`. */
  repeat?: RepeatRule | 'NEVER' | null;
}

/** Body of `PUT` and `PATCH /api/tasks/{id}` (0.15.0): the task plus the copy a completion created. */
export type TaskResult = Task & { nextTask: Task | null };

/** `PATCH /api/tasks/{id}`: at least one of `done` and `status`. The UI sends `status`. */
export interface SetTaskDoneRequest {
  done?: boolean | null;
  status?: TaskStatus | null;
}

/** An item of `GET /api/tags`. */
export interface Tag {
  name: string;
}

export type TaskFilter = 'all' | 'active' | 'in_progress' | 'done';

/** `GET /api/tasks?sort=`. `newest` is the default and is never sent. */
export type TaskSort = 'newest' | 'oldest' | 'due' | 'priority' | 'title';

/** `TaskPage`: one page (at most 50) of `GET /api/tasks`. */
export interface TaskPage {
  items: Task[];
  /** Tasks matching filter + tag + q on all pages, recounted for every page. */
  total: number;
  /** Opaque; `null` on the last page. */
  nextCursor: string | null;
}

/** Which tasks the list shows, as read from the URL (already normalised). */
export interface TaskListQuery {
  filter: TaskFilter;
  /** Raw `?tag=` value; null when missing or blank. */
  tag: string | null;
  /** Trimmed search text, at most 100 characters; null when blank. */
  q: string | null;
  sort: TaskSort;
}

/** Task-related values of `FieldError.field`. */
export type TaskFieldName =
  'title' | 'description' | 'done' | 'status' | 'tags' | 'dueTime' | 'reminder' | 'repeat';

/** `FieldError.field` values of a 400 from `GET /api/tasks`. */
export type TaskListFieldName = 'q' | 'cursor';

/** `FieldError` of a 400 from `GET /api/tasks` (`q` / `TOO_LONG`, `cursor` / `INVALID_CURSOR`). */
export interface TaskListFieldError {
  field: TaskListFieldName;
  code: 'TOO_LONG' | 'INVALID_CURSOR';
  message: string;
}

/** Task-related values of `FieldError.code`. */
export type TaskFieldErrorCode =
  | 'REQUIRED'
  | 'TOO_LONG'
  | 'INVALID_CHARACTERS'
  | 'TOO_MANY'
  | 'TAG_LIMIT'
  | 'STATUS_CONFLICT'
  | 'INVALID_REMINDER'
  | 'REQUIRES_DUE_DATE'
  | 'INVALID_REPEAT';

export interface TaskFieldError {
  field: TaskFieldName;
  code: TaskFieldErrorCode;
  message: string;
}

/** `Problem` whose `errors` refer to task fields. */
export type TaskProblem = Omit<Problem, 'errors'> & { errors?: TaskFieldError[] };

/** `Problem` from `GET /api/tasks`. */
export type TaskListProblem = Omit<Problem, 'errors'> & { errors?: TaskListFieldError[] };
