import { AbstractControl } from '@angular/forms';
import { readProblem } from '../../core/auth/problem';
import { maxTrimmed, requiredTrimmed, SERVER_ERROR_KEY } from '../auth/auth-form';
import { ListProblem, ListProblemCode, TaskList } from './lists.models';

export const LIST_NAME_MAX = 100;
export const listNameValidators = [requiredTrimmed, maxTrimmed(LIST_NAME_MAX)];

export const LIST_NOT_FOUND_MESSAGE = 'List not found';
export const LISTS_LOAD_ERROR = "Couldn't load your lists.";
export const LIST_CREATE_ERROR = "Couldn't create the list. Please try again.";
export const LIST_RENAME_ERROR = "Couldn't rename the list. Please try again.";
export const LIST_DELETE_ERROR = "Couldn't delete the list. Please try again.";
export const LIST_COUNT_ERROR = "Couldn't load the list. Please try again.";
export const LIST_LIMIT_MESSAGE = 'You can have at most 50 lists';

const NAME_MESSAGES: Record<string, string> = {
  REQUIRED: 'Name is required',
  TOO_LONG: 'Name must be 100 characters or fewer',
};

/** Message to show under a list name field: client errors first, then the server's message. */
export function listNameMessage(control: AbstractControl): string | null {
  const errors = control.errors;
  if (!errors) {
    return null;
  }
  for (const [code, message] of Object.entries(NAME_MESSAGES)) {
    if (errors[code]) {
      return message;
    }
  }
  const server = errors[SERVER_ERROR_KEY] as { message?: string } | undefined;
  return server?.message ?? null;
}

/**
 * Puts a 400 `VALIDATION_FAILED` `name` error onto the control (it clears once the value
 * changes). Returns false if the error isn't one.
 */
export function applyListNameError(error: unknown, control: AbstractControl): boolean {
  const problem = readProblem(error) as ListProblem | null;
  if (problem?.status !== 400 || problem.code !== 'VALIDATION_FAILED') {
    return false;
  }
  const fe = (problem.errors ?? []).find((e) => e.field === 'name');
  if (!fe) {
    return false;
  }
  control.setErrors({
    ...(control.errors ?? {}),
    [SERVER_ERROR_KEY]: { code: fe.code, message: fe.message },
  });
  control.markAsTouched();
  return true;
}

/** True if the error is an HTTP error with this status and list problem code. */
export function hasListProblem(error: unknown, status: number, code: ListProblemCode): boolean {
  const problem = readProblem(error) as ListProblem | null;
  return problem?.status === status && problem.code === code;
}

/** 404 `LIST_NOT_FOUND`: the list is not (or no longer) one of mine. */
export function isListNotFound(error: unknown): boolean {
  return hasListProblem(error, 404, 'LIST_NOT_FOUND');
}

/** The `detail` of a problem response, if any. */
export function problemDetail(error: unknown): string | null {
  return readProblem(error)?.detail ?? null;
}

/** Server order: "My tasks" first, then by name ignoring case and accents, then id. */
export function compareLists(a: TaskList, b: TaskList): number {
  if (a.personal !== b.personal) {
    return a.personal ? -1 : 1;
  }
  const byName = a.name.localeCompare(b.name, undefined, { sensitivity: 'base' });
  return byName !== 0 ? byName : a.id - b.id;
}

/**
 * The `:id` of `/lists/:id`: a positive integer in plain digits, else null (the page then shows
 * "List not found" without asking the server).
 */
export function parseListId(raw: string | null): number | null {
  if (raw === null || !/^[1-9]\d*$/.test(raw)) {
    return null;
  }
  const id = Number(raw);
  return Number.isSafeInteger(id) ? id : null;
}

/** "Delete "Home" and its 12 tasks?" (or "Delete "Home"?" when it has none). */
export function deleteListQuestion(name: string, taskCount: number): string {
  if (taskCount === 0) {
    return `Delete "${name}"?`;
  }
  return `Delete "${name}" and its ${taskCount} ${taskCount === 1 ? 'task' : 'tasks'}?`;
}
