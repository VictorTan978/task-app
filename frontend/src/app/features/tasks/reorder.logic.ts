/**
 * "My order" (0.18.0): pure helpers for dragging rows. No `@angular/cdk` here: the page (eager)
 * imports this file, the CDK code lives in the deferred `app-task-sort-list`.
 *
 * With `filter=all` the loaded list is the not-done group followed by the done group; a task only
 * moves inside its own group, and the server is told which task must be right above it.
 */
import { isDone } from './tasks.logic';
import { Task, TaskListQuery } from './tasks.models';

export const REORDER_ERROR = "Couldn't save the new order";
/** Hint text when handles are hidden because the sort is not "My order" (button: "Use My order"). */
export const SWITCH_TO_MANUAL_HINT = 'Drag to reorder is only available in "My order".';
export const SWITCH_TO_MANUAL_ACTION = 'Use My order';
/**
 * Hint text when the sort is "My order" but a tab, tag or search hides the handles; the active
 * blockers follow it (button: "Clear filters").
 */
export const CLEAR_FILTERS_HINT = 'To drag, show all tasks. Now filtered by:';
export const CLEAR_FILTERS_ACTION = 'Clear filters';

/** The task that is dropped on a list in the sidebar, and where. */
export interface ListDrop {
  task: Task;
  listId: number;
}

/** A finished reorder: the task, the row now directly above it in its group, and the new order. */
export interface ReorderDrop {
  id: number;
  afterId: number | null;
  items: Task[];
}

/** Dragging needs "My order" with the All tab, no tag and no search. */
export function canDrag(query: TaskListQuery): boolean {
  return (
    query.sort === 'manual' && query.filter === 'all' && query.tag === null && query.q === null
  );
}

/** What the hint box shows instead of the handles. */
export interface DragHint {
  /** `switch`: sort is not "My order". `clear`: "My order" but filters are on. */
  kind: 'switch' | 'clear';
  text: string;
  /** Active blockers (`clear` only), e.g. `Active tab`, `tag "home"`, `search "milk"`. */
  blockers: string[];
  actionLabel: string;
}

const TAB_LABELS: Record<string, string> = {
  active: 'Active',
  in_progress: 'In progress',
  done: 'Done',
};

/** The hint shown instead of the handles, or null when handles are shown (or not needed). */
export function dragHint(query: TaskListQuery, tagLabel: string | null = null): DragHint | null {
  if (canDrag(query)) {
    return null;
  }
  if (query.sort !== 'manual') {
    return {
      kind: 'switch',
      text: SWITCH_TO_MANUAL_HINT,
      blockers: [],
      actionLabel: SWITCH_TO_MANUAL_ACTION,
    };
  }
  const blockers: string[] = [];
  if (query.filter !== 'all') {
    blockers.push(`${TAB_LABELS[query.filter] ?? query.filter} tab`);
  }
  if (query.tag !== null) {
    blockers.push(`tag "${tagLabel ?? query.tag}"`);
  }
  if (query.q !== null) {
    blockers.push(`search "${query.q}"`);
  }
  return { kind: 'clear', text: CLEAR_FILTERS_HINT, blockers, actionLabel: CLEAR_FILTERS_ACTION };
}

/** The tasks of one group, in list order. */
export function groupOf(items: readonly Task[], task: Task): Task[] {
  const done = isDone(task);
  return items.filter((t) => isDone(t) === done);
}

/** Index of the task inside its group, or -1. */
export function groupIndex(items: readonly Task[], id: number): number {
  const task = items.find((t) => t.id === id);
  return task ? groupOf(items, task).findIndex((t) => t.id === id) : -1;
}

/** The row directly above `id` in its group; null when it is first (= top of the group). */
export function afterIdOf(items: readonly Task[], id: number): number | null {
  const index = groupIndex(items, id);
  if (index <= 0) {
    return null;
  }
  const task = items.find((t) => t.id === id)!;
  return groupOf(items, task)[index - 1].id;
}

/**
 * Moves `id` to `toIndex` inside its group (clamped); the other group keeps its slots. Returns
 * the same array when nothing changes.
 */
export function moveInGroup(items: readonly Task[], id: number, toIndex: number): Task[] {
  const task = items.find((t) => t.id === id);
  if (!task) {
    return [...items];
  }
  const group = groupOf(items, task);
  const from = group.findIndex((t) => t.id === id);
  const to = Math.max(0, Math.min(group.length - 1, toIndex));
  if (from === to) {
    return items as Task[];
  }
  const reordered = [...group];
  reordered.splice(from, 1);
  reordered.splice(to, 0, task);
  const done = isDone(task);
  let next = 0;
  return items.map((t) => (isDone(t) === done ? reordered[next++] : t));
}

/**
 * Puts `id` right after `afterId` (null: top of its group). Used to undo a failed save: it only
 * moves that one task, so a tick or edit that landed meanwhile stays. If `afterId` is gone or in
 * the other group, the task goes to the top of its group.
 */
export function placeAfter(items: readonly Task[], id: number, afterId: number | null): Task[] {
  const task = items.find((t) => t.id === id);
  if (!task) {
    return [...items];
  }
  const group = groupOf(items, task).filter((t) => t.id !== id);
  const anchor = afterId === null ? -1 : group.findIndex((t) => t.id === afterId);
  return moveInGroup(items, id, anchor + 1);
}

/** "Moved to position 3 of 12" (position inside the task's group). */
export function positionText(items: readonly Task[], id: number, verb = 'Moved to'): string {
  const task = items.find((t) => t.id === id);
  if (!task) {
    return '';
  }
  const group = groupOf(items, task);
  return `${verb} position ${group.findIndex((t) => t.id === id) + 1} of ${group.length}`;
}
