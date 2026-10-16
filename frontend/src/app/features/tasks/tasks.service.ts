import { HttpClient, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { map, Observable } from 'rxjs';
import { TASKS_API } from './tasks-api';
import {
  CreateTaskRequest,
  ReplaceTaskRequest,
  SetTaskDoneRequest,
  Tag,
  Task,
  TaskListQuery,
  TaskPage,
  TaskStatus,
} from './tasks.models';

/** Typed HTTP access to `/api/tasks` and `/api/tags`. Stateless: the page owns the list state. */
@Injectable({ providedIn: 'root' })
export class TasksService {
  private readonly http = inject(HttpClient);

  /**
   * One page of `GET /api/tasks`. Params at their default are left out: no `filter` for `all`,
   * no `tag` / `q` when null or blank, no `sort` for `newest`, no `cursor` for the first page.
   * `cursor` must be the `nextCursor` of a page loaded with the same query.
   */
  list(query: TaskListQuery, cursor: string | null = null): Observable<TaskPage> {
    let params = new HttpParams();
    if (query.filter !== 'all') {
      params = params.set('filter', query.filter);
    }
    if (query.tag !== null && query.tag.trim() !== '') {
      params = params.set('tag', query.tag);
    }
    if (query.q !== null && query.q.trim() !== '') {
      params = params.set('q', query.q);
    }
    if (query.sort !== 'newest') {
      params = params.set('sort', query.sort);
    }
    if (cursor) {
      params = params.set('cursor', cursor);
    }
    return this.http.get<TaskPage>(TASKS_API.list, { params });
  }

  create(request: CreateTaskRequest): Observable<Task> {
    return this.http.post<Task>(TASKS_API.list, request);
  }

  replace(id: number, request: ReplaceTaskRequest): Observable<Task> {
    return this.http.put<Task>(TASKS_API.one(id), request);
  }

  /** `PATCH {status}` (the checkbox sends `DONE` / `TODO`). */
  setStatus(id: number, status: TaskStatus): Observable<Task> {
    const body: SetTaskDoneRequest = { status };
    return this.http.patch<Task>(TASKS_API.one(id), body);
  }

  delete(id: number): Observable<void> {
    return this.http.delete<unknown>(TASKS_API.one(id)).pipe(map(() => undefined));
  }

  /** The signed-in user's tags, for suggestions. */
  listTags(): Observable<Tag[]> {
    return this.http.get<Tag[]>(TASKS_API.tags);
  }
}
