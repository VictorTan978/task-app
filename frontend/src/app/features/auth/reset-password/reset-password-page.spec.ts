import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter, Router } from '@angular/router';
import { AuthService } from '../../../core/auth/auth.service';
import { ResetPasswordPage } from './reset-password-page';

const CHECK = '/api/auth/check-reset-token';
const RESET = '/api/auth/reset-password';
const BOB = {
  id: 1,
  email: 'bob@example.com',
  displayName: 'Bob',
  emailVerified: true,
  hasPassword: true,
  role: 'USER',
  connections: [],
};

function problem(status: number, code: string, errors?: unknown[]) {
  return { type: 'about:blank', title: 'x', status, detail: 'x', instance: '/api', code, errors };
}

describe('ResetPasswordPage', () => {
  let fixture: ComponentFixture<ResetPasswordPage>;
  let el: HTMLElement;
  let http: HttpTestingController;
  let auth: AuthService;
  let navigate: ReturnType<typeof vi.spyOn>;
  let navigateByUrl: ReturnType<typeof vi.spyOn>;

  async function setup(query: Record<string, string> = { token: 'tok-r' }) {
    TestBed.configureTestingModule({
      imports: [ResetPasswordPage],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { queryParamMap: convertToParamMap(query) } },
        },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    auth = TestBed.inject(AuthService);
    const router = TestBed.inject(Router);
    navigate = vi.spyOn(router, 'navigate').mockResolvedValue(true);
    navigateByUrl = vi.spyOn(router, 'navigateByUrl').mockResolvedValue(true);
    fixture = TestBed.createComponent(ResetPasswordPage);
    el = fixture.nativeElement;
    await fixture.whenStable();
  }

  /** Opens the page with a valid link (check 204). */
  async function openValid() {
    await setup();
    http.expectOne(CHECK).flush(null, { status: 204, statusText: 'No Content' });
    await fixture.whenStable();
  }

  afterEach(() => http.verify());

  const text = () => el.textContent ?? '';
  const submitButton = () => el.querySelector<HTMLButtonElement>('button[type=submit]');

  function type(selector: string, value: string) {
    const input = el.querySelector<HTMLInputElement>(selector)!;
    input.value = value;
    input.dispatchEvent(new Event('input'));
  }

  async function submit(password = 'new-secret-1', confirm = password) {
    type('#reset-new-password', password);
    type('#reset-confirm-password', confirm);
    el.querySelector('form')!.dispatchEvent(new Event('submit'));
    await fixture.whenStable();
  }

  it('shows "Checking your link…", checks the token once and removes it from the URL', async () => {
    await setup();
    expect(el.querySelector('[data-testid=link-checking]')?.textContent).toContain(
      'Checking your link…',
    );
    expect(el.querySelector('[data-testid=spinner]')).not.toBeNull();
    expect(el.querySelector('form')).toBeNull();
    const req = http.expectOne(CHECK);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ token: 'tok-r' });
    expect(navigate).toHaveBeenCalledWith(
      [],
      expect.objectContaining({ queryParams: { token: null }, replaceUrl: true }),
    );
    fixture.detectChanges();
    await fixture.whenStable();
    http.expectNone(CHECK);
    req.flush(null, { status: 204, statusText: 'No Content' });
  });

  it('without a token shows the invalid state with a link to /forgot-password, calling nothing', async () => {
    await setup({});
    http.expectNone(CHECK);
    expect(el.querySelector('[data-testid=link-invalid]')?.textContent?.trim()).toBe(
      'This link is invalid or has expired',
    );
    expect(el.querySelector('a[href="/forgot-password"]')).not.toBeNull();
  });

  it('check 400 TOKEN_INVALID shows the invalid state', async () => {
    await setup();
    http.expectOne(CHECK).flush(problem(400, 'TOKEN_INVALID'), { status: 400, statusText: 'x' });
    await fixture.whenStable();
    expect(text()).toContain('This link is invalid or has expired');
    expect(el.querySelector('a[href="/forgot-password"]')).not.toBeNull();
    expect(el.querySelector('form')).toBeNull();
  });

  it.each([
    ['429', 429, 'Too many attempts, please try again later'],
    ['network error', 0, 'Something went wrong, please try again'],
    ['server error', 500, 'Something went wrong, please try again'],
  ])('check %s shows an error with "Try again"', async (_l, status, message) => {
    await setup();
    const req = http.expectOne(CHECK);
    if (status === 0) {
      req.error(new ProgressEvent('error'));
    } else {
      const code = status === 429 ? 'RATE_LIMITED' : 'INTERNAL_ERROR';
      req.flush(problem(status, code), { status, statusText: 'x' });
    }
    await fixture.whenStable();
    expect(el.querySelector('[data-testid=link-error]')?.textContent?.trim()).toBe(message);

    el.querySelector<HTMLButtonElement>('button')!.click();
    await fixture.whenStable();
    const retry = http.expectOne(CHECK);
    expect(retry.request.body).toEqual({ token: 'tok-r' });
    retry.flush(null, { status: 204, statusText: 'No Content' });
    await fixture.whenStable();
    expect(el.querySelector('form')).not.toBeNull();
  });

  it('valid link shows "New password" and "Confirm new password" with show/hide', async () => {
    await openValid();
    expect(el.querySelector('label[for=reset-new-password]')?.textContent).toContain(
      'New password',
    );
    expect(el.querySelector('label[for=reset-confirm-password]')?.textContent).toContain(
      'Confirm new password',
    );
    expect(el.querySelector('button[aria-controls=reset-new-password]')).not.toBeNull();
    expect(el.querySelector('button[aria-controls=reset-confirm-password]')).not.toBeNull();
    expect(text()).toContain('8 to 72 characters');
  });

  it.each([
    ['', '', 'Enter a new password'],
    ['short', 'short', 'Password must be 8 to 72 characters'],
    ['x'.repeat(73), 'x'.repeat(73), 'Password must be 8 to 72 characters'],
  ])('validates the new password %j in the browser', async (password, confirm, message) => {
    await openValid();
    await submit(password, confirm);
    expect(el.querySelector('#reset-new-password-error')?.textContent).toContain(message);
    http.expectNone(RESET);
  });

  it('requires a matching confirmation', async () => {
    await openValid();
    await submit('new-secret-1', '');
    expect(el.querySelector('#reset-confirm-password-error')?.textContent).toContain(
      'Confirm your new password',
    );
    await submit('new-secret-1', 'new-secret-2');
    expect(el.querySelector('#reset-confirm-password-error')?.textContent).toContain(
      'Passwords do not match',
    );
    http.expectNone(RESET);
  });

  it('submits once with a spinner, never sends the confirmation, then goes to /login flagged', async () => {
    await openValid();
    await submit();
    const req = http.expectOne(RESET);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ token: 'tok-r', newPassword: 'new-secret-1' });
    expect(submitButton()!.disabled).toBe(true);
    expect(el.querySelector('[data-testid=spinner]')).not.toBeNull();

    el.querySelector('form')!.dispatchEvent(new Event('submit'));
    http.expectNone(RESET);

    req.flush(null, { status: 204, statusText: 'No Content' });
    await fixture.whenStable();
    expect(auth.passwordReset()).toBe(true);
    expect(navigateByUrl).toHaveBeenCalledWith('/login');
    http.expectNone('/api/auth/me');
  });

  it('a signed-in user is signed out locally after the reset', async () => {
    await openValid();
    auth.loadMe().subscribe();
    http.expectOne('/api/auth/me').flush(BOB);
    await submit();
    http.expectOne(RESET).flush(null, { status: 204, statusText: 'No Content' });
    await fixture.whenStable();
    expect(auth.isSignedIn()).toBe(false);
  });

  it('submit 400 TOKEN_INVALID shows the invalid state', async () => {
    await openValid();
    await submit();
    http.expectOne(RESET).flush(problem(400, 'TOKEN_INVALID'), { status: 400, statusText: 'x' });
    await fixture.whenStable();
    expect(el.querySelector('[data-testid=link-invalid]')).not.toBeNull();
    expect(el.querySelector('form')).toBeNull();
    expect(navigateByUrl).not.toHaveBeenCalled();
  });

  it('submit 400 VALIDATION_FAILED shows the error on the new password', async () => {
    await openValid();
    await submit();
    http
      .expectOne(RESET)
      .flush(
        problem(400, 'VALIDATION_FAILED', [
          { field: 'newPassword', code: 'PASSWORD_LENGTH', message: 'server' },
        ]),
        { status: 400, statusText: 'x' },
      );
    await fixture.whenStable();
    expect(el.querySelector('#reset-new-password-error')?.textContent).toContain(
      'Password must be 8 to 72 characters',
    );
  });

  it.each([
    ['429', 429, 'Too many attempts, please try again later'],
    ['network error', 0, 'Something went wrong, please try again'],
    ['server error', 500, 'Something went wrong, please try again'],
  ])('submit %s shows a banner and keeps the form', async (_l, status, message) => {
    await openValid();
    await submit();
    const req = http.expectOne(RESET);
    if (status === 0) {
      req.error(new ProgressEvent('error'));
    } else {
      const code = status === 429 ? 'RATE_LIMITED' : 'INTERNAL_ERROR';
      req.flush(problem(status, code), { status, statusText: 'x' });
    }
    await fixture.whenStable();
    expect(el.querySelector('[role=alert]')?.textContent?.trim()).toBe(message);
    expect(el.querySelector('form')).not.toBeNull();
    expect(submitButton()!.disabled).toBe(false);
    expect(auth.passwordReset()).toBe(false);
  });
});
