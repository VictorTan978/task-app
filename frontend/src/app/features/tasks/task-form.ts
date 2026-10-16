import { HttpErrorResponse } from '@angular/common/http';
import { AbstractControl } from '@angular/forms';
import { readProblem } from '../../core/auth/problem';
import { maxTrimmed, requiredTrimmed, SERVER_ERROR_KEY } from '../auth/auth-form';
import { TaskFieldName, TaskListProblem, TaskProblem } from './tasks.models';

export const TITLE_MAX = 200;
export const DESCRIPTION_MAX = 2000;

export const titleValidators = [requiredTrimmed, maxTrimmed(TITLE_MAX)];
export const descriptionValidators = [maxTrimmed(DESCRIPTION_MAX)];

export const TASK_NOT_FOUND_MESSAGE = 'This task no longer exists';
export const LOAD_ERROR = "Couldn't load your tasks. Please try again.";
export const LOAD_MORE_ERROR = "Couldn't load more tasks. Please try again.";
export const TASK_ADDED_MESSAGE = 'Task added';
export const ADD_ERROR = "Couldn't add the task. Please try again.";
export const TOGGLE_ERROR = "Couldn't update the task. Please try again.";
export const SAVE_ERROR = "Couldn't save the task. Please try again.";
export const DELETE_ERROR = "Couldn't delete the task. Please try again.";

const CLIENT_MESSAGES: Record<TaskFieldName, Record<string, string>> = {
  title: {
    REQUIRED: 'Title is required',
    TOO_LONG: 'Title must be 200 characters or fewer',
  },
  description: {
    TOO_LONG: 'Description must be 2,000 characters or fewer',
  },
  done: {},
  status: {},
  tags: {},
};

interface ServerError {
  code: string;
  message: string;
}

/** Message to show under a task field: client errors first, then the server's own message. */
export function taskFieldMessage(field: TaskFieldName, control: AbstractControl): string | null {
  const errors = control.errors;
  if (!errors) {
    return null;
  }
  for (const [code, message] of Object.entries(CLIENT_MESSAGES[field])) {
    if (errors[code]) {
      return message;
    }
  }
  const server = errors[SERVER_ERROR_KEY] as ServerError | undefined;
  return server?.message ?? null;
}

/** 400 VALIDATION_FAILED problem with field errors, else null. */
export function taskValidationProblem(error: unknown): TaskProblem | null {
  const problem = readProblem(error) as TaskProblem | null;
  return problem?.status === 400 && problem.code === 'VALIDATION_FAILED' && problem.errors?.length
    ? problem
    : null;
}

/**
 * Puts server field errors onto the matching controls (they clear once the value changes).
 * Returns false if none matched.
 */
export function applyTaskFieldErrors(
  problem: TaskProblem,
  controls: Partial<Record<TaskFieldName, AbstractControl>>,
): boolean {
  let applied = false;
  for (const fe of problem.errors ?? []) {
    const control = controls[fe.field];
    if (control) {
      control.setErrors({
        ...(control.errors ?? {}),
        [SERVER_ERROR_KEY]: { code: fe.code, message: fe.message } satisfies ServerError,
      });
      control.markAsTouched();
      applied = true;
    }
  }
  return applied;
}

/** 400 `VALIDATION_FAILED` with `cursor` / `INVALID_CURSOR`: restart the list from page 1. */
export function isInvalidCursor(error: unknown): boolean {
  const problem = readProblem(error) as TaskListProblem | null;
  return (
    problem?.status === 400 &&
    problem.code === 'VALIDATION_FAILED' &&
    (problem.errors ?? []).some((e) => e.field === 'cursor' && e.code === 'INVALID_CURSOR')
  );
}

export function isNotFound(error: unknown): boolean {
  return error instanceof HttpErrorResponse && error.status === 404;
}

/** 401 is handled globally (redirect to /login); components should stay quiet. */
export function isUnauthenticated(error: unknown): boolean {
  return error instanceof HttpErrorResponse && error.status === 401;
}

/** Trimmed description, blank becomes null (never ""). */
export function normaliseDescription(value: string): string | null {
  const trimmed = value.trim();
  return trimmed ? trimmed : null;
}
