import { Component, computed, effect, ElementRef, input, output, viewChild } from '@angular/core';
import { isOverdue } from '../tasks.logic';
import { Task } from '../tasks.models';

@Component({
  selector: 'app-task-row',
  templateUrl: './task-row.html',
  styleUrl: './task-row.scss',
})
export class TaskRow {
  readonly task = input.required<Task>();
  /** Today's local date, `YYYY-MM-DD`. */
  readonly today = input.required<string>();
  readonly busy = input(false);

  /** The requested new `done` value. */
  readonly doneChange = output<boolean>();
  readonly edit = output<void>();
  readonly remove = output<void>();

  protected readonly overdue = computed(() => isOverdue(this.task(), this.today()));

  private readonly checkbox = viewChild.required<ElementRef<HTMLInputElement>>('checkbox');

  constructor() {
    // The list only changes once the server confirms. When no request is running, keep the
    // checkbox in sync with the task (this reverts a click whose request failed).
    effect(() => {
      const done = this.task().done;
      if (!this.busy()) {
        this.checkbox().nativeElement.checked = done;
      }
    });
  }

  protected onToggle(event: Event): void {
    this.doneChange.emit((event.target as HTMLInputElement).checked);
  }
}
