import { Component, computed, effect, ElementRef, input, output, viewChild } from '@angular/core';
import { RouterLink } from '@angular/router';
import { formatDue, isDone, isOverdue, PRIORITY_LABELS, repeatSummary } from '../tasks.logic';
import { RepeatRule, Task } from '../tasks.models';

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
  /** Current `HH:mm` in the account zone, for timed tasks' Overdue (empty: unknown). */
  readonly time = input('');
  readonly busy = input(false);
  /** Show "Added by …" (the list has 2 or more members). */
  readonly showAddedBy = input(false);

  /** The requested new done state (the page sends status `DONE` / `TODO`). */
  readonly doneChange = output<boolean>();
  readonly edit = output<void>();
  readonly remove = output<void>();

  protected readonly done = computed(() => isDone(this.task()));
  protected readonly inProgress = computed(() => this.task().status === 'IN_PROGRESS');
  protected readonly overdue = computed(() => isOverdue(this.task(), this.today(), this.time()));
  /** Badge text, or null for `NONE`. */
  protected readonly priorityLabel = computed(() => {
    const p = this.task().priority;
    return p === 'NONE' ? null : PRIORITY_LABELS[p];
  });
  /** `2026-10-21` or, for a timed task, `2026-10-21 09:30`. */
  protected readonly dueText = computed(() => {
    const t = this.task();
    return t.dueDate === null ? null : formatDue(t.dueDate, t.dueTime);
  });
  /** "Added by Ana" / "Added by a deleted user", or null when not shown. */
  protected readonly addedBy = computed(() => {
    if (!this.showAddedBy()) {
      return null;
    }
    const by = this.task().addedBy;
    return by ? `Added by ${by.displayName}` : 'Added by a deleted user';
  });
  protected readonly hasMeta = computed(() => {
    const t = this.task();
    return (
      t.priority !== 'NONE' ||
      this.inProgress() ||
      t.dueDate !== null ||
      t.myReminder !== null ||
      t.tags.length > 0 ||
      this.addedBy() !== null
    );
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

  protected repeatText(rule: RepeatRule): string {
    return repeatSummary(rule);
  }

  protected onToggle(event: Event): void {
    this.doneChange.emit((event.target as HTMLInputElement).checked);
  }
}
