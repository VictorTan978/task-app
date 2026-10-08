import { HttpClient, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { map, Observable } from 'rxjs';
import { TASKS_API } from './tasks-api';
import {
  CreateTaskRequest,
  ReplaceTaskRequest,
  SetTaskDoneRequest,
  Task,
  TaskFilter,
} from './tasks.models';

/** Typed HTTP access to `/api/tasks`. Stateless: the page owns the list state. */
@Injectable({ providedIn: 'root' })
export class TasksService {
  private readonly http = inject(HttpClient);

  list(filter: TaskFilter): Observable<Task[]> {
    const params = filter === 'all' ? undefined : new HttpParams().set('filter', filter);
    return this.http.get<Task[]>(TASKS_API.list, { params });
  }

  create(request: CreateTaskRequest): Observable<Task> {
    return this.http.post<Task>(TASKS_API.list, request);
  }

  replace(id: number, request: ReplaceTaskRequest): Observable<Task> {
    return this.http.put<Task>(TASKS_API.one(id), request);
  }

  setDone(id: number, done: boolean): Observable<Task> {
    const body: SetTaskDoneRequest = { done };
    return this.http.patch<Task>(TASKS_API.one(id), body);
  }

  delete(id: number): Observable<void> {
    return this.http.delete<unknown>(TASKS_API.one(id)).pipe(map(() => undefined));
  }
}
