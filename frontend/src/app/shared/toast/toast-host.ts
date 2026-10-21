import { Component, inject } from '@angular/core';
import { ToastService } from './toast.service';

/**
 * The toast region, always in the page so screen readers announce the text that appears in it.
 * It stays open while hovered or focused (see `ToastService`).
 */
@Component({
  selector: 'app-toast-host',
  template: `
    <div class="toast-region" role="status" aria-live="polite">
      @if (toasts.current(); as toast) {
        <div
          class="toast"
          (mouseenter)="toasts.hold()"
          (mouseleave)="toasts.release()"
          (focusin)="toasts.hold()"
          (focusout)="toasts.release()"
        >
          <span>{{ toast.message }}</span>
          @if (toast.action; as action) {
            <button type="button" class="btn" (click)="toasts.runAction()">
              {{ action.label }}
            </button>
          }
          <button type="button" class="toast__close" aria-label="Close" (click)="toasts.dismiss()">
            ×
          </button>
        </div>
      }
    </div>
  `,
  styles: `
    .toast-region {
      position: fixed;
      right: 1rem;
      bottom: 1rem;
      left: 1rem;
      z-index: 1000;
      display: flex;
      justify-content: center;
      pointer-events: none;
    }

    .toast {
      display: flex;
      align-items: center;
      gap: 0.75rem;
      max-width: 560px;
      padding: 0.5rem 0.75rem 0.5rem 1rem;
      border-radius: var(--radius);
      color: #fff;
      background: #1f2328;
      overflow-wrap: anywhere;
      pointer-events: auto;
    }

    .toast .btn,
    .toast__close {
      flex: none;
      border-color: #fff;
      color: #fff;
      background: none;
    }

    .toast__close {
      border: 0;
      font-size: 1.25rem;
      cursor: pointer;
    }
  `,
})
export class ToastHost {
  protected readonly toasts = inject(ToastService);
}
