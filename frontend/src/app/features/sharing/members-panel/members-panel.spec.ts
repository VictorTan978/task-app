import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { User } from '../../../core/auth/auth.models';
import { AuthService } from '../../../core/auth/auth.service';
import { TaskList } from '../../lists/lists.models';
import { ListInvitation, Member } from '../sharing.models';
import { MembersPanel } from './members-panel';

const OWNED: TaskList = { id: 8, name: 'Home', personal: false, role: 'OWNER', memberCount: 2 };
const JOINED: TaskList = { ...OWNED, role: 'MEMBER' };
const ANA: Member = {
  userId: 1,
  displayName: 'Ana',
  email: 'ana@example.com',
  role: 'OWNER',
  joinedAt: '2026-10-01T09:00:00Z',
};
const BOB: Member = {
  userId: 9,
  displayName: 'Bob',
  email: 'bob@example.com',
  role: 'MEMBER',
  joinedAt: '2026-10-19T10:12:30.5Z',
};
const CAROL: ListInvitation = {
  id: 31,
  email: 'carol@example.com',
  invitedAt: '2026-10-19T10:00:00Z',
  expiresAt: '2026-11-02T10:00:00Z',
};
const DAN: ListInvitation = { ...CAROL, id: 32, email: 'dan@example.com' };

function problem(status: number, code: string, detail = 'x', errors?: unknown[]) {
  return { type: 'about:blank', title: 'x', status, detail, code, errors };
}

@Component({
  imports: [MembersPanel],
  template: `<app-members-panel
    [list]="list()"
    (closed)="events.push('closed')"
    (notFound)="events.push('notFound')"
  />`,
})
class Host {
  readonly list = signal<TaskList>(OWNED);
  readonly events: string[] = [];
}

describe('MembersPanel', () => {
  let fixture: ComponentFixture<Host>;
  let host: Host;
  let el: HTMLElement;
  let http: HttpTestingController;

  beforeAll(() => {
    HTMLDialogElement.prototype.showModal = function (this: HTMLDialogElement) {
      this.setAttribute('open', '');
    };
  });

  async function setup(list: TaskList = OWNED) {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: AuthService, useValue: { currentUser: signal({ id: 1 } as User) } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(Host);
    host = fixture.componentInstance;
    host.list.set(list);
    el = fixture.nativeElement;
    await fixture.whenStable();
  }

  async function open(members: Member[] = [ANA, BOB], pending: ListInvitation[] = [CAROL]) {
    await setup();
    http.expectOne('/api/lists/8/members').flush(members);
    http.expectOne('/api/lists/8/invitations').flush(pending);
    await stable();
  }

  afterEach(() => http.verify());

  const stable = () => fixture.whenStable();
  const text = () => el.textContent?.replace(/\s+/g, ' ') ?? '';
  const memberRows = () => Array.from(el.querySelectorAll('[data-testid=member-row]'));
  const pendingRows = () => Array.from(el.querySelectorAll('[data-testid=pending-row]'));
  const byLabel = (label: string) =>
    el.querySelector<HTMLButtonElement>(`button[aria-label="${label}"]`);
  const button = (name: string) =>
    Array.from(el.querySelectorAll('button')).find((b) => b.textContent?.trim() === name)!;
  const email = () => el.querySelector<HTMLInputElement>('#invite-email')!;

  async function invite(value: string) {
    email().value = value;
    email().dispatchEvent(new Event('input'));
    el.querySelector<HTMLFormElement>('form')!.dispatchEvent(new Event('submit'));
    await stable();
  }

  it('the owner sees members (name, email, role, "(you)"), the invite form and pending invites', async () => {
    await open();
    expect(el.querySelector('dialog')?.hasAttribute('open')).toBe(true);
    expect(el.querySelector('h2')?.textContent).toContain('Members of Home');
    expect(memberRows().length).toBe(2);
    const [ana, bob] = memberRows().map((r) => r.textContent?.replace(/\s+/g, ' '));
    expect(ana).toContain('Ana (you)');
    expect(ana).toContain('ana@example.com');
    expect(ana).toContain('Owner');
    expect(bob).toContain('bob@example.com');
    expect(bob).toContain('Member');
    expect(bob).not.toContain('(you)');
    expect(byLabel('Remove Ana')).toBeNull();
    expect(byLabel('Remove Bob')).not.toBeNull();
    expect(email()).not.toBeNull();
    expect(pendingRows().length).toBe(1);
    expect(pendingRows()[0].textContent).toContain('carol@example.com');
    expect(pendingRows()[0].querySelector('time')?.getAttribute('datetime')).toBe(CAROL.expiresAt);
  });

  it('a member sees only the members: no invite form, no pending, no Remove', async () => {
    await setup(JOINED);
    http.expectOne('/api/lists/8/members').flush([ANA, BOB]);
    http.expectNone('/api/lists/8/invitations');
    await stable();
    expect(memberRows().length).toBe(2);
    expect(el.querySelector('#invite-email')).toBeNull();
    expect(text()).not.toContain('Pending invitations');
    expect(byLabel('Remove Bob')).toBeNull();
  });

  it('shows loading, and "No pending invitations" when there are none', async () => {
    await setup();
    expect(text()).toContain('Loading members');
    expect(text()).toContain('Loading invitations');
    http.expectOne('/api/lists/8/members').flush([ANA]);
    http.expectOne('/api/lists/8/invitations').flush([]);
    await stable();
    expect(el.querySelector('[data-testid=pending-empty]')?.textContent).toContain(
      'No pending invitations',
    );
  });

  it('failed loads show Retry for each part', async () => {
    await setup();
    http.expectOne('/api/lists/8/members').flush(null, { status: 500, statusText: 'x' });
    http.expectOne('/api/lists/8/invitations').flush(null, { status: 500, statusText: 'x' });
    await stable();
    expect(text()).toContain("Couldn't load the members.");
    expect(text()).toContain("Couldn't load the pending invitations.");
    const retries = Array.from(el.querySelectorAll('button')).filter(
      (b) => b.textContent?.trim() === 'Retry',
    );
    expect(retries.length).toBe(2);
    retries[0].click();
    retries[1].click();
    http.expectOne('/api/lists/8/members').flush([ANA]);
    http.expectOne('/api/lists/8/invitations').flush([]);
    await stable();
    expect(text()).not.toContain("Couldn't load");
  });

  it('LIST_NOT_FOUND (removed, or the list was deleted) emits notFound', async () => {
    await setup();
    http
      .expectOne('/api/lists/8/members')
      .flush(problem(404, 'LIST_NOT_FOUND'), { status: 404, statusText: 'x' });
    http.expectOne('/api/lists/8/invitations').flush([]);
    await stable();
    expect(host.events).toEqual(['notFound']);
  });

  it('OWNER_ONLY shows "Only the owner can do that" and refetches my lists', async () => {
    await setup();
    http.expectOne('/api/lists/8/members').flush([ANA, BOB]);
    http
      .expectOne('/api/lists/8/invitations')
      .flush(problem(403, 'OWNER_ONLY'), { status: 403, statusText: 'x' });
    await stable();
    expect(el.querySelector('[role=alert]')?.textContent).toContain('Only the owner can do that');
    http.expectOne('/api/lists').flush([JOINED]);
  });

  it('Close and Escape emit closed', async () => {
    await open();
    button('Close').click();
    el.querySelector('dialog')!.dispatchEvent(new Event('cancel', { cancelable: true }));
    expect(host.events).toEqual(['closed', 'closed']);
  });

  describe('invite', () => {
    it('validates the address before sending', async () => {
      await open();
      await invite('   ');
      expect(text()).toContain('Enter your email');
      await invite('not-an-email');
      expect(text()).toContain('Enter a valid email address');
      http.expectNone('/api/lists/8/invitations');
    });

    it('sends the trimmed address, says "Invitation sent" and shows it as pending', async () => {
      await open();
      await invite('  Dan@Example.com ');
      expect(byLabel('Remove Bob')).not.toBeNull();
      expect(button('Send invitation').disabled).toBe(true);
      const req = http.expectOne('/api/lists/8/invitations');
      expect(req.request.method).toBe('POST');
      expect(req.request.body).toEqual({ email: 'Dan@Example.com' });
      req.flush(DAN, { status: 201, statusText: 'Created' });
      await stable();
      expect(el.querySelector('[data-testid=invite-sent]')?.textContent).toContain(
        'Invitation sent',
      );
      expect(
        pendingRows().map((r) => r.querySelector('.members-panel__email')?.textContent),
      ).toEqual(['dan@example.com', 'carol@example.com']);
      expect(email().value).toBe('');
      expect(text()).not.toContain('Enter your email');
    });

    it('an expired invitation sent again (same id) replaces its row', async () => {
      await open();
      await invite('carol@example.com');
      const refreshed = { ...CAROL, expiresAt: '2026-11-05T10:00:00Z' };
      http.expectOne('/api/lists/8/invitations').flush(refreshed, { status: 201, statusText: 'x' });
      await stable();
      expect(pendingRows().length).toBe(1);
      expect(pendingRows()[0].querySelector('time')?.getAttribute('datetime')).toBe(
        refreshed.expiresAt,
      );
    });

    it.each([
      ['ALREADY_MEMBER', 'This person is already a member of the list'],
      ['ALREADY_INVITED', 'This email address already has a pending invitation'],
      ['MEMBER_LIMIT', 'A list can have at most 20 members'],
      ['INVITATION_LIMIT', 'A list can have at most 20 pending invitations'],
      ['PERSONAL_LIST', "Your personal list can't be shared"],
    ])('409 %s shows its message next to the field, without Retry', async (code, detail) => {
      await open();
      await invite('bob@example.com');
      http
        .expectOne('/api/lists/8/invitations')
        .flush(problem(409, code, detail), { status: 409, statusText: 'x' });
      await stable();
      const error = el.querySelector('#invite-error')!;
      expect(error.textContent).toContain(detail);
      expect(error.querySelector('button')).toBeNull();
      expect(email().getAttribute('aria-describedby')).toBe('invite-error');
      expect(pendingRows().length).toBe(1);
      expect(text()).not.toContain('Invitation sent');
    });

    it('429 shows the rate-limit message', async () => {
      await open();
      await invite('dan@example.com');
      http
        .expectOne('/api/lists/8/invitations')
        .flush(problem(429, 'RATE_LIMITED', 'Too many attempts. Please try again later.'), {
          status: 429,
          statusText: 'x',
        });
      await stable();
      expect(el.querySelector('#invite-error')?.textContent).toContain('Too many attempts');
    });

    it('a server field error shows under the field', async () => {
      await open();
      await invite('dan@example.com');
      http
        .expectOne('/api/lists/8/invitations')
        .flush(
          problem(400, 'VALIDATION_FAILED', 'Validation failed', [
            { field: 'email', code: 'INVALID_EMAIL', message: 'Must be a valid email' },
          ]),
          { status: 400, statusText: 'x' },
        );
      await stable();
      expect(el.querySelector('#invite-email-error')?.textContent).toContain(
        'Enter a valid email address',
      );
    });

    it('a failed send shows Retry, which sends the same address again', async () => {
      await open();
      await invite('dan@example.com');
      http.expectOne('/api/lists/8/invitations').flush(null, { status: 500, statusText: 'x' });
      await stable();
      expect(el.querySelector('#invite-error')?.textContent).toContain(
        "Couldn't send the invitation.",
      );
      el.querySelector<HTMLButtonElement>('#invite-error button')!.click();
      const req = http.expectOne('/api/lists/8/invitations');
      expect(req.request.body).toEqual({ email: 'dan@example.com' });
      req.flush(DAN, { status: 201, statusText: 'Created' });
      await stable();
      expect(text()).toContain('Invitation sent');
    });
  });

  describe('cancel invitation', () => {
    it('removes the row', async () => {
      await open([ANA], [CAROL, DAN]);
      byLabel('Cancel invitation for carol@example.com')!.click();
      const req = http.expectOne('/api/lists/8/invitations/31');
      expect(req.request.method).toBe('DELETE');
      req.flush(null, { status: 204, statusText: 'No Content' });
      await stable();
      expect(pendingRows().length).toBe(1);
      expect(pendingRows()[0].textContent).toContain('dan@example.com');
    });

    it('already gone (INVITATION_NOT_FOUND) removes it and reloads the pending list', async () => {
      await open([ANA], [CAROL]);
      byLabel('Cancel invitation for carol@example.com')!.click();
      http
        .expectOne('/api/lists/8/invitations/31')
        .flush(problem(404, 'INVITATION_NOT_FOUND'), { status: 404, statusText: 'x' });
      await stable();
      http.expectOne('/api/lists/8/invitations').flush([]);
      await stable();
      expect(el.querySelector('[data-testid=pending-empty]')).not.toBeNull();
    });

    it('a failure shows an error on the row', async () => {
      await open([ANA], [CAROL]);
      byLabel('Cancel invitation for carol@example.com')!.click();
      http.expectOne('/api/lists/8/invitations/31').flush(null, { status: 500, statusText: 'x' });
      await stable();
      expect(pendingRows()[0].textContent).toContain("Couldn't cancel the invitation.");
    });
  });

  describe('remove member', () => {
    it('asks first; Cancel keeps them', async () => {
      await open();
      byLabel('Remove Bob')!.click();
      await stable();
      expect(text()).toContain('Remove Bob?');
      button('Cancel').click();
      await stable();
      expect(text()).not.toContain('Remove Bob?');
      expect(memberRows().length).toBe(2);
    });

    it('confirming removes the row and refetches my lists (member count)', async () => {
      await open();
      byLabel('Remove Bob')!.click();
      await stable();
      button('Remove').click();
      await stable();
      expect(button('Remove').disabled).toBe(true);
      const req = http.expectOne('/api/lists/8/members/9');
      expect(req.request.method).toBe('DELETE');
      req.flush(null, { status: 204, statusText: 'No Content' });
      await stable();
      expect(memberRows().length).toBe(1);
      http.expectOne('/api/lists').flush([{ ...OWNED, memberCount: 1 }]);
    });

    it('already gone (MEMBER_NOT_FOUND) drops the row and reloads the members', async () => {
      await open();
      byLabel('Remove Bob')!.click();
      await stable();
      button('Remove').click();
      http
        .expectOne('/api/lists/8/members/9')
        .flush(problem(404, 'MEMBER_NOT_FOUND'), { status: 404, statusText: 'x' });
      await stable();
      http.expectOne('/api/lists').flush([OWNED]);
      http.expectOne('/api/lists/8/members').flush([ANA]);
      await stable();
      expect(memberRows().length).toBe(1);
    });

    it('a failure shows an error on the row', async () => {
      await open();
      byLabel('Remove Bob')!.click();
      await stable();
      button('Remove').click();
      http.expectOne('/api/lists/8/members/9').flush(null, { status: 500, statusText: 'x' });
      await stable();
      expect(memberRows()[1].textContent).toContain("Couldn't remove the member.");
    });

    it('OWNER_ONLY shows the owner-only banner', async () => {
      await open();
      byLabel('Remove Bob')!.click();
      await stable();
      button('Remove').click();
      http
        .expectOne('/api/lists/8/members/9')
        .flush(problem(403, 'OWNER_ONLY'), { status: 403, statusText: 'x' });
      await stable();
      expect(text()).toContain('Only the owner can do that');
      http.expectOne('/api/lists').flush([JOINED]);
    });
  });
});
