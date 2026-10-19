import { computed, effect, inject, Injectable, signal, untracked } from '@angular/core';
import { Subscription } from 'rxjs';
import { AuthService } from '../../core/auth/auth.service';
import { SharingService } from './sharing.service';

interface CountState {
  /** User the count belongs to; null when nothing was loaded for anyone. */
  userId: number | null;
  count: number;
}

const EMPTY: CountState = { userId: null, count: 0 };

/**
 * My pending invitation count, for the header badge. There are no live updates: it is refetched
 * when the shell opens, on every page change, and set directly by the invitations page after it
 * loads, accepts or declines. A failed refetch keeps the last count (the badge is a hint; the
 * invitations page shows its own errors). Tied to the signed-in user and cleared on sign-out.
 */
@Injectable({ providedIn: 'root' })
export class InvitationsStore {
  private readonly api = inject(SharingService);
  private readonly auth = inject(AuthService);
  private readonly state = signal<CountState>(EMPTY);
  private sub: Subscription | null = null;

  private readonly userId = computed(() => this.auth.currentUser()?.id ?? null);

  /** My pending invitations right now (0 until loaded, and for another user's state). */
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

  /** Refetches the count (a running refetch is replaced). */
  refresh(): void {
    const userId = this.userId();
    if (userId === null) {
      return;
    }
    this.sub?.unsubscribe();
    this.sub = this.api.myInvitationCount().subscribe({
      next: (count) => {
        if (this.userId() === userId) {
          this.state.set({ userId, count });
        }
      },
      // Keep the last count; 401 is handled globally.
      error: () => undefined,
    });
  }

  /** Sets the count from what the invitations page knows (cancels a running refetch). */
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
