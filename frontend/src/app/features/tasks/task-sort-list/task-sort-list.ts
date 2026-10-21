import { LiveAnnouncer } from '@angular/cdk/a11y';
import {
  CdkDrag,
  CdkDragHandle,
  CdkDragMove,
  CdkDropList,
  CdkDragDrop,
} from '@angular/cdk/drag-drop';
import {
  afterNextRender,
  Component,
  computed,
  effect,
  ElementRef,
  inject,
  Injector,
  input,
  output,
  signal,
  untracked,
} from '@angular/core';
import { TaskRow } from '../task-row/task-row';
import {
  afterIdOf,
  groupOf,
  ListDrop,
  moveInGroup,
  positionText,
  ReorderDrop,
} from '../reorder.logic';
import { isDone } from '../tasks.logic';
import { Task } from '../tasks.models';

/** Marks the sidebar entries a task can be dropped on (set in the eager list picker). */
export const DROP_LIST_ATTR = 'data-drop-list-id';
const DROP_CLASS = 'list-picker__link--drop';

/** A task being moved with the keyboard: the order before the pick-up, for Esc. */
interface Picked {
  id: number;
  title: string;
}

interface Group {
  key: 'open' | 'done';
  label: string;
  items: Task[];
}

/**
 * The draggable task list of "My order" (loaded lazily with `@defer`, so `@angular/cdk` stays out
 * of the initial bundle). One drop list per group (to do / done), never connected, so a row only
 * moves inside its group. The row itself comes from the page (`rowTemplate`).
 *
 * - Mouse and touch: CDK drag with a grip handle; on drop the page gets the row now directly
 *   above (`afterId`).
 * - Keyboard (CDK drag-drop has none): Space on the handle picks up, Up / Down move, Space drops,
 *   Esc cancels; each step is announced with the `LiveAnnouncer`.
 * - Dropping on a list in the sidebar (`data-drop-list-id`) moves the task there; found by
 *   hit-testing the pointer, since the picker is eager and not a CDK drop list.
 */
@Component({
  selector: 'app-task-sort-list',
  imports: [CdkDropList, CdkDrag, CdkDragHandle, TaskRow],
  templateUrl: './task-sort-list.html',
  styleUrl: './task-sort-list.scss',
})
export class TaskSortList {
  private readonly announcer = inject(LiveAnnouncer);
  private readonly injector = inject(Injector);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  readonly tasks = input.required<Task[]>();
  /** Today's date and the current time, for the rows' Overdue. */
  readonly today = input.required<string>();
  readonly time = input('');
  /** Rows with a request running (ticking). */
  readonly busyIds = input<ReadonlySet<number>>(new Set());
  readonly showAddedBy = input(false);
  /** A save is running (or the list is loading): no dragging. */
  readonly disabled = input(false);
  /** The open list; dropping on it does nothing. */
  readonly ownListId = input<number | null>(null);

  readonly doneChange = output<{ task: Task; done: boolean }>();
  readonly edit = output<Task>();
  readonly remove = output<Task>();
  readonly reordered = output<ReorderDrop>();
  readonly droppedOnList = output<ListDrop>();

  /** The order while a row is picked up with the keyboard (null otherwise). */
  protected readonly local = signal<Task[] | null>(null);
  protected readonly picked = signal<Picked | null>(null);
  private origin: Task[] = [];
  private hovered: Element | null = null;
  private frame = 0;

  private readonly items = computed(() => this.local() ?? this.tasks());
  protected readonly groups = computed<Group[]>(() => {
    const items = this.items();
    return [
      { key: 'open' as const, label: 'Tasks', items: items.filter((t) => !isDone(t)) },
      { key: 'done' as const, label: 'Done tasks', items: items.filter((t) => isDone(t)) },
    ].filter((g) => g.items.length > 0);
  });

  constructor() {
    // The list changed under a picked-up row (a load, a save): drop the pick-up.
    effect(() => {
      this.tasks();
      if (untracked(this.picked) !== null) {
        this.cancelPick(false);
      }
    });
  }

  // ----- Mouse and touch -----

  protected onDragStarted(): void {
    this.cancelPick(false);
  }

  /** While dragging: highlight the sidebar list under the pointer (at most once per frame). */
  protected onDragMoved(event: CdkDragMove<Task>): void {
    const { x, y } = event.pointerPosition;
    cancelAnimationFrame(this.frame);
    this.frame = requestAnimationFrame(() => this.highlight(this.dropTarget(x, y)));
  }

  protected onDragEnded(): void {
    cancelAnimationFrame(this.frame);
    this.highlight(null);
  }

  protected onDrop(event: CdkDragDrop<Task[]>): void {
    this.onDragEnded();
    const task = event.item.data as Task;
    // Over the sidebar: a move, never a reorder. Elsewhere outside the list: nothing.
    if (!event.isPointerOverContainer) {
      const target = this.dropTarget(event.dropPoint.x, event.dropPoint.y);
      if (target !== null) {
        this.droppedOnList.emit({ task, listId: target.listId });
      }
      return;
    }
    if (event.previousIndex === event.currentIndex) {
      return;
    }
    const items = moveInGroup(this.items(), task.id, event.currentIndex);
    this.reordered.emit({ id: task.id, afterId: afterIdOf(items, task.id), items });
  }

  /** The sidebar list under the point, or null (own list, Trash, anything else). */
  private dropTarget(x: number, y: number): { element: Element; listId: number } | null {
    const elements = document.elementsFromPoint?.(x, y) ?? [];
    for (const el of elements) {
      const found = el.closest(`[${DROP_LIST_ATTR}]`);
      if (found) {
        const listId = Number(found.getAttribute(DROP_LIST_ATTR));
        return Number.isInteger(listId) && listId !== this.ownListId()
          ? { element: found, listId }
          : null;
      }
    }
    return null;
  }

  private highlight(target: { element: Element } | null): void {
    const next = target?.element ?? null;
    if (next === this.hovered) {
      return;
    }
    this.hovered?.classList.remove(DROP_CLASS);
    next?.classList.add(DROP_CLASS);
    this.hovered = next;
  }

  /** "Move up" / "Move down" on the row: one step, saved at once. */
  protected moveBy(task: Task, delta: -1 | 1): void {
    const items = this.tasks();
    const index = groupOf(items, task).findIndex((t) => t.id === task.id);
    const moved = moveInGroup(items, task.id, index + delta);
    if (moved === items || this.disabled()) {
      return;
    }
    this.reordered.emit({ id: task.id, afterId: afterIdOf(moved, task.id), items: moved });
    this.announcer.announce(positionText(moved, task.id));
    // The row moved in the DOM, which drops the focus: put it back on a Move button.
    afterNextRender(
      () => {
        const row = this.host.nativeElement.querySelector(
          `app-task-row[data-task-id="${task.id}"]`,
        );
        const pick = (id: string) =>
          row?.querySelector<HTMLButtonElement>(`[data-testid="${id}"]:not(:disabled)`);
        const [same, other] = delta < 0 ? ['move-up', 'move-down'] : ['move-down', 'move-up'];
        (pick(same) ?? pick(other))?.focus();
      },
      { injector: this.injector },
    );
  }

  // ----- Keyboard -----

  protected onHandleKey(event: KeyboardEvent, task: Task): void {
    const picked = this.picked();
    if (event.key === ' ' || event.key === 'Spacebar') {
      event.preventDefault();
      if (this.disabled()) {
        return;
      }
      if (picked?.id === task.id) {
        this.drop();
      } else {
        this.pickUp(task);
      }
      return;
    }
    if (picked?.id !== task.id) {
      return;
    }
    if (event.key === 'ArrowUp' || event.key === 'ArrowDown') {
      event.preventDefault();
      this.step(event.key === 'ArrowUp' ? -1 : 1);
    } else if (event.key === 'Escape') {
      // Only this: the page's own Esc handler must not also run.
      event.preventDefault();
      event.stopPropagation();
      this.cancelPick(true);
    }
  }

  /** Focus left the handle for another control: the pick-up is over (no focus = a row move). */
  protected onHandleBlur(event: FocusEvent, task: Task): void {
    if (this.picked()?.id === task.id && event.relatedTarget !== null) {
      this.cancelPick(true);
    }
  }

  private pickUp(task: Task): void {
    this.origin = [...this.tasks()];
    this.local.set([...this.tasks()]);
    this.picked.set({ id: task.id, title: task.title });
    const group = groupOf(this.tasks(), task);
    const index = group.findIndex((t) => t.id === task.id) + 1;
    this.announcer.announce(
      `Picked up ${task.title}, position ${index} of ${group.length}. ` +
        'Use the up and down arrow keys to move, Space to drop, Escape to cancel.',
    );
  }

  private step(delta: -1 | 1): void {
    const picked = this.picked();
    const items = this.local();
    if (!picked || !items) {
      return;
    }
    const group = groupOf(
      items,
      items.find((t) => t.id === picked.id)!,
    );
    const from = group.findIndex((t) => t.id === picked.id);
    const to = from + delta;
    if (to < 0 || to >= group.length) {
      this.announcer.announce(delta < 0 ? 'Already at the top' : 'Already at the bottom');
      return;
    }
    const moved = moveInGroup(items, picked.id, to);
    this.local.set(moved);
    this.announcer.announce(positionText(moved, picked.id));
    this.focusHandle(picked.id);
  }

  private drop(): void {
    const picked = this.picked();
    const items = this.local();
    if (!picked || !items) {
      return;
    }
    const afterId = afterIdOf(items, picked.id);
    const changed = afterId !== afterIdOf(this.origin, picked.id);
    const text = positionText(items, picked.id, 'Dropped at');
    this.local.set(null);
    this.picked.set(null);
    if (changed) {
      this.reordered.emit({ id: picked.id, afterId, items });
    }
    this.announcer.announce(changed ? text : text.replace('Dropped at', 'Dropped, still at'));
    this.focusHandle(picked.id);
  }

  private cancelPick(announce: boolean): void {
    const picked = this.picked();
    if (!picked) {
      return;
    }
    this.local.set(null);
    this.picked.set(null);
    if (announce) {
      this.announcer.announce(`Cancelled. ${positionText(this.origin, picked.id, 'Back at')}`);
      this.focusHandle(picked.id);
    }
  }

  /** Moving a row in the DOM drops the focus: put it back on its grip. */
  private focusHandle(id: number): void {
    afterNextRender(
      () =>
        this.host.nativeElement
          .querySelector<HTMLElement>(`[data-handle-for="${id}"]`)
          ?.focus({ preventScroll: false }),
      { injector: this.injector },
    );
  }
}
