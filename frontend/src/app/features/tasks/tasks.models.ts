/** Models matching the task schemas in `contracts/openapi.yaml` (v0.2.0) exactly. */
import { FieldErrorCode, Problem } from '../../core/auth/auth.models';

export interface Task {
  id: number;
  title: string;
  /** Never an empty string; null when absent. */
  description: string | null;
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

/** Full replacement: always send all four properties. */
export interface ReplaceTaskRequest {
  title: string;
  description: string | null;
  dueDate: string | null;
  done: boolean;
}

export interface SetTaskDoneRequest {
  done: boolean;
}

export type TaskFilter = 'all' | 'active' | 'done';

/** Task-related values of `FieldError.field`. */
export type TaskFieldName = 'title' | 'description' | 'done';

export interface TaskFieldError {
  field: TaskFieldName;
  code: FieldErrorCode;
  message: string;
}

/** `Problem` whose `errors` refer to task fields. */
export type TaskProblem = Omit<Problem, 'errors'> & { errors?: TaskFieldError[] };
