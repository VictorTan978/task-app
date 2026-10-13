import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { AuthService } from '../../../core/auth/auth.service';
import { LogoutOthersDialog } from './logout-others-dialog';

const BOB = { id: 1, email: 'bob@example.com', displayName: 'Bob' };
const URL = '/api/account/logout-other-sessions';

function problem(status: number, code: string) {
  return { type: 'about:blank', title: 'x', status, detail: 'x', instance: URL, code };
}

describe('LogoutOthersDialog', () => {
  let fixture: ComponentFixture<LogoutOthersDialog>;
  let el: HTMLElement;
  let http: HttpTestingController;
  let auth: AuthService;
  let loggedOut: ReturnType<typeof vi.fn<() => void>>;
  let closed: ReturnType<typeof vi.fn<() => void>>;
  let showModal: ReturnType<typeof vi.fn>;

  beforeAll(() => {
    // jsdom has no showModal.
    showModal = vi.fn(function (this: HTMLDialogElement) {
      this.setAttribute('open', '');
    });
    HTMLDialogElement.prototype.showModal = showModal as unknown as () => void;
  });

  beforeEach(async () => {
    showModal.mockClear();
    TestBed.configureTestingModule({
      imports: [LogoutOthersDialog],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    auth = TestBed.inject(AuthService);
    auth.loadMe().subscribe();
    http.expectOne('/api/auth/me').flush(BOB);
    fixture = TestBed.createComponent(LogoutOthersDialog);
    loggedOut = vi.fn<() => void>();
    closed = vi.fn<() => void>();
    fixture.componentInstance.loggedOut.subscribe(() => loggedOut());
    fixture.componentInstance.closed.subscribe(() => closed());
    el = fixture.nativeElement;
    await fixture.whenStable();
  });

  afterEach(() => http.verify());

  const confirmButton = () => el.querySelector<HTMLButtonElement>('#logout-others-confirm')!;
  const cancelButton = () =>
    [...el.querySelectorAll<HTMLButtonElement>('button')].find((b) =>
      b.textContent?.includes('Cancel'),
    )!;
  const alert = () => el.querySelector('[role=alert]')?.textContent?.trim();
  const escape = () => {
    const event = new Event('cancel', { cancelable: true });
    el.querySelector('dialog')!.dispatchEvent(event);
    return event;
  };

  async function confirm() {
    confirmButton().click();
    await fixture.whenStable();
  }

  it('opens as a modal with no password field', () => {
    expect(showModal).toHaveBeenCalledTimes(1);
    const dialog = el.querySelector('dialog')!;
    expect(dialog.getAttribute('aria-labelledby')).toBe('logout-others-heading');
    expect(dialog.textContent).toContain('Log out all other devices?');
    expect(dialog.textContent).toContain('You stay signed in on this device');
    expect(el.querySelectorAll('input').length).toBe(0);
    expect(confirmButton().disabled).toBe(false);
    expect(alert()).toBeUndefined();
  });

  it('Cancel closes without a request', () => {
    cancelButton().click();
    expect(closed).toHaveBeenCalledTimes(1);
    http.expectNone(URL);
  });

  it('Escape closes without a request', () => {
    expect(escape().defaultPrevented).toBe(true);
    expect(closed).toHaveBeenCalledTimes(1);
    http.expectNone(URL);
  });

  it('pending: Confirm disabled with a spinner, Cancel and Escape blocked; 204 emits loggedOut', async () => {
    await confirm();
    const req = http.expectOne(URL);
    expect(req.request.method).toBe('POST');
    expect(confirmButton().disabled).toBe(true);
    expect(confirmButton().getAttribute('aria-busy')).toBe('true');
    expect(el.querySelector('[data-testid=spinner]')).not.toBeNull();
    expect(cancelButton().disabled).toBe(true);
    expect(escape().defaultPrevented).toBe(true);
    cancelButton().click();
    expect(closed).not.toHaveBeenCalled();

    // No second request while pending.
    fixture.componentInstance['confirm']();
    http.expectNone(URL);

    req.flush(null, { status: 204, statusText: 'No Content' });
    await fixture.whenStable();
    expect(loggedOut).toHaveBeenCalledTimes(1);
    expect(closed).not.toHaveBeenCalled();
    expect(auth.isSignedIn()).toBe(true);
    http.expectNone('/api/auth/me');
  });

  it.each([
    ['network error', 0, ''],
    ['server error', 500, 'INTERNAL_ERROR'],
    ['service unavailable', 503, 'INTERNAL_ERROR'],
    ['CSRF failure', 403, 'CSRF_INVALID'],
  ])('shows the generic banner on %s and allows a retry', async (_label, status, code) => {
    await confirm();
    const req = http.expectOne(URL);
    if (status === 0) {
      req.error(new ProgressEvent('error'));
    } else {
      req.flush(problem(status, code), { status, statusText: 'x' });
    }
    await fixture.whenStable();
    expect(alert()).toBe('Something went wrong, please try again');
    expect(loggedOut).not.toHaveBeenCalled();
    expect(confirmButton().disabled).toBe(false);
    expect(cancelButton().disabled).toBe(false);
    expect(el.querySelector('[data-testid=spinner]')).toBeNull();

    // Retry clears the banner.
    await confirm();
    expect(alert()).toBeUndefined();
    http.expectOne(URL).flush(null, { status: 204, statusText: 'No Content' });
    await fixture.whenStable();
    expect(loggedOut).toHaveBeenCalledTimes(1);
  });

  it('shows no banner on 401 (the interceptor handles it)', async () => {
    await confirm();
    http
      .expectOne(URL)
      .flush(problem(401, 'UNAUTHENTICATED'), { status: 401, statusText: 'Unauthorized' });
    await fixture.whenStable();
    expect(alert()).toBeUndefined();
    expect(loggedOut).not.toHaveBeenCalled();
  });
});
