/** Relative API URLs (served through the dev proxy; no CORS). */
export const LISTS_API = {
  all: '/api/lists',
  one: (id: number) => `/api/lists/${id}`,
} as const;
