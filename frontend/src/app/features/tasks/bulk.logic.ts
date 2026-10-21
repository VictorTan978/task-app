/** Bulk actions (0.17.0): selection helpers, tag choices and error messages (pure functions). */
import { HttpErrorResponse } from '@angular/common/http';
import { readProblem } from '../../core/auth/problem';
import { BulkAction, BulkProblem, Task } from './tasks.models';

/** What the bar asks the page to do. `value` as in `BulkActionRequest`. */
export interface BulkRequest {
  action: BulkAction;
  value?: string | number;
}

export interface BulkListChoice {
  id: number;
  name: string;
}

/** The most tasks one request takes. */
export const BULK_MAX = 200;
export const BULK_ERROR = "Couldn't update the tasks. Please try again.";
export const RELOAD_LABEL = 'Reload';

/** Ids from `from` to `to` in list order, both included; just `to` if `from` is not shown. */
export function rangeIds(items: readonly Task[], from: number | null, to: number): number[] {
  const a = items.findIndex((t) => t.id === from);
  const b = items.findIndex((t) => t.id === to);
  if (a < 0 || b < 0) {
    return [to];
  }
  return items.slice(Math.min(a, b), Math.max(a, b) + 1).map((t) => t.id);
}

/** Adds `ids` to the selection without going over `BULK_MAX`; later ids are dropped. */
export function addCapped(selected: ReadonlySet<number>, ids: readonly number[]): Set<number> {
  const next = new Set(selected);
  for (const id of ids) {
    if (next.size >= BULK_MAX) {
      break;
    }
    next.add(id);
  }
  return next;
}

/** The tags of the given tasks, once each (first spelling wins, case ignored), sorted. */
export function tagChoices(tasks: readonly Task[]): string[] {
  const seen = new Map<string, string>();
  for (const tag of tasks.flatMap((t) => t.tags)) {
    const key = tag.toLowerCase();
    if (!seen.has(key)) {
      seen.set(key, tag);
    }
  }
  return [...seen.values()].sort((a, b) => a.localeCompare(b));
}

/** What a failed bulk call tells the user. */
export interface BulkFailure {
  message: string;
  /** Offer "Reload" (the loaded tasks are out of date). */
  reload: boolean;
  /** The target list is gone: refresh the picker. */
  refreshLists: boolean;
  /** 401 is handled globally: stay quiet. */
  silent: boolean;
}

function tasksText(n: number): string {
  return n === 1 ? '1 task is' : `${n} tasks are`;
}

/** Maps a failed `bulk` / `bulk-patch` call to a message; the selection is always kept. */
export function bulkFailure(error: unknown): BulkFailure {
  const base: BulkFailure = {
    message: BULK_ERROR,
    reload: false,
    refreshLists: false,
    silent: false,
  };
  if (error instanceof HttpErrorResponse && error.status === 401) {
    return { ...base, silent: true };
  }
  const problem = readProblem(error) as BulkProblem | null;
  if (problem === null) {
    return base;
  }
  const failed = problem.failedIds ?? [];
  const errors = problem.errors ?? [];
  const has = (field: string, code: string): boolean =>
    errors.some((e) => e.field === field && e.code === code);
  if (problem.status === 404 && problem.code === 'LIST_NOT_FOUND') {
    return {
      ...base,
      message: 'The list you picked is no longer available. Pick another one.',
      refreshLists: true,
    };
  }
  if (problem.status === 404 && problem.code === 'NOT_FOUND') {
    const who = failed.length > 0 ? tasksText(failed.length) : 'Some tasks are';
    return { ...base, message: `${who} no longer available. Reload and try again.`, reload: true };
  }
  if (problem.status === 400) {
    if (errors.some((e) => e.code === 'MIXED_LISTS')) {
      return {
        ...base,
        message: 'The selected tasks are in different lists. Reload and try again.',
        reload: true,
      };
    }
    if (has('tags', 'TOO_MANY')) {
      const who = failed.length > 0 ? tasksText(failed.length) : 'Some tasks are';
      return {
        ...base,
        message: `${who} already at the limit of 10 tags, so nothing was changed.`,
      };
    }
    if (has('tags', 'TAG_LIMIT')) {
      return { ...base, message: 'A list can have at most 200 tags, so nothing was changed.' };
    }
    if (has('ids', 'OUT_OF_RANGE')) {
      return { ...base, message: `Select 1 to ${BULK_MAX} tasks.` };
    }
    if (
      has('value', 'INVALID_CHARACTERS') ||
      has('value', 'TOO_LONG') ||
      has('value', 'REQUIRED')
    ) {
      return { ...base, message: "That tag name isn't valid." };
    }
  }
  return base;
}
