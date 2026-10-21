/** What the page needs at once for undo; the rest (`undo.logic`, `task-undo`) loads on first use. */
import { SetTaskDoneRequest } from './tasks.models';

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
