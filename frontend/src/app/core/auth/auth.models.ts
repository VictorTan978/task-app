/** Models matching `contracts/openapi.yaml` schemas exactly. */

export interface User {
  id: number;
  email: string;
  displayName: string;
  /** True once the user confirmed their email (or reset their password by email). */
  emailVerified: boolean;
  /** False for accounts created with Google that have not set a password yet. */
  hasPassword: boolean;
  /** External sign-in accounts connected to this user (empty when none). */
  connections: Connection[];
  /** The account's role (new in 0.9.0). */
  role: Role;
  /** IANA region zone (e.g. `Europe/Bucharest`), or null until set (0.14.0). */
  timeZone: string | null;
  /** Whether reminder emails are sent (0.14.0). */
  reminderEmails: boolean;
}

/** Account role. `SUPER_ADMIN` can only be set outside the app (startup setting or SQL). */
export type Role = 'USER' | 'ADMIN' | 'SUPER_ADMIN';

export type ConnectionProvider = 'google';

/** An external sign-in account connected to this user. */
export interface Connection {
  provider: ConnectionProvider;
  /** The provider account's email, for display only. May differ from `User.email`. */
  email: string;
}

/** `GET /api/auth/providers`: which external sign-in providers are configured. */
export interface Providers {
  google: boolean;
}

/** `intent` form field of `POST /api/auth/oauth2/authorization/google`. */
export type GoogleIntent = 'login' | 'connect';

/** Value of the `oauth` query parameter after a Google round trip. */
export type OAuthResult =
  | 'google_failed'
  | 'google_unverified'
  | 'google_connected'
  | 'google_in_use'
  | 'google_connect_failed'
  | 'account_disabled';

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

export type FieldName =
  'displayName' | 'email' | 'password' | 'currentPassword' | 'newPassword' | 'timeZone';

export type FieldErrorCode =
  | 'REQUIRED'
  | 'INVALID_EMAIL'
  | 'TOO_LONG'
  | 'PASSWORD_LENGTH'
  | 'EMAIL_TAKEN'
  | 'DISPLAY_NAME_TAKEN'
  | 'WRONG_PASSWORD'
  | 'EMAIL_MISMATCH'
  | 'INVALID_TIME_ZONE';

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
  | 'ALREADY_VERIFIED'
  | 'PASSWORD_REQUIRED'
  | 'EMAIL_MISMATCH'
  | 'ACCOUNT_DISABLED'
  | 'CANNOT_MODIFY_SELF'
  | 'INSUFFICIENT_ROLE'
  | 'USER_NOT_FOUND';

export interface Problem {
  type: string;
  title: string;
  status: number;
  detail: string;
  instance?: string;
  code: ProblemCode;
  errors?: FieldError[];
  /** Bulk endpoints only (0.17.0): the task ids that made the call fail, ascending. */
  failedIds?: number[];
}
