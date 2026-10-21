import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { TOAST_MS, ToastService } from './toast.service';

@Component({ template: 'page' })
class Page {}

describe('ToastService', () => {
  let toasts: ToastService;
  let router: Router;

  beforeEach(async () => {
    vi.useFakeTimers();
    TestBed.configureTestingModule({
      providers: [
        provideRouter([
          { path: 'a', component: Page },
          { path: 'b', component: Page },
        ]),
      ],
    });
    toasts = TestBed.inject(ToastService);
    router = TestBed.inject(Router);
    await RouterTestingHarness.create('/a');
  });

  afterEach(() => vi.useRealTimers());

  it('shows one toast and a new one replaces it', () => {
    toasts.show({ message: 'one' });
    expect(toasts.current()?.message).toBe('one');
    toasts.show({ message: 'two' });
    expect(toasts.current()?.message).toBe('two');
  });

  it('closes after 8 seconds', () => {
    toasts.show({ message: 'x' });
    vi.advanceTimersByTime(TOAST_MS - 1);
    expect(toasts.current()).not.toBeNull();
    vi.advanceTimersByTime(1);
    expect(toasts.current()).toBeNull();
  });

  it('a newer toast restarts the 8 seconds', () => {
    toasts.show({ message: 'one' });
    vi.advanceTimersByTime(6000);
    toasts.show({ message: 'two' });
    vi.advanceTimersByTime(6000);
    expect(toasts.current()?.message).toBe('two');
    vi.advanceTimersByTime(2000);
    expect(toasts.current()).toBeNull();
  });

  it('stays open while held (hover or focus) and restarts the 8 seconds on release', () => {
    toasts.show({ message: 'x' });
    toasts.hold();
    vi.advanceTimersByTime(60_000);
    expect(toasts.current()).not.toBeNull();
    toasts.release();
    vi.advanceTimersByTime(TOAST_MS - 1);
    expect(toasts.current()).not.toBeNull();
    vi.advanceTimersByTime(1);
    expect(toasts.current()).toBeNull();
  });

  it('release does nothing when there is no toast', () => {
    toasts.release();
    vi.advanceTimersByTime(TOAST_MS);
    expect(toasts.current()).toBeNull();
  });

  it('dismiss closes it', () => {
    toasts.show({ message: 'x' });
    toasts.dismiss();
    expect(toasts.current()).toBeNull();
  });

  it('the action closes the toast first, then runs once (it may show the next toast)', () => {
    const run = vi.fn(() => toasts.show({ message: 'Undone' }));
    toasts.show({ message: 'x', action: { label: 'Undo', run } });
    toasts.runAction();
    expect(run).toHaveBeenCalledTimes(1);
    expect(toasts.current()?.message).toBe('Undone');
    toasts.runAction();
    expect(run).toHaveBeenCalledTimes(1);
  });

  it('closes when the path changes, but not when only the query changes', async () => {
    toasts.show({ message: 'x' });
    await router.navigateByUrl('/a?q=1');
    expect(toasts.current()).not.toBeNull();
    await router.navigateByUrl('/b');
    expect(toasts.current()).toBeNull();
  });
});
