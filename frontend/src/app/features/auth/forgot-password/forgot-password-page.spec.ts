import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { ForgotPasswordPage } from './forgot-password-page';

const URL = '/api/auth/forgot-password';
const SENT = "If an account exists for that email, we've sent a link to reset your password.";

function problem(status: number, code: string, errors?: unknown[]) {
  return { type: 'about:blank', title: 'x', status, detail: 'x', instance: '/api', code, errors };
}

describe('ForgotPasswordPage', () => {
  let fixture: ComponentFixture<ForgotPasswordPage>;
  let el: HTMLElement;
  let http: HttpTestingController;

  beforeEach(async () => {
    TestBed.configureTestingModule({
      imports: [ForgotPasswordPage],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(ForgotPasswordPage);
    el = fixture.nativeElement;
    await fixture.whenStable();
  });

  afterEach(() => http.verify());

  const button = () => el.querySelector<HTMLButtonElement>('button[type=submit]');
  const text = () => el.textContent ?? '';

  async function submit(email = ' Bob@Example.com ') {
    const input = el.querySelector<HTMLInputElement>('#forgot-email')!;
    input.value = email;
    input.dispatchEvent(new Event('input'));
    el.querySelector('form')!.dispatchEvent(new Event('submit'));
    await fixture.whenStable();
  }

  it('shows the app logo above the heading', () => {
    expect(el.querySelector('app-auth-brand img[src="logo.svg"]')).not.toBeNull();
    expect(el.querySelector('h1')?.textContent).toContain('Forgot password');
  });

  it('renders the email field, "Send reset link" and a link to /login', () => {
    expect(el.querySelector('label[for=forgot-email]')?.textContent).toContain('Email');
    expect(button()?.textContent).toContain('Send reset link');
    expect(el.querySelector('a[href="/login"]')).not.toBeNull();
  });

  it.each([
    ['', 'Enter your email'],
    ['nope', 'Enter a valid email address'],
    [`${'a'.repeat(250)}@b.co`, 'Email must be at most 254 characters'],
  ])('validates %j in the browser and sends nothing', async (email, message) => {
    await submit(email);
    expect(el.querySelector('#forgot-email-error')?.textContent).toContain(message);
    http.expectNone(URL);
  });

  it('sends the trimmed email once with a spinner, then replaces the form with the message', async () => {
    await submit();
    const req = http.expectOne(URL);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ email: 'Bob@Example.com' });
    expect(button()!.disabled).toBe(true);
    expect(el.querySelector('[data-testid=spinner]')).not.toBeNull();

    el.querySelector('form')!.dispatchEvent(new Event('submit'));
    http.expectNone(URL);

    req.flush(null, { status: 202, statusText: 'Accepted' });
    await fixture.whenStable();
    expect(el.querySelector('[data-testid=reset-link-sent]')?.textContent?.trim()).toBe(SENT);
    expect(el.querySelector('form')).toBeNull();
  });

  it.each([
    ['REQUIRED', 'Enter your email'],
    ['INVALID_EMAIL', 'Enter a valid email address'],
    ['TOO_LONG', 'Email must be at most 254 characters'],
  ])('maps server %s to the email field', async (code, message) => {
    await submit();
    http
      .expectOne(URL)
      .flush(problem(400, 'VALIDATION_FAILED', [{ field: 'email', code, message: 'server' }]), {
        status: 400,
        statusText: 'Bad Request',
      });
    await fixture.whenStable();
    expect(el.querySelector('#forgot-email-error')?.textContent).toContain(message);
    expect(el.querySelector('form')).not.toBeNull();
  });

  it('shows "Too many attempts, please try again later" on 429', async () => {
    await submit();
    http.expectOne(URL).flush(problem(429, 'RATE_LIMITED'), {
      status: 429,
      statusText: 'Too Many Requests',
    });
    await fixture.whenStable();
    expect(el.querySelector('[role=alert]')?.textContent?.trim()).toBe(
      'Too many attempts, please try again later',
    );
    expect(button()!.disabled).toBe(false);
    expect(text()).not.toContain(SENT);
  });

  it.each([
    ['network error', 0],
    ['server error', 500],
    ['malformed request', 400],
  ])('shows the generic error on %s', async (_l, status) => {
    await submit();
    const req = http.expectOne(URL);
    if (status === 0) {
      req.error(new ProgressEvent('error'));
    } else {
      const code = status === 400 ? 'MALFORMED_REQUEST' : 'INTERNAL_ERROR';
      req.flush(problem(status, code), { status, statusText: 'x' });
    }
    await fixture.whenStable();
    expect(el.querySelector('[role=alert]')?.textContent?.trim()).toBe(
      'Something went wrong, please try again',
    );
  });
});
