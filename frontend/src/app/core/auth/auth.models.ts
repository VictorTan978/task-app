/** Models matching `contracts/openapi.yaml` schemas exactly. */

export interface User {
  id: number;
  email: string;
  displayName: string;
  /** True once the user confirmed their email (or reset their password by email). */
  emailVerified: boolean;
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

/**
 * Body of `POST /api/auth/verify-email` and `POST /api/auth/check-reset-token`.
 * `token` is the opaque `token` query parameter of the emailed link, unchanged.
 */
export interface TokenRequest {
  token?: string;
}

export interface ForgotPasswordRequest {
  email: string;
}

export interface ResetPasswordRequest {
  token?: string;
  newPassword: string;
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
  | 'INTERNAL_ERROR'
  | 'TOKEN_INVALID'
  | 'ALREADY_VERIFIED';

export interface Problem {
  type: string;
  title: string;
  status: number;
  detail: string;
  instance?: string;
  code: ProblemCode;
  errors?: FieldError[];
}
