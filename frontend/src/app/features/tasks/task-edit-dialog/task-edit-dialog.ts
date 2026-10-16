import {
  afterNextRender,
  Component,
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
import { PRIORITIES, PRIORITY_LABELS, STATUS_LABELS, STATUSES } from '../tasks.logic';
import { Priority, ReplaceTaskRequest, Task, TaskFieldName, TaskStatus } from '../tasks.models';
import { TasksService } from '../tasks.service';

type ShownField = Extract<TaskFieldName, 'title' | 'description' | 'status' | 'tags'>;

@Component({
  selector: 'app-task-edit-dialog',
  imports: [ReactiveFormsModule, TaskTagInput],
  templateUrl: './task-edit-dialog.html',
  styleUrl: '../dialog.scss',
})
export class TaskEditDialog implements OnInit {
  private readonly tasks = inject(TasksService);
  private readonly destroyRef = inject(DestroyRef);
  private sub: Subscription | null = null;

  readonly task = input.required<Task>();

  readonly saved = output<Task>();
  readonly closed = output<void>();
  readonly notFound = output<void>();

  protected readonly priorities = PRIORITIES.map((value) => ({
    value,
    label: PRIORITY_LABELS[value],
  }));
  protected readonly statuses = STATUSES.map((value) => ({ value, label: STATUS_LABELS[value] }));

  protected readonly form = inject(NonNullableFormBuilder).group({
    title: ['', titleValidators],
    description: ['', descriptionValidators],
    priority: ['NONE' as Priority],
    status: ['TODO' as TaskStatus],
    dueDate: [''],
    tags: [[] as string[]],
  });

  protected readonly pending = signal(false);
  protected readonly submitted = signal(false);
  protected readonly error = signal<string | null>(null);
  /** The user's tags for suggestions; stays empty if loading them fails. */
  protected readonly knownTags = signal<readonly string[]>([]);
  private lastRequest: ReplaceTaskRequest | null = null;

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
      tags: [...t.tags],
    });
    // Fresh on every open; a failure only means no suggestions.
    const tagsSub = this.tasks.listTags().subscribe({
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

  protected clearDueDate(): void {
    this.form.controls.dueDate.setValue('');
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
    if (this.form.invalid || !tagsOk) {
      this.form.markAllAsTouched();
      return;
    }
    const { title, description, priority, status, dueDate, tags } = this.form.getRawValue();
    this.send({
      title: title.trim(),
      description: normaliseDescription(description),
      dueDate: dueDate || null,
      priority,
      status,
      tags: tags.filter((t) => t.trim() !== ''),
    });
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
    this.setPending(true);
    this.sub = this.tasks.replace(this.task().id, request).subscribe({
      next: (task) => {
        this.setPending(false);
        this.saved.emit(task);
      },
      error: (error: unknown) => {
        // Re-enable first: enabling re-runs validation, which would drop server errors.
        this.setPending(false);
        if (isUnauthenticated(error)) {
          return;
        }
        if (isNotFound(error)) {
          this.notFound.emit();
          return;
        }
        const problem = taskValidationProblem(error);
        const controls = { ...this.form.controls, done: this.form.controls.status };
        if (problem && applyTaskFieldErrors(problem, controls)) {
          return;
        }
        this.error.set(SAVE_ERROR);
      },
    });
  }

  /** While saving, every control is disabled; the input is kept for a retry. */
  private setPending(pending: boolean): void {
    this.pending.set(pending);
    if (pending) {
      this.form.disable({ emitEvent: false });
    } else {
      this.form.enable({ emitEvent: false });
    }
  }
}
