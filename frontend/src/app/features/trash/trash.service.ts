import { HttpClient, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { map, Observable } from 'rxjs';
import { RestoreTasksResult, Task } from '../tasks/tasks.models';
import { TRASH_API } from './trash-api';
import { EmptyTrashResult, TrashPage } from './trash.models';

/** Typed HTTP access to `/api/trash` (0.16.0). Stateless. */
@Injectable({ providedIn: 'root' })
export class TrashService {
  private readonly http = inject(HttpClient);

  /**
   * One page of my trashed tasks. `listId` null leaves `list` out, which here means **every**
   * list I am a member of (unlike `/api/tasks`, where it means "My tasks").
   */
  list(listId: number | null, cursor: string | null = null): Observable<TrashPage> {
    let params = new HttpParams();
    if (listId !== null) {
      params = params.set('list', listId);
    }
    if (cursor) {
      params = params.set('cursor', cursor);
    }
    return this.http.get<TrashPage>(TRASH_API.list, { params });
  }

  /** Puts a trashed task back in its list (also the undo of a delete). */
  restore(id: number): Observable<Task> {
    return this.http.post<Task>(TRASH_API.restore(id), null);
  }

  /** Puts many trashed tasks back, all or nothing (0.17.0; the undo of a bulk delete). */
  restoreMany(ids: number[]): Observable<RestoreTasksResult> {
    return this.http.post<RestoreTasksResult>(TRASH_API.restoreMany, { ids });
  }

  /** Deletes a trashed task for good (204). */
  deleteForever(id: number): Observable<void> {
    return this.http.delete<unknown>(TRASH_API.one(id)).pipe(map(() => undefined));
  }

  /** Deletes every trashed task I can see, or only those of `listId`. */
  empty(listId: number | null): Observable<EmptyTrashResult> {
    const params = listId === null ? new HttpParams() : new HttpParams().set('list', listId);
    return this.http.post<EmptyTrashResult>(TRASH_API.empty, null, { params });
  }
}
