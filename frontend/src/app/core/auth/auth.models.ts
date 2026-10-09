/** Models matching `contracts/openapi.yaml` schemas exactly. */

export interface User {
  id: number;
  email: string;
  displayName: string;
}

export interface RegisterRequest {
  displayName: string;
  email: string;
  password: string;
}

export interface LoginRequest {
  email: string;
  password: string;
}

export type FieldName = 'displayName' | 'email' | 'password';

export type FieldErrorCode =
  | 'REQUIRED'
  | 'INVALID_EMAIL'
  | 'TOO_LONG'
  | 'PASSWORD_LENGTH'
  | 'EMAIL_TAKEN'
  | 'DISPLAY_NAME_TAKEN';

export interface FieldError {
  field: FieldName;
  code: FieldErrorCode;
  message: string;
}

export type ProblemCode =
  | 'VALIDATION_FAILED'
  | 'MALFORMED_REQUEST'
  | 'EMAIL_TAKEN'
  | 'DISPLAY_NAME_TAKEN'
  | 'INVALID_CREDENTIALS'
  | 'UNAUTHENTICATED'
  | 'CSRF_INVALID'
  | 'FORBIDDEN'
  | 'NOT_FOUND'
  | 'INTERNAL_ERROR';

export interface Problem {
  type: string;
  title: string;
  status: number;
  detail: string;
  instance?: string;
  code: ProblemCode;
  errors?: FieldError[];
}
