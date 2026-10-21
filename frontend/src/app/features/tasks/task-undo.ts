import { inject, Injectable } from '@angular/core';
import { catchError, map, Observable, of, switchMap, throwError } from 'rxjs';
import { TrashService } from '../trash/trash.service';
import { isNotFound, isUnauthenticated } from './task-form';
import { Task } from './tasks.models';
import { TasksUndoService } from './tasks-undo.service';
import {
  UNDO_ERROR_MESSAGE,
  UNDO_GONE_MESSAGE,
  UNDONE_KEPT_MESSAGE,
  UNDONE_MESSAGE,
} from './undo.logic';
import { UndoPlan } from './undo.messages';

/** What an undo did, for the page to show. */
export interface UndoResult {
  /** The task as it is now. */
  task: Task;
  /** Id of the repeat copy that is gone (deleted by the undo, or already gone), else null. */
  removedId: number | null;
  /** The copy was kept because someone had changed it. */
  kept: boolean;
  /** What the toast says: "Undone", or "Undone: the next copy was already changed...". */
  message: string;
}

/** A failed undo: what the toast says (null: 401, which is handled globally). */
export interface UndoFailure {
  message: string | null;
}

/** Runs an `UndoPlan` against the API. Stateless. */
@Injectable({ providedIn: 'root' })
export class TaskUndo {
  private readonly tasks = inject(TasksUndoService);
  private readonly trash = inject(TrashService);

  /** Runs the plan. Errors are mapped to an `UndoFailure` (404: the task is gone). */
  run(plan: UndoPlan): Observable<UndoResult> {
    return this.send(plan).pipe(
      catchError((error: unknown) =>
        throwError((): UndoFailure => ({
          message: isUnauthenticated(error)
            ? null
            : isNotFound(error)
              ? UNDO_GONE_MESSAGE
              : UNDO_ERROR_MESSAGE,
        })),
      ),
    );
  }

  private send(plan: UndoPlan): Observable<UndoResult> {
    switch (plan.kind) {
      case 'restore':
        return this.trash
          .restore(plan.id)
          .pipe(map((task) => ({ task, removedId: null, kept: false, message: UNDONE_MESSAGE })));
      case 'patch':
        return this.tasks
          .patch(plan.id, plan.patch)
          .pipe(map((task) => ({ task, removedId: null, kept: false, message: UNDONE_MESSAGE })));
      case 'complete':
        return this.tasks
          .undoComplete(plan.id, { nextTaskId: plan.nextTaskId, status: plan.status })
          .pipe(
            switchMap((result) => {
              const kept = result.nextTaskOutcome === 'KEPT';
              const done: UndoResult = {
                task: result,
                removedId: kept ? null : plan.nextTaskId,
                kept,
                message: kept ? UNDONE_KEPT_MESSAGE : UNDONE_MESSAGE,
              };
              return plan.followUp === null
                ? of(done)
                : this.tasks.patch(plan.id, plan.followUp).pipe(map((task) => ({ ...done, task })));
            }),
          );
    }
  }
}
