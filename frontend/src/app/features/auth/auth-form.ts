import { HttpErrorResponse } from '@angular/common/http';
import { AbstractControl, ValidationErrors, ValidatorFn, Validators } from '@angular/forms';
import { FieldError, FieldErrorCode, FieldName, Problem } from '../../core/auth/auth.models';
import { readProblem } from '../../core/auth/problem';

export const GENERIC_ERROR = 'Something went wrong, please try again';
export const INVALID_CREDENTIALS_MESSAGE = 'Invalid email or password';
export const SESSION_EXPIRED_MESSAGE = 'Your session expired, please sign in again';
export const RATE_LIMITED_MESSAGE = 'Too many attempts. Please wait a few minutes and try again.';

export const DISPLAY_NAME_MAX = 50;
export const EMAIL_MAX = 254;
export const PASSWORD_MIN = 8;
export const PASSWORD_MAX = 72;

/** UI text per field and error code (client and server codes share these keys). */
export const FIELD_MESSAGES: Record<FieldName, Partial<Record<FieldErrorCode, string>>> = {
  displayName: {
    REQUIRED: 'Enter your display name',
    TOO_LONG: `Display name must be at most ${DISPLAY_NAME_MAX} characters`,
    DISPLAY_NAME_TAKEN: 'This display name is already taken',
  },
  email: {
    REQUIRED: 'Enter your email',
    INVALID_EMAIL: 'Enter a valid email address',
    TOO_LONG: `Email must be at most ${EMAIL_MAX} characters`,
    EMAIL_TAKEN: 'An account with this email already exists',
  },
  password: {
    REQUIRED: 'Enter your password',
    PASSWORD_LENGTH: `Password must be ${PASSWORD_MIN} to ${PASSWORD_MAX} characters`,
  },
};

/** Order in which errors are shown when a control has several (mirrors server precedence). */
const CODE_PRECEDENCE: readonly FieldErrorCode[] = [
  'REQUIRED',
  'INVALID_EMAIL',
  'PASSWORD_LENGTH',
  'TOO_LONG',
  'EMAIL_TAKEN',
  'DISPLAY_NAME_TAKEN',
];

/** Server-reported errors are stored on the control under this key until the value changes. */
export const SERVER_ERROR_KEY = 'server';

export interface ServerFieldError {
  code: FieldErrorCode;
  message: string;
}

function asString(control: AbstractControl): string {
  return typeof control.value === 'string' ? control.value : '';
}

/** Non-blank after trimming. */
export const requiredTrimmed: ValidatorFn = (control) =>
  asString(control).trim().length === 0 ? { REQUIRED: true } : null;

/**
 * Valid email after trimming. Blank is left to `requiredTrimmed`; values over EMAIL_MAX are left
 * to `maxTrimmed` (Angular's email check would otherwise report them as invalid format).
 */
export const emailTrimmed: ValidatorFn = (control) => {
  const value = asString(control).trim();
  if (!value || value.length > EMAIL_MAX) {
    return null;
  }
  const result = Validators.email({ value } as AbstractControl);
  return result ? { INVALID_EMAIL: true } : null;
};

/** At most `max` characters after trimming. */
export function maxTrimmed(max: number): ValidatorFn {
  return (control) => (asString(control).trim().length > max ? { TOO_LONG: true } : null);
}

/** Register password policy: 8–72 characters and at most 72 UTF-8 bytes. */
export const passwordLength: ValidatorFn = (control): ValidationErrors | null => {
  const value = asString(control);
  if (value.trim().length === 0) {
    return null;
  }
  const bytes = new TextEncoder().encode(value).length;
  return value.length < PASSWORD_MIN || value.length > PASSWORD_MAX || bytes > PASSWORD_MAX
    ? { PASSWORD_LENGTH: true }
    : null;
};

/** The message to show under a field, or null. */
export function fieldMessage(field: FieldName, control: AbstractControl): string | null {
  const errors = control.errors;
  if (!errors) {
    return null;
  }
  for (const code of CODE_PRECEDENCE) {
    if (errors[code]) {
      return FIELD_MESSAGES[field][code] ?? null;
    }
  }
  const server = errors[SERVER_ERROR_KEY] as ServerFieldError | undefined;
  if (server) {
    return FIELD_MESSAGES[field][server.code] ?? server.message;
  }
  return null;
}

/** Puts server field errors onto the matching controls. Returns false if none could be applied. */
export function applyServerFieldErrors(
  problem: Problem,
  controls: Partial<Record<FieldName, AbstractControl>>,
): boolean {
  let applied = false;
  for (const fe of problem.errors ?? []) {
    const control = controls[fe.field];
    if (control) {
      control.setErrors({
        ...(control.errors ?? {}),
        [SERVER_ERROR_KEY]: { code: fe.code, message: fe.message } satisfies ServerFieldError,
      });
      control.markAsTouched();
      applied = true;
    }
  }
  return applied;
}

/** Field that each 409 conflict code belongs to (used when a 409 arrives without `errors[]`). */
const CONFLICT_FIELDS: Partial<Record<string, FieldName>> = {
  EMAIL_TAKEN: 'email',
  DISPLAY_NAME_TAKEN: 'displayName',
};

/**
 * Problem body for field-level errors (400 VALIDATION_FAILED / 409 EMAIL_TAKEN or
 * DISPLAY_NAME_TAKEN), else null. A 409 without `errors[]` gets one entry built from its `code`.
 */
export function fieldProblem(error: unknown): Problem | null {
  const problem = readProblem(error);
  if (!problem) {
    return null;
  }
  if (problem.status === 400 && problem.code === 'VALIDATION_FAILED') {
    return problem.errors?.length ? problem : null;
  }
  const conflictField = problem.status === 409 ? CONFLICT_FIELDS[problem.code] : undefined;
  if (!conflictField) {
    return null;
  }
  if (problem.errors?.length) {
    return problem;
  }
  const fallback: FieldError = {
    field: conflictField,
    code: problem.code as FieldErrorCode,
    message: problem.detail,
  };
  return { ...problem, errors: [fallback] };
}

/** True for a 429 (`RATE_LIMITED`) response. Any 429 counts, even without a problem body. */
export function isRateLimited(error: unknown): boolean {
  return error instanceof HttpErrorResponse && error.status === 429;
}
