import { Component, computed, effect, ElementRef, input, output, viewChild } from '@angular/core';
import { RouterLink } from '@angular/router';
import { isDone, isOverdue, PRIORITY_LABELS } from '../tasks.logic';
import { Task } from '../tasks.models';

@Component({
  selector: 'app-task-row',
  imports: [RouterLink],
  templateUrl: './task-row.html',
  styleUrl: './task-row.scss',
})
export class TaskRow {
  readonly task = input.required<Task>();
  /** Today's local date, `YYYY-MM-DD`. */
  readonly today = input.required<string>();
  readonly busy = input(false);

  /** The requested new done state (the page sends status `DONE` / `TODO`). */
  readonly doneChange = output<boolean>();
  readonly edit = output<void>();
  readonly remove = output<void>();

  protected readonly done = computed(() => isDone(this.task()));
  protected readonly inProgress = computed(() => this.task().status === 'IN_PROGRESS');
  protected readonly overdue = computed(() => isOverdue(this.task(), this.today()));
  /** Badge text, or null for `NONE`. */
  protected readonly priorityLabel = computed(() => {
    const p = this.task().priority;
    return p === 'NONE' ? null : PRIORITY_LABELS[p];
  });
  protected readonly hasMeta = computed(() => {
    const t = this.task();
    return t.priority !== 'NONE' || this.inProgress() || t.dueDate !== null || t.tags.length > 0;
  });

  private readonly checkbox = viewChild.required<ElementRef<HTMLInputElement>>('checkbox');

  constructor() {
    // The list only changes once the server confirms. When no request is running, keep the
    // checkbox in sync with the task (this reverts a click whose request failed).
    effect(() => {
      const done = this.done();
      if (!this.busy()) {
        this.checkbox().nativeElement.checked = done;
      }
    });
  }

  protected onToggle(event: Event): void {
    this.doneChange.emit((event.target as HTMLInputElement).checked);
  }
}
