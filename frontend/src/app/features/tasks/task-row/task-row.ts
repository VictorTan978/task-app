import {
  Component,
  computed,
  effect,
  ElementRef,
  input,
  output,
  signal,
  viewChild,
} from '@angular/core';
import { RouterLink } from '@angular/router';
import { formatDue, isDone, isOverdue, PRIORITY_LABELS, repeatSummary } from '../tasks.logic';
import { RepeatRule, Task } from '../tasks.models';

/**
 * A click on the selection checkbox. `checked` is the state a plain click asks for (the
 * opposite of the model); with `shift` (and an anchor) the page selects the whole range.
 */
export interface RowSelect {
  checked: boolean;
  shift: boolean;
}

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
  /** Selection mode: the row shows a "Select" checkbox instead of the Done one. */
  readonly selecting = input(false);
  readonly selected = input(false);

  /** The requested new done state (the page sends status `DONE` / `TODO`). */
  readonly doneChange = output<boolean>();
  readonly edit = output<void>();
  readonly remove = output<void>();
  readonly selectChange = output<RowSelect>();

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

  private readonly checkbox = viewChild<ElementRef<HTMLInputElement>>('checkbox');
  private readonly selectBox = viewChild<ElementRef<HTMLInputElement>>('selectBox');
  /** Bumped by every selection click so the checkbox is re-synced even if `selected` is unchanged. */
  private readonly selectClicks = signal(0);

  constructor() {
    // The list only changes once the server confirms. When no request is running, keep the
    // checkbox in sync with the task (this reverts a click whose request failed).
    effect(() => {
      const done = this.done();
      if (!this.busy()) {
        const box = this.checkbox();
        if (box) {
          box.nativeElement.checked = done;
        }
      }
    });
    // The selection checkbox always shows the model: a native toggle the page did not honour
    // (e.g. Shift+click on a selected row) is undone here.
    effect(() => {
      const selected = this.selected();
      this.selectClicks();
      const box = this.selectBox();
      if (box) {
        box.nativeElement.checked = selected;
      }
    });
  }

  protected repeatText(rule: RepeatRule): string {
    return repeatSummary(rule);
  }

  protected onToggle(event: Event): void {
    this.doneChange.emit((event.target as HTMLInputElement).checked);
  }

  protected onSelect(event: Event): void {
    // The page decides from the model, not from the native toggle (which may have drifted).
    this.selectChange.emit({
      checked: !this.selected(),
      shift: (event as MouseEvent).shiftKey === true,
    });
    this.selectClicks.update((n) => n + 1);
  }

  /** Selection mode: clicking the title selects (or unselects) instead of opening the editor. */
  protected onTitle(event: MouseEvent): void {
    if (this.selecting()) {
      this.selectChange.emit({ checked: !this.selected(), shift: event.shiftKey });
      this.selectClicks.update((n) => n + 1);
    } else {
      this.edit.emit();
    }
  }
}
