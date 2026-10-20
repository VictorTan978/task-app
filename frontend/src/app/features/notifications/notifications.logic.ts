import { formatDue } from '../tasks/tasks.logic';
import { AppNotification } from './notifications.models';

export const NOTIFICATIONS_EMPTY = 'No notifications yet';
export const NOTIFICATIONS_LOAD_ERROR = "Couldn't load your notifications. Please try again.";
export const NOTIFICATIONS_MORE_ERROR = "Couldn't load more notifications. Please try again.";
export const NOTIFICATIONS_ACTION_ERROR = "Couldn't update your notifications. Please try again.";
export const TASK_UNAVAILABLE = 'This task is no longer available';

/** "Reminder: Pay rent — due 2026-10-21 09:30" (date only for a date-only task). */
export function notificationText(n: AppNotification): string {
  return `Reminder: ${n.title} — due ${formatDue(n.dueDate, n.dueTime)}`;
}

/** The task can still be opened (it exists and I have access). */
export function isAvailable(n: AppNotification): boolean {
  return n.taskId !== null && n.listId !== null;
}

/** `/tasks` for my personal list, else `/lists/<id>`. */
export function notificationLink(listId: number, personalListId: number | null): string {
  return listId === personalListId ? '/tasks' : `/lists/${listId}`;
}
