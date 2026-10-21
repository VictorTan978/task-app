import { inject, Injectable, signal } from '@angular/core';
import { NavigationEnd, Router } from '@angular/router';

/** How long a toast stays when nobody hovers or focuses it. */
export const TOAST_MS = 8000;

export interface ToastAction {
  label: string;
  /** Runs when the button is clicked; the toast is closed first, so it may show a new one. */
  run: () => void;
}

export interface ToastOptions {
  message: string;
  action?: ToastAction;
}

export interface Toast {
  message: string;
  action: ToastAction | null;
}

/**
 * The one toast at the bottom of the screen (rendered by `ToastHost` in the shell). A new toast
 * replaces the current one. It closes after 8 s (the timer is held while it is hovered or
 * focused, then restarts), on its close button, and when the page (path) changes; query-param
 * changes keep it. Lives as long as the app.
 */
@Injectable({ providedIn: 'root' })
export class ToastService {
  private readonly router = inject(Router);
  private timer: ReturnType<typeof setTimeout> | null = null;
  private path = this.router.url.split(/[?#]/)[0];

  private readonly state = signal<Toast | null>(null);
  readonly current = this.state.asReadonly();

  constructor() {
    this.router.events.subscribe((e) => {
      if (e instanceof NavigationEnd) {
        const path = e.urlAfterRedirects.split(/[?#]/)[0];
        if (path !== this.path) {
          this.path = path;
          this.dismiss();
        }
      }
    });
  }

  show(options: ToastOptions): void {
    this.state.set({ message: options.message, action: options.action ?? null });
    this.startTimer();
  }

  dismiss(): void {
    this.hold();
    this.state.set(null);
  }

  /** Hover or focus: keep it open. */
  hold(): void {
    if (this.timer !== null) {
      clearTimeout(this.timer);
      this.timer = null;
    }
  }

  /** Pointer or focus left: the 8 s start again. */
  release(): void {
    if (this.state() !== null) {
      this.startTimer();
    }
  }

  /** The action button: close first, then run (the action may show the next toast). */
  runAction(): void {
    const action = this.state()?.action;
    this.dismiss();
    action?.run();
  }

  private startTimer(): void {
    this.hold();
    this.timer = setTimeout(() => this.dismiss(), TOAST_MS);
  }
}
