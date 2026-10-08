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
import { finalize, Subscription } from 'rxjs';
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
import { ReplaceTaskRequest, Task, TaskFieldName } from '../tasks.models';
import { TasksService } from '../tasks.service';

@Component({
  selector: 'app-task-edit-dialog',
  imports: [ReactiveFormsModule],
  templateUrl: './task-edit-dialog.html',
  styleUrl: '../dialog.scss',
})
export class TaskEditDialog implements OnInit {
  private readonly tasks = inject(TasksService);
  private sub: Subscription | null = null;

  readonly task = input.required<Task>();

  readonly saved = output<Task>();
  readonly closed = output<void>();
  readonly notFound = output<void>();

  protected readonly form = inject(NonNullableFormBuilder).group({
    title: ['', titleValidators],
    description: ['', descriptionValidators],
    dueDate: [''],
    done: [false],
  });

  protected readonly pending = signal(false);
  protected readonly submitted = signal(false);
  protected readonly error = signal<string | null>(null);
  private lastRequest: ReplaceTaskRequest | null = null;

  private readonly dialog = viewChild.required<ElementRef<HTMLDialogElement>>('dialog');

  constructor() {
    afterNextRender(() => this.dialog().nativeElement.showModal());
    inject(DestroyRef).onDestroy(() => this.sub?.unsubscribe());
  }

  ngOnInit(): void {
    const t = this.task();
    this.form.setValue({
      title: t.title,
      description: t.description ?? '',
      dueDate: t.dueDate ?? '',
      done: t.done,
    });
  }

  protected fieldError(field: Extract<TaskFieldName, 'title' | 'description' | 'done'>) {
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
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const { title, description, dueDate, done } = this.form.getRawValue();
    this.send({
      title: title.trim(),
      description: normaliseDescription(description),
      dueDate: dueDate || null,
      done,
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
    this.pending.set(true);
    this.sub = this.tasks
      .replace(this.task().id, request)
      .pipe(finalize(() => this.pending.set(false)))
      .subscribe({
        next: (task) => this.saved.emit(task),
        error: (error: unknown) => {
          if (isUnauthenticated(error)) {
            return;
          }
          if (isNotFound(error)) {
            this.notFound.emit();
            return;
          }
          const problem = taskValidationProblem(error);
          if (problem && applyTaskFieldErrors(problem, this.form.controls)) {
            return;
          }
          this.error.set(SAVE_ERROR);
        },
      });
  }
}
