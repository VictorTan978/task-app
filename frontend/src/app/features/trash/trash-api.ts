/** Relative API URLs (served through the dev proxy; no CORS). */
export const TRASH_API = {
  list: '/api/trash',
  empty: '/api/trash/empty',
  restoreMany: '/api/trash/restore',
  restore: (id: number) => `/api/trash/${id}/restore`,
  one: (id: number) => `/api/trash/${id}`,
} as const;
