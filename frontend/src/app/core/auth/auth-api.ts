/** Relative API URLs (served through the dev proxy; no CORS). */
export const AUTH_API = {
  me: '/api/auth/me',
  register: '/api/auth/register',
  login: '/api/auth/login',
  logout: '/api/auth/logout',
} as const;
