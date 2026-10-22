import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
  TestRequest,
} from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { Role } from '../../../core/auth/auth.models';
import { AuthService } from '../../../core/auth/auth.service';
import { AdminUser, AdminUserPage } from '../admin.models';
import { AdminUsersPage } from './admin-users-page';

const LIST = '/api/admin/users';

function me(role: Role) {
  return {
    id: 1,
    email: 'me@example.com',
    displayName: 'Me',
    emailVerified: true,
    hasPassword: true,
    role,
    connections: [],
  };
}

function user(id: number, overrides: Partial<AdminUser> = {}): AdminUser {
  return {
    id,
    email: `user${id}@example.com`,
    displayName: `User ${id}`,
    createdAt: '2026-10-01T10:00:00Z',
    emailVerified: true,
    googleConnected: false,
    role: 'USER',
    disabled: false,
    ...overrides,
  };
}

function page(content: AdminUser[], extra: Partial<AdminUserPage> = {}): AdminUserPage {
  return {
    content,
    page: 0,
    size: 20,
    totalElements: content.length,
    totalPages: content.length ? 1 : 0,
    ...extra,
  };
}

function problem(status: number, code: string) {
  return { type: 'about:blank', title: 'x', status, detail: 'x', instance: '/api', code };
}

const ROWS = [
  user(1, { email: 'me@example.com', role: 'ADMIN' }),
  user(2, { email: 'alice@example.com', displayName: 'Alice', googleConnected: true }),
  user(3, { email: 'bob@example.com', role: 'ADMIN', emailVerified: false, disabled: true }),
  user(4, { email: 'root@example.com', role: 'SUPER_ADMIN' }),
];

describe('AdminUsersPage', () => {
  let fixture: ComponentFixture<AdminUsersPage>;
  let el: HTMLElement;
  let http: HttpTestingController;
  let navigate: ReturnType<typeof vi.spyOn>;

  beforeAll(() => {
    HTMLDialogElement.prototype.showModal = function (this: HTMLDialogElement) {
      this.setAttribute('open', '');
    };
  });

  async function create(role: Role = 'ADMIN') {
    TestBed.configureTestingModule({
      imports: [AdminUsersPage],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    navigate = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
    TestBed.inject(AuthService).loadMe().subscribe();
    http.expectOne('/api/auth/me').flush(me(role));
    fixture = TestBed.createComponent(AdminUsersPage);
    el = fixture.nativeElement;
    await fixture.whenStable();
  }

  afterEach(() => {
    vi.useRealTimers();
    http.verify();
  });

  const listRequest = (): TestRequest => http.expectOne((r) => r.url === LIST);
  async function flushList(body: AdminUserPage) {
    listRequest().flush(body);
    await fixture.whenStable();
  }
  const rows = () => [...el.querySelectorAll<HTMLTableRowElement>('[data-testid=admin-row]')];
  const row = (id: number) => el.querySelector<HTMLElement>(`[data-user-id="${id}"]`)!;
  const toggle = (id: number) =>
    row(id).querySelector<HTMLButtonElement>('[data-testid=admin-menu-toggle]');
  const menuItems = (id: number) =>
    [...row(id).querySelectorAll<HTMLButtonElement>('[role=menuitem]')].map((b) =>
      b.textContent?.trim(),
    );
  const notice = () => el.querySelector('[data-testid=admin-notice]')?.textContent?.trim();
  const button = (label: string) =>
    [...el.querySelectorAll<HTMLButtonElement>('button')].find(
      (b) => b.textContent?.trim() === label,
    )!;

  async function openMenu(id: number) {
    toggle(id)!.click();
    await fixture.whenStable();
  }

  async function choose(id: number, label: string) {
    await openMenu(id);
    [...row(id).querySelectorAll<HTMLButtonElement>('[role=menuitem]')]
      .find((b) => b.textContent?.trim() === label)!
      .click();
    await fixture.whenStable();
  }

  it('has a "Back to tasks" link to /tasks', async () => {
    await create();
    await flushList(page(ROWS));
    const link = el.querySelector('[data-testid=admin-users-back]');
    expect(link?.getAttribute('href')).toBe('/tasks');
    expect(link?.textContent).toContain('Back to tasks');
  });

  it('shows a loading state, then the table with the AC8 columns', async () => {
    await create();
    expect(el.querySelector('h1')?.textContent?.trim()).toBe('Users');
    expect(el.querySelector('[data-testid=admin-loading]')?.textContent).toContain('Loading users');
    const req = listRequest();
    expect(req.request.params.get('page')).toBe('0');
    expect(req.request.params.get('size')).toBe('20');
    expect(req.request.params.has('q')).toBe(false);
    req.flush(page(ROWS));
    await fixture.whenStable();

    expect(el.querySelector('[data-testid=admin-loading]')).toBeNull();
    const headers = [...el.querySelectorAll('th')].map((th) => th.textContent?.trim());
    expect(headers).toEqual([
      'Email',
      'Display name',
      'Created',
      'Email confirmed',
      'Google',
      'Role',
      'Status',
      'Actions',
    ]);
    expect(rows().length).toBe(4);
    const alice = row(2).textContent!.replace(/\s+/g, ' ');
    expect(alice).toContain('alice@example.com');
    expect(alice).toContain('Alice');
    expect(alice).toContain('Oct 1, 2026');
    expect(row(2).querySelector('time')?.getAttribute('datetime')).toBe('2026-10-01T10:00:00Z');
    const cells = (id: number) =>
      [...row(id).querySelectorAll('td')].map((td) => td.textContent?.trim());
    expect(cells(2).slice(3, 7)).toEqual(['Yes', 'Yes', 'User', 'Active']);
    expect(cells(3).slice(3, 7)).toEqual(['No', 'No', 'Admin', 'Disabled']);
    expect(cells(4)[5]).toBe('Super admin');
  });

  it('marks my own row "(you)" and only that one', async () => {
    await create();
    await flushList(page(ROWS));
    expect(row(1).querySelector('[data-testid=admin-you]')?.textContent?.trim()).toBe('(you)');
    expect(el.querySelectorAll('[data-testid=admin-you]').length).toBe(1);
  });

  it('admin: actions only on USER rows; none on myself, admins or super admins', async () => {
    await create('ADMIN');
    await flushList(page(ROWS));
    expect(toggle(1)).toBeNull();
    expect(toggle(3)).toBeNull();
    expect(toggle(4)).toBeNull();
    expect(toggle(2)?.getAttribute('aria-label')).toBe('Actions for alice@example.com');
    expect(toggle(2)?.getAttribute('aria-expanded')).toBe('false');
    await openMenu(2);
    expect(toggle(2)?.getAttribute('aria-expanded')).toBe('true');
    expect(menuItems(2)).toEqual(['Sign out everywhere', 'Disable', 'Delete']);
    const danger = [...row(2).querySelectorAll('.admin-menu__item--danger')].map((b) =>
      b.textContent?.trim(),
    );
    expect(danger).toEqual(['Disable', 'Delete']);
  });

  it('super admin: role actions, and admin rows too (never super admins or myself)', async () => {
    await create('SUPER_ADMIN');
    await flushList(page(ROWS));
    expect(toggle(1)).toBeNull();
    expect(toggle(4)).toBeNull();
    await openMenu(2);
    expect(menuItems(2)).toEqual(['Make admin', 'Sign out everywhere', 'Disable', 'Delete']);
    await openMenu(3);
    expect(menuItems(3)).toEqual(['Remove admin', 'Sign out everywhere', 'Enable', 'Delete']);
    // Opening one menu closed the other.
    expect(menuItems(2)).toEqual([]);
  });

  it('closes the menu on Escape and on a click outside', async () => {
    await create();
    await flushList(page(ROWS));
    await openMenu(2);
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    await fixture.whenStable();
    expect(menuItems(2)).toEqual([]);
    await openMenu(2);
    el.querySelector('h1')!.click();
    await fixture.whenStable();
    expect(menuItems(2)).toEqual([]);
  });

  it('shows "No users found" when nothing matches', async () => {
    await create();
    await flushList(page([]));
    expect(el.querySelector('[data-testid=admin-empty]')?.textContent?.trim()).toBe(
      'No users found',
    );
    expect(el.querySelector('table')).toBeNull();
    expect(el.querySelector('[data-testid=admin-page]')).toBeNull();
  });

  it('shows the error with Retry when the list fails, and Retry reloads', async () => {
    await create();
    listRequest().flush(problem(500, 'INTERNAL_ERROR'), { status: 500, statusText: 'x' });
    await fixture.whenStable();
    const error = el.querySelector('[data-testid=admin-load-error]');
    expect(error?.getAttribute('role')).toBe('alert');
    expect(error?.textContent).toContain('Something went wrong, please try again');
    button('Retry').click();
    await fixture.whenStable();
    expect(el.querySelector('[data-testid=admin-load-error]')).toBeNull();
    await flushList(page(ROWS));
    expect(rows().length).toBe(4);
  });

  it('on 403 FORBIDDEN (no longer an admin) re-reads /me and goes to /tasks', async () => {
    await create();
    listRequest().flush(problem(403, 'FORBIDDEN'), { status: 403, statusText: 'x' });
    http.expectOne('/api/auth/me').flush(me('USER'));
    await fixture.whenStable();
    expect(navigate).toHaveBeenCalledWith('/tasks');
    expect(el.querySelector('[data-testid=admin-load-error]')).toBeNull();
  });

  it('pages with Previous / Next and "Page X of Y"', async () => {
    await create();
    await flushList(page(ROWS, { totalElements: 45, totalPages: 3 }));
    expect(el.querySelector('[data-testid=admin-page]')?.textContent?.trim()).toBe('Page 1 of 3');
    expect(button('Previous').disabled).toBe(true);
    expect(button('Next').disabled).toBe(false);

    button('Next').click();
    await fixture.whenStable();
    const req = listRequest();
    expect(req.request.params.get('page')).toBe('1');
    expect(button('Next').disabled).toBe(true); // while loading
    req.flush(page(ROWS, { page: 1, totalElements: 45, totalPages: 3 }));
    await fixture.whenStable();
    expect(el.querySelector('[data-testid=admin-page]')?.textContent?.trim()).toBe('Page 2 of 3');
    expect(button('Previous').disabled).toBe(false);

    button('Previous').click();
    await fixture.whenStable();
    expect(listRequest().request.params.get('page')).toBe('0');
  });

  it('a page past the end loads the last page instead', async () => {
    await create();
    await flushList(page(ROWS, { totalElements: 45, totalPages: 3 }));
    button('Next').click();
    await fixture.whenStable();
    listRequest().flush(page([], { page: 1, totalElements: 20, totalPages: 1 }));
    await fixture.whenStable();
    const req = listRequest();
    expect(req.request.params.get('page')).toBe('0');
    req.flush(page(ROWS, { totalElements: 20, totalPages: 1 }));
    await fixture.whenStable();
    expect(el.querySelector('[data-testid=admin-page]')?.textContent?.trim()).toBe('Page 1 of 1');
  });

  it('searches 300 ms after typing stops, trimmed, back on page 0', async () => {
    await create();
    await flushList(page(ROWS, { totalElements: 45, totalPages: 3 }));
    button('Next').click();
    await fixture.whenStable();
    listRequest().flush(page(ROWS, { page: 1, totalElements: 45, totalPages: 3 }));
    await fixture.whenStable();

    vi.useFakeTimers();
    const input = el.querySelector<HTMLInputElement>('#admin-users-search')!;
    expect(input.getAttribute('maxlength')).toBe('100');
    for (const value of ['a', 'al', ' ali ']) {
      input.value = value;
      input.dispatchEvent(new Event('input'));
      vi.advanceTimersByTime(299);
    }
    http.expectNone((r) => r.url === LIST);
    vi.advanceTimersByTime(1);
    const req = listRequest();
    expect(req.request.params.get('q')).toBe('ali');
    expect(req.request.params.get('page')).toBe('0');
    req.flush(page([ROWS[1]]));
    vi.useRealTimers();
    await fixture.whenStable();
    expect(rows().length).toBe(1);
  });

  it('disable: confirm dialog, then "User disabled" and a refreshed table', async () => {
    await create();
    await flushList(page(ROWS));
    await choose(2, 'Disable');
    const dialog = el.querySelector('app-admin-action-dialog');
    expect(dialog?.textContent?.replace(/\s+/g, ' ')).toContain(
      "Disable alice@example.com? They will be signed out everywhere and can't sign in until enabled.",
    );
    el.querySelector<HTMLButtonElement>('[data-testid=admin-action-confirm]')!.click();
    await fixture.whenStable();
    http.expectOne('/api/admin/users/2/disable').flush({ ...ROWS[1], disabled: true });
    await fixture.whenStable();
    expect(el.querySelector('app-admin-action-dialog')).toBeNull();
    expect(notice()).toBe('User disabled');
    expect(el.querySelector('[data-testid=admin-notice]')?.getAttribute('role')).toBe('status');
    // The table stays visible while it refreshes.
    expect(el.querySelector('table')?.getAttribute('aria-busy')).toBe('true');
    await flushList(page([ROWS[0], { ...ROWS[1], disabled: true }]));
    expect(row(2).querySelector('[data-testid=admin-status]')?.textContent?.trim()).toBe(
      'Disabled',
    );
  });

  it.each([
    ['Enable', 'enable', 'User enabled', 'POST', '/api/admin/users/3/enable'],
    [
      'Sign out everywhere',
      'revokeSessions',
      'User signed out everywhere',
      'POST',
      '/api/admin/users/3/revoke-sessions',
    ],
    ['Remove admin', 'removeAdmin', 'Role changed', 'PUT', '/api/admin/users/3/role'],
  ])('%s shows "%s"', async (label, _action, message, method, url) => {
    await create('SUPER_ADMIN');
    await flushList(page(ROWS));
    await choose(3, label);
    el.querySelector<HTMLButtonElement>('[data-testid=admin-action-confirm]')!.click();
    await fixture.whenStable();
    const req = http.expectOne(url);
    expect(req.request.method).toBe(method);
    req.flush(ROWS[2]);
    await fixture.whenStable();
    expect(notice()).toBe(message);
    await flushList(page(ROWS));
  });

  it('delete: typed email, then "User deleted"', async () => {
    await create();
    await flushList(page(ROWS));
    await choose(2, 'Delete');
    const input = el.querySelector<HTMLInputElement>('#admin-action-email')!;
    input.value = 'ALICE@example.com ';
    input.dispatchEvent(new Event('input'));
    await fixture.whenStable();
    el.querySelector<HTMLButtonElement>('[data-testid=admin-action-confirm]')!.click();
    await fixture.whenStable();
    http.expectOne('/api/admin/users/2').flush(null, { status: 204, statusText: 'No Content' });
    await fixture.whenStable();
    expect(notice()).toBe('User deleted');
    await flushList(page([ROWS[0]]));
    expect(rows().length).toBe(1);
  });

  it.each([
    [403, 'INSUFFICIENT_ROLE', "You don't have permission to do this."],
    [404, 'USER_NOT_FOUND', 'This user no longer exists'],
  ])('%s %s: error in the dialog and the table refreshes', async (status, code, message) => {
    await create();
    await flushList(page(ROWS));
    await choose(2, 'Disable');
    el.querySelector<HTMLButtonElement>('[data-testid=admin-action-confirm]')!.click();
    await fixture.whenStable();
    http
      .expectOne('/api/admin/users/2/disable')
      .flush(problem(status, code), { status, statusText: 'x' });
    await fixture.whenStable();
    expect(el.querySelector('[data-testid=admin-action-error]')?.textContent?.trim()).toBe(message);
    await flushList(page([ROWS[0]]));
    expect(el.querySelector('app-admin-action-dialog')).not.toBeNull();
    expect(notice()).toBeUndefined();
    button('Cancel').click();
    await fixture.whenStable();
    expect(el.querySelector('app-admin-action-dialog')).toBeNull();
  });

  it('403 FORBIDDEN on an action closes the dialog, re-reads /me and goes to /tasks', async () => {
    await create();
    await flushList(page(ROWS));
    await choose(2, 'Disable');
    el.querySelector<HTMLButtonElement>('[data-testid=admin-action-confirm]')!.click();
    await fixture.whenStable();
    http
      .expectOne('/api/admin/users/2/disable')
      .flush(problem(403, 'FORBIDDEN'), { status: 403, statusText: 'x' });
    await fixture.whenStable();
    expect(el.querySelector('app-admin-action-dialog')).toBeNull();
    http.expectOne('/api/auth/me').flush(me('USER'));
    await fixture.whenStable();
    expect(navigate).toHaveBeenCalledWith('/tasks');
  });
});
