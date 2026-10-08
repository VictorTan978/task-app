import { Component, computed, DestroyRef, inject, output, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { NonNullableFormBuilder, ReactiveFormsModule } from '@angular/forms';
import { finalize, Subscription } from 'rxjs';
import {
  applyTaskFieldErrors,
  isUnauthenticated,
  taskFieldMessage,
  taskValidationProblem,
  titleValidators,
} from '../task-form';
import { CreateTaskRequest, Task } from '../tasks.models';
import { TasksService } from '../tasks.service';

/** A failed create that the page can offer to retry. */
export interface AddFailure {
  retry: () => void;
}

@Component({
  selector: 'app-task-add-form',
  imports: [ReactiveFormsModule],
  templateUrl: './task-add-form.html',
  styleUrl: './task-add-form.scss',
})
export class TaskAddForm {
  private readonly tasks = inject(TasksService);
  private sub: Subscription | null = null;

  readonly created = output<Task>();
  readonly failed = output<AddFailure>();
  /** Emitted when a create starts, so the page can clear an old banner. */
  readonly started = output<void>();

  protected readonly form = inject(NonNullableFormBuilder).group({
    title: ['', titleValidators],
  });

  private readonly title = toSignal(this.form.controls.title.valueChanges, {
    initialValue: '',
  });

  protected readonly pending = signal(false);
  protected readonly submitted = signal(false);
  protected readonly blank = computed(() => this.title().trim().length === 0);

  constructor() {
    inject(DestroyRef).onDestroy(() => this.sub?.unsubscribe());
  }

  protected titleError(): string | null {
    const control = this.form.controls.title;
    if (this.blank() || (!this.submitted() && !control.touched)) {
      return null;
    }
    return taskFieldMessage('title', control);
  }

  protected submit(): void {
    if (this.pending() || this.blank()) {
      return;
    }
    this.submitted.set(true);
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    this.send({ title: this.form.controls.title.value.trim() });
  }

  private send(request: CreateTaskRequest): void {
    if (this.pending()) {
      return;
    }
    this.started.emit();
    this.pending.set(true);
    this.sub = this.tasks
      .create(request)
      .pipe(finalize(() => this.pending.set(false)))
      .subscribe({
        next: (task) => {
          this.form.reset();
          this.submitted.set(false);
          this.created.emit(task);
        },
        error: (error: unknown) => {
          if (isUnauthenticated(error)) {
            return;
          }
          const problem = taskValidationProblem(error);
          if (problem && applyTaskFieldErrors(problem, this.form.controls)) {
            return;
          }
          this.failed.emit({ retry: () => this.send(request) });
        },
      });
  }
}
