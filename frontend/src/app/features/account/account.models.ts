/** Models matching `contracts/openapi.yaml` schemas exactly. The response type is `User`. */

export interface ChangeDisplayNameRequest {
  displayName: string;
}

export interface ChangePasswordRequest {
  currentPassword: string;
  newPassword: string;
}

export interface DeleteAccountRequest {
  password: string;
}
