import { HttpErrorResponse } from '@angular/common/http';
import { adminActionFailure } from './admin-errors';

function httpError(status: number, code?: string): HttpErrorResponse {
  return new HttpErrorResponse({
    status,
    error: code ? { type: 'about:blank', title: 'x', status, detail: 'x', code } : null,
  });
}

describe('adminActionFailure', () => {
  it.each([
    [403, 'INSUFFICIENT_ROLE', "You don't have permission to do this.", true, false],
    [403, 'FORBIDDEN', null, false, true],
    [403, 'CSRF_INVALID', 'Something went wrong, please try again', false, false],
    [404, 'USER_NOT_FOUND', 'This user no longer exists', true, false],
    [
      409,
      'CANNOT_MODIFY_SELF',
      "You can't do this to your own account. Use your Account page instead.",
      false,
      false,
    ],
    [429, 'RATE_LIMITED', 'Too many attempts, please try again later', false, false],
    [400, 'VALIDATION_FAILED', 'Something went wrong, please try again', false, false],
    [500, 'INTERNAL_ERROR', 'Something went wrong, please try again', false, false],
    [0, undefined, 'Something went wrong, please try again', false, false],
    [401, 'UNAUTHENTICATED', null, false, false],
    [401, 'ACCOUNT_DISABLED', null, false, false],
  ])(
    '%s %s -> message %j, refresh %s, lostAdmin %s',
    (status, code, message, refresh, lostAdmin) => {
      expect(adminActionFailure(httpError(status, code))).toEqual({ message, refresh, lostAdmin });
    },
  );

  it('any 429 counts, even without a problem body', () => {
    expect(adminActionFailure(httpError(429)).message).toBe(
      'Too many attempts, please try again later',
    );
  });

  it('a non-HTTP error is generic', () => {
    expect(adminActionFailure(new Error('x')).message).toBe(
      'Something went wrong, please try again',
    );
  });
});
