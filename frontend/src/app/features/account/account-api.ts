/** Relative API URLs (served through the dev proxy; no CORS). */
export const ACCOUNT_API = {
  displayName: '/api/account/display-name',
  password: '/api/account/password',
  delete: '/api/account/delete',
  logoutOtherSessions: '/api/account/logout-other-sessions',
} as const;
