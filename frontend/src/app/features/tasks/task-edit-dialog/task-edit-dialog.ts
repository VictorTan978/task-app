import {
  afterNextRender,
  Component,
  computed,
  DestroyRef,
  ElementRef,
  inject,
  input,
  OnInit,
  output,
  signal,
  viewChild,
} from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule } from '@angular/forms';
import { Subscription } from 'rxjs';
import { isListNotFound } from '../../lists/lists.logic';
import { ListsStore } from '../../lists/lists.store';
import {
  applyTaskFieldErrors,
  descriptionValidators,
  isNotFound,
  isUnauthenticated,
  normaliseDescription,
  SAVE_ERROR,
  taskFieldMessage,
  taskValidationProblem,
  titleValidators,
} from '../task-form';
import { TaskTagInput } from '../task-tag-input/task-tag-input';
import {
  PRIORITIES,
  PRIORITY_LABELS,
  REPEAT_OPTIONS,
  reminderJustPassed,
  reminderOptions,
  repeatHint,
  STATUS_LABELS,
  STATUSES,
  WEEKDAYS,
  weekdayOf,
} from '../tasks.logic';
import {
  Priority,
  Reminder,
  RepeatFrequency,
  ReplaceTaskRequest,
  Task,
  TaskFieldName,
  TaskProblem,
  TaskResult,
  TaskStatus,
  Weekday,
} from '../tasks.models';
import { TasksService } from '../tasks.service';

type ShownField = Extract<
  TaskFieldName,
  'title' | 'description' | 'status' | 'tags' | 'dueTime' | 'reminder' | 'repeat'
>;

export const WEEKDAY_REQUIRED = 'Pick at least one weekday';
export const REPEAT_NEEDS_DATE = 'Set a due date to repeat';

export const REMINDER_PASSED_NOTE = 'This reminder time has passed';

export const MOVE_LIST_GONE = 'This list no longer exists. Choose another list.';
export const LISTS_UNAVAILABLE = "Couldn't load your lists, so the task can't be moved right now.";

/** Own text for `tags` / `TAG_LIMIT` on a move, naming the target list. */
export function moveTagLimitMessage(listName: string): string {
  return `Can't move to "${listName}": a list can have at most 200 tags`;
}

@Component({
  selector: 'app-task-edit-dialog',
  imports: [ReactiveFormsModule, TaskTagInput],
  templateUrl: './task-edit-dialog.html',
  styleUrl: '../dialog.scss',
})
export class TaskEditDialog implements OnInit {
  private readonly tasks = inject(TasksService);
  private readonly lists = inject(ListsStore);
  private readonly destroyRef = inject(DestroyRef);
  private sub: Subscription | null = null;

  readonly task = input.required<Task>();
  /** List whose tags are suggested (the open list); null leaves `list` out ("My tasks"). */
  readonly tagsListId = input<number | null>(null);

  readonly saved = output<TaskResult>();
  readonly closed = output<void>();
  readonly notFound = output<void>();

  protected readonly priorities = PRIORITIES.map((value) => ({
    value,
    label: PRIORITY_LABELS[value],
  }));
  protected readonly repeatOptions = REPEAT_OPTIONS;
  protected readonly weekdays = WEEKDAYS;
  protected readonly repeatNeedsDate = REPEAT_NEEDS_DATE;
  protected readonly statuses = STATUSES.map((value) => ({ value, label: STATUS_LABELS[value] }));

  protected readonly form = inject(NonNullableFormBuilder).group({
    title: ['', titleValidators],
    description: ['', descriptionValidators],
    priority: ['NONE' as Priority],
    status: ['TODO' as TaskStatus],
    dueDate: [''],
    dueTime: [''],
    reminder: ['NONE' as Reminder | 'NONE'],
    repeat: ['NEVER' as RepeatFrequency | 'NEVER'],
    repeatDays: [[] as Weekday[]],
    tags: [[] as string[]],
  });

  protected readonly pending = signal(false);
  protected readonly submitted = signal(false);
  protected readonly error = signal<string | null>(null);
  /** The user's tags for suggestions; stays empty if loading them fails. */
  protected readonly knownTags = signal<readonly string[]>([]);
  private lastRequest: ReplaceTaskRequest | null = null;

  /** Mirrors of the date and time controls, so the reminder choices follow them. */
  private readonly hasDate = signal(false);
  private readonly hasTime = signal(false);
  /** Reminder choices: they depend on whether the task has a due time. */
  protected readonly reminderChoices = computed(() => reminderOptions(this.hasTime()));
  /** True when my reminder time is in the past (on open, or right after a save). */
  protected readonly reminderPassed = signal(false);
  /** A save went through and the dialog was kept open to show the passed-reminder note. */
  protected readonly reminderPassedNote = REMINDER_PASSED_NOTE;
  protected readonly savedPassed = signal(false);

  /** Mirrors of the repeat controls, for the hint and the weekday error. */
  protected readonly repeatFrequency = signal<RepeatFrequency | 'NEVER'>('NEVER');
  protected readonly repeatDays = signal<readonly Weekday[]>([]);
  protected readonly dueDateValue = signal('');
  protected readonly hasDateValue = this.hasDate.asReadonly();
  protected readonly repeatHintText = computed(() =>
    repeatHint(this.repeatFrequency(), this.dueDateValue()),
  );
  /** Weekly with no weekday picked: Save is blocked and this is shown. */
  protected readonly weekdayError = computed(() =>
    this.submitted() && this.repeatFrequency() === 'WEEKLY' && this.repeatDays().length === 0
      ? WEEKDAY_REQUIRED
      : null,
  );

  /** "List" select: my lists; the task moves when another one is picked. */
  protected readonly listOptions = this.lists.lists;
  protected readonly targetListId = signal(0);
  protected readonly listError = signal<string | null>(null);
  protected readonly listsReady = this.lists.loaded;
  /** Why the List select is disabled (lists not loaded), else null. */
  protected readonly listNote = computed(() => {
    if (this.lists.loaded()) {
      return null;
    }
    return this.lists.error() ? LISTS_UNAVAILABLE : 'Loading lists…';
  });

  private readonly dialog = viewChild.required<ElementRef<HTMLDialogElement>>('dialog');
  private readonly tagInput = viewChild.required(TaskTagInput);

  constructor() {
    afterNextRender(() => this.dialog().nativeElement.showModal());
    this.destroyRef.onDestroy(() => this.sub?.unsubscribe());
  }

  ngOnInit(): void {
    const t = this.task();
    this.form.setValue({
      title: t.title,
      description: t.description ?? '',
      priority: t.priority,
      status: t.status,
      dueDate: t.dueDate ?? '',
      dueTime: t.dueTime ?? '',
      reminder: t.myReminder ?? 'NONE',
      repeat: t.repeat?.frequency ?? 'NEVER',
      repeatDays: t.repeat ? [...t.repeat.weekdays] : [],
      tags: [...t.tags],
    });
    this.hasDate.set(!!t.dueDate);
    this.dueDateValue.set(t.dueDate ?? '');
    this.repeatFrequency.set(t.repeat?.frequency ?? 'NEVER');
    this.repeatDays.set(t.repeat ? [...t.repeat.weekdays] : []);
    this.hasTime.set(!!t.dueTime);
    this.reminderPassed.set(t.myReminder !== null && t.myReminderPassed);
    this.syncDateDependent();
    this.watchDateTime();
    this.targetListId.set(t.listId);
    this.lists.load();
    // Fresh on every open; a failure only means no suggestions.
    const tagsSub = this.tasks.listTags(this.tagsListId()).subscribe({
      next: (tags) => this.knownTags.set(tags.map((t) => t.name)),
      error: () => this.knownTags.set([]),
    });
    this.destroyRef.onDestroy(() => tagsSub.unsubscribe());
  }

  protected fieldError(field: ShownField): string | null {
    const control = this.form.controls[field];
    if (!this.submitted() && !control.touched) {
      return null;
    }
    return taskFieldMessage(field, control);
  }

  protected onListChange(event: Event): void {
    this.targetListId.set(Number((event.target as HTMLSelectElement).value));
    this.listError.set(null);
  }

  protected isDaySelected(day: Weekday): boolean {
    return this.repeatDays().includes(day);
  }

  protected toggleDay(day: Weekday): void {
    const next = this.isDaySelected(day)
      ? this.repeatDays().filter((d) => d !== day)
      : [...this.repeatDays(), day];
    this.repeatDays.set(next);
    const { repeat, repeatDays } = this.form.controls;
    repeatDays.setValue(next as Weekday[]);
    // A server error on `repeat` goes away once the user changes the rule.
    repeat.setErrors(null);
  }

  /** Server message for the Repeat select (400 on `repeat`), or null. */
  protected repeatError(): string | null {
    const control = this.form.controls.repeat;
    return control.touched || this.submitted() ? taskFieldMessage('repeat', control) : null;
  }

  protected clearDueDate(): void {
    this.form.controls.dueDate.setValue('');
  }

  /** Time and reminder follow the date: no date, no time and no reminder. */
  private watchDateTime(): void {
    const { dueDate, dueTime, reminder, repeat, repeatDays } = this.form.controls;
    const repeatSub = repeat.valueChanges.subscribe((value) => {
      this.repeatFrequency.set(value);
      if (value === 'WEEKLY' && repeatDays.value.length === 0) {
        // Preselect the due date's weekday.
        const day = weekdayOf(dueDate.value);
        if (day) {
          repeatDays.setValue([day]);
          this.repeatDays.set([day]);
        }
      }
    });
    const sub = dueDate.valueChanges.subscribe((value) => {
      this.hasDate.set(!!value);
      this.dueDateValue.set(value);
      if (!value) {
        repeat.setValue('NEVER');
        repeatDays.setValue([]);
        this.repeatDays.set([]);
        dueTime.setValue('', { emitEvent: false });
        this.hasTime.set(false);
        reminder.setValue('NONE', { emitEvent: false });
      }
      this.onScheduleChanged();
    });
    const timeSub = dueTime.valueChanges.subscribe((value) => {
      this.hasTime.set(!!value);
      this.onScheduleChanged();
    });
    const reminderSub = reminder.valueChanges.subscribe(() => this.reminderPassed.set(false));
    this.destroyRef.onDestroy(() => {
      sub.unsubscribe();
      repeatSub.unsubscribe();
      timeSub.unsubscribe();
      reminderSub.unsubscribe();
    });
  }

  /** A reminder that no longer fits the date or time is reset to None; the note goes away. */
  private onScheduleChanged(): void {
    const current = this.form.controls.reminder.value;
    if (current !== 'NONE' && !this.reminderChoices().some((o) => o.value === current)) {
      this.form.controls.reminder.setValue('NONE', { emitEvent: false });
    }
    this.reminderPassed.set(false);
    this.syncDateDependent();
  }

  /** Time and reminder are disabled without a due date (not while a save runs: all are). */
  private syncDateDependent(): void {
    const { dueTime, reminder, repeat } = this.form.controls;
    if (this.hasDate() && !this.pending()) {
      dueTime.enable({ emitEvent: false });
      reminder.enable({ emitEvent: false });
      repeat.enable({ emitEvent: false });
    } else {
      dueTime.disable({ emitEvent: false });
      reminder.disable({ emitEvent: false });
      repeat.disable({ emitEvent: false });
    }
  }

  /** Escape key: close unless a save is running. */
  protected onCancel(event: Event): void {
    event.preventDefault();
    this.cancel();
  }

  protected cancel(): void {
    if (!this.pending()) {
      this.closed.emit();
    }
  }

  protected save(): void {
    if (this.pending()) {
      return;
    }
    this.submitted.set(true);
    // Text still typed in the Tags field counts as a tag.
    const tagsOk = this.tagInput().commitPending();
    const weekdayMissing =
      this.repeatFrequency() === 'WEEKLY' && this.hasDate() && this.repeatDays().length === 0;
    if (this.form.invalid || !tagsOk || weekdayMissing) {
      this.form.markAllAsTouched();
      return;
    }
    const { title, description, priority, status, dueDate, dueTime, reminder, repeat, tags } =
      this.form.getRawValue();
    const request: ReplaceTaskRequest = {
      title: title.trim(),
      description: normaliseDescription(description),
      dueDate: dueDate || null,
      dueTime: dueDate && dueTime ? dueTime : null,
      priority,
      status,
      tags: tags.filter((t) => t.trim() !== ''),
    };
    // Without a date the server clears the reminder itself.
    // Missing leaves my reminder as it is, so it is sent only when I changed it.
    if (dueDate && reminder !== (this.task().myReminder ?? 'NONE')) {
      request.reminder = reminder;
    }
    // Always send the current rule with the date (re-sending it keeps its anchor). Without a
    // date the server clears the rule itself.
    if (dueDate) {
      if (repeat !== 'NEVER') {
        const days = WEEKDAYS.map((d) => d.value).filter((d) => this.repeatDays().includes(d));
        request.repeat = { frequency: repeat, weekdays: repeat === 'WEEKLY' ? days : [] };
      } else if (this.task().repeat !== null) {
        request.repeat = 'NEVER';
      }
    }
    // listId only when another list was picked (a move).
    const target = this.targetListId();
    if (target !== this.task().listId) {
      request.listId = target;
    }
    this.send(request);
  }

  protected retry(): void {
    if (this.lastRequest) {
      this.send(this.lastRequest);
    }
  }

  private send(request: ReplaceTaskRequest): void {
    if (this.pending()) {
      return;
    }
    this.lastRequest = request;
    this.error.set(null);
    this.listError.set(null);
    this.setPending(true);
    this.sub = this.tasks.replace(this.task().id, request).subscribe({
      next: (task) => {
        this.setPending(false);
        // The page keeps the dialog open exactly when this is true (same rule).
        const passed = reminderJustPassed(this.task(), task) && task.listId === this.task().listId;
        this.savedPassed.set(passed);
        this.reminderPassed.set(passed);
        this.saved.emit(task);
      },
      error: (error: unknown) => {
        // Re-enable first: enabling re-runs validation, which would drop server errors.
        this.setPending(false);
        if (isUnauthenticated(error)) {
          return;
        }
        const moving = request.listId !== undefined && request.listId !== null;
        if (moving && isListNotFound(error)) {
          // The target list was deleted meanwhile; the task itself is fine.
          this.listError.set(MOVE_LIST_GONE);
          this.targetListId.set(this.task().listId);
          this.lists.refresh();
          return;
        }
        if (isNotFound(error)) {
          this.notFound.emit();
          return;
        }
        const problem = taskValidationProblem(error);
        if (problem && moving && this.applyMoveTagLimit(problem, request.listId as number)) {
          return;
        }
        const controls = { ...this.form.controls, done: this.form.controls.status };
        // `repeat` errors show under the Repeat select.
        if (problem && applyTaskFieldErrors(problem, controls)) {
          return;
        }
        this.error.set(SAVE_ERROR);
      },
    });
  }

  /** `tags` / `TAG_LIMIT` on a move: shown next to the List select, naming the target list. */
  private applyMoveTagLimit(problem: TaskProblem, target: number): boolean {
    const errors = problem.errors ?? [];
    if (!errors.some((e) => e.field === 'tags' && e.code === 'TAG_LIMIT')) {
      return false;
    }
    const name = this.lists.byId().get(target)?.name ?? 'that list';
    this.listError.set(moveTagLimitMessage(name));
    return true;
  }

  /** While saving, every control is disabled; the input is kept for a retry. */
  private setPending(pending: boolean): void {
    this.pending.set(pending);
    if (pending) {
      this.form.disable({ emitEvent: false });
    } else {
      this.form.enable({ emitEvent: false });
      this.syncDateDependent();
    }
  }
}
