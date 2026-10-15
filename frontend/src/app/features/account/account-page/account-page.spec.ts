import { HttpXsrfTokenExtractor, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { routes } from '../../../app.routes';
import { authGuard } from '../../../core/auth/auth.guards';
import { User } from '../../../core/auth/auth.models';
import { AuthService } from '../../../core/auth/auth.service';
import { LoginPage } from '../../auth/login/login-page';
import { AccountPage } from './account-page';

const BOB = {
  id: 1,
  email: 'bob@example.com',
  displayName: 'Bob',
  emailVerified: true,
  hasPassword: true,
  role: 'USER' as const,
  connections: [],
};

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
    http.expectOne('/api/auth/providers').flush({ google: false });
    await harness.fixture.whenStable();
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
    http.expectOne('/api/auth/providers').flush({ google: false });
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
    http.expectOne('/api/auth/providers').flush({ google: false });
    await harness.fixture.whenStable();
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

describe('AccountPage Google', () => {
  const GOOGLE = { provider: 'google' as const, email: 'bob@gmail.com' };
  const WITH_PASSWORD: User = { ...BOB, connections: [] };
  const CONNECTED: User = { ...BOB, connections: [GOOGLE] };
  const GOOGLE_ONLY: User = { ...BOB, hasPassword: false, connections: [GOOGLE] };

  let harness: RouterTestingHarness;
  let http: HttpTestingController;
  let el: HTMLElement;
  let token: string | null;
  let submit: ReturnType<typeof vi.spyOn>;

  beforeAll(() => {
    HTMLDialogElement.prototype.showModal = function (this: HTMLDialogElement) {
      this.setAttribute('open', '');
    };
  });

  beforeEach(() => {
    token = 'tok-1';
    submit = vi.spyOn(HTMLFormElement.prototype, 'submit').mockImplementation(() => undefined);
  });

  afterEach(() => {
    http.verify();
    submit.mockRestore();
    document.getElementById('google-start-form')?.remove();
  });

  async function open(user: User, google: boolean, url = '/account') {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([
          { path: 'account', component: AccountPage },
          { path: 'login', component: LoginPage },
        ]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: HttpXsrfTokenExtractor, useValue: { getToken: () => token } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    TestBed.inject(AuthService).loadMe().subscribe();
    http.expectOne('/api/auth/me').flush(user);
    harness = await RouterTestingHarness.create();
    await harness.navigateByUrl(url, AccountPage);
    http.expectOne('/api/auth/providers').flush({ google });
    await harness.fixture.whenStable();
    el = harness.routeNativeElement!;
  }

  const stable = () => harness.fixture.whenStable();
  const headings = () => [...el.querySelectorAll('h2')].map((h) => h.textContent?.trim());
  const card = () => el.querySelector('[data-testid=connected-accounts]');
  const status = () => card()?.querySelector('[data-testid=google-connection-status]');
  const connectButton = () => el.querySelector<HTMLButtonElement>('#account-connect-google');
  const disconnectButton = () => el.querySelector<HTMLButtonElement>('#account-disconnect-google');
  const cardAlert = () => card()?.querySelector('[role=alert]')?.textContent?.trim();
  const cardStatus = () => el.querySelector('#account-connections-status')?.textContent?.trim();
  const notice = () => el.querySelector('[data-testid=oauth-notice]');

  describe('Password card', () => {
    it('replaces "Change password" for accounts without a password', async () => {
      await open(GOOGLE_ONLY, true);
      const passwordCard = el.querySelector('[data-testid=password-card]')!;
      expect(passwordCard.querySelector('h2')?.textContent?.trim()).toBe('Password');
      expect(passwordCard.textContent).toContain(
        "You sign in with Google. To also sign in with a password, set one with 'Forgot password?' on the sign-in page.",
      );
      expect(el.querySelector('app-change-password-form')).toBeNull();
      expect(headings()).toEqual([
        'Email',
        'Display name',
        'Password',
        'Sessions',
        'Connected accounts',
        'Delete account',
      ]);
    });

    it('keeps "Change password" for accounts with a password', async () => {
      await open(WITH_PASSWORD, true);
      expect(el.querySelector('[data-testid=password-card]')).toBeNull();
      expect(el.querySelector('app-change-password-form')).not.toBeNull();
    });
  });

  describe('Connected accounts card', () => {
    it('sits between Sessions and Delete account', async () => {
      await open(WITH_PASSWORD, true);
      expect(headings()).toEqual([
        'Email',
        'Display name',
        'Change password',
        'Sessions',
        'Connected accounts',
        'Delete account',
      ]);
    });

    it('not connected + Google on: "Google: not connected" and "Connect Google"', async () => {
      await open(WITH_PASSWORD, true);
      expect(status()?.textContent?.trim()).toBe('Google: not connected');
      expect(connectButton()!.textContent?.trim()).toBe('Connect Google');
      expect(connectButton()!.disabled).toBe(false);
      expect(disconnectButton()).toBeNull();
    });

    it('connected + password: "Google: connected as <email>" and an enabled Disconnect', async () => {
      await open(CONNECTED, true);
      expect(status()?.textContent?.replace(/\s+/g, ' ').trim()).toBe(
        'Google: connected as bob@gmail.com',
      );
      expect(disconnectButton()!.textContent?.trim()).toBe('Disconnect');
      expect(disconnectButton()!.disabled).toBe(false);
      expect(connectButton()).toBeNull();
      expect(el.querySelector('#account-disconnect-google-hint')).toBeNull();
    });

    it('connected without a password: Disconnect is disabled with the hint (AC15)', async () => {
      await open(GOOGLE_ONLY, true);
      expect(disconnectButton()!.disabled).toBe(true);
      expect(disconnectButton()!.getAttribute('aria-describedby')).toBe(
        'account-disconnect-google-hint',
      );
      expect(el.querySelector('#account-disconnect-google-hint')?.textContent?.trim()).toBe(
        "Set a password first so you don't lose access to your account.",
      );
      disconnectButton()!.click();
      await stable();
      expect(el.querySelector('app-disconnect-google-dialog')).toBeNull();
    });

    it('Google off with a connection: read-only, no buttons', async () => {
      await open(CONNECTED, false);
      expect(status()?.textContent).toContain('Google: connected as');
      expect(connectButton()).toBeNull();
      expect(disconnectButton()).toBeNull();
    });

    it('Google off without connections: the card is hidden', async () => {
      await open(WITH_PASSWORD, false);
      expect(card()).toBeNull();
    });
  });

  describe('Connect Google', () => {
    it('checks the session, then submits the hidden form with intent=connect', async () => {
      await open(WITH_PASSWORD, true);
      connectButton()!.click();
      await stable();
      expect(connectButton()!.disabled).toBe(true);
      expect(connectButton()!.getAttribute('aria-busy')).toBe('true');
      expect(card()!.querySelector('[data-testid=spinner]')).not.toBeNull();
      expect(submit).not.toHaveBeenCalled();

      // A second click does nothing while connecting.
      connectButton()!.click();
      http.expectOne('/api/auth/me').flush(WITH_PASSWORD);
      await stable();

      expect(submit).toHaveBeenCalledTimes(1);
      const form = submit.mock.contexts[0] as HTMLFormElement;
      expect(form.method).toBe('post');
      expect(form.getAttribute('action')).toBe('/api/auth/oauth2/authorization/google');
      expect((form.elements.namedItem('intent') as HTMLInputElement).value).toBe('connect');
      expect((form.elements.namedItem('_csrf') as HTMLInputElement).value).toBe('tok-1');
      // Still busy while the browser leaves for Google.
      expect(connectButton()!.disabled).toBe(true);

      window.dispatchEvent(new PageTransitionEvent('pageshow', { persisted: true }));
      await stable();
      expect(connectButton()!.disabled).toBe(false);
    });

    it('reads the CSRF token after GET /me (a rotated token is used)', async () => {
      await open(WITH_PASSWORD, true);
      connectButton()!.click();
      token = 'rotated';
      http.expectOne('/api/auth/me').flush(WITH_PASSWORD);
      await stable();
      const form = submit.mock.contexts[0] as HTMLFormElement;
      expect((form.elements.namedItem('_csrf') as HTMLInputElement).value).toBe('rotated');
    });

    it('on 401 signs the tab out and goes to /login without submitting', async () => {
      await open(WITH_PASSWORD, true);
      connectButton()!.click();
      http
        .expectOne('/api/auth/me')
        .flush(
          { type: 'about:blank', title: 'x', status: 401, detail: 'x', code: 'UNAUTHENTICATED' },
          { status: 401, statusText: 'Unauthorized' },
        );
      await stable();
      http.expectOne('/api/auth/providers').flush({ google: true });
      await stable();
      expect(submit).not.toHaveBeenCalled();
      expect(TestBed.inject(Router).url).toBe('/login');
      expect(TestBed.inject(AuthService).isSignedIn()).toBe(false);
      expect(harness.routeNativeElement?.textContent).toContain(
        'Your session expired, please sign in again',
      );
    });

    it.each([
      ['network error', 0],
      ['server error', 500],
    ])('on a %s shows "Couldn\'t connect Google" and re-enables the button', async (_l, code) => {
      await open(WITH_PASSWORD, true);
      connectButton()!.click();
      const req = http.expectOne('/api/auth/me');
      if (code === 0) {
        req.error(new ProgressEvent('error'));
      } else {
        req.flush(null, { status: code, statusText: 'x' });
      }
      await stable();
      expect(submit).not.toHaveBeenCalled();
      expect(cardAlert()).toBe("Couldn't connect Google. Please try again.");
      expect(connectButton()!.disabled).toBe(false);
    });

    it('without a CSRF token after GET /me shows the error and submits nothing', async () => {
      await open(WITH_PASSWORD, true);
      token = null;
      connectButton()!.click();
      http.expectOne('/api/auth/me').flush(WITH_PASSWORD);
      await stable();
      // GoogleStart tries GET /me once more for the cookie.
      http.expectOne('/api/auth/me').flush(WITH_PASSWORD);
      await stable();
      expect(submit).not.toHaveBeenCalled();
      expect(cardAlert()).toBe("Couldn't connect Google. Please try again.");
      expect(connectButton()!.disabled).toBe(false);
    });

    it('is disabled and sends nothing while the session id is being renewed', async () => {
      await open(WITH_PASSWORD, true);
      const input = (selector: string, value: string) => {
        const field = el.querySelector<HTMLInputElement>(selector)!;
        field.value = value;
        field.dispatchEvent(new Event('input'));
      };
      input('#account-current-password', 'old-pass-1');
      input('#account-new-password', 'new-pass-1');
      input('#account-confirm-password', 'new-pass-1');
      el.querySelector('app-change-password-form form')!.dispatchEvent(new Event('submit'));
      await stable();
      const req = http.expectOne('/api/account/password');
      expect(connectButton()!.disabled).toBe(true);
      connectButton()!.click();
      http.expectNone('/api/auth/me');
      req.flush(null, { status: 204, statusText: 'No Content' });
      await stable();
      expect(connectButton()!.disabled).toBe(false);
    });
  });

  describe('Disconnect', () => {
    it('confirms in a dialog, then shows "Google disconnected" and "not connected"', async () => {
      await open(CONNECTED, true);
      disconnectButton()!.click();
      await stable();
      expect(el.querySelector('app-disconnect-google-dialog dialog')).not.toBeNull();
      http.expectNone('/api/account/connections/google');

      el.querySelector<HTMLButtonElement>('#disconnect-google-confirm')!.click();
      await stable();
      http.expectOne('/api/account/connections/google').flush(WITH_PASSWORD);
      await stable();

      expect(el.querySelector('app-disconnect-google-dialog')).toBeNull();
      expect(cardStatus()).toBe('Google disconnected');
      expect(status()?.textContent?.trim()).toBe('Google: not connected');
      expect(connectButton()).not.toBeNull();
    });

    it('Cancel closes the dialog with no request', async () => {
      await open(CONNECTED, true);
      disconnectButton()!.click();
      await stable();
      [...el.querySelectorAll<HTMLButtonElement>('app-disconnect-google-dialog button')]
        .find((b) => b.textContent?.includes('Cancel'))!
        .click();
      await stable();
      expect(el.querySelector('app-disconnect-google-dialog')).toBeNull();
      http.expectNone('/api/account/connections/google');
    });
  });

  describe('oauth notices', () => {
    it.each([
      ['google_connected', 'Google connected', 'status'],
      ['google_in_use', 'This Google account is already connected to another account.', 'alert'],
      ['google_connect_failed', "Couldn't connect Google. Please try again.", 'alert'],
    ])('shows %s once and removes the parameter', async (code, message, role) => {
      await open(CONNECTED, true, `/account?oauth=${code}`);
      expect(notice()?.textContent?.trim()).toBe(message);
      expect(notice()?.getAttribute('role')).toBe(role);
      expect(TestBed.inject(Router).url).toBe('/account');
    });

    it.each(['google_failed', 'nope'])('ignores "%s" and removes it', async (code) => {
      await open(CONNECTED, true, `/account?oauth=${code}`);
      expect(notice()).toBeNull();
      expect(TestBed.inject(Router).url).toBe('/account');
    });

    it('clears the notice when connecting again', async () => {
      await open(WITH_PASSWORD, true, '/account?oauth=google_connect_failed');
      expect(notice()).not.toBeNull();
      connectButton()!.click();
      await stable();
      expect(notice()).toBeNull();
      http.expectOne('/api/auth/me').flush(WITH_PASSWORD);
    });
  });
});
