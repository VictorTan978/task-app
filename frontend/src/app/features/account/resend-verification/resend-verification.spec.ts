import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { authErrorInterceptor } from '../../../core/auth/auth-error.interceptor';
import { AuthService } from '../../../core/auth/auth.service';
import { ResendVerification } from './resend-verification';

const BOB = { id: 1, email: 'bob@example.com', displayName: 'Bob', emailVerified: false };
const URL = '/api/account/resend-verification';

function problem(status: number, code: string) {
  return { type: 'about:blank', title: 'x', status, detail: 'x', instance: '/api', code };
}

describe('ResendVerification', () => {
  let fixture: ComponentFixture<ResendVerification>;
  let el: HTMLElement;
  let http: HttpTestingController;
  let auth: AuthService;
  let navigate: ReturnType<typeof vi.spyOn>;

  beforeEach(async () => {
    TestBed.configureTestingModule({
      imports: [ResendVerification],
      providers: [
        provideRouter([]),
        provideHttpClient(withInterceptors([authErrorInterceptor])),
        provideHttpClientTesting(),
      ],
    });
    http = TestBed.inject(HttpTestingController);
    auth = TestBed.inject(AuthService);
    navigate = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
    auth.loadMe().subscribe();
    http.expectOne('/api/auth/me').flush(BOB);
    fixture = TestBed.createComponent(ResendVerification);
    el = fixture.nativeElement;
    await fixture.whenStable();
  });

  afterEach(() => http.verify());

  const button = () => el.querySelector<HTMLButtonElement>('#resend-verification')!;
  const status = () => el.querySelector('[data-testid=resend-status]')?.textContent?.trim();
  const alert = () => el.querySelector('[role=alert]')?.textContent?.trim();

  async function click() {
    button().click();
    await fixture.whenStable();
  }

  it('renders the "Resend email" button', () => {
    expect(button().textContent?.trim()).toBe('Resend email');
    expect(button().type).toBe('button');
    expect(button().disabled).toBe(false);
  });

  it('sends once with a spinner while pending, then shows "Email sent"', async () => {
    await click();
    const req = http.expectOne(URL);
    expect(req.request.method).toBe('POST');
    expect(button().disabled).toBe(true);
    expect(button().getAttribute('aria-busy')).toBe('true');
    expect(el.querySelector('[data-testid=spinner]')).not.toBeNull();

    button().dispatchEvent(new Event('click'));
    http.expectNone(URL);

    req.flush(null, { status: 204, statusText: 'No Content' });
    await fixture.whenStable();
    expect(status()).toBe('Email sent');
    expect(button().disabled).toBe(false);
    expect(el.querySelector('[data-testid=spinner]')).toBeNull();
  });

  it('on 409 ALREADY_VERIFIED refetches the user (stays disabled until done)', async () => {
    await click();
    http.expectOne(URL).flush(problem(409, 'ALREADY_VERIFIED'), { status: 409, statusText: 'x' });
    await fixture.whenStable();
    expect(button().disabled).toBe(true);
    http.expectOne('/api/auth/me').flush({ ...BOB, emailVerified: true });
    await fixture.whenStable();
    expect(auth.currentUser()?.emailVerified).toBe(true);
    expect(button().disabled).toBe(false);
    expect(alert()).toBeUndefined();
    expect(status()).toBe('');
  });

  it('shows "Too many emails, please try again later" on 429', async () => {
    await click();
    http.expectOne(URL).flush(problem(429, 'RATE_LIMITED'), {
      status: 429,
      statusText: 'Too Many Requests',
      headers: { 'Retry-After': '600' },
    });
    await fixture.whenStable();
    expect(alert()).toBe('Too many emails, please try again later');
    expect(button().disabled).toBe(false);

    // A new attempt clears the message.
    await click();
    expect(alert()).toBeUndefined();
    http.expectOne(URL).flush(null, { status: 204, statusText: 'No Content' });
  });

  it.each([
    ['network error', 0],
    ['server error', 500],
    ['CSRF error', 403],
  ])('shows the generic message on %s', async (_label, statusCode) => {
    await click();
    const req = http.expectOne(URL);
    if (statusCode === 0) {
      req.error(new ProgressEvent('error'));
    } else {
      const code = statusCode === 403 ? 'CSRF_INVALID' : 'INTERNAL_ERROR';
      req.flush(problem(statusCode, code), { status: statusCode, statusText: 'x' });
    }
    await fixture.whenStable();
    expect(alert()).toBe('Something went wrong, please try again');
  });

  it('on 401 leaves it to the interceptor (signed out, /login) and shows no message', async () => {
    await click();
    http.expectOne(URL).flush(problem(401, 'UNAUTHENTICATED'), { status: 401, statusText: 'x' });
    await fixture.whenStable();
    expect(auth.isSignedIn()).toBe(false);
    expect(navigate).toHaveBeenCalledWith('/login');
    expect(alert()).toBeUndefined();
  });
});
