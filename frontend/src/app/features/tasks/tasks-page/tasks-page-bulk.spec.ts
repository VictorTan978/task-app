import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
  TestRequest,
} from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { User } from '../../../core/auth/auth.models';
import { AuthService } from '../../../core/auth/auth.service';
import { ToastService } from '../../../shared/toast/toast.service';
import { TaskList } from '../../lists/lists.models';
import { Task, TaskPage, TaskResult } from '../tasks.models';
import { TasksPage } from './tasks-page';

const MINE: TaskList = { id: 3, name: 'My tasks', personal: true, role: 'OWNER', memberCount: 1 };
const HOME: TaskList = { id: 8, name: 'Home', personal: false, role: 'OWNER', memberCount: 1 };
const WORK: TaskList = { id: 9, name: 'Work', personal: false, role: 'OWNER', memberCount: 1 };

function task(id: number, overrides: Partial<Task> = {}): Task {
  return {
    id,
    listId: 8,
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
    createdAt: `2026-10-0${id}T09:00:00Z`,
    updatedAt: `2026-10-0${id}T09:00:00Z`,
    ...overrides,
  };
}
const done = (t: Task, o: Partial<Task> = {}): Task => ({ ...t, status: 'DONE', done: true, ...o });
const res = (t: Task, nextTask: Task | null = null): TaskResult => ({ ...t, nextTask });
const onePage = (items: Task[]): TaskPage => ({ items, total: items.length, nextCursor: null });
const problem = (status: number, code: string, extra: object = {}) => ({
  type: 'about:blank',
  title: 'x',
  status,
  detail: 'x',
  code,
  ...extra,
});

describe('TasksPage bulk actions', () => {
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
          { path: 'tasks', component: TasksPage },
          { path: 'lists/:id', component: TasksPage },
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
  const tasksReq = (): TestRequest =>
    http.expectOne((r) => r.method === 'GET' && r.url === '/api/tasks');
  const byId = (id: string) => el.querySelector<HTMLElement>(`[data-testid="${id}"]`);
  const titles = () =>
    Array.from(el.querySelectorAll('.task-row__title')).map((b) => b.textContent?.trim());
  const selectBoxes = () =>
    Array.from(el.querySelectorAll<HTMLInputElement>('[data-testid="task-select"]'));
  const sent = (match: Parameters<HttpTestingController['expectOne']>[0]) =>
    vi.waitFor(() => http.expectOne(match));
  const shown = () => vi.waitFor(() => expect(toasts.current()).not.toBeNull());

  async function open(tasks: Task[], url = '/lists/8') {
    harness = await RouterTestingHarness.create();
    await harness.navigateByUrl(url);
    el = harness.routeNativeElement!;
    http.expectOne('/api/lists').flush([MINE, HOME, WORK]);
    tasksReq().flush(onePage(tasks));
    await stable();
  }

  /** Enters selection mode and ticks the given task numbers (by position). */
  async function select(...positions: number[]) {
    byId('select-mode')!.click();
    await stable();
    for (const p of positions) {
      selectBoxes()[p].click();
    }
    await stable();
    await vi.waitFor(() => expect(byId('bulk-bar')).not.toBeNull());
  }

  const press = async (id: string) => {
    byId(id)!.click();
    await stable();
  };

  describe('selection mode', () => {
    it('Select shows a labelled checkbox per row and the bar with the count', async () => {
      await open([task(3), task(2), task(1)]);
      expect(byId('bulk-bar')).toBeNull();
      await select();
      expect(selectBoxes().map((b) => b.getAttribute('aria-label'))).toEqual([
        'Select Task 3',
        'Select Task 2',
        'Select Task 1',
      ]);
      expect(byId('bulk-count')!.textContent?.trim()).toBe('0 selected');
      expect(byId('select-mode')).toBeNull();
    });

    it('ticking rows counts them; Select all loaded takes every loaded task', async () => {
      await open([task(3), task(2), task(1)]);
      await select(0, 2);
      expect(byId('bulk-count')!.textContent?.trim()).toBe('2 selected');
      await press('bulk-select-all');
      expect(byId('bulk-count')!.textContent?.trim()).toBe('3 selected');
    });

    it('Shift+click selects the range from the last ticked row', async () => {
      await open([task(4), task(3), task(2), task(1)]);
      await select(0);
      selectBoxes()[2].dispatchEvent(new MouseEvent('click', { shiftKey: true, bubbles: true }));
      await stable();
      expect(byId('bulk-count')!.textContent?.trim()).toBe('3 selected');
      expect(selectBoxes().map((b) => b.checked)).toEqual([true, true, true, false]);
    });

    it('Cancel and Esc leave the mode', async () => {
      await open([task(2), task(1)]);
      await select(0);
      await press('bulk-cancel');
      expect(byId('bulk-bar')).toBeNull();
      expect(selectBoxes()).toHaveLength(0);
      await select(0);
      document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
      await stable();
      expect(byId('bulk-bar')).toBeNull();
    });

    it('a filter change leaves the mode and forgets the selection', async () => {
      await open([task(2), task(1)]);
      await select(0);
      await harness.navigateByUrl('/lists/8?filter=active');
      tasksReq().flush(onePage([task(2), task(1)]));
      await stable();
      expect(byId('bulk-bar')).toBeNull();
      byId('select-mode')!.click();
      await stable();
      await vi.waitFor(() => expect(byId('bulk-count')!.textContent?.trim()).toBe('0 selected'));
    });

    it('only loaded tasks can be selected (Select all loaded ignores unloaded pages)', async () => {
      harness = await RouterTestingHarness.create();
      await harness.navigateByUrl('/lists/8');
      el = harness.routeNativeElement!;
      http.expectOne('/api/lists').flush([MINE, HOME, WORK]);
      tasksReq().flush({ items: [task(2), task(1)], total: 80, nextCursor: 'c' });
      await stable();
      await select();
      await press('bulk-select-all');
      expect(byId('bulk-count')!.textContent?.trim()).toBe('2 selected');
    });
  });

  describe('actions', () => {
    it('Complete: one request, rows update, toast "2 tasks completed"', async () => {
      await open([task(3), task(2), task(1)]);
      await select(0, 1);
      await press('bulk-complete');
      const req = http.expectOne({ method: 'POST', url: '/api/tasks/bulk' });
      expect(req.request.body).toEqual({ ids: [3, 2], action: 'COMPLETE' });
      expect(byId('bulk-busy')).not.toBeNull();
      req.flush({ count: 2, tasks: [res(done(task(3))), res(done(task(2)))] });
      await stable();
      await shown();
      expect(toasts.current()?.message).toBe('2 tasks completed');
      expect(toasts.current()?.action?.label).toBe('Undo');
      expect(byId('bulk-bar')).toBeNull();
      expect(
        Array.from(el.querySelectorAll<HTMLInputElement>('input[type=checkbox]')).map(
          (c) => c.checked,
        ),
      ).toEqual([false, true, true]);
    });

    it('Undo of a COMPLETE over repeating and plain tasks is ONE bulk-undo-complete', async () => {
      const rule = { frequency: 'DAILY' as const, weekdays: [] };
      const water = task(5, { title: 'Water', dueDate: '2999-01-01', repeat: rule });
      const plain = task(4, { status: 'IN_PROGRESS' });
      const copy = task(6, { title: 'Water', dueDate: '2999-01-02', repeat: rule });
      await open([water, plain]);
      await select(0, 1);
      await press('bulk-complete');
      http.expectOne('/api/tasks/bulk').flush({
        count: 2,
        tasks: [res(done(water, { repeat: null }), copy), res(done(plain))],
      });
      await stable();
      await shown();
      expect(titles()).toHaveLength(3);

      toasts.runAction();
      const req = await sent({ method: 'POST', url: '/api/tasks/bulk-undo-complete' });
      expect(req.request.body).toEqual({
        items: [
          { id: 5, nextTaskId: 6, status: 'TODO' },
          { id: 4, status: 'IN_PROGRESS' },
        ],
      });
      req.flush({
        results: [
          { ...water, nextTaskOutcome: 'DELETED' },
          { ...plain, nextTaskOutcome: 'NONE' },
        ],
      });
      await stable();
      expect(titles()).toHaveLength(2);
      expect(toasts.current()?.message).toBe('Undone');
    });

    it('Undo with a KEPT copy uses the kept wording and leaves the copy', async () => {
      const rule = { frequency: 'DAILY' as const, weekdays: [] };
      const water = task(5, { title: 'Water', dueDate: '2999-01-01', repeat: rule });
      const copy = task(6, { title: 'Water', dueDate: '2999-01-02', repeat: rule });
      await open([water]);
      await select(0);
      await press('bulk-complete');
      http
        .expectOne('/api/tasks/bulk')
        .flush({ count: 1, tasks: [res(done(water, { repeat: null }), copy)] });
      await stable();
      await shown();
      toasts.runAction();
      (await sent('/api/tasks/bulk-undo-complete')).flush({
        results: [{ ...water, nextTaskOutcome: 'KEPT' }],
      });
      await stable();
      expect(titles()).toHaveLength(2);
      expect(toasts.current()?.message).toBe(
        'Undone: the next copy was already changed, so it was kept',
      );
    });

    it('Set priority: toast, and Undo is one bulk-patch with each task old value', async () => {
      await open([task(3, { priority: 'LOW' }), task(2)]);
      await select(0, 1);
      await press('bulk-priority');
      await press('bulk-priority-HIGH');
      const req = http.expectOne('/api/tasks/bulk');
      expect(req.request.body).toEqual({ ids: [3, 2], action: 'SET_PRIORITY', value: 'HIGH' });
      req.flush({
        count: 2,
        tasks: [res(task(3, { priority: 'HIGH' })), res(task(2, { priority: 'HIGH' }))],
      });
      await stable();
      await shown();
      expect(toasts.current()?.message).toBe('Priority set on 2 tasks');
      toasts.runAction();
      const undo = await sent('/api/tasks/bulk-patch');
      expect(undo.request.body).toEqual({
        items: [
          { id: 3, patch: { priority: 'LOW' } },
          { id: 2, patch: { priority: 'NONE' } },
        ],
      });
      undo.flush({
        count: 2,
        tasks: [res(task(3, { priority: 'LOW' })), res(task(2))],
      });
      await stable();
      expect(toasts.current()?.message).toBe('Undone');
    });

    it('Add tag undoes with REMOVE_TAG on the ids whose tags changed', async () => {
      await open([task(3), task(2, { tags: ['Home'] })]);
      await select(0, 1);
      await press('bulk-add-tag');
      const input = byId('bulk-tag-input') as HTMLInputElement;
      input.value = 'Home';
      input.dispatchEvent(new Event('input'));
      await stable();
      await press('bulk-tag-apply');
      http.expectOne('/api/tasks/bulk').flush({
        count: 2,
        tasks: [res(task(3, { tags: ['Home'] })), res(task(2, { tags: ['Home'] }))],
      });
      await stable();
      await shown();
      toasts.runAction();
      const undo = await sent('/api/tasks/bulk');
      expect(undo.request.body).toEqual({ ids: [3], action: 'REMOVE_TAG', value: 'Home' });
      undo.flush({ count: 1, tasks: [res(task(3))] });
      await stable();
    });

    it('Remove tag offers the selected tasks tags', async () => {
      await open([task(3, { tags: ['Home'] }), task(2, { tags: ['Work', 'home'] })]);
      await select(0, 1);
      await press('bulk-remove-tag');
      const choices = el.querySelectorAll('[data-testid="bulk-remove-tag-choice"]');
      expect(Array.from(choices).map((c) => c.textContent?.trim())).toEqual(['Home', 'Work']);
      (choices[0] as HTMLElement).click();
      const req = http.expectOne('/api/tasks/bulk');
      expect(req.request.body).toEqual({ ids: [3, 2], action: 'REMOVE_TAG', value: 'Home' });
      req.flush({ count: 2, tasks: [res(task(3)), res(task(2, { tags: ['Work'] }))] });
      await stable();
      await shown();
    });

    it('Move: lists exclude the open one, tasks leave the view, Undo patches list and tags', async () => {
      await open([task(3, { tags: ['A'] }), task(2)]);
      await select(0, 1);
      await press('bulk-move');
      const choices = Array.from(el.querySelectorAll('[data-testid="bulk-move-choice"]'));
      expect(choices.map((c) => c.textContent?.trim())).toEqual(['My tasks', 'Work']);
      (choices[1] as HTMLElement).click();
      const req = http.expectOne('/api/tasks/bulk');
      expect(req.request.body).toEqual({ ids: [3, 2], action: 'MOVE', value: 9 });
      req.flush({
        count: 2,
        tasks: [res(task(3, { listId: 9, tags: ['A'] })), res(task(2, { listId: 9 }))],
      });
      await stable();
      await shown();
      expect(titles()).toEqual([]);
      expect(toasts.current()?.message).toBe('2 tasks moved to Work');
      toasts.runAction();
      const undo = await sent('/api/tasks/bulk-patch');
      expect(undo.request.body).toEqual({
        items: [
          { id: 3, patch: { listId: 8, tags: ['A'] } },
          { id: 2, patch: { listId: 8, tags: [] } },
        ],
      });
      undo.flush({ count: 2, tasks: [res(task(3, { tags: ['A'] })), res(task(2))] });
      await stable();
      expect(titles()).toHaveLength(2);
    });

    it('on My tasks a move still removes the rows', async () => {
      await open([task(3, { listId: 3 }), task(2, { listId: 3 })], '/tasks');
      await select(0, 1);
      await press('bulk-move');
      (el.querySelector('[data-testid="bulk-move-choice"]') as HTMLElement).click();
      http.expectOne('/api/tasks/bulk').flush({
        count: 2,
        tasks: [res(task(3, { listId: 8 })), res(task(2, { listId: 8 }))],
      });
      await stable();
      expect(titles()).toEqual([]);
    });

    it('Reopen sends REOPEN and its Undo re-sets DONE', async () => {
      await open([done(task(3)), task(2)]);
      await select(0, 1);
      await press('bulk-reopen');
      http.expectOne('/api/tasks/bulk').flush({ count: 2, tasks: [res(task(3)), res(task(2))] });
      await stable();
      await shown();
      expect(toasts.current()?.message).toBe('2 tasks reopened');
      toasts.runAction();
      const undo = await sent('/api/tasks/bulk-patch');
      expect(undo.request.body).toEqual({ items: [{ id: 3, patch: { status: 'DONE' } }] });
      undo.flush({ count: 1, tasks: [res(done(task(3)))] });
      await stable();
    });

    it('a second press while the request runs sends nothing more', async () => {
      await open([task(3), task(2)]);
      await select(0, 1);
      await press('bulk-complete');
      expect((byId('bulk-complete') as HTMLButtonElement).disabled).toBe(true);
      byId('bulk-complete')!.click();
      http.expectOne('/api/tasks/bulk');
    });
  });

  describe('delete', () => {
    it('confirm names the count; Cancel sends nothing and keeps the selection', async () => {
      await open([task(3), task(2), task(1)]);
      await select(0, 1);
      await press('bulk-delete');
      await vi.waitFor(() => expect(byId('bulk-delete-dialog')).not.toBeNull());
      expect(el.ownerDocument.querySelector('#bulk-delete-heading')?.textContent).toBe(
        'Delete 2 tasks?',
      );
      Array.from(byId('bulk-delete-dialog')!.querySelectorAll('button'))
        .find((b) => b.textContent?.trim() === 'Cancel')!
        .click();
      await stable();
      expect(byId('bulk-delete-dialog')).toBeNull();
      expect(byId('bulk-count')!.textContent?.trim()).toBe('2 selected');
    });

    it('Delete moves them to the trash and Undo restores all in one request', async () => {
      await open([task(3), task(2), task(1)]);
      await select(0, 1);
      await press('bulk-delete');
      await vi.waitFor(() => expect(byId('bulk-delete-confirm')).not.toBeNull());
      byId('bulk-delete-confirm')!.click();
      const req = http.expectOne('/api/tasks/bulk');
      expect(req.request.body).toEqual({ ids: [3, 2], action: 'DELETE' });
      req.flush({ count: 2, tasks: [] });
      await stable();
      await shown();
      expect(titles()).toEqual(['Task 1']);
      expect(toasts.current()?.message).toBe('2 tasks moved to trash');
      expect(byId('bulk-delete-dialog')).toBeNull();

      toasts.runAction();
      const restore = await sent({ method: 'POST', url: '/api/trash/restore' });
      expect(restore.request.body).toEqual({ ids: [3, 2] });
      restore.flush({ tasks: [task(3), task(2)] });
      await stable();
      expect(titles()).toEqual(['Task 3', 'Task 2', 'Task 1']);
    });
  });

  describe('errors (all or nothing)', () => {
    async function failComplete(status: number, body: object) {
      await open([task(3), task(2), task(1)]);
      await select(0, 1);
      await press('bulk-complete');
      http.expectOne('/api/tasks/bulk').flush(body, { status, statusText: 'x' });
      await stable();
    }

    it('404 with failedIds names the count, keeps the selection, and Reload refetches', async () => {
      await failComplete(404, problem(404, 'NOT_FOUND', { failedIds: [2, 3, 9] }));
      const banner = el.querySelector('[role=alert]')!;
      expect(banner.textContent).toContain(
        '3 tasks are no longer available. Reload and try again.',
      );
      expect(byId('bulk-count')!.textContent?.trim()).toBe('2 selected');
      expect((byId('bulk-complete') as HTMLButtonElement).disabled).toBe(false);
      expect(toasts.current()).toBeNull();
      const reload = Array.from(banner.querySelectorAll('button')).find(
        (b) => b.textContent?.trim() === 'Reload',
      )!;
      reload.click();
      tasksReq().flush(onePage([task(1)]));
      await stable();
      expect(titles()).toEqual(['Task 1']);
    });

    it('MIXED_LISTS says to reload', async () => {
      await failComplete(
        400,
        problem(400, 'VALIDATION_FAILED', {
          errors: [{ field: 'ids', code: 'MIXED_LISTS', message: 'x' }],
        }),
      );
      expect(el.querySelector('[role=alert]')?.textContent).toContain('Reload and try again');
    });

    it('TAG_LIMIT is explained and a generic failure offers Retry', async () => {
      await failComplete(
        400,
        problem(400, 'VALIDATION_FAILED', {
          errors: [{ field: 'tags', code: 'TAG_LIMIT', message: 'x' }],
        }),
      );
      expect(el.querySelector('[role=alert]')?.textContent).toContain('200 tags');
    });

    it('a 500 shows the generic message with Retry that resends', async () => {
      await failComplete(500, problem(500, 'INTERNAL_ERROR'));
      const alert = el.querySelector('[role=alert]')!;
      expect(alert.textContent).toContain("Couldn't update the tasks");
      Array.from(alert.querySelectorAll('button'))
        .find((b) => b.textContent?.trim() === 'Retry')!
        .click();
      http.expectOne('/api/tasks/bulk');
    });

    it('LIST_NOT_FOUND on a move refreshes the lists and keeps the selection', async () => {
      await open([task(3), task(2)]);
      await select(0, 1);
      await press('bulk-move');
      (el.querySelectorAll('[data-testid="bulk-move-choice"]')[1] as HTMLElement).click();
      http
        .expectOne('/api/tasks/bulk')
        .flush(problem(404, 'LIST_NOT_FOUND'), { status: 404, statusText: 'x' });
      await stable();
      http.expectOne('/api/lists').flush([MINE, HOME]);
      await stable();
      expect(el.querySelector('[role=alert]')?.textContent).toContain('list');
      expect(byId('bulk-count')!.textContent?.trim()).toBe('2 selected');
      expect(el.querySelector('[data-testid=list-not-found]')).toBeNull();
    });

    it('a failed Delete closes the dialog and keeps the selection', async () => {
      await open([task(3), task(2)]);
      await select(0, 1);
      await press('bulk-delete');
      await vi.waitFor(() => expect(byId('bulk-delete-confirm')).not.toBeNull());
      byId('bulk-delete-confirm')!.click();
      http
        .expectOne('/api/tasks/bulk')
        .flush(problem(404, 'NOT_FOUND', { failedIds: [2] }), { status: 404, statusText: 'x' });
      await stable();
      expect(byId('bulk-delete-dialog')).toBeNull();
      expect(el.querySelector('[role=alert]')?.textContent).toContain(
        '1 task is no longer available',
      );
      expect(byId('bulk-count')!.textContent?.trim()).toBe('2 selected');
    });

    it('a failed Undo reloads the list like the single undo', async () => {
      await open([task(3)]);
      await select(0);
      await press('bulk-complete');
      http.expectOne('/api/tasks/bulk').flush({ count: 1, tasks: [res(done(task(3)))] });
      await stable();
      await shown();
      toasts.runAction();
      const req = await sent('/api/tasks/bulk-undo-complete');
      req.flush(problem(404, 'NOT_FOUND', { failedIds: [3] }), { status: 404, statusText: 'x' });
      await stable();
      expect(toasts.current()?.message).toBe(
        "Couldn't undo: the task was deleted or you no longer have access",
      );
      tasksReq().flush(onePage([]));
      await stable();
    });
  });
});
