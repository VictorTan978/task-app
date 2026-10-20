/** Relative API URLs (served through the dev proxy; no CORS). */
export const NOTIFICATIONS_API = {
  list: '/api/notifications',
  count: '/api/notifications/count',
  read: (id: number) => `/api/notifications/${id}/read`,
  readAll: '/api/notifications/read-all',
} as const;
