import { Component, ElementRef, HostListener, inject, input, output, signal } from '@angular/core';
import { PRIORITIES, PRIORITY_LABELS, STATUS_LABELS } from '../tasks.logic';
import { BulkListChoice, BulkRequest } from '../bulk.logic';
import { BulkAction, Priority, TaskStatus } from '../tasks.models';
import { TAG_MAX_LENGTH } from '../task-tags';

type MenuName = 'status' | 'priority' | 'addTag' | 'removeTag' | 'move';

/**
 * The bulk action bar: the selection header ("N selected", "Select all loaded", "Cancel") and the
 * eight actions. Shown only in selection mode. On phone width the actions sit in a "More" menu.
 * It only emits; the page sends the request.
 */
@Component({
  selector: 'app-bulk-bar',
  templateUrl: './bulk-bar.html',
  styleUrl: './bulk-bar.scss',
})
export class BulkBar {
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  readonly count = input.required<number>();
  /** A request is running: every control is disabled. */
  readonly busy = input(false);
  /** The selection is at the 200-task cap. */
  readonly atMax = input(false);
  /** Tags of the selected tasks, for "Remove tag". */
  readonly tags = input<readonly string[]>([]);
  /** Lists a task can move to (not the open one). */
  readonly lists = input<readonly BulkListChoice[]>([]);

  readonly run = output<BulkRequest>();
  readonly selectAll = output<void>();
  readonly cancelled = output<void>();

  protected readonly menu = signal<MenuName | null>(null);
  protected readonly moreOpen = signal(false);
  protected readonly tagText = signal('');
  protected readonly tagMax = TAG_MAX_LENGTH;

  protected readonly statuses: readonly { value: TaskStatus; label: string }[] = (
    ['TODO', 'IN_PROGRESS', 'DONE'] as const
  ).map((value) => ({ value, label: STATUS_LABELS[value] }));
  protected readonly priorities: readonly { value: Priority; label: string }[] = PRIORITIES.map(
    (value) => ({ value, label: PRIORITY_LABELS[value] }),
  );

  protected disabled(): boolean {
    return this.busy() || this.count() === 0;
  }

  protected toggleMenu(name: MenuName): void {
    this.menu.update((open) => (open === name ? null : name));
  }

  protected toggleMore(): void {
    this.moreOpen.update((open) => !open);
    this.menu.set(null);
  }

  protected emit(action: BulkAction, value?: string | number): void {
    if (this.disabled()) {
      return;
    }
    this.menu.set(null);
    this.moreOpen.set(false);
    this.run.emit(value === undefined ? { action } : { action, value });
  }

  protected addTag(event: Event): void {
    event.preventDefault();
    const name = this.tagText().trim();
    if (name === '') {
      return;
    }
    this.tagText.set('');
    this.emit('ADD_TAG', name);
  }

  /** Esc closes an open menu first; only then it cancels the selection (the page's listener). */
  @HostListener('keydown.escape', ['$event'])
  protected onEscape(event: Event): void {
    if (this.menu() !== null || this.moreOpen()) {
      event.stopPropagation();
      this.menu.set(null);
      this.moreOpen.set(false);
    }
  }

  @HostListener('document:click', ['$event'])
  protected onDocumentClick(event: Event): void {
    if (!this.host.nativeElement.contains(event.target as Node)) {
      this.menu.set(null);
      this.moreOpen.set(false);
    }
  }
}
