import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { routes } from '../../../app.routes';
import { authGuard } from '../../../core/auth/auth.guards';
import { AuthService } from '../../../core/auth/auth.service';
import { LoginPage } from '../../auth/login/login-page';
import { AccountPage } from './account-page';

const BOB = { id: 1, email: 'bob@example.com', displayName: 'Bob' };

describe('AccountPage', () => {
  let harness: RouterTestingHarness;
  let http: HttpTestingController;
  let el: HTMLElement;

  beforeAll(() => {
    // jsdom has no showModal.
    HTMLDialogElement.prototype.showModal = function (this: HTMLDialogElement) {
      this.setAttribute('open', '');
    };
  });

  beforeEach(async () => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([
          { path: 'account', component: AccountPage },
          { path: 'login', component: LoginPage },
        ]),
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    });
    http = TestBed.inject(HttpTestingController);
    TestBed.inject(AuthService).loadMe().subscribe();
    http.expectOne('/api/auth/me').flush(BOB);
    harness = await RouterTestingHarness.create();
    await harness.navigateByUrl('/account', AccountPage);
    el = harness.routeNativeElement!;
  });

  afterEach(() => http.verify());

  const headings = () => [...el.querySelectorAll('h2')].map((h) => h.textContent?.trim());
  const button = (label: string, root: ParentNode = el) =>
    [...root.querySelectorAll<HTMLButtonElement>('button')].find(
      (b) => b.textContent?.trim() === label,
    )!;
  const dialog = () => el.querySelector('dialog');

  it('shows the read-only email and the sections', () => {
    const email = el.querySelector<HTMLInputElement>('#account-email')!;
    expect(email.value).toBe('bob@example.com');
    expect(email.readOnly).toBe(true);
    expect(headings()).toEqual([
      'Email',
      'Display name',
      'Change password',
      'Sessions',
      'Delete account',
    ]);
    expect(el.querySelector('app-display-name-form')).not.toBeNull();
    expect(el.querySelector('app-change-password-form')).not.toBeNull();
    expect(button('Delete account').classList).toContain('btn--danger');
    expect(dialog()).toBeNull();
  });

  it('opens the delete dialog and Cancel closes it with no request', async () => {
    button('Delete account').click();
    await harness.fixture.whenStable();
    expect(dialog()).not.toBeNull();

    button('Cancel', dialog()!).click();
    await harness.fixture.whenStable();
    expect(dialog()).toBeNull();
    http.expectNone('/api/account/delete');
  });

  it('after a successful delete lands on /login with "Your account has been deleted"', async () => {
    button('Delete account').click();
    await harness.fixture.whenStable();
    const input = el.querySelector<HTMLInputElement>('#delete-account-password')!;
    input.value = 'secret12';
    input.dispatchEvent(new Event('input'));
    dialog()!.querySelector('form')!.dispatchEvent(new Event('submit'));
    await harness.fixture.whenStable();

    http.expectOne('/api/account/delete').flush(null, { status: 204, statusText: 'No Content' });
    await harness.fixture.whenStable();

    expect(TestBed.inject(Router).url).toBe('/login');
    expect(harness.routeNativeElement?.textContent).toContain('Your account has been deleted');
    expect(TestBed.inject(AuthService).isSignedIn()).toBe(false);
    http.expectNone('/api/auth/me');
  });
});

describe('AccountPage sessions and busy lock', () => {
  let harness: RouterTestingHarness;
  let http: HttpTestingController;
  let el: HTMLElement;

  beforeAll(() => {
    HTMLDialogElement.prototype.showModal = function (this: HTMLDialogElement) {
      this.setAttribute('open', '');
    };
  });

  beforeEach(async () => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([{ path: 'account', component: AccountPage }]),
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    });
    http = TestBed.inject(HttpTestingController);
    TestBed.inject(AuthService).loadMe().subscribe();
    http.expectOne('/api/auth/me').flush(BOB);
    harness = await RouterTestingHarness.create();
    await harness.navigateByUrl('/account', AccountPage);
    el = harness.routeNativeElement!;
  });

  afterEach(() => http.verify());

  const sessionsButton = () => el.querySelector<HTMLButtonElement>('#account-logout-others')!;
  const sessionsStatus = () =>
    el.querySelector('#account-logout-others-status')?.textContent?.trim();
  const dialog = () => el.querySelector('app-logout-others-dialog dialog');
  const lockedButtons = () => ({
    displayName: el.querySelector<HTMLButtonElement>('app-display-name-form button[type=submit]')!
      .disabled,
    password: el.querySelector<HTMLButtonElement>('app-change-password-form button[type=submit]')!
      .disabled,
    sessions: sessionsButton().disabled,
    delete: el.querySelector<HTMLButtonElement>('#account-delete')!.disabled,
  });
  const ALL_LOCKED = { displayName: true, password: true, sessions: true, delete: true };
  const ALL_FREE = { displayName: false, password: false, sessions: false, delete: false };

  function type(selector: string, value: string) {
    const input = el.querySelector<HTMLInputElement>(selector)!;
    input.value = value;
    input.dispatchEvent(new Event('input'));
  }

  async function openAndConfirm() {
    sessionsButton().click();
    await harness.fixture.whenStable();
    el.querySelector<HTMLButtonElement>('#logout-others-confirm')!.click();
    await harness.fixture.whenStable();
  }

  it('shows the Sessions card text and button', () => {
    const card = el.querySelector('[aria-labelledby=account-sessions-heading]')!;
    expect(card.textContent).toContain(
      'Signed in somewhere else, like a shared computer? Log out everywhere except this device.',
    );
    expect(sessionsButton().textContent?.trim()).toBe('Log out all other devices');
    expect(sessionsStatus()).toBe('');
    expect(lockedButtons()).toEqual(ALL_FREE);
  });

  it('Cancel closes the dialog with no request', async () => {
    sessionsButton().click();
    await harness.fixture.whenStable();
    expect(dialog()).not.toBeNull();
    [...dialog()!.querySelectorAll('button')]
      .find((b) => b.textContent?.includes('Cancel'))!
      .click();
    await harness.fixture.whenStable();
    expect(dialog()).toBeNull();
    http.expectNone('/api/account/logout-other-sessions');
  });

  it('locks the page while logging out others, then shows the success message in the card', async () => {
    await openAndConfirm();
    const req = http.expectOne('/api/account/logout-other-sessions');
    expect(lockedButtons()).toEqual(ALL_LOCKED);

    req.flush(null, { status: 204, statusText: 'No Content' });
    await harness.fixture.whenStable();
    expect(dialog()).toBeNull();
    expect(sessionsStatus()).toBe("You've been logged out on all other devices");
    expect(lockedButtons()).toEqual(ALL_FREE);
    expect(TestBed.inject(AuthService).isSignedIn()).toBe(true);

    // Opening the dialog again clears the old message.
    sessionsButton().click();
    await harness.fixture.whenStable();
    expect(sessionsStatus()).toBe('');
  });

  it('keeps the dialog open with the error banner and unlocks on failure', async () => {
    await openAndConfirm();
    http.expectOne('/api/account/logout-other-sessions').error(new ProgressEvent('error'));
    await harness.fixture.whenStable();
    expect(dialog()).not.toBeNull();
    expect(dialog()!.querySelector('[role=alert]')?.textContent?.trim()).toBe(
      'Something went wrong, please try again',
    );
    expect(sessionsStatus()).toBe('');
    expect(lockedButtons()).toEqual(ALL_FREE);
  });

  it('locks the page while the password change is in flight and sends no other request', async () => {
    type('#account-current-password', 'old-pass-1');
    type('#account-new-password', 'new-pass-1');
    type('#account-confirm-password', 'new-pass-1');
    el.querySelector('app-change-password-form form')!.dispatchEvent(new Event('submit'));
    await harness.fixture.whenStable();
    const req = http.expectOne('/api/account/password');
    expect(lockedButtons()).toEqual(ALL_LOCKED);

    // Submitting the display name form (e.g. Enter) sends nothing while locked.
    el.querySelector('app-display-name-form form')!.dispatchEvent(new Event('submit'));
    await harness.fixture.whenStable();
    http.expectNone('/api/account/display-name');

    req.flush(null, { status: 204, statusText: 'No Content' });
    await harness.fixture.whenStable();
    expect(lockedButtons()).toEqual(ALL_FREE);
  });
});

describe('account route', () => {
  it('is a guarded child of the shell titled "Account · Task App"', () => {
    const shell = routes.find((r) => r.children?.some((c) => c.path === 'account'))!;
    expect(shell.canActivate).toContain(authGuard);
    const account = shell.children!.find((c) => c.path === 'account')!;
    expect(account.component).toBe(AccountPage);
    expect(account.title).toBe('Account · Task App');
  });
});
