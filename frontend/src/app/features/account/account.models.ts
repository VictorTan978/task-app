/** Models matching `contracts/openapi.yaml` schemas exactly. The response type is `User`. */

export interface ChangeDisplayNameRequest {
  displayName: string;
}

export interface ChangePasswordRequest {
  currentPassword: string;
  newPassword: string;
}

/**
 * Accounts with a password send `password`; accounts without one (`User.hasPassword: false`)
 * send `email` instead. The server ignores the other field.
 */
export interface DeleteAccountRequest {
  password?: string;
  email?: string;
}

/** `PUT /api/account/time-zone`. `ifUnset: true` only sets it when the account has none. */
export interface SetTimeZoneRequest {
  timeZone: string;
  ifUnset?: boolean;
}

export interface SetReminderEmailsRequest {
  enabled: boolean;
}
