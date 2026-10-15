import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { AuthService } from '../../../core/auth/auth.service';
import { DisplayNameForm } from './display-name-form';

const BOB = {
  id: 1,
  email: 'bob@example.com',
  displayName: 'Bob',
  emailVerified: true,
  hasPassword: true,
  role: 'USER',
  connections: [],
};
const URL = '/api/account/display-name';

function problem(status: number, code: string, errors?: unknown[]) {
  return { type: 'about:blank', title: 'x', status, detail: 'x', instance: URL, code, errors };
}

describe('DisplayNameForm', () => {
  let fixture: ComponentFixture<DisplayNameForm>;
  let el: HTMLElement;
  let http: HttpTestingController;
  let auth: AuthService;

  beforeEach(async () => {
    TestBed.configureTestingModule({
      imports: [DisplayNameForm],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    auth = TestBed.inject(AuthService);
    auth.loadMe().subscribe();
    http.expectOne('/api/auth/me').flush(BOB);
    fixture = TestBed.createComponent(DisplayNameForm);
    el = fixture.nativeElement;
    await fixture.whenStable();
  });

  afterEach(() => http.verify());

  const input = () => el.querySelector<HTMLInputElement>('#account-display-name')!;
  const button = () => el.querySelector<HTMLButtonElement>('button[type=submit]')!;
  const fieldError = () => el.querySelector('#account-display-name-error')?.textContent?.trim();
  const status = () => el.querySelector('[role=status]')?.textContent?.trim();
  const alert = () => el.querySelector('[role=alert]')?.textContent?.trim();

  async function save(value: string) {
    input().value = value;
    input().dispatchEvent(new Event('input'));
    el.querySelector('form')!.dispatchEvent(new Event('submit'));
    await fixture.whenStable();
  }

  it('starts with the current display name', () => {
    expect(input().value).toBe('Bob');
    expect(button().textContent).toContain('Save');
  });

  it('saves the trimmed name, shows "Display name updated" and updates the signed-in user', async () => {
    await save('  Bobby ');
    const req = http.expectOne(URL);
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual({ displayName: 'Bobby' });
    expect(button().disabled).toBe(true);
    expect(button().getAttribute('aria-busy')).toBe('true');
    expect(el.querySelector('[data-testid=spinner]')).not.toBeNull();

    // No double submit while pending.
    el.querySelector('form')!.dispatchEvent(new Event('submit'));
    http.expectNone(URL);

    req.flush({ ...BOB, displayName: 'Bobby' });
    await fixture.whenStable();
    expect(status()).toBe('Display name updated');
    expect(input().value).toBe('Bobby');
    expect(auth.currentUser()?.displayName).toBe('Bobby');
    expect(button().disabled).toBe(false);
    expect(el.querySelector('[data-testid=spinner]')).toBeNull();
  });

  it.each([
    ['empty', '', 'Enter your display name'],
    ['whitespace-only', '   ', 'Enter your display name'],
    ['too long', 'a'.repeat(51), 'Display name must be at most 50 characters'],
  ])('shows an error and sends nothing when %s', async (_label, value, message) => {
    await save(value);
    expect(fieldError()).toBe(message);
    expect(input().getAttribute('aria-invalid')).toBe('true');
    http.expectNone(URL);
  });

  it('accepts exactly 50 characters after trimming', async () => {
    await save(` ${'a'.repeat(50)} `);
    http.expectOne(URL).flush({ ...BOB, displayName: 'a'.repeat(50) });
  });

  it('shows "This display name is already taken" under the field on 409', async () => {
    await save('jose');
    http
      .expectOne(URL)
      .flush(
        problem(409, 'DISPLAY_NAME_TAKEN', [
          {
            field: 'displayName',
            code: 'DISPLAY_NAME_TAKEN',
            message: 'This display name is already taken',
          },
        ]),
        { status: 409, statusText: 'Conflict' },
      );
    await fixture.whenStable();
    expect(fieldError()).toBe('This display name is already taken');
    expect(alert()).toBeUndefined();
    expect(status()).toBe('');
    expect(auth.currentUser()?.displayName).toBe('Bob');
  });

  it('maps server validation errors to the field', async () => {
    await save('ok');
    http
      .expectOne(URL)
      .flush(
        problem(400, 'VALIDATION_FAILED', [
          { field: 'displayName', code: 'TOO_LONG', message: 'server text' },
        ]),
        { status: 400, statusText: 'Bad Request' },
      );
    await fixture.whenStable();
    expect(fieldError()).toBe('Display name must be at most 50 characters');
  });

  it.each([
    ['network error', 0, ''],
    ['server error', 500, 'INTERNAL_ERROR'],
    ['CSRF failure', 403, 'CSRF_INVALID'],
    ['malformed request', 400, 'MALFORMED_REQUEST'],
  ])('shows the generic banner on %s', async (_label, status, code) => {
    await save('Bobby');
    const req = http.expectOne(URL);
    if (status === 0) {
      req.error(new ProgressEvent('error'));
    } else {
      req.flush(problem(status, code), { status, statusText: 'x' });
    }
    await fixture.whenStable();
    expect(alert()).toBe('Something went wrong, please try again');
    expect(button().disabled).toBe(false);
  });

  it('stays quiet on 401 (handled globally)', async () => {
    await save('Bobby');
    http
      .expectOne(URL)
      .flush(problem(401, 'UNAUTHENTICATED'), { status: 401, statusText: 'Unauthorized' });
    await fixture.whenStable();
    expect(alert()).toBeUndefined();
  });
});
