import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { RegisterPage } from './register-page';

const BOB = { id: 1, email: 'bob@example.com', displayName: 'Bob' };

function problem(status: number, code: string, errors?: unknown[]) {
  return { type: 'about:blank', title: 'x', status, detail: 'x', instance: '/api', code, errors };
}

describe('RegisterPage', () => {
  let fixture: ComponentFixture<RegisterPage>;
  let el: HTMLElement;
  let http: HttpTestingController;
  let navigate: ReturnType<typeof vi.spyOn>;

  beforeEach(async () => {
    TestBed.configureTestingModule({
      imports: [RegisterPage],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    navigate = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
    fixture = TestBed.createComponent(RegisterPage);
    el = fixture.nativeElement;
    await fixture.whenStable();
  });

  afterEach(() => http.verify());

  function type(selector: string, value: string) {
    const input = el.querySelector<HTMLInputElement>(selector)!;
    input.value = value;
    input.dispatchEvent(new Event('input'));
  }

  async function submit(displayName = ' Bob ', email = 'Bob@Example.com', password = 'secret12') {
    type('#register-display-name', displayName);
    type('#register-email', email);
    type('#register-password', password);
    el.querySelector('form')!.dispatchEvent(new Event('submit'));
    await fixture.whenStable();
  }

  const button = () => el.querySelector<HTMLButtonElement>('button[type=submit]')!;
  const errorOf = (id: string) => el.querySelector(`#${id}-error`)?.textContent?.trim();

  it('renders fields, button and link to /login', () => {
    expect(el.querySelector('label[for=register-display-name]')).not.toBeNull();
    expect(el.querySelector('label[for=register-email]')).not.toBeNull();
    expect(el.querySelector('label[for=register-password]')).not.toBeNull();
    expect(button().textContent).toContain('Create account');
    expect(el.querySelector('a')?.getAttribute('href')).toBe('/login');
  });

  it('shows an error under each invalid field and sends nothing', async () => {
    await submit('   ', 'bad', '1234567');
    expect(errorOf('register-display-name')).toBe('Enter your display name');
    expect(errorOf('register-email')).toBe('Enter a valid email address');
    expect(errorOf('register-password')).toBe('Password must be 8 to 72 characters');
    http.expectNone('/api/auth/register');
  });

  it('rejects too-long values', async () => {
    await submit('x'.repeat(51), `${'a'.repeat(64)}@${`${'b'.repeat(60)}.`.repeat(4)}com`, 'a'.repeat(73));
    expect(errorOf('register-display-name')).toBe('Display name must be at most 50 characters');
    expect(errorOf('register-email')).toBe('Email must be at most 254 characters');
    expect(errorOf('register-password')).toBe('Password must be 8 to 72 characters');
    http.expectNone('/api/auth/register');
  });

  it('sends trimmed values, shows pending state, then goes to /tasks', async () => {
    await submit();
    const req = http.expectOne('/api/auth/register');
    expect(req.request.body).toEqual({
      displayName: 'Bob',
      email: 'Bob@Example.com',
      password: 'secret12',
    });
    expect(button().disabled).toBe(true);
    expect(el.querySelector('[data-testid=spinner]')).not.toBeNull();
    req.flush(BOB, { status: 201, statusText: 'Created' });
    await fixture.whenStable();
    expect(button().disabled).toBe(false);
    expect(navigate).toHaveBeenCalledWith('/tasks');
  });

  it('shows the exact duplicate-email message under email on 409 EMAIL_TAKEN', async () => {
    await submit();
    http.expectOne('/api/auth/register').flush(
      problem(409, 'EMAIL_TAKEN', [{ field: 'email', code: 'EMAIL_TAKEN', message: 'srv' }]),
      { status: 409, statusText: 'Conflict' },
    );
    await fixture.whenStable();
    expect(errorOf('register-email')).toBe('An account with this email already exists');
    expect(navigate).not.toHaveBeenCalled();

    // Editing the email clears the server error.
    type('#register-email', 'other@example.com');
    await fixture.whenStable();
    expect(errorOf('register-email')).toBeUndefined();
  });

  it('shows the display-name-taken message under display name on 409 DISPLAY_NAME_TAKEN', async () => {
    await submit();
    http.expectOne('/api/auth/register').flush(
      problem(409, 'DISPLAY_NAME_TAKEN', [
        { field: 'displayName', code: 'DISPLAY_NAME_TAKEN', message: 'srv' },
      ]),
      { status: 409, statusText: 'Conflict' },
    );
    await fixture.whenStable();
    expect(errorOf('register-display-name')).toBe('This display name is already taken');
    expect(errorOf('register-email')).toBeUndefined();
    expect(el.querySelector('[role=alert]')).toBeNull();
    expect(navigate).not.toHaveBeenCalled();

    // Editing the display name clears the server error.
    type('#register-display-name', 'Bobby');
    await fixture.whenStable();
    expect(errorOf('register-display-name')).toBeUndefined();
  });

  it('shows both field errors when email and display name are taken', async () => {
    await submit();
    http.expectOne('/api/auth/register').flush(
      problem(409, 'EMAIL_TAKEN', [
        { field: 'email', code: 'EMAIL_TAKEN', message: 'srv' },
        { field: 'displayName', code: 'DISPLAY_NAME_TAKEN', message: 'srv' },
      ]),
      { status: 409, statusText: 'Conflict' },
    );
    await fixture.whenStable();
    expect(errorOf('register-email')).toBe('An account with this email already exists');
    expect(errorOf('register-display-name')).toBe('This display name is already taken');
    expect(el.querySelector('[role=alert]')).toBeNull();

    // Editing one field clears only that field's server error.
    type('#register-email', 'other@example.com');
    await fixture.whenStable();
    expect(errorOf('register-email')).toBeUndefined();
    expect(errorOf('register-display-name')).toBe('This display name is already taken');
  });

  it('shows the email message on 409 EMAIL_TAKEN without errors[]', async () => {
    await submit();
    http.expectOne('/api/auth/register').flush(problem(409, 'EMAIL_TAKEN'), {
      status: 409,
      statusText: 'Conflict',
    });
    await fixture.whenStable();
    expect(errorOf('register-email')).toBe('An account with this email already exists');
    expect(el.querySelector('[role=alert]')).toBeNull();
  });

  it('shows the display name message on 409 DISPLAY_NAME_TAKEN without errors[]', async () => {
    await submit();
    http.expectOne('/api/auth/register').flush(problem(409, 'DISPLAY_NAME_TAKEN', []), {
      status: 409,
      statusText: 'Conflict',
    });
    await fixture.whenStable();
    expect(errorOf('register-display-name')).toBe('This display name is already taken');
    expect(el.querySelector('[role=alert]')).toBeNull();
  });

  it('maps 400 VALIDATION_FAILED field errors with own text', async () => {
    await submit();
    http.expectOne('/api/auth/register').flush(
      problem(400, 'VALIDATION_FAILED', [
        { field: 'displayName', code: 'TOO_LONG', message: 'srv' },
        { field: 'password', code: 'PASSWORD_LENGTH', message: 'srv' },
      ]),
      { status: 400, statusText: 'Bad Request' },
    );
    await fixture.whenStable();
    expect(errorOf('register-display-name')).toBe('Display name must be at most 50 characters');
    expect(errorOf('register-password')).toBe('Password must be 8 to 72 characters');
  });

  it('shows the generic banner when the server is unreachable', async () => {
    await submit();
    http.expectOne('/api/auth/register').error(new ProgressEvent('error'));
    await fixture.whenStable();
    expect(el.querySelector('[role=alert]')?.textContent).toContain(
      'Something went wrong, please try again',
    );
    expect(button().disabled).toBe(false);
  });
});
