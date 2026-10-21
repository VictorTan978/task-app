/** What the page needs at once for undo; the rest (`undo.logic`, `task-undo`) loads on first use. */
import { BulkPatchItem, BulkUndoCompleteItem, SetTaskDoneRequest } from './tasks.models';

/** What "Undo" will send. */
export type UndoPlan =
  /** Undo of a delete: `POST /api/trash/{id}/restore`. */
  | { kind: 'restore'; id: number }
  /** Undo of a tick, move or edit: `PATCH` with the changed fields only. */
  | { kind: 'patch'; id: number; patch: SetTaskDoneRequest }
  /** Undo of a completion that created a copy: `POST .../undo-complete`, then `followUp`, if any. */
  | {
      kind: 'complete';
      id: number;
      nextTaskId: number;
      status: 'TODO' | 'IN_PROGRESS';
      followUp: SetTaskDoneRequest | null;
    };

/** What "Undo" of a batch will send (0.17.0): always one request. */
export type BulkUndoPlan =
  /** Undo of a bulk delete: `POST /api/trash/restore`. */
  | { kind: 'bulk-restore'; ids: number[] }
  /** Undo of status / priority / move / reopen: each task's own old values. */
  | { kind: 'bulk-patch'; items: BulkPatchItem[] }
  /** Undo of a bulk completion: `nextTaskId` only on the tasks that got a repeat copy. */
  | { kind: 'bulk-complete'; items: BulkUndoCompleteItem[] }
  /** Undo of a tag action: the opposite tag action on the ids it changed. */
  | { kind: 'bulk-tag'; action: 'ADD_TAG' | 'REMOVE_TAG'; ids: number[]; tag: string };

export function isBulkPlan(plan: UndoPlan | BulkUndoPlan): plan is BulkUndoPlan {
  return plan.kind.startsWith('bulk-');
}
