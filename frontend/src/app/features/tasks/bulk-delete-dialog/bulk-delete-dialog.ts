import { afterNextRender, Component, ElementRef, input, output, viewChild } from '@angular/core';

/** Confirm dialog for deleting the selected tasks; names the count. The page sends the request. */
@Component({
  selector: 'app-bulk-delete-dialog',
  templateUrl: './bulk-delete-dialog.html',
  styleUrl: '../dialog.scss',
})
export class BulkDeleteDialog {
  readonly count = input.required<number>();
  readonly pending = input(false);

  readonly confirmed = output<void>();
  readonly closed = output<void>();

  private readonly dialog = viewChild.required<ElementRef<HTMLDialogElement>>('dialog');

  constructor() {
    afterNextRender(() => this.dialog().nativeElement.showModal());
  }

  protected title(): string {
    const n = this.count();
    return `Delete ${n === 1 ? '1 task' : `${n} tasks`}?`;
  }

  protected onCancel(event: Event): void {
    event.preventDefault();
    this.cancel();
  }

  protected cancel(): void {
    if (!this.pending()) {
      this.closed.emit();
    }
  }

  protected confirm(): void {
    if (!this.pending()) {
      this.confirmed.emit();
    }
  }
}
