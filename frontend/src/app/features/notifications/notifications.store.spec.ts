import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { DestroyRef, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { AuthService } from '../../core/auth/auth.service';
import { NOTIFICATION_POLL_MS, NotificationsStore } from './notifications.store';

describe('NotificationsStore', () => {
  const user = signal<{ id: number } | null>({ id: 1 });
  let store: NotificationsStore;
  let http: HttpTestingController;

  beforeEach(() => {
    user.set({ id: 1 });
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: AuthService, useValue: { currentUser: user } },
      ],
    });
    store = TestBed.inject(NotificationsStore);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    vi.useRealTimers();
    http.verify();
  });

  it('is 0 until refreshed, then shows the count', () => {
    expect(store.count()).toBe(0);
    store.refresh();
    http.expectOne('/api/notifications/count').flush({ count: 3 });
    expect(store.count()).toBe(3);
  });

  it('keeps the last count when a refresh fails', () => {
    store.refresh();
    http.expectOne('/api/notifications/count').flush({ count: 3 });
    store.refresh();
    http.expectOne('/api/notifications/count').flush(null, { status: 500, statusText: 'x' });
    expect(store.count()).toBe(3);
  });

  it('set() overrides the count (never below 0) and cancels a running refresh', () => {
    store.refresh();
    store.set(-2);
    expect(http.expectOne('/api/notifications/count').cancelled).toBe(true);
    expect(store.count()).toBe(0);
  });

  it('does nothing when signed out and never shows another user’s count', () => {
    store.refresh();
    http.expectOne('/api/notifications/count').flush({ count: 3 });
    user.set({ id: 2 });
    expect(store.count()).toBe(0);
    user.set(null);
    store.refresh();
    http.expectNone('/api/notifications/count');
  });

  it('polls every 60 s only while visible, and refetches when the tab becomes visible', () => {
    vi.useFakeTimers();
    let destroy: () => void = () => undefined;
    store.startPolling({ onDestroy: (fn: () => void) => (destroy = fn) } as unknown as DestroyRef);
    const state = vi.spyOn(document, 'visibilityState', 'get');

    state.mockReturnValue('hidden');
    vi.advanceTimersByTime(NOTIFICATION_POLL_MS);
    http.expectNone('/api/notifications/count');

    state.mockReturnValue('visible');
    vi.advanceTimersByTime(NOTIFICATION_POLL_MS);
    http.expectOne('/api/notifications/count').flush({ count: 1 });

    document.dispatchEvent(new Event('visibilitychange'));
    http.expectOne('/api/notifications/count').flush({ count: 2 });
    expect(store.count()).toBe(2);

    destroy();
    vi.advanceTimersByTime(NOTIFICATION_POLL_MS * 2);
    document.dispatchEvent(new Event('visibilitychange'));
    http.expectNone('/api/notifications/count');
    state.mockRestore();
  });
});
