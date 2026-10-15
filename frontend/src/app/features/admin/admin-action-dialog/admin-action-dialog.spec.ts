import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { AdminAction } from '../admin-permissions';
import { AdminUser } from '../admin.models';
import { AdminActionDialog } from './admin-action-dialog';

const ALICE: AdminUser = {
  id: 2,
  email: 'alice@example.com',
  displayName: 'Alice',
  createdAt: '2026-10-01T10:00:00Z',
  emailVerified: true,
  googleConnected: false,
  role: 'USER',
  disabled: false,
};

function problem(status: number, code: string) {
  return { type: 'about:blank', title: 'x', status, detail: 'x', instance: '/api', code };
}

describe('AdminActionDialog', () => {
  let fixture: ComponentFixture<AdminActionDialog>;
  let el: HTMLElement;
  let http: HttpTestingController;
  let done: ReturnType<typeof vi.fn<(action: AdminAction) => void>>;
  let closed: ReturnType<typeof vi.fn<() => void>>;
  let stale: ReturnType<typeof vi.fn<() => void>>;
  let forbidden: ReturnType<typeof vi.fn<() => void>>;

  beforeAll(() => {
    HTMLDialogElement.prototype.showModal = function (this: HTMLDialogElement) {
      this.setAttribute('open', '');
    };
  });

  async function open(action: AdminAction, user: AdminUser = ALICE) {
    TestBed.configureTestingModule({
      imports: [AdminActionDialog],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(AdminActionDialog);
    fixture.componentRef.setInput('user', user);
    fixture.componentRef.setInput('action', action);
    done = vi.fn<(action: AdminAction) => void>();
    closed = vi.fn<() => void>();
    stale = vi.fn<() => void>();
    forbidden = vi.fn<() => void>();
    fixture.componentInstance.done.subscribe((a) => done(a));
    fixture.componentInstance.closed.subscribe(() => closed());
    fixture.componentInstance.stale.subscribe(() => stale());
    fixture.componentInstance.forbidden.subscribe(() => forbidden());
    el = fixture.nativeElement;
    await fixture.whenStable();
  }

  afterEach(() => http.verify());

  const confirmButton = () =>
    el.querySelector<HTMLButtonElement>('[data-testid=admin-action-confirm]')!;
  const cancelButton = () =>
    [...el.querySelectorAll<HTMLButtonElement>('button')].find((b) =>
      b.textContent?.includes('Cancel'),
    )!;
  const text = () =>
    el.querySelector('[data-testid=admin-action-text]')?.textContent?.replace(/\s+/g, ' ').trim();
  const error = () => el.querySelector('[data-testid=admin-action-error]')?.textContent?.trim();

  async function submit() {
    el.querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true }));
    await fixture.whenStable();
  }

  async function typeEmail(value: string) {
    const input = el.querySelector<HTMLInputElement>('#admin-action-email')!;
    input.value = value;
    input.dispatchEvent(new Event('input'));
    await fixture.whenStable();
  }

  it.each([
    [
      'disable',
      'Disable user?',
      "Disable alice@example.com? They will be signed out everywhere and can't sign in until enabled.",
      'Disable',
      true,
    ],
    [
      'enable',
      'Enable user?',
      'Enable alice@example.com? They can sign in again.',
      'Enable',
      false,
    ],
    [
      'revokeSessions',
      'Sign out everywhere?',
      'Sign out alice@example.com on every device? Their account stays active and they can sign in again.',
      'Sign out everywhere',
      false,
    ],
    [
      'makeAdmin',
      'Make admin?',
      "Make alice@example.com an admin? They can disable, sign out and delete normal users' accounts.",
      'Make admin',
      false,
    ],
    [
      'removeAdmin',
      'Remove admin?',
      'Remove admin rights from alice@example.com? They become a normal user.',
      'Remove admin',
      false,
    ],
  ] as const)(
    '%s: names the email and confirms with "%s"',
    async (action, title, sentence, button, danger) => {
      await open(action);
      expect(el.querySelector('h2')?.textContent?.trim()).toBe(title);
      expect(text()).toBe(sentence);
      expect(confirmButton().textContent?.trim()).toBe(button);
      expect(confirmButton().classList.contains('btn--danger')).toBe(danger);
      expect(el.querySelector('#admin-action-email')).toBeNull();
    },
  );

  it.each([
    ['disable', 'POST', '/api/admin/users/2/disable', null],
    ['enable', 'POST', '/api/admin/users/2/enable', null],
    ['revokeSessions', 'POST', '/api/admin/users/2/revoke-sessions', null],
    ['makeAdmin', 'PUT', '/api/admin/users/2/role', { role: 'ADMIN' }],
    ['removeAdmin', 'PUT', '/api/admin/users/2/role', { role: 'USER' }],
  ] as const)('%s: spinner while pending, then emits done', async (action, method, url, body) => {
    await open(action);
    await submit();
    const req = http.expectOne(url);
    expect(req.request.method).toBe(method);
    expect(req.request.body).toEqual(body);
    expect(confirmButton().disabled).toBe(true);
    expect(confirmButton().getAttribute('aria-busy')).toBe('true');
    expect(el.querySelector('[data-testid=spinner]')).not.toBeNull();
    expect(cancelButton().disabled).toBe(true);

    // Escape is ignored while pending.
    const escape = new Event('cancel', { cancelable: true });
    el.querySelector('dialog')!.dispatchEvent(escape);
    expect(escape.defaultPrevented).toBe(true);
    expect(closed).not.toHaveBeenCalled();

    req.flush(ALICE);
    await fixture.whenStable();
    expect(done).toHaveBeenCalledWith(action);
  });

  it('Cancel and Escape close without a request', async () => {
    await open('disable');
    cancelButton().click();
    expect(closed).toHaveBeenCalledTimes(1);
    const escape = new Event('cancel', { cancelable: true });
    el.querySelector('dialog')!.dispatchEvent(escape);
    expect(escape.defaultPrevented).toBe(true);
    expect(closed).toHaveBeenCalledTimes(2);
    http.expectNone(() => true);
  });

  describe('delete', () => {
    beforeEach(async () => open('delete'));

    it('explains the permanent delete and asks for the email', () => {
      expect(el.querySelector('h2')?.textContent?.trim()).toBe('Delete user?');
      expect(text()).toContain('Delete alice@example.com?');
      expect(text()).toContain('all their tasks will be permanently deleted');
      expect(el.querySelector('label[for=admin-action-email]')?.textContent?.trim()).toBe(
        'Email address',
      );
      expect(confirmButton().textContent?.trim()).toBe('Delete permanently');
      expect(confirmButton().classList).toContain('btn--danger');
      expect(confirmButton().disabled).toBe(true);
    });

    it('shows "Email doesn\'t match" and keeps the button disabled for wrong text', async () => {
      await typeEmail('bob@example.com');
      expect(el.querySelector('#admin-action-email-error')?.textContent?.trim()).toBe(
        "Email doesn't match",
      );
      expect(el.querySelector('#admin-action-email')?.getAttribute('aria-invalid')).toBe('true');
      expect(confirmButton().disabled).toBe(true);
      await submit();
      http.expectNone('/api/admin/users/2');
    });

    it('matches trimmed and case-insensitive, then deletes', async () => {
      await typeEmail('  Alice@Example.COM ');
      expect(el.querySelector('#admin-action-email-error')).toBeNull();
      expect(confirmButton().disabled).toBe(false);
      await submit();
      const req = http.expectOne('/api/admin/users/2');
      expect(req.request.method).toBe('DELETE');
      req.flush(null, { status: 204, statusText: 'No Content' });
      await fixture.whenStable();
      expect(done).toHaveBeenCalledWith('delete');
    });
  });

  it.each([
    [403, 'INSUFFICIENT_ROLE', "You don't have permission to do this.", 'stale'],
    [404, 'USER_NOT_FOUND', 'This user no longer exists', 'stale'],
    [429, 'RATE_LIMITED', 'Too many attempts, please try again later', null],
    [
      409,
      'CANNOT_MODIFY_SELF',
      "You can't do this to your own account. Use your Account page instead.",
      null,
    ],
    [500, 'INTERNAL_ERROR', 'Something went wrong, please try again', null],
  ] as const)('shows %s %s inside the dialog', async (status, code, message, event) => {
    await open('disable');
    await submit();
    http
      .expectOne('/api/admin/users/2/disable')
      .flush(problem(status, code), { status, statusText: 'x' });
    await fixture.whenStable();
    expect(error()).toBe(message);
    expect(el.querySelector('[data-testid=admin-action-error]')?.getAttribute('role')).toBe(
      'alert',
    );
    expect(stale).toHaveBeenCalledTimes(event === 'stale' ? 1 : 0);
    expect(forbidden).not.toHaveBeenCalled();
    expect(done).not.toHaveBeenCalled();
    expect(confirmButton().disabled).toBe(false);
  });

  it('emits forbidden on 403 FORBIDDEN (no longer an admin)', async () => {
    await open('enable', { ...ALICE, disabled: true });
    await submit();
    http
      .expectOne('/api/admin/users/2/enable')
      .flush(problem(403, 'FORBIDDEN'), { status: 403, statusText: 'x' });
    await fixture.whenStable();
    expect(forbidden).toHaveBeenCalledTimes(1);
    expect(stale).not.toHaveBeenCalled();
    expect(error()).toBeUndefined();
  });

  it('stays quiet on 401 (handled globally)', async () => {
    await open('disable');
    await submit();
    http
      .expectOne('/api/admin/users/2/disable')
      .flush(problem(401, 'UNAUTHENTICATED'), { status: 401, statusText: 'x' });
    await fixture.whenStable();
    expect(error()).toBeUndefined();
  });
});
