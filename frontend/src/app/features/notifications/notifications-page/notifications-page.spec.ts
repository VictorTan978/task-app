import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { AuthService } from '../../../core/auth/auth.service';
import { AppNotification } from '../notifications.models';
import { NotificationsPage } from './notifications-page';

const USER = {
  id: 1,
  email: 'bob@example.com',
  displayName: 'Bob',
  emailVerified: true,
  hasPassword: true,
  role: 'USER' as const,
  timeZone: 'Europe/Bucharest',
  reminderEmails: true,
  connections: [],
};

function n(id: number, overrides: Partial<AppNotification> = {}): AppNotification {
  return {
    id,
    type: 'REMINDER',
    read: false,
    createdAt: '2026-10-21T06:30:00Z',
    late: false,
    title: `Task ${id}`,
    dueDate: '2026-10-21',
    dueTime: '09:30',
    taskId: id,
    listId: 9,
    taskTrashed: false,
    ...overrides,
  };
}

const LISTS = [
  { id: 2, name: 'My tasks', personal: true, role: 'OWNER', memberCount: 1, taskCount: 0 },
  { id: 9, name: 'Team', personal: false, role: 'MEMBER', memberCount: 2, taskCount: 0 },
];

describe('NotificationsPage', () => {
  let fixture: ComponentFixture<NotificationsPage>;
  let el: HTMLElement;
  let http: HttpTestingController;
  let navigate: ReturnType<typeof vi.spyOn>;

  beforeEach(async () => {
    TestBed.configureTestingModule({
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    navigate = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
    TestBed.inject(AuthService).loadMe().subscribe();
    http.expectOne('/api/auth/me').flush(USER);
    fixture = TestBed.createComponent(NotificationsPage);
    el = fixture.nativeElement;
  });

  afterEach(() => http.verify());

  const stable = () => fixture.whenStable();
  const page = () => http.expectOne((r) => r.url === '/api/notifications');
  const items = () => Array.from(el.querySelectorAll<HTMLElement>('[data-testid=notification]'));
  const btn = (text: string) =>
    Array.from(el.querySelectorAll('button')).find((b) => b.textContent?.trim() === text)!;

  async function init(list: AppNotification[], nextCursor: string | null = null) {
    await stable();
    http.expectOne('/api/lists').flush(LISTS);
    page().flush({ items: list, nextCursor });
    http.expectOne('/api/notifications/count').flush({ count: 0 });
    await stable();
  }

  it('has a "Back to tasks" link to /tasks', async () => {
    await init([]);
    const link = el.querySelector('[data-testid=notifications-back]');
    expect(link?.getAttribute('href')).toBe('/tasks');
    expect(link?.textContent).toContain('Back to tasks');
  });

  it('shows loading, then the notifications newest first with unread marked and (late)', async () => {
    await stable();
    expect(el.textContent).toContain('Loading notifications');
    http.expectOne('/api/lists').flush(LISTS);
    page().flush({ items: [n(2, { late: true }), n(1, { read: true })], nextCursor: null });
    http.expectOne('/api/notifications/count').flush({ count: 1 });
    await stable();
    expect(
      items().map((i) => i.querySelector('[data-testid=notification-text]')!.textContent),
    ).toEqual([
      'Reminder: Task 2 — due 2026-10-21 09:30',
      'Reminder: Task 1 — due 2026-10-21 09:30',
    ]);
    expect(items().map((i) => i.dataset['read'])).toEqual(['false', 'true']);
    expect(items()[0].querySelector('[data-testid=notification-late]')?.textContent).toContain(
      'late',
    );
    expect(items()[1].querySelector('[data-testid=notification-late]')).toBeNull();
  });

  it('shows "No notifications yet" when empty', async () => {
    await init([]);
    expect(el.querySelector('[data-testid=notifications-empty]')?.textContent?.trim()).toBe(
      'No notifications yet',
    );
  });

  it('shows an error with Retry that reloads', async () => {
    await stable();
    http.expectOne('/api/lists').flush(LISTS);
    page().flush(null, { status: 500, statusText: 'x' });
    await stable();
    expect(el.querySelector('[role=alert]')?.textContent).toContain("Couldn't load");
    btn('Retry').click();
    page().flush({ items: [n(1)], nextCursor: null });
    http.expectOne('/api/notifications/count').flush({ count: 1 });
    await stable();
    expect(items().length).toBe(1);
  });

  it('Load more appends the next page with the cursor', async () => {
    await init([n(2)], 'c1');
    btn('Load more').click();
    const req = page();
    expect(req.request.params.get('cursor')).toBe('c1');
    req.flush({ items: [n(1)], nextCursor: null });
    await stable();
    expect(items().length).toBe(2);
    expect(el.querySelector('[data-testid=load-more]')).toBeNull();
  });

  it('clicking an unread one marks it read, then opens its list (/tasks for my personal list)', async () => {
    await init([n(1, { listId: 9 }), n(2, { listId: 2 })]);
    items()[1].querySelector('button')!.click();
    const req = http.expectOne('/api/notifications/2/read');
    req.flush(n(2, { read: true, listId: 2 }));
    http.expectOne('/api/notifications/count').flush({ count: 1 });
    await stable();
    expect(navigate).toHaveBeenCalledWith('/tasks');
    expect(items()[1].dataset['read']).toBe('true');

    items()[0].querySelector('button')!.click();
    http.expectOne('/api/notifications/1/read').flush(n(1, { read: true }));
    http.expectOne('/api/notifications/count').flush({ count: 0 });
    expect(navigate).toHaveBeenLastCalledWith('/lists/9');
  });

  it('says the task is no longer available and does not navigate', async () => {
    await init([n(1, { taskId: null, listId: null })]);
    expect(el.querySelector('[data-testid=notification-unavailable]')?.textContent?.trim()).toBe(
      'This task is no longer available',
    );
    items()[0].querySelector('button')!.click();
    http
      .expectOne('/api/notifications/1/read')
      .flush(n(1, { read: true, taskId: null, listId: null }));
    http.expectOne('/api/notifications/count').flush({ count: 0 });
    expect(navigate).not.toHaveBeenCalled();
  });

  describe('trashed task', () => {
    const trashed = (id: number) => n(id, { taskTrashed: true });
    const restoreBtn = (i: number) =>
      items()[i].querySelector<HTMLButtonElement>('[data-testid=notification-restore]')!;

    it('says "This task is in the trash" with a Restore button, and clicking opens nothing', async () => {
      await init([trashed(1)]);
      expect(items()[0].querySelector('[data-testid=notification-trashed]')?.textContent).toContain(
        'This task is in the trash',
      );
      expect(restoreBtn(0)).not.toBeNull();
      expect(items()[0].querySelector('[data-testid=notification-unavailable]')).toBeNull();

      items()[0].querySelector('button')!.click();
      http.expectOne('/api/notifications/1/read').flush(n(1, { read: true, taskTrashed: true }));
      http.expectOne('/api/notifications/count').flush({ count: 0 });
      await stable();
      expect(navigate).not.toHaveBeenCalled();
    });

    it('Restore posts to the trash and clears the note on every notification of that task', async () => {
      await init([trashed(1), n(2, { taskId: 1, taskTrashed: true })]);
      restoreBtn(0).click();
      await stable();
      expect(restoreBtn(0).disabled).toBe(true);
      http.expectOne('/api/trash/1/restore').flush({ id: 1 });
      await stable();
      expect(el.querySelector('[data-testid=notification-trashed]')).toBeNull();
    });

    it('a 404 on Restore shows the task as no longer available', async () => {
      await init([trashed(1)]);
      restoreBtn(0).click();
      http.expectOne('/api/trash/1/restore').flush(null, { status: 404, statusText: 'x' });
      await stable();
      expect(el.querySelector('[data-testid=notification-trashed]')).toBeNull();
      expect(el.querySelector('[data-testid=notification-unavailable]')).not.toBeNull();
    });

    it('another failure keeps the button and shows an error', async () => {
      await init([trashed(1)]);
      restoreBtn(0).click();
      http.expectOne('/api/trash/1/restore').flush(null, { status: 500, statusText: 'x' });
      await stable();
      expect(restoreBtn(0).disabled).toBe(false);
      expect(el.querySelector('[data-testid=notification-restore-error]')?.textContent).toContain(
        "Couldn't restore",
      );
    });
  });

  it('Mark all read marks every item and sets the count to 0', async () => {
    await init([n(1), n(2)]);
    btn('Mark all read').click();
    http.expectOne('/api/notifications/read-all').flush(null, { status: 204, statusText: 'x' });
    await stable();
    expect(items().every((i) => i.dataset['read'] === 'true')).toBe(true);
    expect(btn('Mark all read').disabled).toBe(true);
  });

  it('shows an error when Mark all read fails', async () => {
    await init([n(1)]);
    btn('Mark all read').click();
    http.expectOne('/api/notifications/read-all').flush(null, { status: 500, statusText: 'x' });
    await stable();
    expect(el.querySelector('[data-testid=notifications-action-error]')).not.toBeNull();
    expect(items()[0].dataset['read']).toBe('false');
  });
});
