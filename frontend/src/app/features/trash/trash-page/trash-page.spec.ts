import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
  TestRequest,
} from '@angular/common/http/testing';
import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { User } from '../../../core/auth/auth.models';
import { AuthService } from '../../../core/auth/auth.service';
import { ToastService } from '../../../shared/toast/toast.service';
import { TaskList } from '../../lists/lists.models';
import { TrashItem, TrashPage as TrashPageData } from '../trash.models';
import { TrashPage } from './trash-page';

@Component({ template: 'tasks' })
class TasksStub {}

const MINE: TaskList = { id: 3, name: 'My tasks', personal: true, role: 'OWNER', memberCount: 1 };
const WORK: TaskList = { id: 9, name: 'Work', personal: false, role: 'MEMBER', memberCount: 2 };

const DAY = 24 * 60 * 60 * 1000;

function item(id: number, overrides: Partial<TrashItem> = {}): TrashItem {
  return {
    id,
    listId: 3,
    addedBy: { displayName: 'Ana' },
    title: `Task ${id}`,
    description: null,
    priority: 'NONE',
    status: 'TODO',
    tags: [],
    done: false,
    dueDate: null,
    dueTime: null,
    myReminder: null,
    myReminderPassed: false,
    repeat: null,
    createdAt: '2026-10-01T09:00:00Z',
    updatedAt: '2026-10-01T09:00:00Z',
    deletedAt: new Date(Date.now() - 2 * DAY).toISOString(),
    deletedBy: { displayName: 'Ben' },
    purgeAt: new Date(Date.now() + 28 * DAY - 60_000).toISOString(),
    ...overrides,
  };
}

const page = (
  items: TrashItem[],
  nextCursor: string | null = null,
  total = items.length,
): TrashPageData => ({ items, total, nextCursor });

const problem = (status: number, code: string, errors?: unknown[]) => ({
  type: 'about:blank',
  title: 'x',
  status,
  detail: 'x',
  code,
  errors,
});

describe('TrashPage', () => {
  let harness: RouterTestingHarness;
  let http: HttpTestingController;
  let toasts: ToastService;
  let el: HTMLElement;

  beforeAll(() => {
    HTMLDialogElement.prototype.showModal = function (this: HTMLDialogElement) {
      this.setAttribute('open', '');
    };
  });

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([
          { path: 'trash', component: TrashPage },
          { path: 'tasks', component: TasksStub },
        ]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: AuthService, useValue: { currentUser: signal({ id: 1 } as User) } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    toasts = TestBed.inject(ToastService);
  });

  afterEach(() => {
    toasts.dismiss();
    http.verify();
  });

  const stable = () => harness.fixture.whenStable();
  const q = <T extends Element = HTMLElement>(testId: string) =>
    el.querySelector<T>(`[data-testid=${testId}]`);
  const rows = () => Array.from(el.querySelectorAll<HTMLElement>('[data-testid=trash-row]'));
  const titles = () => rows().map((r) => r.querySelector('[data-testid=trash-title]')?.textContent);
  const rowButton = (row: number, testId: string) =>
    rows()[row].querySelector<HTMLButtonElement>(`[data-testid=${testId}]`)!;

  const trashReq = (params: Record<string, string | undefined> = {}): TestRequest =>
    http.expectOne(
      (r) =>
        r.method === 'GET' &&
        r.url === '/api/trash' &&
        r.params.keys().length === Object.values(params).filter((v) => v !== undefined).length &&
        Object.entries(params).every(([k, v]) => (r.params.get(k) ?? undefined) === v),
    );

  async function open(data: TrashPageData | null = page([item(2), item(1)])) {
    harness = await RouterTestingHarness.create();
    await harness.navigateByUrl('/trash');
    el = harness.routeNativeElement!;
    http.expectOne('/api/lists').flush([MINE, WORK]);
    if (data !== null) {
      trashReq().flush(data);
      await stable();
    }
  }

  async function confirm() {
    q<HTMLButtonElement>('trash-confirm')!.click();
    await stable();
  }

  it('shows a loading state, then the rows with list, who deleted and when, and days left', async () => {
    await open(null);
    expect(el.textContent).toContain('Loading the trash');
    trashReq().flush(page([item(2, { listId: 9 }), item(1, { deletedBy: null })]));
    await stable();
    expect(titles()).toEqual(['Task 2', 'Task 1']);
    expect(rows()[0].querySelector('[data-testid=trash-list]')?.textContent).toBe('Work');
    expect(rows()[0].querySelector('[data-testid=trash-deleted]')?.textContent).toBe(
      'Deleted by Ben · 2 days ago',
    );
    expect(rows()[0].querySelector('[data-testid=trash-left]')?.textContent).toBe('28 days left');
    expect(rows()[1].querySelector('[data-testid=trash-deleted]')?.textContent).toContain(
      'Deleted by a deleted user',
    );
    expect(el.textContent).toContain('2 tasks');
  });

  it('has the Trash entry highlighted in the list picker and a "Back to tasks" link', async () => {
    await open();
    expect(
      el.querySelector('a[href="/trash"].list-picker__link')?.getAttribute('aria-current'),
    ).toBe('page');
    expect(q('trash-back')?.getAttribute('href')).toBe('/tasks');
    expect(q('trash-back')?.textContent).toContain('Back to tasks');
  });

  it('shows the empty state, and no Empty trash button is enabled', async () => {
    await open(page([]));
    expect(q('trash-empty')?.textContent?.trim()).toBe(
      'Trash is empty. Deleted tasks stay here for 30 days.',
    );
    expect(q<HTMLButtonElement>('empty-trash')!.disabled).toBe(true);
  });

  it('shows an error with Retry that reloads', async () => {
    await open(null);
    trashReq().flush(null, { status: 500, statusText: 'Error' });
    await stable();
    expect(q('trash-error')?.textContent).toContain("Couldn't load the trash");
    expect(q('trash-empty')).toBeNull();
    el.querySelector<HTMLButtonElement>('[data-testid=trash-error] button')!.click();
    trashReq().flush(page([item(1)]));
    await stable();
    expect(q('trash-error')).toBeNull();
    expect(titles()).toEqual(['Task 1']);
  });

  describe('list filter', () => {
    it('lists All lists and my lists; choosing one reloads with list=', async () => {
      await open();
      const select = q<HTMLSelectElement>('trash-filter')!;
      expect(Array.from(select.options).map((o) => o.textContent?.trim())).toEqual([
        'All lists',
        'My tasks',
        'Work',
      ]);
      select.value = '9';
      select.dispatchEvent(new Event('change'));
      await stable();
      expect(titles()).toEqual([]);
      trashReq({ list: '9' }).flush(page([item(5, { listId: 9 })]));
      await stable();
      expect(titles()).toEqual(['Task 5']);
      select.value = '';
      select.dispatchEvent(new Event('change'));
      trashReq().flush(page([item(2), item(1)]));
      await stable();
      expect(titles()).toEqual(['Task 2', 'Task 1']);
    });

    it('LIST_NOT_FOUND resets the filter to all lists and refetches the lists', async () => {
      await open();
      const select = q<HTMLSelectElement>('trash-filter')!;
      select.value = '9';
      select.dispatchEvent(new Event('change'));
      trashReq({ list: '9' }).flush(problem(404, 'LIST_NOT_FOUND'), {
        status: 404,
        statusText: 'Not Found',
      });
      http.expectOne('/api/lists').flush([MINE]);
      trashReq().flush(page([item(1)]));
      await stable();
      expect(titles()).toEqual(['Task 1']);
      expect(q<HTMLSelectElement>('trash-filter')!.value).toBe('');
    });
  });

  describe('Load more', () => {
    it('appends the next page with the cursor, without repeating rows', async () => {
      await open(page([item(3), item(2)], 'c1', 3));
      expect(el.textContent).toContain('Showing 2 of 3');
      q<HTMLButtonElement>('trash-load-more')!.click();
      trashReq({ cursor: 'c1' }).flush(page([item(2), item(1)], null, 3));
      await stable();
      expect(titles()).toEqual(['Task 3', 'Task 2', 'Task 1']);
      expect(q('trash-load-more')).toBeNull();
    });

    it('a failure keeps the rows and shows Retry', async () => {
      await open(page([item(3)], 'c1', 2));
      q<HTMLButtonElement>('trash-load-more')!.click();
      trashReq({ cursor: 'c1' }).flush(null, { status: 500, statusText: 'Error' });
      await stable();
      expect(titles()).toEqual(['Task 3']);
      expect(el.textContent).toContain("Couldn't load more tasks");
      el.querySelector<HTMLButtonElement>('.trash-footer [role=alert] button')!.click();
      trashReq({ cursor: 'c1' }).flush(page([item(1)], null, 2));
      await stable();
      expect(titles()).toEqual(['Task 3', 'Task 1']);
    });

    it('INVALID_CURSOR restarts from the first page', async () => {
      await open(page([item(3)], 'c1', 2));
      q<HTMLButtonElement>('trash-load-more')!.click();
      trashReq({ cursor: 'c1' }).flush(
        problem(400, 'VALIDATION_FAILED', [
          { field: 'cursor', code: 'INVALID_CURSOR', message: 'x' },
        ]),
        { status: 400, statusText: 'Bad Request' },
      );
      trashReq().flush(page([item(3), item(2)]));
      await stable();
      expect(titles()).toEqual(['Task 3', 'Task 2']);
    });
  });

  describe('Restore', () => {
    it('shows a busy row, removes it, lowers the count and toasts', async () => {
      await open();
      rowButton(0, 'trash-restore').click();
      await stable();
      expect(rowButton(0, 'trash-restore').disabled).toBe(true);
      expect(rowButton(1, 'trash-restore').disabled).toBe(false);
      http.expectOne({ method: 'POST', url: '/api/trash/2/restore' }).flush(item(2));
      await stable();
      expect(titles()).toEqual(['Task 1']);
      expect(toasts.current()?.message).toBe('Task restored');
    });

    it('restoring the last task shows the empty state', async () => {
      await open(page([item(1)]));
      rowButton(0, 'trash-restore').click();
      http.expectOne('/api/trash/1/restore').flush(item(1));
      await stable();
      expect(q('trash-empty')).not.toBeNull();
    });

    it('restoring the last loaded row while more exist reloads instead of showing empty', async () => {
      await open(page([item(1)], 'c1', 3));
      rowButton(0, 'trash-restore').click();
      http.expectOne('/api/trash/1/restore').flush(item(1));
      await stable();
      expect(q('trash-empty')).toBeNull();
      trashReq().flush(page([item(3), item(2)], null, 2));
      await stable();
      expect(titles()).toEqual(['Task 3', 'Task 2']);
    });

    it('a 404 drops the row with a note', async () => {
      await open();
      rowButton(0, 'trash-restore').click();
      http
        .expectOne('/api/trash/2/restore')
        .flush(problem(404, 'NOT_FOUND'), { status: 404, statusText: 'Not Found' });
      await stable();
      expect(titles()).toEqual(['Task 1']);
      expect(q('trash-note')?.textContent).toContain('no longer in the trash');
    });

    it('another error keeps the row and shows an error', async () => {
      await open();
      rowButton(0, 'trash-restore').click();
      http.expectOne('/api/trash/2/restore').flush(null, { status: 500, statusText: 'Error' });
      await stable();
      expect(titles()).toEqual(['Task 2', 'Task 1']);
      expect(q('trash-row-error')?.textContent).toContain("Couldn't restore");
      expect(rowButton(0, 'trash-restore').disabled).toBe(false);
    });
  });

  describe('Delete forever', () => {
    async function ask(row: number) {
      rowButton(row, 'trash-delete').click();
      await stable();
    }

    it('asks first; Cancel changes nothing', async () => {
      await open();
      await ask(0);
      expect(el.querySelector('dialog h2')?.textContent).toBe('Delete forever?');
      expect(el.querySelector('dialog p')?.textContent).toContain('"Task 2"');
      el.querySelector<HTMLButtonElement>('dialog .btn--secondary')!.click();
      await stable();
      expect(el.querySelector('dialog')).toBeNull();
      expect(titles()).toEqual(['Task 2', 'Task 1']);
    });

    it('confirming sends DELETE /api/trash/{id}, disables the dialog while pending, then drops the row', async () => {
      await open();
      await ask(0);
      await confirm();
      const req = http.expectOne({ method: 'DELETE', url: '/api/trash/2' });
      expect(q<HTMLButtonElement>('trash-confirm')!.disabled).toBe(true);
      req.flush(null, { status: 204, statusText: 'No Content' });
      await stable();
      expect(el.querySelector('dialog')).toBeNull();
      expect(titles()).toEqual(['Task 1']);
    });

    it('a 404 closes the dialog, drops the row and leaves a note', async () => {
      await open();
      await ask(0);
      await confirm();
      http
        .expectOne('/api/trash/2')
        .flush(problem(404, 'NOT_FOUND'), { status: 404, statusText: 'Not Found' });
      await stable();
      expect(el.querySelector('dialog')).toBeNull();
      expect(titles()).toEqual(['Task 1']);
      expect(q('trash-note')).not.toBeNull();
    });

    it('another error stays in the dialog, which can be confirmed again', async () => {
      await open();
      await ask(0);
      await confirm();
      http.expectOne('/api/trash/2').flush(null, { status: 500, statusText: 'Error' });
      await stable();
      expect(el.querySelector('dialog [role=alert]')?.textContent).toContain("Couldn't delete");
      expect(titles()).toEqual(['Task 2', 'Task 1']);
      expect(q<HTMLButtonElement>('trash-confirm')!.disabled).toBe(false);
    });
  });

  describe('Empty trash', () => {
    it('names the count, then POSTs /api/trash/empty, toasts and reloads', async () => {
      await open(page([item(2), item(1)], null, 7));
      q<HTMLButtonElement>('empty-trash')!.click();
      await stable();
      expect(el.querySelector('dialog h2')?.textContent).toBe('Empty trash?');
      expect(el.querySelector('dialog p')?.textContent).toContain('all 7 tasks in the trash');
      await confirm();
      const req = http.expectOne((r) => r.method === 'POST' && r.url === '/api/trash/empty');
      expect(req.request.params.keys()).toEqual([]);
      req.flush({ deleted: 7 });
      await stable();
      expect(toasts.current()?.message).toBe('Deleted 7 tasks forever');
      trashReq().flush(page([]));
      await stable();
      expect(q('trash-empty')).not.toBeNull();
      expect(el.querySelector('dialog')).toBeNull();
    });

    it('with a list filter it names the list and sends list=', async () => {
      await open();
      const select = q<HTMLSelectElement>('trash-filter')!;
      select.value = '9';
      select.dispatchEvent(new Event('change'));
      trashReq({ list: '9' }).flush(page([item(5, { listId: 9 })], null, 1));
      await stable();
      q<HTMLButtonElement>('empty-trash')!.click();
      await stable();
      expect(el.querySelector('dialog p')?.textContent).toContain(
        'the 1 task in the trash of "Work"',
      );
      await confirm();
      const req = http.expectOne((r) => r.method === 'POST' && r.url === '/api/trash/empty');
      expect(req.request.params.get('list')).toBe('9');
      req.flush({ deleted: 1 });
      trashReq({ list: '9' }).flush(page([]));
      await stable();
    });

    it('a failure stays in the dialog', async () => {
      await open();
      q<HTMLButtonElement>('empty-trash')!.click();
      await stable();
      await confirm();
      http
        .expectOne((r) => r.url === '/api/trash/empty')
        .flush(null, { status: 500, statusText: 'Error' });
      await stable();
      expect(el.querySelector('dialog [role=alert]')?.textContent).toContain(
        "Couldn't empty the trash",
      );
      expect(titles()).toEqual(['Task 2', 'Task 1']);
    });
  });
});
