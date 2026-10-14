import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter, Router } from '@angular/router';
import { User } from '../../../core/auth/auth.models';
import { AuthService } from '../../../core/auth/auth.service';
import { VerifyEmailPage } from './verify-email-page';

const BOB: User = { id: 1, email: 'bob@example.com', displayName: 'Bob', emailVerified: false };
const URL = '/api/auth/verify-email';

function problem(status: number, code: string) {
  return { type: 'about:blank', title: 'x', status, detail: 'x', instance: '/api', code };
}

describe('VerifyEmailPage', () => {
  let fixture: ComponentFixture<VerifyEmailPage>;
  let el: HTMLElement;
  let http: HttpTestingController;
  let auth: AuthService;
  let navigate: ReturnType<typeof vi.spyOn>;
  let navigateByUrl: ReturnType<typeof vi.spyOn>;

  function setup(query: Record<string, string>, user: User | null = null) {
    TestBed.configureTestingModule({
      imports: [VerifyEmailPage],
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
    if (user) {
      auth.loadMe().subscribe();
      http.expectOne('/api/auth/me').flush(user);
    }
  }

  async function render() {
    fixture = TestBed.createComponent(VerifyEmailPage);
    el = fixture.nativeElement;
    await fixture.whenStable();
  }

  afterEach(() => http.verify());

  const text = () => el.textContent ?? '';
  const button = (label: string) =>
    Array.from(el.querySelectorAll<HTMLButtonElement>('button')).find((b) =>
      b.textContent?.includes(label),
    );

  it('shows a spinner while checking, POSTs the token once and removes it from the URL', async () => {
    setup({ token: 'tok-abc' });
    await render();
    expect(el.querySelector('[data-testid=link-checking]')?.textContent).toContain(
      'Confirming your email',
    );
    expect(el.querySelector('[data-testid=spinner]')).not.toBeNull();
    const req = http.expectOne(URL);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ token: 'tok-abc' });
    expect(navigate).toHaveBeenCalledWith(
      [],
      expect.objectContaining({ queryParams: { token: null }, replaceUrl: true }),
    );

    // Re-rendering does not send it again.
    fixture.detectChanges();
    await fixture.whenStable();
    http.expectNone(URL);
    req.flush(null, { status: 204, statusText: 'No Content' });
  });

  it('signed out: success shows "Your email is confirmed" and Continue goes to /login', async () => {
    setup({ token: 't' });
    await render();
    http.expectOne(URL).flush(null, { status: 204, statusText: 'No Content' });
    await fixture.whenStable();
    expect(el.querySelector('[data-testid=email-confirmed]')?.textContent?.trim()).toBe(
      'Your email is confirmed',
    );
    button('Continue')!.click();
    await fixture.whenStable();
    expect(navigateByUrl).toHaveBeenCalledWith('/login');
    http.expectNone('/api/auth/me');
  });

  it('signed in: Continue refetches the user (with a spinner), then goes to /tasks', async () => {
    setup({ token: 't' }, BOB);
    await render();
    http.expectOne(URL).flush(null, { status: 204, statusText: 'No Content' });
    await fixture.whenStable();
    button('Continue')!.click();
    await fixture.whenStable();
    expect(button('Continue')!.disabled).toBe(true);
    expect(el.querySelector('[data-testid=spinner]')).not.toBeNull();
    button('Continue')!.click();
    http.expectOne('/api/auth/me').flush({ ...BOB, emailVerified: true });
    await fixture.whenStable();
    expect(auth.currentUser()?.emailVerified).toBe(true);
    expect(navigateByUrl).toHaveBeenCalledWith('/tasks');
  });

  it('without a token shows the invalid message and calls nothing', async () => {
    setup({});
    await render();
    http.expectNone(URL);
    expect(el.querySelector('[data-testid=link-invalid]')?.textContent?.trim()).toBe(
      'This link is invalid or has expired',
    );
    expect(navigate).not.toHaveBeenCalled();
  });

  it('with an empty token shows the invalid message and calls nothing', async () => {
    setup({ token: '' });
    await render();
    http.expectNone(URL);
    expect(text()).toContain('This link is invalid or has expired');
  });

  it('signed out: 400 TOKEN_INVALID shows the invalid message without "Resend email"', async () => {
    setup({ token: 't' });
    await render();
    http.expectOne(URL).flush(problem(400, 'TOKEN_INVALID'), { status: 400, statusText: 'x' });
    await fixture.whenStable();
    expect(text()).toContain('This link is invalid or has expired');
    expect(el.querySelector('#resend-verification')).toBeNull();
  });

  it('signed in and unconfirmed: the invalid state offers "Resend email"', async () => {
    setup({ token: 't' }, BOB);
    await render();
    http.expectOne(URL).flush(problem(400, 'TOKEN_INVALID'), { status: 400, statusText: 'x' });
    await fixture.whenStable();
    el.querySelector<HTMLButtonElement>('#resend-verification')!.click();
    http
      .expectOne('/api/account/resend-verification')
      .flush(null, { status: 204, statusText: 'No Content' });
    await fixture.whenStable();
    expect(text()).toContain('Email sent');
  });

  it('signed in and confirmed: the invalid state has no "Resend email"', async () => {
    setup({ token: 't' }, { ...BOB, emailVerified: true });
    await render();
    http.expectOne(URL).flush(problem(400, 'TOKEN_INVALID'), { status: 400, statusText: 'x' });
    await fixture.whenStable();
    expect(el.querySelector('#resend-verification')).toBeNull();
  });

  it('shows "Too many attempts, please try again later" on 429', async () => {
    setup({ token: 't' });
    await render();
    http.expectOne(URL).flush(problem(429, 'RATE_LIMITED'), { status: 429, statusText: 'x' });
    await fixture.whenStable();
    expect(el.querySelector('[data-testid=link-error]')?.textContent?.trim()).toBe(
      'Too many attempts, please try again later',
    );
  });

  it.each([
    ['network error', 0],
    ['server error', 500],
  ])('shows the generic error on %s and retries with the same token', async (_l, status) => {
    setup({ token: 'tok-retry' });
    await render();
    const req = http.expectOne(URL);
    if (status === 0) {
      req.error(new ProgressEvent('error'));
    } else {
      req.flush(problem(500, 'INTERNAL_ERROR'), { status, statusText: 'x' });
    }
    await fixture.whenStable();
    expect(el.querySelector('[data-testid=link-error]')?.textContent?.trim()).toBe(
      'Something went wrong, please try again',
    );

    button('Try again')!.click();
    await fixture.whenStable();
    expect(el.querySelector('[data-testid=link-checking]')).not.toBeNull();
    const retry = http.expectOne(URL);
    expect(retry.request.body).toEqual({ token: 'tok-retry' });
    retry.flush(null, { status: 204, statusText: 'No Content' });
    await fixture.whenStable();
    expect(text()).toContain('Your email is confirmed');
  });
});
