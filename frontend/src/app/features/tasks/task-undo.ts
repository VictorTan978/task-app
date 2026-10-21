import { inject, Injectable } from '@angular/core';
import { catchError, map, Observable, of, switchMap, throwError } from 'rxjs';
import { TrashService } from '../trash/trash.service';
import { isNotFound, isUnauthenticated } from './task-form';
import { Task } from './tasks.models';
import { TasksService } from './tasks.service';
import { TasksUndoService } from './tasks-undo.service';
import {
  UNDO_ERROR_MESSAGE,
  UNDO_GONE_MESSAGE,
  UNDONE_KEPT_MESSAGE,
  UNDONE_MESSAGE,
} from './undo.logic';
import { BulkUndoPlan, UndoPlan } from './undo.messages';

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

/** What a batch undo did: every task as it is now, and the repeat copies that are gone. */
export interface BulkUndoResult {
  tasks: Task[];
  removedIds: number[];
  kept: boolean;
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
  private readonly tasksApi = inject(TasksService);

  /** Runs the plan. Errors are mapped to an `UndoFailure` (404: the task is gone). */
  run(plan: UndoPlan): Observable<UndoResult> {
    return this.send(plan).pipe(catchError((error: unknown) => throwError(() => this.fail(error))));
  }

  /** Runs a batch plan: always one request. Errors are mapped like `run`. */
  runBulk(plan: BulkUndoPlan): Observable<BulkUndoResult> {
    return this.sendBulk(plan).pipe(
      catchError((error: unknown) => throwError(() => this.fail(error))),
    );
  }

  private fail(error: unknown): UndoFailure {
    return {
      message: isUnauthenticated(error)
        ? null
        : isNotFound(error)
          ? UNDO_GONE_MESSAGE
          : UNDO_ERROR_MESSAGE,
    };
  }

  private sendBulk(plan: BulkUndoPlan): Observable<BulkUndoResult> {
    const done = (tasks: Task[]): BulkUndoResult => ({
      tasks,
      removedIds: [],
      kept: false,
      message: UNDONE_MESSAGE,
    });
    switch (plan.kind) {
      case 'bulk-restore':
        return this.trash.restoreMany(plan.ids).pipe(map((r) => done(r.tasks)));
      case 'bulk-patch':
        return this.tasks.bulkPatch({ items: plan.items }).pipe(map((r) => done(r.tasks)));
      case 'bulk-tag':
        return this.tasksApi
          .bulk({ ids: plan.ids, action: plan.action, value: plan.tag })
          .pipe(map((r) => done(r.tasks)));
      case 'bulk-complete':
        return this.tasks.bulkUndoComplete({ items: plan.items }).pipe(
          map(({ results }) => {
            const kept = results.some((r) => r.nextTaskOutcome === 'KEPT');
            // A copy is gone if the server deleted it or it was already gone; a kept one stays.
            const removedIds = plan.items.flatMap((item, i) =>
              item.nextTaskId !== undefined && results[i]?.nextTaskOutcome !== 'KEPT'
                ? [item.nextTaskId]
                : [],
            );
            return {
              tasks: results,
              removedIds,
              kept,
              message: kept ? UNDONE_KEPT_MESSAGE : UNDONE_MESSAGE,
            };
          }),
        );
    }
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
