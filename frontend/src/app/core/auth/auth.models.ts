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
  /** Optional, default false. True keeps this device signed in for 30 days. */
  rememberMe?: boolean;
}

export type FieldName = 'displayName' | 'email' | 'password' | 'currentPassword' | 'newPassword';

export type FieldErrorCode =
  | 'REQUIRED'
  | 'INVALID_EMAIL'
  | 'TOO_LONG'
  | 'PASSWORD_LENGTH'
  | 'EMAIL_TAKEN'
  | 'DISPLAY_NAME_TAKEN'
  | 'WRONG_PASSWORD';

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
  | 'WRONG_PASSWORD'
  | 'INVALID_CREDENTIALS'
  | 'UNAUTHENTICATED'
  | 'CSRF_INVALID'
  | 'FORBIDDEN'
  | 'NOT_FOUND'
  | 'RATE_LIMITED'
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
