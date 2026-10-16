/** Models matching the task schemas in `contracts/openapi.yaml` (v0.10.0) exactly. */
import { Problem } from '../../core/auth/auth.models';

/** `Priority`: `NONE` is the default (no badge). */
export type Priority = 'NONE' | 'LOW' | 'MEDIUM' | 'HIGH';

/** `Status`: `DONE` if and only if `done` is true. */
export type TaskStatus = 'TODO' | 'IN_PROGRESS' | 'DONE';

export interface Task {
  id: number;
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
  /** UTC instant with `Z` suffix and 0–6 fractional digits. */
  createdAt: string;
  updatedAt: string;
}

export interface CreateTaskRequest {
  title: string;
  description?: string | null;
  dueDate?: string | null;
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
}

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

/** Task-related values of `FieldError.field`. */
export type TaskFieldName = 'title' | 'description' | 'done' | 'status' | 'tags';

/** Task-related values of `FieldError.code`. */
export type TaskFieldErrorCode =
  'REQUIRED' | 'TOO_LONG' | 'INVALID_CHARACTERS' | 'TOO_MANY' | 'TAG_LIMIT' | 'STATUS_CONFLICT';

export interface TaskFieldError {
  field: TaskFieldName;
  code: TaskFieldErrorCode;
  message: string;
}

/** `Problem` whose `errors` refer to task fields. */
export type TaskProblem = Omit<Problem, 'errors'> & { errors?: TaskFieldError[] };
