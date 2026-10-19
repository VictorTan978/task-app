import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { User } from '../../../core/auth/auth.models';
import { AuthService } from '../../../core/auth/auth.service';
import { TaskList } from '../../lists/lists.models';
import { ListsStore } from '../../lists/lists.store';
import { InvitationsStore } from '../invitations.store';
import { MyInvitation } from '../sharing.models';
import { InvitationsPage } from './invitations-page';

const HOME_INV: MyInvitation = {
  id: 31,
  list: { id: 8, name: 'Home' },
  invitedBy: { displayName: 'Ana' },
  invitedAt: '2026-10-19T10:00:00Z',
  expiresAt: '2026-11-02T10:00:00Z',
};
const WORK_INV: MyInvitation = {
  id: 32,
  list: { id: 9, name: 'Work' },
  invitedBy: { displayName: 'Bob' },
  invitedAt: '2026-10-18T10:00:00Z',
  expiresAt: '2026-11-01T10:00:00Z',
};
const HOME: TaskList = { id: 8, name: 'Home', personal: false, role: 'MEMBER', memberCount: 4 };
const MINE: TaskList = { id: 3, name: 'My tasks', personal: true, role: 'OWNER', memberCount: 1 };

function problem(status: number, code: string, detail = 'x') {
  return { type: 'about:blank', title: 'x', status, detail, code };
}

describe('InvitationsPage', () => {
  let fixture: ComponentFixture<InvitationsPage>;
  let el: HTMLElement;
  let http: HttpTestingController;
  let user: ReturnType<typeof signal<User | null>>;
  let lists: ListsStore;
  let badge: InvitationsStore;

  function setup(emailVerified = true) {
    user = signal<User | null>({ id: 1, emailVerified } as User);
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: AuthService, useValue: { currentUser: user } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    lists = TestBed.inject(ListsStore);
    badge = TestBed.inject(InvitationsStore);
    lists.load();
    http.expectOne('/api/lists').flush([MINE]);
    fixture = TestBed.createComponent(InvitationsPage);
    el = fixture.nativeElement;
  }

  afterEach(() => http.verify());

  const stable = () => fixture.whenStable();
  const rows = () => Array.from(el.querySelectorAll('[data-testid=invitation]'));
  const button = (label: string) =>
    el.querySelector<HTMLButtonElement>(`button[aria-label="${label}"]`)!;
  const text = () => el.textContent?.replace(/\s+/g, ' ') ?? '';

  async function loaded(invitations: MyInvitation[]) {
    await stable();
    http.expectOne('/api/invitations').flush(invitations);
    await stable();
  }

  it('shows loading, then who invited me to which list, with the expiry; sets the badge', async () => {
    setup();
    await stable();
    expect(el.querySelector('[role=status]')?.textContent).toContain('Loading invitations');
    http.expectOne('/api/invitations').flush([HOME_INV, WORK_INV]);
    await stable();
    expect(rows().length).toBe(2);
    expect(rows()[0].textContent?.replace(/\s+/g, ' ')).toContain('Ana invited you to Home');
    expect(rows()[0].querySelector('time')?.getAttribute('datetime')).toBe(HOME_INV.expiresAt);
    expect(rows()[0].textContent).toContain('Expires');
    expect(badge.count()).toBe(2);
  });

  it('shows "No pending invitations" when there are none', async () => {
    setup();
    await loaded([]);
    expect(el.querySelector('[data-testid=invitations-empty]')?.textContent).toContain(
      'No pending invitations',
    );
    expect(badge.count()).toBe(0);
  });

  it('a failed load shows Retry, which loads again', async () => {
    setup();
    await stable();
    http.expectOne('/api/invitations').flush(null, { status: 500, statusText: 'x' });
    await stable();
    expect(text()).toContain("Couldn't load your invitations.");
    Array.from(el.querySelectorAll('button'))
      .find((b) => b.textContent?.trim() === 'Retry')!
      .click();
    await loaded([HOME_INV]);
    expect(rows().length).toBe(1);
    expect(text()).not.toContain("Couldn't load");
  });

  it('an unverified email is asked to confirm it, and nothing is requested', async () => {
    setup(false);
    await stable();
    http.expectNone('/api/invitations');
    expect(el.querySelector('[data-testid=invitations-unverified]')?.textContent).toContain(
      'Confirm your email to see invitations',
    );
  });

  it('loads once the email becomes verified', async () => {
    setup(false);
    await stable();
    user.set({ id: 1, emailVerified: true } as User);
    await loaded([HOME_INV]);
    expect(rows().length).toBe(1);
  });

  it('Accept adds the list to the picker and shows "Joined Home. Open list"', async () => {
    setup();
    await loaded([HOME_INV, WORK_INV]);
    button('Accept invitation to Home').click();
    await stable();
    expect(button('Accept invitation to Home').disabled).toBe(true);
    expect(button('Decline invitation to Home').disabled).toBe(true);
    expect(button('Accept invitation to Work').disabled).toBe(false);
    const req = http.expectOne('/api/invitations/31/accept');
    expect(req.request.method).toBe('POST');
    req.flush(HOME);
    await stable();
    http.expectOne('/api/lists').flush([MINE, HOME]);
    await stable();
    expect(lists.byId().get(8)).toEqual(HOME);
    expect(rows().length).toBe(1);
    expect(badge.count()).toBe(1);
    const joined = el.querySelector('[data-testid=joined]')!;
    expect(joined.textContent?.replace(/\s+/g, ' ')).toContain('Joined Home.');
    expect(joined.querySelector('a')?.getAttribute('href')).toBe('/lists/8');
    expect(joined.querySelector('a')?.textContent?.trim()).toBe('Open list');
  });

  it('a stale invitation shows "This invitation is no longer valid", drops the row and reloads', async () => {
    setup();
    await loaded([HOME_INV, WORK_INV]);
    button('Accept invitation to Home').click();
    http
      .expectOne('/api/invitations/31/accept')
      .flush(problem(404, 'INVITATION_NOT_FOUND', 'This invitation is no longer valid'), {
        status: 404,
        statusText: 'x',
      });
    await stable();
    expect(el.querySelector('[data-testid=invitations-notice]')?.textContent).toContain(
      'This invitation is no longer valid',
    );
    expect(rows().length).toBe(1);
    http.expectOne('/api/invitations').flush([WORK_INV]);
    await stable();
    expect(rows().length).toBe(1);
    expect(lists.byId().has(8)).toBe(false);
    expect(badge.count()).toBe(1);
  });

  it('a full list (409 MEMBER_LIMIT) keeps the row and shows the message', async () => {
    setup();
    await loaded([HOME_INV]);
    button('Accept invitation to Home').click();
    http
      .expectOne('/api/invitations/31/accept')
      .flush(problem(409, 'MEMBER_LIMIT', 'A list can have at most 20 members'), {
        status: 409,
        statusText: 'x',
      });
    await stable();
    expect(rows().length).toBe(1);
    expect(rows()[0].querySelector('[role=alert]')?.textContent).toContain(
      'A list can have at most 20 members',
    );
    expect(button('Accept invitation to Home').disabled).toBe(false);
  });

  it('a failed accept shows an error; pressing Accept again retries', async () => {
    setup();
    await loaded([HOME_INV]);
    button('Accept invitation to Home').click();
    http.expectOne('/api/invitations/31/accept').flush(null, { status: 500, statusText: 'x' });
    await stable();
    expect(text()).toContain("Couldn't accept the invitation. Please try again.");
    button('Accept invitation to Home').click();
    await stable();
    expect(text()).not.toContain("Couldn't accept");
    http.expectOne('/api/invitations/31/accept').flush(HOME);
    http.expectOne('/api/lists').flush([MINE, HOME]);
    await stable();
    expect(rows().length).toBe(0);
    expect(el.querySelector('[data-testid=invitations-empty]')).not.toBeNull();
  });

  it('Decline removes the row and lowers the badge', async () => {
    setup();
    await loaded([HOME_INV, WORK_INV]);
    button('Decline invitation to Work').click();
    const req = http.expectOne('/api/invitations/32/decline');
    expect(req.request.method).toBe('POST');
    req.flush(null, { status: 204, statusText: 'No Content' });
    await stable();
    expect(rows().length).toBe(1);
    expect(badge.count()).toBe(1);
    expect(el.querySelector('[data-testid=joined]')).toBeNull();
  });

  it('Decline of a stale invitation also shows the message and reloads', async () => {
    setup();
    await loaded([HOME_INV]);
    button('Decline invitation to Home').click();
    http
      .expectOne('/api/invitations/31/decline')
      .flush(problem(404, 'INVITATION_NOT_FOUND'), { status: 404, statusText: 'x' });
    await stable();
    expect(text()).toContain('This invitation is no longer valid');
    http.expectOne('/api/invitations').flush([]);
    await stable();
    expect(el.querySelector('[data-testid=invitations-empty]')).not.toBeNull();
  });

  it('a failed decline keeps the row with an error; 401 stays quiet', async () => {
    setup();
    await loaded([HOME_INV]);
    button('Decline invitation to Home').click();
    http.expectOne('/api/invitations/31/decline').flush(null, { status: 500, statusText: 'x' });
    await stable();
    expect(text()).toContain("Couldn't decline the invitation. Please try again.");
    button('Decline invitation to Home').click();
    http
      .expectOne('/api/invitations/31/decline')
      .flush(problem(401, 'UNAUTHENTICATED'), { status: 401, statusText: 'x' });
    await stable();
    expect(rows().length).toBe(1);
    expect(rows()[0].querySelector('[role=alert]')).toBeNull();
  });
});
