import { Component, computed, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { distinctUntilChanged, map, Subscription } from 'rxjs';
import { ConfirmDeleteDialog } from '../confirm-delete-dialog/confirm-delete-dialog';
import { AddFailure, TaskAddForm } from '../task-add-form/task-add-form';
import { TaskEditDialog } from '../task-edit-dialog/task-edit-dialog';
import {
  ADD_ERROR,
  isNotFound,
  isUnauthenticated,
  LOAD_ERROR,
  TASK_NOT_FOUND_MESSAGE,
  TOGGLE_ERROR,
} from '../task-form';
import { TaskRow } from '../task-row/task-row';
import { findTag } from '../task-tags';
import {
  applyTask,
  normaliseFilter,
  normaliseTagParam,
  sortTasks,
  todayLocal,
} from '../tasks.logic';
import { Task, TaskFilter } from '../tasks.models';
import { TasksService } from '../tasks.service';

interface Banner {
  message: string;
  retry: (() => void) | null;
}

interface FilterTab {
  filter: TaskFilter;
  label: string;
  /** `null` removes the param, so "All" is plain `/tasks`. */
  param: TaskFilter | null;
}

const EMPTY_TEXT: Record<TaskFilter, string> = {
  all: 'No tasks yet — add your first one above',
  active: 'Nothing left to do',
  in_progress: 'Nothing in progress',
  done: 'Nothing done yet',
};

/** Empty text on every tab while a tag filter is set. */
const NO_TAGGED_TASKS = 'No tasks with this tag';

interface ListQuery {
  filter: TaskFilter;
  /** Raw `?tag=` value; null when missing or blank. */
  tag: string | null;
}

@Component({
  selector: 'app-tasks-page',
  imports: [RouterLink, TaskAddForm, TaskRow, TaskEditDialog, ConfirmDeleteDialog],
  templateUrl: './tasks-page.html',
  styleUrl: './tasks-page.scss',
})
export class TasksPage {
  private readonly tasksApi = inject(TasksService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private loadSub: Subscription | null = null;

  protected readonly tabs: readonly FilterTab[] = [
    { filter: 'all', label: 'All', param: null },
    { filter: 'active', label: 'Active', param: 'active' },
    { filter: 'in_progress', label: 'In progress', param: 'in_progress' },
    { filter: 'done', label: 'Done', param: 'done' },
  ];

  /** Filter selected in the URL. */
  protected readonly filter = signal<TaskFilter>('all');
  /** Filter the shown list was loaded with (differs from `filter` only after a failed load). */
  private readonly listFilter = signal<TaskFilter>('all');
  /** Tag selected in the URL (raw value), or null. */
  protected readonly tag = signal<string | null>(null);
  /** Tag the shown list was loaded with. */
  private readonly listTag = signal<string | null>(null);

  protected readonly tasks = signal<Task[]>([]);
  protected readonly loading = signal(false);
  /** True once any list load succeeded. */
  protected readonly loaded = signal(false);
  protected readonly banner = signal<Banner | null>(null);
  protected readonly busyIds = signal<ReadonlySet<number>>(new Set());
  protected readonly today = signal(todayLocal());

  protected readonly editing = signal<Task | null>(null);
  protected readonly deleting = signal<Task | null>(null);

  protected readonly emptyText = computed(() =>
    this.listTag() === null ? EMPTY_TEXT[this.listFilter()] : NO_TAGGED_TASKS,
  );

  /** The tag bar shows the stored spelling if a loaded task has the tag, else the raw value. */
  protected readonly tagLabel = computed(() => {
    const tag = this.tag();
    if (tag === null) {
      return null;
    }
    return (
      findTag(
        this.tasks().flatMap((t) => t.tags),
        tag,
      ) ?? tag
    );
  });

  constructor() {
    this.route.queryParamMap
      .pipe(
        map((params): ListQuery => ({
          filter: normaliseFilter(params.get('filter')),
          tag: normaliseTagParam(params.get('tag')),
        })),
        distinctUntilChanged((a, b) => a.filter === b.filter && a.tag === b.tag),
        takeUntilDestroyed(),
      )
      .subscribe(({ filter, tag }) => {
        this.filter.set(filter);
        this.tag.set(tag);
        this.load();
      });
    this.destroyRef.onDestroy(() => this.loadSub?.unsubscribe());
  }

  protected isBusy(id: number): boolean {
    return this.busyIds().has(id);
  }

  protected load(clearBanner = true): void {
    if (clearBanner) {
      this.banner.set(null);
    }
    const filter = this.filter();
    const tag = this.tag();
    this.loadSub?.unsubscribe();
    this.loading.set(true);
    this.loadSub = this.tasksApi.list(filter, tag).subscribe({
      next: (tasks) => {
        this.loading.set(false);
        this.today.set(todayLocal());
        this.listFilter.set(filter);
        this.listTag.set(tag);
        this.tasks.set(sortTasks(tasks));
        this.loaded.set(true);
      },
      error: (error: unknown) => {
        this.loading.set(false);
        if (!isUnauthenticated(error)) {
          this.banner.set({ message: LOAD_ERROR, retry: () => this.load() });
        }
      },
    });
  }

  protected onAddStarted(): void {
    this.banner.set(null);
  }

  protected onCreated(task: Task): void {
    this.apply(task);
  }

  protected onAddFailed(failure: AddFailure): void {
    this.banner.set({ message: ADD_ERROR, retry: failure.retry });
  }

  /** Removes `?tag`, keeping the tab. */
  protected clearTag(): void {
    void this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { tag: null },
      queryParamsHandling: 'merge',
    });
  }

  /** Checkbox: ticked → status Done, unticked → To do (also from In progress). */
  protected toggle(task: Task, done: boolean): void {
    if (this.isBusy(task.id)) {
      return;
    }
    this.banner.set(null);
    this.setBusy(task.id, true);
    this.tasksApi
      .setStatus(task.id, done ? 'DONE' : 'TODO')
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (updated) => {
          this.setBusy(task.id, false);
          this.apply(updated);
        },
        error: (error: unknown) => {
          this.setBusy(task.id, false);
          if (isUnauthenticated(error)) {
            return;
          }
          if (isNotFound(error)) {
            this.taskGone();
            return;
          }
          this.banner.set({ message: TOGGLE_ERROR, retry: () => this.toggle(task, done) });
        },
      });
  }

  protected openEdit(task: Task): void {
    this.deleting.set(null);
    this.editing.set(task);
  }

  protected openDelete(task: Task): void {
    this.editing.set(null);
    this.deleting.set(task);
  }

  protected onSaved(task: Task): void {
    this.editing.set(null);
    this.apply(task);
  }

  protected onDeleted(id: number): void {
    this.deleting.set(null);
    this.tasks.update((tasks) => tasks.filter((t) => t.id !== id));
  }

  /** 404 on toggle, edit or delete: the task is gone (deleted elsewhere). */
  protected taskGone(): void {
    this.editing.set(null);
    this.deleting.set(null);
    this.banner.set({ message: TASK_NOT_FOUND_MESSAGE, retry: null });
    this.load(false);
  }

  private apply(task: Task): void {
    this.tasks.update((tasks) => applyTask(tasks, task, this.listFilter(), this.listTag()));
  }

  private setBusy(id: number, busy: boolean): void {
    this.busyIds.update((ids) => {
      const next = new Set(ids);
      if (busy) {
        next.add(id);
      } else {
        next.delete(id);
      }
      return next;
    });
  }
}
