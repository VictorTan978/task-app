import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { AuthService } from '../../../core/auth/auth.service';
import { DeleteAccountDialog } from './delete-account-dialog';

const BOB = {
  id: 1,
  email: 'bob@example.com',
  displayName: 'Bob',
  emailVerified: true,
  hasPassword: true,
  role: 'USER',
  connections: [],
};
const URL = '/api/account/delete';

function problem(status: number, code: string, errors?: unknown[]) {
  return { type: 'about:blank', title: 'x', status, detail: 'x', instance: URL, code, errors };
}

describe('DeleteAccountDialog', () => {
  let fixture: ComponentFixture<DeleteAccountDialog>;
  let el: HTMLElement;
  let http: HttpTestingController;
  let auth: AuthService;
  let deleted: ReturnType<typeof vi.fn<() => void>>;
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
      imports: [DeleteAccountDialog],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    auth = TestBed.inject(AuthService);
    auth.loadMe().subscribe();
    http.expectOne('/api/auth/me').flush(BOB);
    fixture = TestBed.createComponent(DeleteAccountDialog);
    deleted = vi.fn<() => void>();
    closed = vi.fn<() => void>();
    fixture.componentInstance.deleted.subscribe(() => deleted());
    fixture.componentInstance.closed.subscribe(() => closed());
    el = fixture.nativeElement;
    await fixture.whenStable();
  });

  afterEach(() => http.verify());

  const password = () => el.querySelector<HTMLInputElement>('#delete-account-password')!;
  const confirmButton = () => el.querySelector<HTMLButtonElement>('button[type=submit]')!;
  const cancelButton = () =>
    [...el.querySelectorAll<HTMLButtonElement>('button')].find((b) =>
      b.textContent?.includes('Cancel'),
    )!;
  const fieldError = () => el.querySelector('#delete-account-password-error')?.textContent?.trim();
  const alert = () => el.querySelector('[role=alert]')?.textContent?.trim();

  async function confirm(value = 'secret12') {
    password().value = value;
    password().dispatchEvent(new Event('input'));
    el.querySelector('form')!.dispatchEvent(new Event('submit'));
    await fixture.whenStable();
  }

  it('opens as a modal and explains what will be deleted', () => {
    expect(showModal).toHaveBeenCalledTimes(1);
    const dialog = el.querySelector('dialog')!;
    expect(dialog.getAttribute('aria-labelledby')).toBe('delete-account-heading');
    expect(dialog.textContent).toContain('Delete your account?');
    expect(dialog.textContent).toContain(
      'Your account and all your tasks will be permanently deleted',
    );
    expect(password().type).toBe('password');
    expect(confirmButton().classList).toContain('btn--danger');
  });

  it('has a show/hide toggle on the password and no confirm field', async () => {
    expect(el.querySelectorAll('input').length).toBe(1);
    const toggle = el.querySelector<HTMLButtonElement>(
      'button[aria-controls=delete-account-password]',
    )!;
    expect(toggle.type).toBe('button');
    expect(toggle.getAttribute('aria-label')).toBe('Show password');
    toggle.click();
    await fixture.whenStable();
    expect(password().type).toBe('text');
    expect(toggle.getAttribute('aria-label')).toBe('Hide password');
    expect(toggle.getAttribute('aria-pressed')).toBe('true');
    expect(closed).not.toHaveBeenCalled();
    http.expectNone(URL);
  });

  it('Cancel closes without a request', () => {
    cancelButton().click();
    expect(closed).toHaveBeenCalledTimes(1);
    http.expectNone(URL);
  });

  it('Escape closes without a request', () => {
    const cancel = new Event('cancel', { cancelable: true });
    el.querySelector('dialog')!.dispatchEvent(cancel);
    expect(cancel.defaultPrevented).toBe(true);
    expect(closed).toHaveBeenCalledTimes(1);
  });

  it('requires the password', async () => {
    await confirm('   ');
    expect(fieldError()).toBe('Enter your password');
    http.expectNone(URL);
  });

  it('deletes: pending state blocks cancel, then emits deleted', async () => {
    await confirm(' secret12 ');
    const req = http.expectOne(URL);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ password: ' secret12 ' });
    expect(confirmButton().disabled).toBe(true);
    expect(confirmButton().getAttribute('aria-busy')).toBe('true');
    expect(el.querySelector('[data-testid=spinner]')).not.toBeNull();
    expect(cancelButton().disabled).toBe(true);

    const escape = new Event('cancel', { cancelable: true });
    el.querySelector('dialog')!.dispatchEvent(escape);
    expect(escape.defaultPrevented).toBe(true);
    expect(closed).not.toHaveBeenCalled();

    req.flush(null, { status: 204, statusText: 'No Content' });
    await fixture.whenStable();
    expect(deleted).toHaveBeenCalledTimes(1);
    expect(auth.isSignedIn()).toBe(false);
    expect(auth.accountDeleted()).toBe(true);
    http.expectNone('/api/auth/me');
  });

  it('shows "Password is incorrect" on WRONG_PASSWORD and keeps the session', async () => {
    await confirm('nope');
    http
      .expectOne(URL)
      .flush(
        problem(400, 'WRONG_PASSWORD', [
          { field: 'password', code: 'WRONG_PASSWORD', message: 'Password is incorrect' },
        ]),
        { status: 400, statusText: 'Bad Request' },
      );
    await fixture.whenStable();
    expect(fieldError()).toBe('Password is incorrect');
    expect(alert()).toBeUndefined();
    expect(deleted).not.toHaveBeenCalled();
    expect(auth.isSignedIn()).toBe(true);
    expect(confirmButton().disabled).toBe(false);
  });

  it('shows the account rate-limit text on 429', async () => {
    await confirm();
    http
      .expectOne(URL)
      .flush(
        {
          type: 'about:blank',
          title: 'Too Many Requests',
          status: 429,
          detail: 'x',
          code: 'RATE_LIMITED',
        },
        { status: 429, statusText: 'Too Many Requests', headers: { 'Retry-After': '600' } },
      );
    await fixture.whenStable();
    expect(alert()).toBe('Too many attempts, please try again later');
    expect(el.querySelector('.field-error')).toBeNull();
    expect(deleted).not.toHaveBeenCalled();
  });

  it.each([
    ['network error', 0, ''],
    ['server error', 500, 'INTERNAL_ERROR'],
    ['CSRF failure', 403, 'CSRF_INVALID'],
  ])('shows the generic banner on %s', async (_label, status, code) => {
    await confirm();
    const req = http.expectOne(URL);
    if (status === 0) {
      req.error(new ProgressEvent('error'));
    } else {
      req.flush(problem(status, code), { status, statusText: 'x' });
    }
    await fixture.whenStable();
    expect(alert()).toBe('Something went wrong, please try again');
    expect(auth.isSignedIn()).toBe(true);
  });
});

describe('DeleteAccountDialog (account without a password)', () => {
  const GOOGLE_ONLY = {
    id: 1,
    email: 'bob@gmail.com',
    displayName: 'Bob',
    emailVerified: true,
    hasPassword: false,
    role: 'USER',
    connections: [{ provider: 'google', email: 'bob@gmail.com' }],
  };
  let fixture: ComponentFixture<DeleteAccountDialog>;
  let el: HTMLElement;
  let http: HttpTestingController;
  let auth: AuthService;
  let deleted: ReturnType<typeof vi.fn<() => void>>;

  beforeAll(() => {
    HTMLDialogElement.prototype.showModal = function (this: HTMLDialogElement) {
      this.setAttribute('open', '');
    };
  });

  beforeEach(async () => {
    TestBed.configureTestingModule({
      imports: [DeleteAccountDialog],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    auth = TestBed.inject(AuthService);
    auth.loadMe().subscribe();
    http.expectOne('/api/auth/me').flush(GOOGLE_ONLY);
    fixture = TestBed.createComponent(DeleteAccountDialog);
    deleted = vi.fn<() => void>();
    fixture.componentInstance.deleted.subscribe(() => deleted());
    el = fixture.nativeElement;
    await fixture.whenStable();
  });

  afterEach(() => http.verify());

  const email = () => el.querySelector<HTMLInputElement>('#delete-account-email')!;
  const fieldError = () => el.querySelector('#delete-account-email-error')?.textContent?.trim();
  const alert = () => el.querySelector('[role=alert]')?.textContent?.trim();

  async function confirm(value = 'bob@gmail.com') {
    email().value = value;
    email().dispatchEvent(new Event('input'));
    el.querySelector('form')!.dispatchEvent(new Event('submit'));
    await fixture.whenStable();
  }

  it('asks for the email address instead of the password', () => {
    expect(el.querySelector('#delete-account-password')).toBeNull();
    expect(el.querySelectorAll('input').length).toBe(1);
    expect(email().type).toBe('email');
    expect(el.querySelector('label[for=delete-account-email]')?.textContent?.trim()).toBe(
      'Email address',
    );
    expect(el.querySelector('#delete-account-text')?.textContent).toContain(
      'Enter your email address to confirm.',
    );
    expect(el.querySelector('#delete-account-text')?.textContent).not.toContain('password');
  });

  it('requires the email', async () => {
    await confirm('   ');
    expect(fieldError()).toBe('Enter your email');
    http.expectNone(URL);
  });

  it('sends { email } (trimmed) and emits deleted on 204', async () => {
    await confirm('  Bob@Gmail.com ');
    const req = http.expectOne(URL);
    expect(req.request.body).toEqual({ email: 'Bob@Gmail.com' });
    req.flush(null, { status: 204, statusText: 'No Content' });
    await fixture.whenStable();
    expect(deleted).toHaveBeenCalledTimes(1);
    expect(auth.accountDeleted()).toBe(true);
  });

  it('shows "Email doesn\'t match" under the field on EMAIL_MISMATCH', async () => {
    await confirm('someone@else.com');
    http
      .expectOne(URL)
      .flush(
        problem(400, 'EMAIL_MISMATCH', [
          { field: 'email', code: 'EMAIL_MISMATCH', message: "Email doesn't match" },
        ]),
        { status: 400, statusText: 'Bad Request' },
      );
    await fixture.whenStable();
    expect(fieldError()).toBe("Email doesn't match");
    expect(email().getAttribute('aria-invalid')).toBe('true');
    expect(alert()).toBeUndefined();
    expect(deleted).not.toHaveBeenCalled();
    expect(auth.isSignedIn()).toBe(true);
  });

  it('shows "Enter your email" on a server REQUIRED error', async () => {
    await confirm();
    http
      .expectOne(URL)
      .flush(
        problem(400, 'VALIDATION_FAILED', [
          { field: 'email', code: 'REQUIRED', message: 'Email is required' },
        ]),
        { status: 400, statusText: 'Bad Request' },
      );
    await fixture.whenStable();
    expect(fieldError()).toBe('Enter your email');
  });

  it('shows the account rate-limit text on 429', async () => {
    await confirm();
    http
      .expectOne(URL)
      .flush(problem(429, 'RATE_LIMITED'), { status: 429, statusText: 'Too Many Requests' });
    await fixture.whenStable();
    expect(alert()).toBe('Too many attempts, please try again later');
    expect(fieldError()).toBeUndefined();
  });
});
