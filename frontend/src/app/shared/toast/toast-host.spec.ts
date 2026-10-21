import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { ToastHost } from './toast-host';
import { ToastService } from './toast.service';

describe('ToastHost', () => {
  let el: HTMLElement;
  let toasts: ToastService;
  let fixture: ReturnType<typeof TestBed.createComponent<ToastHost>>;

  beforeEach(async () => {
    TestBed.configureTestingModule({ providers: [provideRouter([])] });
    toasts = TestBed.inject(ToastService);
    fixture = TestBed.createComponent(ToastHost);
    el = fixture.nativeElement;
    await fixture.whenStable();
  });

  afterEach(() => toasts.dismiss());

  const region = () => el.querySelector('[role=status]')!;
  const SELECTORS: Record<string, string> = {
    toast: '.toast',
    'toast-action': '.toast .btn',
    'toast-close': '.toast__close',
  };
  const byId = (id: string) => el.querySelector<HTMLElement>(SELECTORS[id]);

  it('has a polite status region even with no toast, so the text is announced when it appears', () => {
    expect(region().getAttribute('aria-live')).toBe('polite');
    expect(byId('toast')).toBeNull();
  });

  it('shows the message, an Undo button and a close button inside the region', async () => {
    toasts.show({
      message: 'Task moved to trash',
      action: { label: 'Undo', run: () => undefined },
    });
    await fixture.whenStable();
    expect(region().textContent).toContain('Task moved to trash');
    expect(byId('toast-action')?.textContent?.trim()).toBe('Undo');
    expect(byId('toast-close')?.getAttribute('aria-label')).toBe('Close');
  });

  it('has no action button when the toast has none', async () => {
    toasts.show({ message: 'Undone' });
    await fixture.whenStable();
    expect(byId('toast-action')).toBeNull();
  });

  it('Undo runs the action once and closes the toast', async () => {
    const run = vi.fn();
    toasts.show({ message: 'x', action: { label: 'Undo', run } });
    await fixture.whenStable();
    byId('toast-action')!.click();
    await fixture.whenStable();
    expect(run).toHaveBeenCalledTimes(1);
    expect(byId('toast')).toBeNull();
  });

  it('the close button closes it', async () => {
    toasts.show({ message: 'x' });
    await fixture.whenStable();
    byId('toast-close')!.click();
    await fixture.whenStable();
    expect(byId('toast')).toBeNull();
  });

  it('stays open while hovered or focused and closes 8 seconds after it is left', async () => {
    vi.useFakeTimers();
    try {
      for (const [enter, leave] of [
        ['mouseenter', 'mouseleave'],
        ['focusin', 'focusout'],
      ]) {
        toasts.show({ message: 'x' });
        fixture.detectChanges();
        const toast = byId('toast')!;
        toast.dispatchEvent(new Event(enter));
        vi.advanceTimersByTime(60_000);
        expect(toasts.current()).not.toBeNull();
        toast.dispatchEvent(new Event(leave));
        vi.advanceTimersByTime(7999);
        expect(toasts.current()).not.toBeNull();
        vi.advanceTimersByTime(1);
        expect(toasts.current()).toBeNull();
      }
    } finally {
      vi.useRealTimers();
    }
  });
});
