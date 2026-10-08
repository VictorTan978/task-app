import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { AuthService } from '../../../core/auth/auth.service';
import { LoginPage } from './login-page';

const BOB = { id: 1, email: 'bob@example.com', displayName: 'Bob' };

function problem(status: number, code: string, errors?: unknown[]) {
  return { type: 'about:blank', title: 'x', status, detail: 'x', instance: '/api', code, errors };
}

describe('LoginPage', () => {
  let fixture: ComponentFixture<LoginPage>;
  let el: HTMLElement;
  let http: HttpTestingController;
  let navigate: ReturnType<typeof vi.spyOn>;

  beforeEach(async () => {
    TestBed.configureTestingModule({
      imports: [LoginPage],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    navigate = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
    fixture = TestBed.createComponent(LoginPage);
    el = fixture.nativeElement;
    await fixture.whenStable();
  });

  afterEach(() => http.verify());

  function type(selector: string, value: string) {
    const input = el.querySelector<HTMLInputElement>(selector)!;
    input.value = value;
    input.dispatchEvent(new Event('input'));
  }

  async function submit(email = ' Bob@Example.com ', password = 'secret12') {
    type('#login-email', email);
    type('#login-password', password);
    el.querySelector('form')!.dispatchEvent(new Event('submit'));
    await fixture.whenStable();
  }

  const button = () => el.querySelector<HTMLButtonElement>('button[type=submit]')!;
  const text = () => el.textContent ?? '';

  it('renders fields, button and link to /register', () => {
    expect(el.querySelector('label[for=login-email]')?.textContent).toContain('Email');
    expect(el.querySelector('label[for=login-password]')?.textContent).toContain('Password');
    expect(button().textContent).toContain('Sign in');
    expect(el.querySelector('a')?.getAttribute('href')).toBe('/register');
  });

  it('shows field errors and sends nothing when invalid', async () => {
    await submit('nope', '   ');
    expect(text()).toContain('Enter a valid email address');
    expect(text()).toContain('Enter your password');
    http.expectNone('/api/auth/login');
  });

  it('disables the button with a spinner while pending, then goes to /tasks', async () => {
    await submit();
    const req = http.expectOne('/api/auth/login');
    expect(req.request.body).toEqual({ email: 'Bob@Example.com', password: 'secret12' });
    expect(button().disabled).toBe(true);
    expect(el.querySelector('[data-testid=spinner]')).not.toBeNull();

    // No double submit while pending.
    el.querySelector('form')!.dispatchEvent(new Event('submit'));
    http.expectNone('/api/auth/login');

    req.flush(BOB);
    await fixture.whenStable();
    expect(button().disabled).toBe(false);
    expect(navigate).toHaveBeenCalledWith('/tasks');
  });

  it('shows "Invalid email or password" on 401 INVALID_CREDENTIALS', async () => {
    await submit();
    http
      .expectOne('/api/auth/login')
      .flush(problem(401, 'INVALID_CREDENTIALS'), { status: 401, statusText: 'Unauthorized' });
    await fixture.whenStable();
    expect(el.querySelector('[data-testid=form-error]')?.textContent).toContain(
      'Invalid email or password',
    );
    expect(navigate).not.toHaveBeenCalled();
  });

  it('maps server field errors to fields', async () => {
    await submit();
    http.expectOne('/api/auth/login').flush(
      problem(400, 'VALIDATION_FAILED', [
        { field: 'email', code: 'INVALID_EMAIL', message: 'server text' },
      ]),
      { status: 400, statusText: 'Bad Request' },
    );
    await fixture.whenStable();
    expect(el.querySelector('#login-email-error')?.textContent).toContain(
      'Enter a valid email address',
    );
  });

  it.each([
    ['network error', 0],
    ['server error', 500],
  ])('shows the generic banner on %s', async (_label, status) => {
    await submit();
    const req = http.expectOne('/api/auth/login');
    if (status === 0) {
      req.error(new ProgressEvent('error'));
    } else {
      req.flush(problem(500, 'INTERNAL_ERROR'), { status, statusText: 'x' });
    }
    await fixture.whenStable();
    expect(el.querySelector('[role=alert]')?.textContent).toContain(
      'Something went wrong, please try again',
    );
  });

  it('shows the generic banner on MALFORMED_REQUEST', async () => {
    await submit();
    http
      .expectOne('/api/auth/login')
      .flush(problem(400, 'MALFORMED_REQUEST'), { status: 400, statusText: 'Bad Request' });
    await fixture.whenStable();
    expect(text()).toContain('Something went wrong, please try again');
  });

  it('shows the session-expired message and clears it on submit', async () => {
    const auth = TestBed.inject(AuthService);
    auth.loadMe().subscribe();
    http.expectOne('/api/auth/me').flush(BOB);
    auth.handleSessionLost();
    await fixture.whenStable();
    expect(text()).toContain('Your session expired, please sign in again');

    await submit();
    expect(text()).not.toContain('Your session expired');
    http.expectOne('/api/auth/login').flush(BOB);
  });
});
