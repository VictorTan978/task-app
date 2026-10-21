import { TrashItem } from './trash.models';

export const TRASH_EMPTY = 'Trash is empty. Deleted tasks stay here for 30 days.';
export const TRASH_LOAD_ERROR = "Couldn't load the trash. Please try again.";
export const TRASH_MORE_ERROR = "Couldn't load more tasks. Please try again.";
export const RESTORE_ERROR = "Couldn't restore the task. Please try again.";
export const DELETE_FOREVER_ERROR = "Couldn't delete the task. Please try again.";
export const EMPTY_TRASH_ERROR = "Couldn't empty the trash. Please try again.";
export const NOT_IN_TRASH_NOTE = 'That task is no longer in the trash.';
export const RESTORED_MESSAGE = 'Task restored';

const MINUTE = 60_000;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;

function plural(n: number, one: string, many: string): string {
  return n === 1 ? `1 ${one}` : `${n} ${many}`;
}

/** "just now", "5 minutes ago", "3 hours ago", "2 days ago". */
export function agoText(instant: string, nowMs: number): string {
  const at = Date.parse(instant);
  if (Number.isNaN(at)) {
    return instant;
  }
  const age = Math.max(0, nowMs - at);
  if (age < MINUTE) {
    return 'just now';
  }
  if (age < HOUR) {
    return `${plural(Math.floor(age / MINUTE), 'minute', 'minutes')} ago`;
  }
  if (age < DAY) {
    return `${plural(Math.floor(age / HOUR), 'hour', 'hours')} ago`;
  }
  return `${plural(Math.floor(age / DAY), 'day', 'days')} ago`;
}

/** "Deleted by Ana · 2 days ago" ("Deleted by a deleted user" when that account is gone). */
export function deletedText(item: TrashItem, nowMs: number): string {
  const by = item.deletedBy ? item.deletedBy.displayName : 'a deleted user';
  return `Deleted by ${by} · ${agoText(item.deletedAt, nowMs)}`;
}

/** Days before it is deleted for good: "28 days left", "1 day left", "Deleted soon". */
export function daysLeftText(purgeAt: string, nowMs: number): string {
  const at = Date.parse(purgeAt);
  if (Number.isNaN(at)) {
    return purgeAt;
  }
  const days = Math.ceil((at - nowMs) / DAY);
  return days <= 0 ? 'Deleted soon' : `${plural(days, 'day', 'days')} left`;
}

export function tasksText(n: number): string {
  return plural(n, 'task', 'tasks');
}

/** Question of the "Empty trash" dialog; names the count (and the list when filtered). */
export function emptyTrashQuestion(count: number, listName: string | null): string {
  const where = listName === null ? 'in the trash' : `in the trash of "${listName}"`;
  return `Delete ${count === 1 ? 'the 1 task' : `all ${count} tasks`} ${where} forever? This can't be undone.`;
}
