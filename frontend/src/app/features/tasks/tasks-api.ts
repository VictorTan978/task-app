/** Relative API URLs (served through the dev proxy; no CORS). */
export const TASKS_API = {
  list: '/api/tasks',
  one: (id: number) => `/api/tasks/${id}`,
} as const;
