import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { TASKS_API } from './tasks-api';
import {
  BulkPatchRequest,
  BulkResult,
  BulkUndoCompleteRequest,
  BulkUndoCompleteResult,
  SetTaskDoneRequest,
  TaskResult,
  UndoCompleteRequest,
  UndoCompleteResult,
} from './tasks.models';

/**
 * The two task calls only undo makes (0.16.0). Loaded with `TaskUndo`, on first use, so they stay
 * out of the initial bundle.
 */
@Injectable({ providedIn: 'root' })
export class TasksUndoService {
  private readonly http = inject(HttpClient);

  /** `PATCH` with any subset of fields: undo sends only what the undone action changed. */
  patch(id: number, request: SetTaskDoneRequest): Observable<TaskResult> {
    return this.http.patch<TaskResult>(TASKS_API.one(id), request);
  }

  /** Undo of completing a repeating task; the server decides what happens to the copy. */
  undoComplete(id: number, request: UndoCompleteRequest): Observable<UndoCompleteResult> {
    return this.http.post<UndoCompleteResult>(`${TASKS_API.one(id)}/undo-complete`, request);
  }

  /** Undo of a batch: a different `PATCH` body per task, one list, all or nothing (0.17.0). */
  bulkPatch(request: BulkPatchRequest): Observable<BulkResult> {
    return this.http.post<BulkResult>(TASKS_API.bulkPatch, request);
  }

  /** Undo of a batch of completions, repeating or not, in one call (0.17.0). */
  bulkUndoComplete(request: BulkUndoCompleteRequest): Observable<BulkUndoCompleteResult> {
    return this.http.post<BulkUndoCompleteResult>(TASKS_API.bulkUndoComplete, request);
  }
}
