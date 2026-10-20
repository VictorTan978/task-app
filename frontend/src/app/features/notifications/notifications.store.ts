import { computed, DestroyRef, effect, inject, Injectable, signal, untracked } from '@angular/core';
import { DOCUMENT } from '@angular/common';
import { Subscription } from 'rxjs';
import { AuthService } from '../../core/auth/auth.service';
import { NotificationsService } from './notifications.service';

interface CountState {
  /** User the count belongs to; null when nothing was loaded for anyone. */
  userId: number | null;
  count: number;
}

const EMPTY: CountState = { userId: null, count: 0 };

/** How often the unread count is refetched while the tab is visible. */
export const NOTIFICATION_POLL_MS = 60_000;

/**
 * My unread notification count, for the header bell. No live updates: it is refetched on
 * navigation, every 60 s while the tab is visible, and when the tab becomes visible again.
 * A failed refetch keeps the last count. Tied to the signed-in user and cleared on sign-out.
 */
@Injectable({ providedIn: 'root' })
export class NotificationsStore {
  private readonly api = inject(NotificationsService);
  private readonly auth = inject(AuthService);
  private readonly document = inject(DOCUMENT);
  private readonly state = signal<CountState>(EMPTY);
  private sub: Subscription | null = null;

  private readonly userId = computed(() => this.auth.currentUser()?.id ?? null);

  /** Unread notifications right now (0 until loaded, and for another user's state). */
  readonly count = computed(() => {
    const state = this.state();
    const userId = this.userId();
    return userId !== null && state.userId === userId ? state.count : 0;
  });

  constructor() {
    effect(() => {
      if (this.userId() === null) {
        untracked(() => this.clear());
      }
    });
  }

  /**
   * Starts the 60 s poll (only fires while the tab is visible) and the refetch on becoming
   * visible. Stops when `destroyRef` is destroyed.
   */
  startPolling(destroyRef: DestroyRef): void {
    const visible = () => this.document.visibilityState !== 'hidden';
    const id = setInterval(() => {
      if (visible()) {
        this.refresh();
      }
    }, NOTIFICATION_POLL_MS);
    const onVisibility = () => {
      if (visible()) {
        this.refresh();
      }
    };
    this.document.addEventListener('visibilitychange', onVisibility);
    destroyRef.onDestroy(() => {
      clearInterval(id);
      this.document.removeEventListener('visibilitychange', onVisibility);
    });
  }

  /** Refetches the count (a running refetch is replaced). */
  refresh(): void {
    const userId = this.userId();
    if (userId === null) {
      return;
    }
    this.sub?.unsubscribe();
    this.sub = this.api.unreadCount().subscribe({
      next: (count) => {
        if (this.userId() === userId) {
          this.state.set({ userId, count });
        }
      },
      // Keep the last count; 401 is handled globally.
      error: () => undefined,
    });
  }

  /** Sets the count from what the notifications page knows (cancels a running refetch). */
  set(count: number): void {
    const userId = this.userId();
    if (userId === null) {
      return;
    }
    this.sub?.unsubscribe();
    this.sub = null;
    this.state.set({ userId, count: Math.max(0, count) });
  }

  clear(): void {
    this.sub?.unsubscribe();
    this.sub = null;
    this.state.set(EMPTY);
  }
}
