/** Models matching the notification schemas in `contracts/openapi.yaml` (0.14.0). */

export interface AppNotification {
  id: number;
  /** Kind of notification; today only reminders. */
  type: 'REMINDER';
  read: boolean;
  /** UTC instant with `Z` suffix. */
  createdAt: string;
  /** True when the reminder fired more than 5 minutes after its time. */
  late: boolean;
  /** Task title when the reminder fired (a snapshot). */
  title: string;
  /** Wall-clock due date `YYYY-MM-DD` when it fired (snapshot). */
  dueDate: string;
  /** Wall-clock `HH:mm`, or null. */
  dueTime: string | null;
  /** Null when the task is gone or I lost access. */
  taskId: number | null;
  /** Null when the task is gone or I lost access. */
  listId: number | null;
}

/** `GET /api/notifications`: newest first. */
export interface NotificationPage {
  items: AppNotification[];
  /** Opaque; null on the last page. */
  nextCursor: string | null;
}

export interface NotificationCount {
  count: number;
}
