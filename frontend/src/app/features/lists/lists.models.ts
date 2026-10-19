/** Models matching the list schemas in `contracts/openapi.yaml` (v0.13.0) exactly. */
import { Problem } from '../../core/auth/auth.models';

/** `ListRole` (0.13.0): my role in a list, or a member's role. */
export type ListRole = 'OWNER' | 'MEMBER';

/**
 * `TaskList`: a list I'm a member of, owned or shared (`GET /api/lists`, `POST /api/lists`,
 * `PATCH /api/lists/{id}`, `POST /api/invitations/{id}/accept`).
 */
export interface TaskList {
  id: number;
  /** Stored stripped; 1–100 characters; not unique. */
  name: string;
  /** True only for "My tasks": renamable, can't be deleted, listed first, the default list. */
  personal: boolean;
  /** My role in this list (0.13.0). Only the owner renames, deletes, invites and removes. */
  role: ListRole;
  /** Members right now, the owner included (0.13.0; 1 = not shared, at most 20). */
  memberCount: number;
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

/** List-related problem codes (0.12.0; `OWNER_ONLY` 0.13.0). */
export type ListProblemCode = 'LIST_NOT_FOUND' | 'LIST_LIMIT' | 'PERSONAL_LIST' | 'OWNER_ONLY';

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
