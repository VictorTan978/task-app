import {
  afterNextRender,
  Component,
  computed,
  DestroyRef,
  ElementRef,
  inject,
  Injector,
  input,
  signal,
  viewChild,
} from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { finalize, Subscription } from 'rxjs';
import { memberCountLabel } from '../../sharing/sharing.logic';
import { isUnauthenticated } from '../../tasks/task-form';
import {
  applyListNameError,
  hasListProblem,
  LIST_CREATE_ERROR,
  LIST_LIMIT_MESSAGE,
  listNameMessage,
  listNameValidators,
  LISTS_LOAD_ERROR,
  problemDetail,
} from '../lists.logic';
import { CreateListRequest, TaskList } from '../lists.models';
import { ListsService } from '../lists.service';
import { ListsStore } from '../lists.store';

interface CreateError {
  message: string;
  /** Null when retrying can't help (the 50-list limit). */
  retry: (() => void) | null;
}

/** URL of a list: "My tasks" is `/tasks`, any other list `/lists/<id>`. */
export function listLink(list: TaskList): string {
  return list.personal ? '/tasks' : `/lists/${list.id}`;
}

/**
 * The list picker: a sidebar on desktop, a `<select>` on phone width, and "+ New list" with an
 * inline form. Links carry no query params, so switching lists resets tab, tag, search and sort.
 * Shared lists (2+ members) show their member count.
 */
@Component({
  selector: 'app-list-picker',
  imports: [ReactiveFormsModule, RouterLink],
  templateUrl: './list-picker.html',
  styleUrl: './list-picker.scss',
})
export class ListPicker {
  private readonly store = inject(ListsStore);
  private readonly api = inject(ListsService);
  private readonly router = inject(Router);
  private readonly injector = inject(Injector);
  private sub: Subscription | null = null;

  /** Id of the open list (for "My tasks" on `/tasks` its real id), else null. */
  readonly activeId = input<number | null>(null);

  protected readonly lists = this.store.lists;
  protected readonly loaded = this.store.loaded;
  protected readonly loading = this.store.loading;
  protected readonly loadFailed = this.store.error;
  protected readonly loadError = LISTS_LOAD_ERROR;
  /** The open list is in the picker (else the phone select shows a placeholder). */
  protected readonly activeKnown = computed(() => {
    const id = this.activeId();
    return id !== null && this.store.byId().has(id);
  });

  protected readonly creating = signal(false);
  protected readonly pending = signal(false);
  protected readonly submitted = signal(false);
  protected readonly createError = signal<CreateError | null>(null);
  protected readonly form = inject(NonNullableFormBuilder).group({
    name: ['', listNameValidators],
  });

  private readonly nameInput = viewChild<ElementRef<HTMLInputElement>>('nameInput');
  private readonly newButton = viewChild<ElementRef<HTMLButtonElement>>('newButton');

  constructor() {
    inject(DestroyRef).onDestroy(() => this.sub?.unsubscribe());
  }

  protected link(list: TaskList): string {
    return listLink(list);
  }

  /** "3 members", shown on shared lists. */
  protected countLabel(list: TaskList): string {
    return memberCountLabel(list.memberCount);
  }

  protected retryLoad(): void {
    this.store.refresh();
  }

  /** Phone `<select>`: open the chosen list. */
  protected onSelect(event: Event): void {
    const id = Number((event.target as HTMLSelectElement).value);
    const list = this.store.byId().get(id);
    if (list && id !== this.activeId()) {
      void this.router.navigateByUrl(listLink(list));
    }
  }

  protected nameError(): string | null {
    const control = this.form.controls.name;
    if (!this.submitted() && !control.touched) {
      return null;
    }
    return listNameMessage(control);
  }

  protected openCreate(): void {
    this.creating.set(true);
    this.createError.set(null);
    afterNextRender(() => this.nameInput()?.nativeElement.focus(), { injector: this.injector });
  }

  protected cancelCreate(): void {
    if (this.pending()) {
      return;
    }
    this.closeCreate();
    afterNextRender(() => this.newButton()?.nativeElement.focus(), { injector: this.injector });
  }

  protected onKeydown(event: KeyboardEvent): void {
    if (event.key === 'Escape') {
      event.preventDefault();
      this.cancelCreate();
    }
  }

  protected submit(): void {
    if (this.pending()) {
      return;
    }
    this.submitted.set(true);
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    this.send({ name: this.form.controls.name.value.trim() });
  }

  private send(request: CreateListRequest): void {
    if (this.pending()) {
      return;
    }
    this.createError.set(null);
    this.pending.set(true);
    this.sub = this.api
      .create(request)
      .pipe(finalize(() => this.pending.set(false)))
      .subscribe({
        next: (list) => {
          this.store.upsert(list);
          this.store.refresh();
          this.closeCreate();
          void this.router.navigateByUrl(listLink(list));
        },
        error: (error: unknown) => {
          if (isUnauthenticated(error)) {
            return;
          }
          if (applyListNameError(error, this.form.controls.name)) {
            return;
          }
          if (hasListProblem(error, 409, 'LIST_LIMIT')) {
            this.createError.set({
              message: problemDetail(error) ?? LIST_LIMIT_MESSAGE,
              retry: null,
            });
            return;
          }
          this.createError.set({ message: LIST_CREATE_ERROR, retry: () => this.send(request) });
        },
      });
  }

  private closeCreate(): void {
    this.creating.set(false);
    this.submitted.set(false);
    this.createError.set(null);
    this.form.reset();
  }
}
