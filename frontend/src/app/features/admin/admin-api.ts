/** Relative API URLs (served through the dev proxy; no CORS). */
export const ADMIN_API = {
  users: '/api/admin/users',
  user: (id: number) => `/api/admin/users/${id}`,
  disable: (id: number) => `/api/admin/users/${id}/disable`,
  enable: (id: number) => `/api/admin/users/${id}/enable`,
  revokeSessions: (id: number) => `/api/admin/users/${id}/revoke-sessions`,
  role: (id: number) => `/api/admin/users/${id}/role`,
} as const;
