import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
  TestRequest,
} from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { User } from '../../../core/auth/auth.models';
import { AuthService } from '../../../core/auth/auth.service';
import { ToastService } from '../../../shared/toast/toast.service';
import { TaskList } from '../../lists/lists.models';
import { Task, TaskPage, TaskResult } from '../tasks.models';
import { TaskSortList } from '../task-sort-list/task-sort-list';
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
const done = (t: Task): Task => ({ ...t, status: 'DONE', done: true });
const page = (items: Task[], nextCursor: string | null = null, total = items.length): TaskPage => ({
  items,
  total,
  nextCursor,
});
const problem = (status: number, code: string, extra: object = {}) => ({
  type: 'about:blank',
  title: 'x',
  status,
  detail: 'x',
  code,
  ...extra,
});
const NO_CONTENT = { status: 204, statusText: 'No Content' };

describe('TasksPage My order', () => {
  let harness: RouterTestingHarness;
  let http: HttpTestingController;
  let toasts: ToastService;
  let el: HTMLElement;

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
  const handles = () => el.querySelectorAll('[data-testid="drag-handle"]');
  const alertText = () => el.querySelector('[role="alert"]')?.textContent?.trim() ?? null;
  const sortList = () => harness.fixture.debugElement.query(By.directive(TaskSortList));
  const rowButton = (id: number, testid: string) =>
    el.querySelector<HTMLButtonElement>(
      `app-task-row[data-task-id="${id}"] [data-testid="${testid}"]`,
    )!;
  const message = () => toasts.current()?.message ?? null;

  async function open(tasks: TaskPage, url = '/lists/8?sort=manual') {
    harness = await RouterTestingHarness.create();
    await harness.navigateByUrl(url);
    el = harness.routeNativeElement!;
    http.expectOne('/api/lists').flush([MINE, HOME, WORK]);
    tasksReq().flush(tasks);
    await stable();
  }

  /** A manual-order list whose chunk has loaded (handles are on screen). */
  async function openManual(items: Task[] = [task(3), task(2), task(1), done(task(7))]) {
    await open(page(items));
    await vi.waitFor(() => expect(handles().length).toBe(items.length));
  }

  describe('handles and hint', () => {
    it('sends sort=manual and shows a handle per row, no hint', async () => {
      harness = await RouterTestingHarness.create();
      await harness.navigateByUrl('/lists/8?sort=manual');
      el = harness.routeNativeElement!;
      http.expectOne('/api/lists').flush([MINE, HOME, WORK]);
      const req = tasksReq();
      expect(req.request.params.get('sort')).toBe('manual');
      req.flush(page([task(2), task(1)]));
      await stable();
      await vi.waitFor(() => expect(handles()).toHaveLength(2));
      expect(byId('drag-hint')).toBeNull();
      expect(
        Array.from(el.querySelectorAll<HTMLOptionElement>('#task-sort option')).some(
          (o) => o.value === 'manual' && o.selected,
        ),
      ).toBe(true);
    });

    it('with another sort there are no handles and the hint says how to get them', async () => {
      await open(page([task(2), task(1)]), '/lists/8');
      expect(handles()).toHaveLength(0);
      expect(byId('drag-hint')!.textContent?.trim()).toBe('Switch to My order to drag');
      expect(titles()).toEqual(['Task 2', 'Task 1']);
    });

    it('in My order with a tag, search or a non-All tab: no handles, another hint', async () => {
      for (const query of ['tag=home', 'q=milk', 'filter=active']) {
        await open(page([task(2), task(1)]), `/lists/8?sort=manual&${query}`);
        expect(handles()).toHaveLength(0);
        expect(byId('drag-hint')!.textContent?.trim()).toBe(
          'Show All with no tag or search to drag',
        );
        TestBed.resetTestingModule();
        TestBed.configureTestingModule({
          providers: [
            provideRouter([{ path: 'lists/:id', component: TasksPage }]),
            provideHttpClient(),
            provideHttpClientTesting(),
            { provide: AuthService, useValue: { currentUser: signal({ id: 1 } as User) } },
          ],
        });
        http = TestBed.inject(HttpTestingController);
        toasts = TestBed.inject(ToastService);
      }
    });

    it('no hint on an empty list', async () => {
      await open(page([]), '/lists/8');
      expect(byId('drag-hint')).toBeNull();
    });

    it('selection mode hides the handles', async () => {
      await openManual();
      byId('select-mode')!.click();
      await stable();
      expect(handles()).toHaveLength(0);
      expect(byId('drag-hint')).toBeNull();
      expect(el.querySelectorAll('[data-testid="task-select"]')).toHaveLength(4);
    });
  });

  describe('reordering', () => {
    it('Move down shows the new order at once and PUTs afterId; no toast', async () => {
      await openManual();
      rowButton(3, 'move-down').click();
      await stable();
      expect(titles()).toEqual(['Task 2', 'Task 3', 'Task 1', 'Task 7']);
      const put = http.expectOne('/api/tasks/3/position');
      expect(put.request.method).toBe('PUT');
      expect(put.request.body).toEqual({ afterId: 2 });
      put.flush(null, NO_CONTENT);
      await stable();
      expect(message()).toBeNull();
      expect(alertText()).toBeNull();
      expect(titles()).toEqual(['Task 2', 'Task 3', 'Task 1', 'Task 7']);
    });

    it('a drop on top of the done group sends afterId null for that task', async () => {
      await openManual([task(3), done(task(8)), done(task(7))]);
      sortList().componentInstance.reordered.emit({
        id: 7,
        afterId: null,
        items: [task(3), done(task(7)), done(task(8))],
      });
      await stable();
      expect(titles()).toEqual(['Task 3', 'Task 7', 'Task 8']);
      const put = http.expectOne('/api/tasks/7/position');
      expect(put.request.body).toEqual({ afterId: null });
      put.flush(null, NO_CONTENT);
    });

    it('while a save runs nothing else can be reordered', async () => {
      await openManual();
      rowButton(3, 'move-down').click();
      await stable();
      expect(rowButton(1, 'move-up').disabled).toBe(true);
      expect(sortList().componentInstance.disabled()).toBe(true);
      http.expectOne('/api/tasks/3/position').flush(null, NO_CONTENT);
      await stable();
      expect(rowButton(1, 'move-up').disabled).toBe(false);
    });

    it('a failed save snaps the row back and says "Couldn\'t save the new order"', async () => {
      await openManual();
      rowButton(2, 'move-up').click();
      await stable();
      expect(titles()).toEqual(['Task 2', 'Task 3', 'Task 1', 'Task 7']);
      http
        .expectOne('/api/tasks/2/position')
        .flush(problem(500, 'INTERNAL_ERROR'), { status: 500, statusText: 'Server Error' });
      await stable();
      expect(titles()).toEqual(['Task 3', 'Task 2', 'Task 1', 'Task 7']);
      expect(alertText()).toBe("Couldn't save the new order");
      expect(sortList().componentInstance.disabled()).toBe(false);
    });

    it('a 400 (other group) also snaps back with the same message', async () => {
      await openManual();
      rowButton(3, 'move-down').click();
      await stable();
      http.expectOne('/api/tasks/3/position').flush(
        problem(400, 'VALIDATION_FAILED', {
          errors: [{ field: 'afterId', code: 'OTHER_GROUP', message: 'x' }],
        }),
        { status: 400, statusText: 'Bad Request' },
      );
      await stable();
      expect(titles()).toEqual(['Task 3', 'Task 2', 'Task 1', 'Task 7']);
      expect(alertText()).toBe("Couldn't save the new order");
    });

    it('a 404 snaps back, shows the message and reloads the list', async () => {
      await openManual();
      rowButton(3, 'move-down').click();
      await stable();
      http
        .expectOne('/api/tasks/3/position')
        .flush(problem(404, 'NOT_FOUND'), { status: 404, statusText: 'Not Found' });
      await stable();
      const reload = tasksReq();
      expect(reload.request.params.get('sort')).toBe('manual');
      expect(reload.request.params.has('cursor')).toBe(false);
      reload.flush(page([task(2), task(1)]));
      await stable();
      expect(alertText()).toBe("Couldn't save the new order");
      await vi.waitFor(() => expect(titles()).toEqual(['Task 2', 'Task 1']));
    });

    it('a snap-back moves only that task: a tick that landed meanwhile stays', async () => {
      await openManual();
      rowButton(3, 'move-down').click();
      await stable();
      const tick = el.querySelector<HTMLInputElement>(
        'app-task-row[data-task-id="1"] input[type=checkbox]',
      )!;
      tick.click();
      await stable();
      http.expectOne('/api/tasks/1').flush({ ...done(task(1)), nextTask: null } as TaskResult);
      await stable();
      toasts.dismiss();
      http
        .expectOne('/api/tasks/3/position')
        .flush(problem(500, 'X'), { status: 500, statusText: 'Server Error' });
      await stable();
      // Task 3 is back above Task 2; the tick on Task 1 (now in the done group) stays.
      expect(titles()).toEqual(['Task 3', 'Task 2', 'Task 1', 'Task 7']);
      expect(
        el.querySelector<HTMLInputElement>('app-task-row[data-task-id="1"] input[type=checkbox]')!
          .checked,
      ).toBe(true);
      expect(alertText()).toBe("Couldn't save the new order");
    });

    it('nothing is sent when the task ends where it started', async () => {
      await openManual();
      sortList().componentInstance.reordered.emit({
        id: 3,
        afterId: null,
        items: [task(3), task(2), task(1), done(task(7))],
      });
      await stable();
      http.expectNone('/api/tasks/3/position');
    });
  });

  describe('Load more', () => {
    it('skips ids that are already loaded and keeps the dropped order', async () => {
      harness = await RouterTestingHarness.create();
      await harness.navigateByUrl('/lists/8?sort=manual');
      el = harness.routeNativeElement!;
      http.expectOne('/api/lists').flush([MINE, HOME, WORK]);
      tasksReq().flush(page([task(4), task(3), task(2)], 'c1', 5));
      await stable();
      await vi.waitFor(() => expect(handles()).toHaveLength(3));
      // Drop task 4 after the last loaded row.
      for (let i = 0; i < 2; i++) {
        rowButton(4, 'move-down').click();
        await stable();
        http.expectOne('/api/tasks/4/position').flush(null, NO_CONTENT);
        await stable();
      }
      expect(titles()).toEqual(['Task 3', 'Task 2', 'Task 4']);
      el.querySelector<HTMLButtonElement>('.tasks-footer__more')!.click();
      const more = tasksReq();
      expect(more.request.params.get('cursor')).toBe('c1');
      expect(more.request.params.get('sort')).toBe('manual');
      more.flush(page([task(4), task(1)], null, 5));
      await stable();
      await vi.waitFor(() => expect(titles()).toEqual(['Task 3', 'Task 2', 'Task 4', 'Task 1']));
    });

    it('INVALID_CURSOR reloads the first page', async () => {
      harness = await RouterTestingHarness.create();
      await harness.navigateByUrl('/lists/8?sort=manual');
      el = harness.routeNativeElement!;
      http.expectOne('/api/lists').flush([MINE, HOME, WORK]);
      tasksReq().flush(page([task(4)], 'old', 5));
      await stable();
      el.querySelector<HTMLButtonElement>('.tasks-footer__more')!.click();
      tasksReq().flush(
        problem(400, 'VALIDATION_FAILED', {
          errors: [{ field: 'cursor', code: 'INVALID_CURSOR' }],
        }),
        { status: 400, statusText: 'Bad Request' },
      );
      await stable();
      const reload = tasksReq();
      expect(reload.request.params.has('cursor')).toBe(false);
      reload.flush(page([task(4)]));
    });
  });

  describe('drop on a list in the sidebar', () => {
    const emit = (t: Task, listId: number) =>
      sortList().componentInstance.droppedOnList.emit({ task: t, listId });

    it('PATCHes listId and the tags, removes the row and offers Undo "Moved to Work"', async () => {
      const tagged = task(2, { tags: ['a', 'b'] });
      await openManual([task(3), tagged, task(1)]);
      emit(tagged, 9);
      const patch = http.expectOne('/api/tasks/2');
      expect(patch.request.method).toBe('PATCH');
      expect(patch.request.body).toEqual({ listId: 9, tags: ['a', 'b'] });
      patch.flush({ ...tagged, listId: 9, nextTask: null } as TaskResult);
      await stable();
      expect(titles()).toEqual(['Task 3', 'Task 1']);
      await vi.waitFor(() => expect(message()).toBe('Moved to Work'));
      expect(toasts.current()?.action?.label).toBe('Undo');
    });

    it('dropping on the own list sends nothing', async () => {
      const t = task(2);
      await openManual([task(3), t, task(1)]);
      emit(t, 8);
      http.expectNone('/api/tasks/2');
    });

    it('the 200-tag limit names the target list', async () => {
      const t = task(2, { tags: ['a'] });
      await openManual([task(3), t]);
      emit(t, 9);
      http
        .expectOne('/api/tasks/2')
        .flush(
          problem(400, 'VALIDATION_FAILED', { errors: [{ field: 'tags', code: 'TAG_LIMIT' }] }),
          { status: 400, statusText: 'Bad Request' },
        );
      await stable();
      expect(alertText()).toBe('Can\'t move to "Work": a list can have at most 200 tags');
      expect(titles()).toEqual(['Task 3', 'Task 2']);
    });

    it('a target list that is gone says so and refreshes the lists', async () => {
      const t = task(2);
      await openManual([task(3), t]);
      emit(t, 9);
      http
        .expectOne('/api/tasks/2')
        .flush(problem(404, 'LIST_NOT_FOUND'), { status: 404, statusText: 'Not Found' });
      await stable();
      expect(alertText()).toBe('This list no longer exists. Choose another list.');
      http.expectOne('/api/lists').flush([MINE, HOME]);
    });

    it('a task that is gone (404) says so and reloads', async () => {
      const t = task(2);
      await openManual([task(3), t]);
      emit(t, 9);
      http
        .expectOne('/api/tasks/2')
        .flush(problem(404, 'NOT_FOUND'), { status: 404, statusText: 'Not Found' });
      await stable();
      tasksReq().flush(page([task(3)]));
      await stable();
      expect(alertText()).toContain('no longer');
    });

    it('any other failure keeps the row and shows an error', async () => {
      const t = task(2);
      await openManual([task(3), t]);
      emit(t, 9);
      http
        .expectOne('/api/tasks/2')
        .flush(problem(500, 'X'), { status: 500, statusText: 'Server Error' });
      await stable();
      expect(alertText()).toBe("Couldn't save the task. Please try again.");
      expect(titles()).toEqual(['Task 3', 'Task 2']);
    });
  });

  describe('sidebar markers', () => {
    it('every list has a drop marker; the Trash entry does not', async () => {
      await open(page([task(1)]), '/lists/8');
      const marked = Array.from(el.querySelectorAll('[data-drop-list-id]')).map((n) =>
        n.getAttribute('data-drop-list-id'),
      );
      expect(marked).toEqual(['3', '8', '9']);
      expect(el.querySelector('.list-picker__trash')?.hasAttribute('data-drop-list-id')).toBe(
        false,
      );
    });
  });
});
