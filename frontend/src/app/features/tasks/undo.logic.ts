/**
 * Undo of tick, move, edit and delete (pure functions; see `TaskUndo` for the HTTP part).
 * Undo sends only the fields the undone action changed (spec AC13), so a teammate's later edit
 * to any other field survives. Loaded on first use (`import()`), to keep it out of the initial
 * bundle.
 */
import {
  BulkAction,
  BulkPatchItem,
  BulkUndoCompleteItem,
  RepeatRule,
  SetTaskDoneRequest,
  Task,
  TaskResult,
} from './tasks.models';
import { TaskListState } from './task-list';
import { BulkUndoPlan, UndoPlan } from './undo.messages';

export const TRASHED_MESSAGE = 'Task moved to trash';
export const UNDO_LABEL = 'Undo';
export const COMPLETED_MESSAGE = 'Task completed';
export const REOPENED_MESSAGE = 'Task reopened';
export const UPDATED_MESSAGE = 'Task updated';
export const UNDONE_MESSAGE = 'Undone';
export const UNDONE_KEPT_MESSAGE = 'Undone: the next copy was already changed, so it was kept';
export const UNDO_GONE_MESSAGE = "Couldn't undo: the task was deleted or you no longer have access";
export const UNDO_ERROR_MESSAGE = "Couldn't undo. Please try again.";
export function movedMessage(listName: string | null): string {
  return `Moved to ${listName ?? 'another list'}`;
}

function sameTags(a: readonly string[], b: readonly string[]): boolean {
  return a.length === b.length && a.every((tag, i) => tag === b[i]);
}

function sameRepeat(a: RepeatRule | null, b: RepeatRule | null): boolean {
  if (a === null || b === null) {
    return a === b;
  }
  return a.frequency === b.frequency && sameTags(a.weekdays, b.weekdays);
}

/** True if the patch has no field at all (undo would be a 400). */
export function isEmptyPatch(patch: SetTaskDoneRequest): boolean {
  return Object.keys(patch).length === 0;
}

/**
 * The fields that put `before` back over `after`: only those that differ. `null` clears
 * description / due date / due time, `NONE` the reminder, `NEVER` the rule. A move also sends
 * the old tag names (they may be spelled differently in the list it left).
 */
export function reversePatch(before: Task, after: Task): SetTaskDoneRequest {
  const patch: SetTaskDoneRequest = {};
  if (before.title !== after.title) {
    patch.title = before.title;
  }
  if (before.description !== after.description) {
    patch.description = before.description;
  }
  if (before.priority !== after.priority) {
    patch.priority = before.priority;
  }
  if (before.status !== after.status) {
    patch.status = before.status;
  }
  if (before.dueDate !== after.dueDate) {
    patch.dueDate = before.dueDate;
  }
  if (before.dueTime !== after.dueTime) {
    patch.dueTime = before.dueTime;
  }
  if (before.myReminder !== after.myReminder) {
    patch.reminder = before.myReminder ?? 'NONE';
  }
  if (!sameRepeat(before.repeat, after.repeat)) {
    patch.repeat = before.repeat ?? 'NEVER';
  }
  if (!sameTags(before.tags, after.tags) || before.listId !== after.listId) {
    patch.tags = [...before.tags];
  }
  if (before.listId !== after.listId) {
    patch.listId = before.listId;
  }
  return patch;
}

/**
 * Plan for undoing a save that turned `before` into `after`, or null if nothing changed.
 * A save that completed a repeating task (it returned `nextTask`) is undone with
 * `undo-complete`, which also restores the rule; the other changed fields go in a follow-up
 * `PATCH` (without `status`, and without `repeat`, which the server has just put back).
 */
export function planUndo(before: Task, after: TaskResult): UndoPlan | null {
  const patch = reversePatch(before, after);
  const next = after.nextTask;
  if (next && before.status !== 'DONE') {
    const rest = { ...patch };
    delete rest.status;
    delete rest.repeat;
    return {
      kind: 'complete',
      id: after.id,
      nextTaskId: next.id,
      status: before.status === 'IN_PROGRESS' ? 'IN_PROGRESS' : 'TODO',
      followUp: isEmptyPatch(rest) ? null : rest,
    };
  }
  return isEmptyPatch(patch) ? null : { kind: 'patch', id: after.id, patch };
}

/**
 * A task that comes back (undo of a delete or a move): it may be shown again, so it is no longer
 * skipped on later pages. Apply it with `applyChange(..., 'updated')` afterwards.
 */
export function reinstate(state: TaskListState, id: number): TaskListState {
  if (!state.deleted.has(id)) {
    return state;
  }
  const deleted = new Set(state.deleted);
  deleted.delete(id);
  return { ...state, deleted };
}

/** Toast text after a bulk action: "12 tasks completed". `listName` is the target of a move. */
export function bulkMessage(
  action: BulkAction,
  count: number,
  value: string | number | undefined,
  listName: string | null,
): string {
  const tasks = count === 1 ? '1 task' : `${count} tasks`;
  switch (action) {
    case 'COMPLETE':
      return `${tasks} completed`;
    case 'REOPEN':
      return `${tasks} reopened`;
    case 'SET_STATUS':
      return `Status set on ${tasks}`;
    case 'SET_PRIORITY':
      return `Priority set on ${tasks}`;
    case 'ADD_TAG':
      return `Tag '${String(value)}' added to ${tasks}`;
    case 'REMOVE_TAG':
      return `Tag '${String(value)}' removed from ${tasks}`;
    case 'MOVE':
      return `${tasks} moved to ${listName ?? 'another list'}`;
    case 'DELETE':
      return `${tasks} moved to trash`;
  }
}

/**
 * Plan for undoing a bulk action in ONE request (contract 0.17.0, "Undo of a batch"), from the
 * tasks as they were (`before`) and as the server returned them (`after`). Only tasks the action
 * really changed take part; null if there are none.
 */
export function planBulkUndo(
  action: BulkAction,
  value: string | number | undefined,
  before: readonly Task[],
  after: readonly TaskResult[],
): BulkUndoPlan | null {
  if (action === 'DELETE') {
    return before.length === 0 ? null : { kind: 'bulk-restore', ids: before.map((t) => t.id) };
  }
  const now = new Map(after.map((t) => [t.id, t]));
  const pairs = before.flatMap((old) => {
    const current = now.get(old.id);
    return current ? [{ old, current }] : [];
  });
  const none = (plan: BulkUndoPlan | null, empty: boolean): BulkUndoPlan | null =>
    empty ? null : plan;

  if (action === 'COMPLETE' || (action === 'SET_STATUS' && value === 'DONE')) {
    const items: BulkUndoCompleteItem[] = pairs
      .filter(({ old }) => old.status !== 'DONE')
      .map(({ old, current }) => ({
        id: old.id,
        ...(current.nextTask ? { nextTaskId: current.nextTask.id } : {}),
        status: old.status === 'IN_PROGRESS' ? 'IN_PROGRESS' : 'TODO',
      }));
    return none({ kind: 'bulk-complete', items }, items.length === 0);
  }
  if (action === 'ADD_TAG' || action === 'REMOVE_TAG') {
    const adding = action === 'ADD_TAG';
    const ids = pairs
      .filter(({ old, current }) =>
        adding ? current.tags.length > old.tags.length : current.tags.length < old.tags.length,
      )
      .map(({ old }) => old.id);
    return none(
      { kind: 'bulk-tag', action: adding ? 'REMOVE_TAG' : 'ADD_TAG', ids, tag: String(value) },
      ids.length === 0,
    );
  }
  const items: BulkPatchItem[] = pairs.flatMap(({ old, current }) => {
    const patch: SetTaskDoneRequest = {};
    if (action === 'SET_PRIORITY' && old.priority !== current.priority) {
      patch.priority = old.priority;
    } else if ((action === 'REOPEN' || action === 'SET_STATUS') && old.status !== current.status) {
      patch.status = old.status;
    } else if (action === 'MOVE' && old.listId !== current.listId) {
      patch.listId = old.listId;
      patch.tags = [...old.tags];
    }
    return isEmptyPatch(patch) ? [] : [{ id: old.id, patch }];
  });
  return none({ kind: 'bulk-patch', items }, items.length === 0);
}
