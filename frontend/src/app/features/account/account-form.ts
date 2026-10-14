import { HttpErrorResponse } from '@angular/common/http';
import { AbstractControl } from '@angular/forms';
import { FieldName, Problem } from '../../core/auth/auth.models';
import { readProblem } from '../../core/auth/problem';
import {
  applyServerFieldErrors,
  fieldProblem,
  GENERIC_ERROR,
  isRateLimited,
} from '../auth/auth-form';

/** AC15 text, used only on the account page (the login rate-limit text is different). */
export const ACCOUNT_RATE_LIMITED_MESSAGE = 'Too many attempts, please try again later';
export const DISPLAY_NAME_UPDATED_MESSAGE = 'Display name updated';
export const PASSWORD_CHANGED_MESSAGE = 'Password changed';
export const LOGGED_OUT_OTHERS_MESSAGE = "You've been logged out on all other devices";

/** Field-level problem: 400 VALIDATION_FAILED, 409 DISPLAY_NAME_TAKEN or 400 WRONG_PASSWORD. */
function accountFieldProblem(error: unknown): Problem | null {
  const problem = readProblem(error);
  if (problem?.status === 400 && problem.code === 'WRONG_PASSWORD') {
    return problem.errors?.length ? problem : null;
  }
  return fieldProblem(error);
}

/**
 * Handles an error from an account call. Field errors are put on the matching controls.
 * Returns the banner text to show, or null when nothing more should be shown
 * (field errors applied, or 401 which the global interceptor handles).
 */
export function accountErrorBanner(
  error: unknown,
  controls: Partial<Record<FieldName, AbstractControl>>,
): string | null {
  if (error instanceof HttpErrorResponse && error.status === 401) {
    return null;
  }
  if (isRateLimited(error)) {
    return ACCOUNT_RATE_LIMITED_MESSAGE;
  }
  const problem = accountFieldProblem(error);
  if (problem && applyServerFieldErrors(problem, controls)) {
    return null;
  }
  return GENERIC_ERROR;
}
