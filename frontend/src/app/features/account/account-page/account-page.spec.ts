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

  it('shows the read-only email and the three sections', () => {
    const email = el.querySelector<HTMLInputElement>('#account-email')!;
    expect(email.value).toBe('bob@example.com');
    expect(email.readOnly).toBe(true);
    expect(headings()).toEqual(['Email', 'Display name', 'Change password', 'Delete account']);
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

describe('account route', () => {
  it('is a guarded child of the shell titled "Account · Task App"', () => {
    const shell = routes.find((r) => r.children?.some((c) => c.path === 'account'))!;
    expect(shell.canActivate).toContain(authGuard);
    const account = shell.children!.find((c) => c.path === 'account')!;
    expect(account.component).toBe(AccountPage);
    expect(account.title).toBe('Account · Task App');
  });
});
