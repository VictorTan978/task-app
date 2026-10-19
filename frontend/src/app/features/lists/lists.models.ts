/** Models matching the list schemas in `contracts/openapi.yaml` (v0.12.0) exactly. */
import { Problem } from '../../core/auth/auth.models';

/** `TaskList`: one of my lists (`GET /api/lists`, `POST /api/lists`, `PATCH /api/lists/{id}`). */
export interface TaskList {
  id: number;
  /** Stored stripped; 1–100 characters; not unique. */
  name: string;
  /** True only for "My tasks": renamable, can't be deleted, listed first, the default list. */
  personal: boolean;
}

/** `TaskListDetails`: `TaskList` plus its task count (every status); only `GET /api/lists/{id}`. */
export interface TaskListDetails extends TaskList {
  taskCount: number;
}

/** `CreateListRequest`: body of `POST /api/lists`. */
export interface CreateListRequest {
  name: string;
}

/** `RenameListRequest`: body of `PATCH /api/lists/{id}`. */
export interface RenameListRequest {
  name: string;
}

/** List-related problem codes (0.12.0). */
export type ListProblemCode = 'LIST_NOT_FOUND' | 'LIST_LIMIT' | 'PERSONAL_LIST';

/** `FieldError` of a list 400: only `name`. */
export interface ListFieldError {
  field: 'name';
  code: 'REQUIRED' | 'TOO_LONG';
  message: string;
}

/** `Problem` from the list endpoints. */
export type ListProblem = Omit<Problem, 'code' | 'errors'> & {
  code: Problem['code'] | ListProblemCode;
  errors?: ListFieldError[];
};
