import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { map, Observable } from 'rxjs';
import { LISTS_API } from './lists-api';
import { CreateListRequest, RenameListRequest, TaskList, TaskListDetails } from './lists.models';

/** Typed HTTP access to `/api/lists`. Stateless: `ListsStore` keeps the picker's lists. */
@Injectable({ providedIn: 'root' })
export class ListsService {
  private readonly http = inject(HttpClient);

  /** All my lists, "My tasks" first, then by name, then id. Never empty. */
  list(): Observable<TaskList[]> {
    return this.http.get<TaskList[]>(LISTS_API.all);
  }

  /** One list with its task count (the delete dialog reads it on open). */
  get(id: number): Observable<TaskListDetails> {
    return this.http.get<TaskListDetails>(LISTS_API.one(id));
  }

  create(request: CreateListRequest): Observable<TaskList> {
    return this.http.post<TaskList>(LISTS_API.all, request);
  }

  rename(id: number, request: RenameListRequest): Observable<TaskList> {
    return this.http.patch<TaskList>(LISTS_API.one(id), request);
  }

  delete(id: number): Observable<void> {
    return this.http.delete<unknown>(LISTS_API.one(id)).pipe(map(() => undefined));
  }
}
