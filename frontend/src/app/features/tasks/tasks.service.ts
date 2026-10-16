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
  TaskFilter,
  TaskStatus,
} from './tasks.models';

/** Typed HTTP access to `/api/tasks` and `/api/tags`. Stateless: the page owns the list state. */
@Injectable({ providedIn: 'root' })
export class TasksService {
  private readonly http = inject(HttpClient);

  /** `tag` null, empty or blank means no tag filter (the param is left out). */
  list(filter: TaskFilter, tag: string | null = null): Observable<Task[]> {
    let params = new HttpParams();
    if (filter !== 'all') {
      params = params.set('filter', filter);
    }
    if (tag !== null && tag.trim() !== '') {
      params = params.set('tag', tag);
    }
    return this.http.get<Task[]>(TASKS_API.list, { params });
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
