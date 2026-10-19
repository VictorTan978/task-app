/**
 * What the tasks page shows, read from the URL: which list (`/tasks` = "My tasks",
 * `/lists/:id` = one of my lists) and which tasks in it (`?filter=&tag=&q=&sort=`).
 */
import { ParamMap } from '@angular/router';
import { parseListId } from '../../lists/lists.logic';
import { normaliseFilter, normaliseQuery, normaliseSort, normaliseTagParam } from '../tasks.logic';
import { TaskListQuery } from '../tasks.models';

/**
 * - `personal`: `/tasks`; requests leave `list` out.
 * - `list`: `/lists/<id>` with a plain positive integer id.
 * - `invalid`: `/lists/<anything else>`; "List not found" without asking the server.
 */
export type ListRef = { kind: 'personal' } | { kind: 'list'; id: number } | { kind: 'invalid' };

export interface PageRoute {
  list: ListRef;
  query: TaskListQuery;
}

export const PERSONAL: ListRef = { kind: 'personal' };

export function readListRef(params: ParamMap): ListRef {
  if (!params.has('id')) {
    return PERSONAL;
  }
  const id = parseListId(params.get('id'));
  return id === null ? { kind: 'invalid' } : { kind: 'list', id };
}

export function readQuery(params: ParamMap): TaskListQuery {
  return {
    filter: normaliseFilter(params.get('filter')),
    tag: normaliseTagParam(params.get('tag')),
    q: normaliseQuery(params.get('q')),
    sort: normaliseSort(params.get('sort')),
  };
}

export function readPageRoute(params: ParamMap, queryParams: ParamMap): PageRoute {
  return { list: readListRef(params), query: readQuery(queryParams) };
}

export function sameListRef(a: ListRef, b: ListRef): boolean {
  if (a.kind === 'list' && b.kind === 'list') {
    return a.id === b.id;
  }
  return a.kind === b.kind;
}

export function sameQuery(a: TaskListQuery, b: TaskListQuery): boolean {
  return a.filter === b.filter && a.tag === b.tag && a.q === b.q && a.sort === b.sort;
}

export function samePageRoute(a: PageRoute, b: PageRoute): boolean {
  return sameListRef(a.list, b.list) && sameQuery(a.query, b.query);
}

/** The `list` param for requests: the id on `/lists/:id`, else null (left out). */
export function listParam(ref: ListRef): number | null {
  return ref.kind === 'list' ? ref.id : null;
}
