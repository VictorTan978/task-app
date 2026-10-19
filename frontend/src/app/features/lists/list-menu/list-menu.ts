import {
  Component,
  computed,
  ElementRef,
  inject,
  input,
  output,
  signal,
  viewChild,
} from '@angular/core';
import { TaskList } from '../lists.models';

/** A list header menu action. */
export type ListMenuAction = 'rename' | 'remove' | 'members' | 'leave';

/**
 * The "⋯" menu on the list header, by role. Owner: Rename, Members and Delete (only Rename on
 * "My tasks", which can't be shared or deleted). Member: Members and Leave list. A plain
 * disclosure (button + buttons); Escape or a click outside closes it.
 */
@Component({
  selector: 'app-list-menu',
  templateUrl: './list-menu.html',
  styleUrl: './list-menu.scss',
  host: {
    '(document:click)': 'onDocumentClick($event)',
    '(keydown)': 'onKeydown($event)',
  },
})
export class ListMenu {
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  readonly list = input.required<TaskList>();

  readonly rename = output<void>();
  readonly remove = output<void>();
  readonly members = output<void>();
  readonly leave = output<void>();

  protected readonly isOwner = computed(() => this.list().role === 'OWNER');

  protected readonly open = signal(false);
  private readonly toggleButton = viewChild.required<ElementRef<HTMLButtonElement>>('toggleButton');

  protected toggle(): void {
    this.open.update((open) => !open);
  }

  protected onKeydown(event: KeyboardEvent): void {
    if (event.key === 'Escape' && this.open()) {
      event.preventDefault();
      this.open.set(false);
      this.toggleButton().nativeElement.focus();
    }
  }

  protected onDocumentClick(event: Event): void {
    if (this.open() && !this.host.nativeElement.contains(event.target as Node)) {
      this.open.set(false);
    }
  }

  protected choose(action: ListMenuAction): void {
    this.open.set(false);
    this[action].emit();
  }
}
