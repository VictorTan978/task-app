import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { User } from '../../../core/auth/auth.models';
import { AuthService } from '../../../core/auth/auth.service';
import { DisconnectGoogleDialog } from './disconnect-google-dialog';

const CONNECTED: User = {
  id: 1,
  email: 'bob@example.com',
  displayName: 'Bob',
  emailVerified: true,
  hasPassword: true,
  role: 'USER',
  timeZone: 'Europe/Bucharest',
  reminderEmails: true,
  connections: [{ provider: 'google', email: 'bob@gmail.com' }],
};
const DISCONNECTED: User = { ...CONNECTED, connections: [] };
const URL = '/api/account/connections/google';

function problem(status: number, code: string) {
  return { type: 'about:blank', title: 'x', status, detail: 'x', instance: URL, code };
}

describe('DisconnectGoogleDialog', () => {
  let fixture: ComponentFixture<DisconnectGoogleDialog>;
  let el: HTMLElement;
  let http: HttpTestingController;
  let auth: AuthService;
  let disconnected: ReturnType<typeof vi.fn<() => void>>;
  let closed: ReturnType<typeof vi.fn<() => void>>;
  let showModal: ReturnType<typeof vi.fn>;

  beforeAll(() => {
    showModal = vi.fn(function (this: HTMLDialogElement) {
      this.setAttribute('open', '');
    });
    HTMLDialogElement.prototype.showModal = showModal as unknown as () => void;
  });

  beforeEach(async () => {
    showModal.mockClear();
    TestBed.configureTestingModule({
      imports: [DisconnectGoogleDialog],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    auth = TestBed.inject(AuthService);
    auth.loadMe().subscribe();
    http.expectOne('/api/auth/me').flush(CONNECTED);
    fixture = TestBed.createComponent(DisconnectGoogleDialog);
    disconnected = vi.fn<() => void>();
    closed = vi.fn<() => void>();
    fixture.componentInstance.disconnected.subscribe(() => disconnected());
    fixture.componentInstance.closed.subscribe(() => closed());
    el = fixture.nativeElement;
    await fixture.whenStable();
  });

  afterEach(() => http.verify());

  const confirmButton = () => el.querySelector<HTMLButtonElement>('#disconnect-google-confirm')!;
  const cancelButton = () =>
    [...el.querySelectorAll<HTMLButtonElement>('button')].find((b) =>
      b.textContent?.includes('Cancel'),
    )!;
  const alert = () => el.querySelector('[role=alert]')?.textContent?.trim();

  async function confirm() {
    confirmButton().click();
    await fixture.whenStable();
  }

  it('opens as a modal asking to confirm', () => {
    expect(showModal).toHaveBeenCalledTimes(1);
    const dialog = el.querySelector('dialog')!;
    expect(dialog.getAttribute('aria-labelledby')).toBe('disconnect-google-heading');
    expect(dialog.textContent).toContain('Disconnect Google?');
    expect(confirmButton().textContent?.trim()).toBe('Disconnect');
  });

  it('Cancel and Escape close without a request', () => {
    cancelButton().click();
    const escape = new Event('cancel', { cancelable: true });
    el.querySelector('dialog')!.dispatchEvent(escape);
    expect(escape.defaultPrevented).toBe(true);
    expect(closed).toHaveBeenCalledTimes(2);
    http.expectNone(URL);
  });

  it('disconnects: pending blocks cancel, then updates the user and emits disconnected', async () => {
    await confirm();
    const req = http.expectOne(URL);
    expect(req.request.method).toBe('DELETE');
    expect(confirmButton().disabled).toBe(true);
    expect(confirmButton().getAttribute('aria-busy')).toBe('true');
    expect(el.querySelector('[data-testid=spinner]')).not.toBeNull();
    expect(cancelButton().disabled).toBe(true);
    cancelButton().click();
    expect(closed).not.toHaveBeenCalled();

    // No double request.
    confirmButton().click();
    http.expectNone(URL);

    req.flush(DISCONNECTED);
    await fixture.whenStable();
    expect(disconnected).toHaveBeenCalledTimes(1);
    expect(auth.currentUser()?.connections).toEqual([]);
  });

  it('on 409 PASSWORD_REQUIRED shows "Set a password first…"', async () => {
    await confirm();
    http.expectOne(URL).flush(problem(409, 'PASSWORD_REQUIRED'), { status: 409, statusText: 'x' });
    await fixture.whenStable();
    expect(alert()).toBe("Set a password first so you don't lose access to your account.");
    expect(disconnected).not.toHaveBeenCalled();
    expect(confirmButton().disabled).toBe(false);
  });

  it('on 404 refetches the user and shows the generic error', async () => {
    await confirm();
    http.expectOne(URL).flush(problem(404, 'NOT_FOUND'), { status: 404, statusText: 'x' });
    await fixture.whenStable();
    http.expectOne('/api/auth/me').flush(DISCONNECTED);
    await fixture.whenStable();
    expect(alert()).toBe('Something went wrong, please try again');
    expect(auth.currentUser()?.connections).toEqual([]);
    expect(disconnected).not.toHaveBeenCalled();
  });

  it('on 401 shows nothing (the interceptor handles it)', async () => {
    await confirm();
    http.expectOne(URL).flush(problem(401, 'UNAUTHENTICATED'), { status: 401, statusText: 'x' });
    await fixture.whenStable();
    expect(alert()).toBeUndefined();
    expect(disconnected).not.toHaveBeenCalled();
  });

  it.each([
    ['network error', 0, ''],
    ['server error', 500, 'INTERNAL_ERROR'],
    ['CSRF failure', 403, 'CSRF_INVALID'],
  ])('shows the generic error on %s', async (_label, status, code) => {
    await confirm();
    const req = http.expectOne(URL);
    if (status === 0) {
      req.error(new ProgressEvent('error'));
    } else {
      req.flush(problem(status, code), { status, statusText: 'x' });
    }
    await fixture.whenStable();
    expect(alert()).toBe('Something went wrong, please try again');
    expect(auth.currentUser()).toEqual(CONNECTED);
    http.expectNone('/api/auth/me');
  });
});
