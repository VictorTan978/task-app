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

const done = (t: Task, overrides: Partial<Task> = {}): Task => ({
  ...t,
  status: 'DONE',
  done: true,
  ...overrides,
});

const onePage = (items: Task[]): TaskPage => ({ items, total: items.length, nextCursor: null });
const result = (t: Task, nextTask: Task | null = null): TaskResult => ({ ...t, nextTask });
const problem404 = { type: 'about:blank', title: 'x', status: 404, detail: 'x', code: 'NOT_FOUND' };
const NO_CONTENT = { status: 204, statusText: 'No Content' };

describe('TasksPage undo', () => {
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
  const titles = () =>
    Array.from(el.querySelectorAll('.task-row__title')).map((b) => b.textContent?.trim());
  const message = () => toasts.current()?.message ?? null;

  const tasksReq = (): TestRequest =>
    http.expectOne((r) => r.method === 'GET' && r.url === '/api/tasks');

  async function open(tasks: Task[], url = '/lists/8') {
    harness = await RouterTestingHarness.create();
    await harness.navigateByUrl(url);
    el = harness.routeNativeElement!;
    http.expectOne('/api/lists').flush([MINE, HOME, WORK]);
    tasksReq().flush(onePage(tasks));
    await stable();
  }

  const row = (title: string) =>
    Array.from(el.querySelectorAll('li')).find((li) =>
      li.querySelector('.task-row__title')?.textContent?.includes(title),
    )!;

  /** The undo code is loaded on first use, so toasts and requests follow a moment later. */
  const shown = () => vi.waitFor(() => expect(toasts.current()).not.toBeNull());
  const sent = (match: Parameters<HttpTestingController['expectOne']>[0]) =>
    vi.waitFor(() => http.expectOne(match));

  async function tick(title: string, response: TaskResult, expectedBody: unknown) {
    row(title).querySelector<HTMLInputElement>('input[type=checkbox]')!.click();
    await stable();
    const req = http.expectOne({ method: 'PATCH', url: `/api/tasks/${response.id}` });
    expect(req.request.body).toEqual(expectedBody);
    req.flush(response);
    await stable();
    await shown();
  }

  it('delete: confirm, then a toast with Undo; Undo restores it from the trash', async () => {
    await open([task(2), task(1)]);
    Array.from(row('Task 2').querySelectorAll('button'))
      .find((b) => b.textContent?.trim() === 'Delete')!
      .click();
    await stable();
    el.querySelector<HTMLButtonElement>('dialog .btn--danger')!.click();
    http.expectOne({ method: 'DELETE', url: '/api/tasks/2' }).flush(null, NO_CONTENT);
    await stable();
    await shown();
    expect(titles()).toEqual(['Task 1']);
    expect(message()).toBe('Task moved to trash');
    expect(toasts.current()?.action?.label).toBe('Undo');

    toasts.runAction();
    (await sent({ method: 'POST', url: '/api/trash/2/restore' })).flush(task(2));
    await stable();
    expect(titles()).toEqual(['Task 2', 'Task 1']);
    expect(message()).toBe('Undone');
    expect(toasts.current()?.action).toBeNull();
    expect(el.textContent).toContain('2 tasks');
  });

  it('tick: toast "Task completed"; Undo sends PATCH with only the old status', async () => {
    await open([task(2, { status: 'IN_PROGRESS' })]);
    await tick('Task 2', result(done(task(2))), { status: 'DONE' });
    expect(message()).toBe('Task completed');

    toasts.runAction();
    const req = await sent({ method: 'PATCH', url: '/api/tasks/2' });
    expect(req.request.body).toEqual({ status: 'IN_PROGRESS' });
    req.flush(result(task(2, { status: 'IN_PROGRESS' })));
    await stable();
    expect(message()).toBe('Undone');
    expect(row('Task 2').querySelector<HTMLInputElement>('input[type=checkbox]')!.checked).toBe(
      false,
    );
  });

  it('untick: toast "Task reopened"; Undo sends PATCH status DONE', async () => {
    await open([done(task(2))]);
    await tick('Task 2', result(task(2)), { status: 'TODO' });
    expect(message()).toBe('Task reopened');
    toasts.runAction();
    const req = await sent({ method: 'PATCH', url: '/api/tasks/2' });
    expect(req.request.body).toEqual({ status: 'DONE' });
    req.flush(result(done(task(2))));
    await stable();
    expect(message()).toBe('Undone');
  });

  describe('completing a repeating task', () => {
    const RULE = { frequency: 'DAILY' as const, weekdays: [] };
    const water = task(5, { title: 'Water', dueDate: '2999-01-01', repeat: RULE });
    const copy = task(6, { title: 'Water', dueDate: '2999-01-02', repeat: RULE });

    async function completeIt() {
      await open([water]);
      await tick('Water', result(done(water, { repeat: null }), copy), { status: 'DONE' });
      expect(titles()).toHaveLength(2);
    }

    it('Undo uses undo-complete with the old status; DELETED removes the copy', async () => {
      await completeIt();
      toasts.runAction();
      const req = await sent({ method: 'POST', url: '/api/tasks/5/undo-complete' });
      expect(req.request.body).toEqual({ nextTaskId: 6, status: 'TODO' });
      req.flush({ ...water, nextTaskOutcome: 'DELETED' });
      await stable();
      expect(titles()).toEqual(['Water']);
      expect(el.querySelectorAll('[data-testid=task-repeat]').length).toBe(1);
      expect(message()).toBe('Undone');
      expect(el.querySelector('[data-testid=task-next-scheduled]')).toBeNull();
    });

    it('KEPT keeps the copy and says so', async () => {
      await completeIt();
      toasts.runAction();
      (await sent('/api/tasks/5/undo-complete')).flush({
        ...water,
        repeat: null,
        nextTaskOutcome: 'KEPT',
      });
      await stable();
      expect(titles()).toHaveLength(2);
      expect(message()).toBe('Undone: the next copy was already changed, so it was kept');
    });

    it('GONE says Undone and drops the copy from the view', async () => {
      await completeIt();
      toasts.runAction();
      (await sent('/api/tasks/5/undo-complete')).flush({
        ...water,
        repeat: null,
        nextTaskOutcome: 'GONE',
      });
      await stable();
      expect(titles()).toHaveLength(1);
      expect(message()).toBe('Undone');
    });
  });

  describe('edit dialog', () => {
    async function openEdit(title: string) {
      row(title).querySelector<HTMLButtonElement>('.task-row__title')!.click();
      await stable();
      http.expectOne((r) => r.url === '/api/tags').flush([]);
      await stable();
    }

    function save() {
      el.querySelector<HTMLFormElement>('dialog form')!.dispatchEvent(new Event('submit'));
    }

    it('toasts "Task updated"; Undo patches only the changed fields back', async () => {
      await open([task(2, { description: 'Old' })]);
      await openEdit('Task 2');
      const title = el.querySelector<HTMLInputElement>('#task-edit-title')!;
      title.value = 'Renamed';
      title.dispatchEvent(new Event('input'));
      const description = el.querySelector<HTMLTextAreaElement>('#task-edit-description')!;
      description.value = '';
      description.dispatchEvent(new Event('input'));
      save();
      http
        .expectOne({ method: 'PUT', url: '/api/tasks/2' })
        .flush(result(task(2, { title: 'Renamed', description: null })));
      await stable();
      await shown();
      expect(message()).toBe('Task updated');

      toasts.runAction();
      const req = await sent({ method: 'PATCH', url: '/api/tasks/2' });
      expect(req.request.body).toEqual({ title: 'Task 2', description: 'Old' });
      req.flush(result(task(2, { description: 'Old' })));
      await stable();
      expect(titles()).toEqual(['Task 2']);
      expect(message()).toBe('Undone');
    });

    it('a save that changes nothing shows no toast', async () => {
      await open([task(2)]);
      await openEdit('Task 2');
      save();
      http.expectOne({ method: 'PUT', url: '/api/tasks/2' }).flush(result(task(2)));
      await stable();
      // The undo code is loaded by now; let the page find that there is nothing to undo.
      await import('../undo.logic');
      await Promise.resolve();
      await Promise.resolve();
      expect(toasts.current()).toBeNull();
    });

    it('a move toasts "Moved to Work"; Undo moves it back with the old tags', async () => {
      await open([task(2, { tags: ['Trip'] }), task(1)]);
      await openEdit('Task 2');
      const select = el.querySelector<HTMLSelectElement>('#task-edit-list')!;
      select.value = '9';
      select.dispatchEvent(new Event('change'));
      save();
      http
        .expectOne({ method: 'PUT', url: '/api/tasks/2' })
        .flush(result(task(2, { listId: 9, tags: ['trip'] })));
      await stable();
      await shown();
      expect(titles()).toEqual(['Task 1']);
      expect(message()).toBe('Moved to Work');

      toasts.runAction();
      const req = await sent({ method: 'PATCH', url: '/api/tasks/2' });
      expect(req.request.body).toEqual({ listId: 8, tags: ['Trip'] });
      req.flush(result(task(2, { tags: ['Trip'] })));
      await stable();
      expect(titles()).toEqual(['Task 2', 'Task 1']);
      expect(message()).toBe('Undone');
    });
  });

  describe('when the undo fails', () => {
    async function deleteTask2() {
      await open([task(2), task(1)]);
      Array.from(row('Task 2').querySelectorAll('button'))
        .find((b) => b.textContent?.trim() === 'Delete')!
        .click();
      await stable();
      el.querySelector<HTMLButtonElement>('dialog .btn--danger')!.click();
      http.expectOne({ method: 'DELETE', url: '/api/tasks/2' }).flush(null, NO_CONTENT);
      await stable();
      await shown();
    }

    it('404 says the task was deleted or access is gone, offers no retry and reloads the list', async () => {
      await deleteTask2();
      toasts.runAction();
      (await sent('/api/trash/2/restore')).flush(problem404, {
        status: 404,
        statusText: 'Not Found',
      });
      await stable();
      expect(message()).toBe("Couldn't undo: the task was deleted or you no longer have access");
      expect(toasts.current()?.action).toBeNull();
      tasksReq().flush(onePage([task(1)]));
      await stable();
      expect(titles()).toEqual(['Task 1']);
      // Not "This task no longer exists" from the usual 404 handling.
      expect(el.querySelector('.tasks-page > [role=alert]')).toBeNull();
    });

    it('another error says it could not undo and reloads the list', async () => {
      await deleteTask2();
      toasts.runAction();
      (await sent('/api/trash/2/restore')).flush(null, { status: 500, statusText: 'Error' });
      await stable();
      expect(message()).toBe("Couldn't undo. Please try again.");
      tasksReq().flush(onePage([task(2), task(1)]));
      await stable();
      expect(titles()).toEqual(['Task 2', 'Task 1']);
    });
  });

  it('only the latest action can be undone: a new action replaces the toast', async () => {
    await open([task(2), task(1)]);
    await tick('Task 2', result(done(task(2))), { status: 'DONE' });
    await tick('Task 1', result(done(task(1))), { status: 'DONE' });
    toasts.runAction();
    const req = await sent({ method: 'PATCH', url: '/api/tasks/1' });
    expect(req.request.body).toEqual({ status: 'TODO' });
    req.flush(result(task(1)));
    await stable();
    expect(message()).toBe('Undone');
  });

  it('the toast closes when another list opens', async () => {
    await open([task(2)]);
    await tick('Task 2', result(done(task(2))), { status: 'DONE' });
    expect(toasts.current()).not.toBeNull();
    await harness.navigateByUrl('/lists/9');
    http.expectOne((r) => r.url === '/api/tasks').flush(onePage([]));
    await stable();
    expect(toasts.current()).toBeNull();
  });

  it('the toast stays when only the query changes (sort)', async () => {
    await open([task(2)]);
    await tick('Task 2', result(done(task(2))), { status: 'DONE' });
    await harness.navigateByUrl('/lists/8?sort=title');
    http
      .expectOne((r) => r.url === '/api/tasks' && r.params.get('sort') === 'title')
      .flush(onePage([done(task(2))]));
    await stable();
    expect(message()).toBe('Task completed');
  });
});
