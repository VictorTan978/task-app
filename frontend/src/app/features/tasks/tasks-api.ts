/** Relative API URLs (served through the dev proxy; no CORS). */
export const TASKS_API = {
  list: '/api/tasks',
  one: (id: number) => `/api/tasks/${id}`,
  tags: '/api/tags',
  bulk: '/api/tasks/bulk',
  bulkPatch: '/api/tasks/bulk-patch',
  bulkUndoComplete: '/api/tasks/bulk-undo-complete',
} as const;
