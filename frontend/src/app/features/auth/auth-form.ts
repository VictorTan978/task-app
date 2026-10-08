import { AbstractControl, ValidationErrors, ValidatorFn, Validators } from '@angular/forms';
import { FieldErrorCode, FieldName, Problem } from '../../core/auth/auth.models';
import { readProblem } from '../../core/auth/problem';

export const GENERIC_ERROR = 'Something went wrong, please try again';
export const INVALID_CREDENTIALS_MESSAGE = 'Invalid email or password';
export const SESSION_EXPIRED_MESSAGE = 'Your session expired, please sign in again';

export const DISPLAY_NAME_MAX = 50;
export const EMAIL_MAX = 254;
export const PASSWORD_MIN = 8;
export const PASSWORD_MAX = 72;

/** UI text per field and error code (client and server codes share these keys). */
export const FIELD_MESSAGES: Record<FieldName, Partial<Record<FieldErrorCode, string>>> = {
  displayName: {
    REQUIRED: 'Enter your display name',
    TOO_LONG: `Display name must be at most ${DISPLAY_NAME_MAX} characters`,
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

/** Problem body for field-level errors (400 VALIDATION_FAILED / 409 EMAIL_TAKEN), else null. */
export function fieldProblem(error: unknown): Problem | null {
  const problem = readProblem(error);
  if (
    problem &&
    ((problem.status === 400 && problem.code === 'VALIDATION_FAILED') ||
      (problem.status === 409 && problem.code === 'EMAIL_TAKEN')) &&
    problem.errors?.length
  ) {
    return problem;
  }
  return null;
}
