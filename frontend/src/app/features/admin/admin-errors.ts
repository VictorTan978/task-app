import { HttpErrorResponse } from '@angular/common/http';
import { readProblem } from '../../core/auth/problem';
import { GENERIC_ERROR } from '../auth/auth-form';

export const LOAD_USERS_ERROR = GENERIC_ERROR;
export const NO_PERMISSION_MESSAGE = "You don't have permission to do this.";
export const USER_GONE_MESSAGE = 'This user no longer exists';
export const CANNOT_MODIFY_SELF_MESSAGE =
  "You can't do this to your own account. Use your Account page instead.";
export const ADMIN_RATE_LIMITED_MESSAGE = 'Too many attempts, please try again later';
export const EMAIL_DOESNT_MATCH_MESSAGE = "Email doesn't match";

/** What the admin page should do after a failed admin action. */
export interface AdminActionFailure {
  /** Text shown inside the dialog, or null to show nothing (401 is handled globally). */
  message: string | null;
  /** The table is out of date (target changed or gone): reload it. */
  refresh: boolean;
  /** 403 FORBIDDEN: the signed-in user is no longer an admin at all. */
  lostAdmin: boolean;
}

/** Maps an admin action error (problem+json `code`) to what the UI shows and does. */
export function adminActionFailure(error: unknown): AdminActionFailure {
  const none: AdminActionFailure = { message: null, refresh: false, lostAdmin: false };
  if (!(error instanceof HttpErrorResponse)) {
    return { ...none, message: GENERIC_ERROR };
  }
  const code = readProblem(error)?.code;
  switch (error.status) {
    case 401:
      return none;
    case 403:
      if (code === 'FORBIDDEN') {
        return { ...none, lostAdmin: true };
      }
      if (code === 'INSUFFICIENT_ROLE') {
        return { ...none, message: NO_PERMISSION_MESSAGE, refresh: true };
      }
      return { ...none, message: GENERIC_ERROR };
    case 404:
      return { ...none, message: USER_GONE_MESSAGE, refresh: true };
    case 409:
      return code === 'CANNOT_MODIFY_SELF'
        ? { ...none, message: CANNOT_MODIFY_SELF_MESSAGE }
        : { ...none, message: GENERIC_ERROR };
    case 429:
      return { ...none, message: ADMIN_RATE_LIMITED_MESSAGE };
    default:
      return { ...none, message: GENERIC_ERROR };
  }
}
