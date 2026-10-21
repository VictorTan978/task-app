import { afterNextRender, Component, ElementRef, input, output, viewChild } from '@angular/core';

/**
 * Confirm dialog of the Trash page ("Delete forever", "Empty trash"). The page does the request
 * and says when it is `pending` or has an `error`.
 */
@Component({
  selector: 'app-trash-confirm-dialog',
  template: `
    <dialog
      #dialog
      class="dialog dialog--narrow"
      aria-labelledby="trash-confirm-heading"
      (cancel)="onCancel($event)"
    >
      <h2 id="trash-confirm-heading" class="dialog__title">{{ heading() }}</h2>
      <p>{{ message() }}</p>

      @if (error(); as text) {
        <div class="notice notice--error" role="alert">{{ text }}</div>
      }

      <div class="dialog__actions">
        <button type="button" class="btn btn--secondary" [disabled]="pending()" (click)="cancel()">
          Cancel
        </button>
        <button
          type="button"
          class="btn btn--danger"
          data-testid="trash-confirm"
          [disabled]="pending()"
          [attr.aria-busy]="pending()"
          (click)="confirmed.emit()"
        >
          @if (pending()) {
            <span class="spinner" aria-hidden="true"></span>
          }
          {{ confirmLabel() }}
        </button>
      </div>
    </dialog>
  `,
  styleUrl: '../../tasks/dialog.scss',
})
export class TrashConfirmDialog {
  readonly heading = input.required<string>();
  readonly message = input.required<string>();
  readonly confirmLabel = input.required<string>();
  readonly pending = input(false);
  readonly error = input<string | null>(null);

  readonly confirmed = output<void>();
  readonly closed = output<void>();

  private readonly dialog = viewChild.required<ElementRef<HTMLDialogElement>>('dialog');

  constructor() {
    afterNextRender(() => this.dialog().nativeElement.showModal());
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
}
