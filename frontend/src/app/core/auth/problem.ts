import { HttpErrorResponse } from '@angular/common/http';
import { Problem, ProblemCode } from './auth.models';

/** Extracts the RFC 7807 problem body from an HTTP error, or null if there is none. */
export function readProblem(error: unknown): Problem | null {
  if (!(error instanceof HttpErrorResponse)) {
    return null;
  }
  const body: unknown = error.error;
  if (body && typeof body === 'object' && typeof (body as Problem).code === 'string') {
    return body as Problem;
  }
  return null;
}

/** True if the error is an HTTP error with the given status and problem code. */
export function hasProblemCode(error: unknown, status: number, code: ProblemCode): boolean {
  return (
    error instanceof HttpErrorResponse &&
    error.status === status &&
    readProblem(error)?.code === code
  );
}
