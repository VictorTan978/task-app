import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { AuthService } from '../../../core/auth/auth.service';
import { ChangePasswordForm } from './change-password-form';

const BOB = { id: 1, email: 'bob@example.com', displayName: 'Bob', emailVerified: true };
const URL = '/api/account/password';

function problem(status: number, code: string, errors?: unknown[]) {
  return { type: 'about:blank', title: 'x', status, detail: 'x', instance: URL, code, errors };
}

describe('ChangePasswordForm', () => {
  let fixture: ComponentFixture<ChangePasswordForm>;
  let el: HTMLElement;
  let http: HttpTestingController;
  let auth: AuthService;

  beforeEach(async () => {
    TestBed.configureTestingModule({
      imports: [ChangePasswordForm],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    auth = TestBed.inject(AuthService);
    auth.loadMe().subscribe();
    http.expectOne('/api/auth/me').flush(BOB);
    fixture = TestBed.createComponent(ChangePasswordForm);
    el = fixture.nativeElement;
    await fixture.whenStable();
  });

  afterEach(() => http.verify());

  const current = () => el.querySelector<HTMLInputElement>('#account-current-password')!;
  const next = () => el.querySelector<HTMLInputElement>('#account-new-password')!;
  const confirm = () => el.querySelector<HTMLInputElement>('#account-confirm-password')!;
  const toggleFor = (id: string) =>
    el.querySelector<HTMLButtonElement>(`button[aria-controls=${id}]`)!;
  const confirmError = () =>
    el.querySelector('#account-confirm-password-error')?.textContent?.trim();
  const button = () => el.querySelector<HTMLButtonElement>('button[type=submit]')!;
  const currentError = () =>
    el.querySelector('#account-current-password-error')?.textContent?.trim();
  const newError = () => el.querySelector('#account-new-password-error')?.textContent?.trim();
  const status = () => el.querySelector('[role=status]')?.textContent?.trim();
  const alert = () => el.querySelector('[role=alert]')?.textContent?.trim();

  const ids = () => [
    'account-current-password',
    'account-new-password',
    'account-confirm-password',
  ];

  function type(input: HTMLInputElement, value: string) {
    input.value = value;
    input.dispatchEvent(new Event('input'));
  }

  async function submit(
    currentPassword = 'old-pass',
    newPassword = 'new-pass-1',
    confirmPassword = newPassword,
  ) {
    type(current(), currentPassword);
    type(next(), newPassword);
    type(confirm(), confirmPassword);
    el.querySelector('form')!.dispatchEvent(new Event('submit'));
    await fixture.whenStable();
  }

  it('renders all three fields and the button', () => {
    expect(el.querySelector('label[for=account-current-password]')?.textContent).toContain(
      'Current password',
    );
    expect(el.querySelector('label[for=account-new-password]')?.textContent).toContain(
      'New password',
    );
    expect(current().type).toBe('password');
    expect(next().type).toBe('password');
    expect(el.querySelector('label[for=account-confirm-password]')?.textContent).toContain(
      'Confirm new password',
    );
    expect(confirm().type).toBe('password');
    const ids = [...el.querySelectorAll('input')].map((i) => i.id);
    expect(ids).toEqual([
      'account-current-password',
      'account-new-password',
      'account-confirm-password',
    ]);
    expect(button().textContent).toContain('Change password');
  });

  it('has an independent show/hide toggle on each password field', async () => {
    for (const id of ids()) {
      expect(toggleFor(id).type).toBe('button');
      expect(toggleFor(id).getAttribute('aria-label')).toBe('Show password');
    }
    toggleFor('account-new-password').click();
    await fixture.whenStable();
    expect(next().type).toBe('text');
    expect(current().type).toBe('password');
    expect(confirm().type).toBe('password');
    expect(toggleFor('account-new-password').getAttribute('aria-pressed')).toBe('true');
    http.expectNone(URL);
  });

  it('shows "Confirm your new password" when the confirm field is empty and sends nothing', async () => {
    await submit('old-pass', 'new-pass-1', '');
    expect(confirmError()).toBe('Confirm your new password');
    expect(confirm().getAttribute('aria-invalid')).toBe('true');
    expect(newError()).toBeUndefined();
    http.expectNone(URL);
  });

  it('shows "Passwords do not match" when the confirm value differs and sends nothing', async () => {
    await submit('old-pass', 'new-pass-1', 'new-pass-2');
    expect(confirmError()).toBe('Passwords do not match');
    expect(newError()).toBeUndefined();
    http.expectNone(URL);

    // Changing the new password to match clears the error.
    type(next(), 'new-pass-2');
    await fixture.whenStable();
    expect(confirmError()).toBeUndefined();
  });

  it('sends the passwords untrimmed, then shows "Password changed" and clears the fields', async () => {
    await submit(' old-pass ', ' new-pass-1 ');
    const req = http.expectOne(URL);
    expect(req.request.method).toBe('PUT');
    // Exactly the contract's ChangePasswordRequest: the confirm value is never sent.
    expect(req.request.body).toEqual({
      currentPassword: ' old-pass ',
      newPassword: ' new-pass-1 ',
    });
    expect(Object.keys(req.request.body).sort()).toEqual(['currentPassword', 'newPassword']);
    expect(button().disabled).toBe(true);
    expect(button().getAttribute('aria-busy')).toBe('true');
    expect(el.querySelector('[data-testid=spinner]')).not.toBeNull();

    req.flush(null, { status: 204, statusText: 'No Content' });
    await fixture.whenStable();
    expect(status()).toBe('Password changed');
    expect(current().value).toBe('');
    expect(next().value).toBe('');
    expect(confirm().value).toBe('');
    expect(el.querySelector('.field-error')).toBeNull();
    expect(button().disabled).toBe(false);
    expect(auth.isSignedIn()).toBe(true);
  });

  it('hides shown passwords again after "Password changed"', async () => {
    for (const id of ids()) {
      toggleFor(id).click();
    }
    await fixture.whenStable();
    expect(current().type).toBe('text');
    await submit();
    http.expectOne(URL).flush(null, { status: 204, statusText: 'No Content' });
    await fixture.whenStable();
    expect(status()).toBe('Password changed');
    for (const id of ids()) {
      expect(el.querySelector<HTMLInputElement>(`#${id}`)!.type).toBe('password');
      expect(toggleFor(id).getAttribute('aria-label')).toBe('Show password');
      expect(toggleFor(id).getAttribute('aria-pressed')).toBe('false');
    }
  });

  it('requires both fields', async () => {
    await submit('', '   ');
    expect(currentError()).toBe('Enter your current password');
    expect(newError()).toBe('Enter a new password');
    http.expectNone(URL);
  });

  it.each([
    ['shorter than 8', '1234567'],
    ['longer than 72', 'a'.repeat(73)],
    ['over 72 UTF-8 bytes', 'é'.repeat(37)],
  ])('rejects a new password %s', async (_label, value) => {
    await submit('old-pass', value);
    expect(newError()).toBe('Password must be 8 to 72 characters');
    expect(next().getAttribute('aria-invalid')).toBe('true');
    http.expectNone(URL);
  });

  it('shows "Current password is incorrect" under the current password on WRONG_PASSWORD', async () => {
    await submit();
    http
      .expectOne(URL)
      .flush(
        problem(400, 'WRONG_PASSWORD', [
          {
            field: 'currentPassword',
            code: 'WRONG_PASSWORD',
            message: 'Current password is incorrect',
          },
        ]),
        { status: 400, statusText: 'Bad Request' },
      );
    await fixture.whenStable();
    expect(currentError()).toBe('Current password is incorrect');
    expect(alert()).toBeUndefined();
    expect(status()).toBe('');
    expect(auth.isSignedIn()).toBe(true);

    // Typing clears the server error.
    type(current(), 'other');
    await fixture.whenStable();
    expect(currentError()).toBeUndefined();
  });

  it('maps server PASSWORD_LENGTH to the new password field', async () => {
    await submit();
    http
      .expectOne(URL)
      .flush(
        problem(400, 'VALIDATION_FAILED', [
          { field: 'newPassword', code: 'PASSWORD_LENGTH', message: 'server text' },
        ]),
        { status: 400, statusText: 'Bad Request' },
      );
    await fixture.whenStable();
    expect(newError()).toBe('Password must be 8 to 72 characters');
  });

  it('shows the account rate-limit text on 429 with no field errors', async () => {
    await submit();
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
    expect(button().disabled).toBe(false);
  });

  it.each([
    ['network error', 0, ''],
    ['server error', 500, 'INTERNAL_ERROR'],
    ['CSRF failure', 403, 'CSRF_INVALID'],
  ])('shows the generic banner on %s', async (_label, status, code) => {
    await submit();
    const req = http.expectOne(URL);
    if (status === 0) {
      req.error(new ProgressEvent('error'));
    } else {
      req.flush(problem(status, code), { status, statusText: 'x' });
    }
    await fixture.whenStable();
    expect(alert()).toBe('Something went wrong, please try again');
  });
});
