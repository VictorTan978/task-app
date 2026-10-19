import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
  TestRequest,
} from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { User } from '../../../core/auth/auth.models';
import { AuthService } from '../../../core/auth/auth.service';
import { TaskList } from '../../lists/lists.models';
import { Task, TaskPage } from '../tasks.models';
import { TasksPage } from './tasks-page';

const MINE: TaskList = { id: 3, name: 'My tasks', personal: true };
const HOME: TaskList = { id: 8, name: 'Home', personal: false };
const WORK: TaskList = { id: 9, name: 'Work', personal: false };

function task(id: number, listId: number, overrides: Partial<Task> = {}): Task {
  return {
    id,
    listId,
    title: `Task ${id}`,
    description: null,
    priority: 'NONE',
    status: 'TODO',
    tags: [],
    done: false,
    dueDate: null,
    createdAt: `2026-10-0${id}T09:00:00Z`,
    updatedAt: `2026-10-0${id}T09:00:00Z`,
    ...overrides,
  };
}

function problem(status: number, code: string) {
  return { type: 'about:blank', title: 'x', status, detail: 'x', code };
}

const onePage = (
  items: Task[],
  nextCursor: string | null = null,
  total = items.length,
): TaskPage => ({
  items,
  total,
  nextCursor,
});

describe('TasksPage with lists', () => {
  let harness: RouterTestingHarness;
  let http: HttpTestingController;
  let el: HTMLElement;
  let router: Router;

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
    router = TestBed.inject(Router);
  });

  afterEach(() => http.verify());

  const stable = () => harness.fixture.whenStable();
  const text = () => el.textContent ?? '';
  const titles = () =>
    Array.from(el.querySelectorAll('.task-row__title')).map((b) => b.textContent?.trim());
  const heading = () => el.querySelector('h1')?.textContent?.trim();

  /** The pending GET /api/tasks with this `list` (undefined = absent) and optional extras. */
  function tasksReq(list: string | undefined, extra: Record<string, string> = {}): TestRequest {
    return http.expectOne(
      (r) =>
        r.method === 'GET' &&
        r.url === '/api/tasks' &&
        (r.params.get('list') ?? undefined) === list &&
        Object.entries(extra).every(([k, v]) => r.params.get(k) === v) &&
        r.params.keys().length === (list === undefined ? 0 : 1) + Object.keys(extra).length,
    );
  }

  async function open(url: string, lists: TaskList[] = [MINE, HOME, WORK]) {
    harness = await RouterTestingHarness.create();
    await harness.navigateByUrl(url);
    el = harness.routeNativeElement!;
    http.expectOne('/api/lists').flush(lists);
    await stable();
  }

  /** `/tasks` and `/lists/:id` are separate routes: the page may be a new instance. */
  async function go(url: string) {
    await harness.navigateByUrl(url);
    el = harness.routeNativeElement!;
    await stable();
  }

  it('/tasks is "My tasks": no list param, heading from the store, picker highlights it', async () => {
    await open('/tasks');
    tasksReq(undefined).flush(onePage([task(1, 3)]));
    await stable();
    expect(heading()).toBe('My tasks');
    expect(titles()).toEqual(['Task 1']);
    const active = el.querySelector('.list-picker__link[aria-current=page]');
    expect(active?.textContent?.trim()).toBe('My tasks');
    expect(active?.getAttribute('href')).toBe('/tasks');
  });

  it('old /tasks?filter=&tag=&q=&sort= links still work (no list param)', async () => {
    await open('/tasks?filter=done&tag=Work&q=milk&sort=title');
    tasksReq(undefined, { filter: 'done', tag: 'Work', q: 'milk', sort: 'title' }).flush(
      onePage([]),
    );
    await stable();
    expect(text()).toContain('No tasks match "milk"');
  });

  it("/lists/8 sends list=8 and shows that list's name", async () => {
    await open('/lists/8');
    tasksReq('8').flush(onePage([task(2, 8)]));
    await stable();
    expect(heading()).toBe('Home');
    expect(el.querySelector('.list-picker__link[aria-current=page]')?.textContent?.trim()).toBe(
      'Home',
    );
  });

  it.each(['/lists/abc', '/lists/0', '/lists/-3', '/lists/1.5'])(
    '%s shows "List not found" without a tasks request',
    async (url) => {
      await open(url);
      http.expectNone('/api/tasks');
      expect(heading()).toBe('List not found');
      const back = el.querySelector<HTMLAnchorElement>('[data-testid=list-not-found] a');
      expect(back?.getAttribute('href')).toBe('/tasks');
      expect(back?.textContent).toContain('My tasks');
    },
  );

  it('API 404 LIST_NOT_FOUND shows "List not found" and refetches the lists', async () => {
    await open('/lists/999999');
    tasksReq('999999').flush(problem(404, 'LIST_NOT_FOUND'), {
      status: 404,
      statusText: 'Not Found',
    });
    await stable();
    expect(heading()).toBe('List not found');
    expect(el.querySelector('[role=alert]')).toBeNull();
    http.expectOne('/api/lists').flush([MINE, HOME, WORK]);
    await stable();
    // The link back still works.
    await go('/tasks');
    tasksReq(undefined).flush(onePage([]));
    await stable();
    expect(heading()).toBe('My tasks');
  });

  it('a list unknown to the picker but readable refreshes the store once', async () => {
    await open('/lists/12', [MINE]);
    tasksReq('12').flush(onePage([]));
    await stable();
    http.expectOne('/api/lists').flush([MINE, { id: 12, name: 'New', personal: false }]);
    await stable();
    expect(heading()).toBe('New');
  });

  it('switching lists resets tab, tag, search and sort, and starts from page 1', async () => {
    await open('/lists/8?filter=done&q=x&sort=title');
    tasksReq('8', { filter: 'done', q: 'x', sort: 'title' }).flush(
      onePage([task(2, 8, { status: 'DONE', done: true })], 'c1', 60),
    );
    await stable();
    expect(el.querySelector<HTMLInputElement>('#task-search')?.value).toBe('x');
    // Picker link to Work carries no query params.
    const work = Array.from(el.querySelectorAll<HTMLAnchorElement>('.list-picker__link')).find(
      (a) => a.textContent?.trim() === 'Work',
    )!;
    expect(work.getAttribute('href')).toBe('/lists/9');
    work.click();
    await stable();
    expect(router.url).toBe('/lists/9');
    tasksReq('9').flush(onePage([task(4, 9)]));
    await stable();
    expect(heading()).toBe('Work');
    expect(titles()).toEqual(['Task 4']);
    expect(el.querySelector<HTMLInputElement>('#task-search')?.value).toBe('');
    expect(el.querySelector<HTMLSelectElement>('#task-sort')?.value).toBe('newest');
    expect(text()).toContain('1 task');
  });

  it("switching lists cancels the previous list's load in flight", async () => {
    await open('/lists/8');
    const first = tasksReq('8');
    await go('/lists/9');
    expect(first.cancelled).toBe(true);
    tasksReq('9').flush(onePage([task(4, 9)]));
    await stable();
    expect(titles()).toEqual(['Task 4']);
  });

  it('switching lists closes an open edit dialog', async () => {
    await open('/lists/8');
    tasksReq('8').flush(onePage([task(2, 8)]));
    await stable();
    el.querySelector<HTMLButtonElement>('.task-row__title')!.click();
    await stable();
    expect(el.ownerDocument.querySelector('dialog')).not.toBeNull();
    http.expectOne((r) => r.url === '/api/tags' && r.params.get('list') === '8').flush([]);
    await go('/lists/9');
    tasksReq('9').flush(onePage([]));
    await stable();
    expect(harness.fixture.nativeElement.querySelector('dialog')).toBeNull();
  });

  it('Load more inside a list sends the list and the cursor', async () => {
    await open('/lists/8');
    tasksReq('8').flush(onePage([task(2, 8)], 'c1', 2));
    await stable();
    Array.from(el.querySelectorAll('button'))
      .find((b) => b.textContent?.trim() === 'Load more')!
      .click();
    tasksReq('8', { cursor: 'c1' }).flush(onePage([task(1, 8)], null, 2));
    await stable();
    expect(titles()).toEqual(['Task 2', 'Task 1']);
  });

  it('quick-add inside a list sends its listId; on /tasks it sends none', async () => {
    await open('/lists/8');
    tasksReq('8').flush(onePage([]));
    await stable();
    const input = el.querySelector<HTMLInputElement>('#task-add-title')!;
    input.value = 'Milk';
    input.dispatchEvent(new Event('input'));
    await stable();
    el.querySelector<HTMLFormElement>('.add-form')!.dispatchEvent(new Event('submit'));
    const req = http.expectOne({ method: 'POST', url: '/api/tasks' });
    expect(req.request.body).toEqual({ title: 'Milk', listId: 8 });
    req.flush(task(5, 8, { title: 'Milk' }));
    await stable();
    expect(titles()).toEqual(['Milk']);

    await go('/tasks');
    tasksReq(undefined).flush(onePage([]));
    await stable();
    const input2 = el.querySelector<HTMLInputElement>('#task-add-title')!;
    input2.value = 'Bread';
    input2.dispatchEvent(new Event('input'));
    await stable();
    el.querySelector<HTMLFormElement>('.add-form')!.dispatchEvent(new Event('submit'));
    const req2 = http.expectOne({ method: 'POST', url: '/api/tasks' });
    expect(req2.request.body).toEqual({ title: 'Bread' });
    req2.flush(task(6, 3, { title: 'Bread' }));
    await stable();
  });

  it('quick-add into a list deleted meanwhile shows "List not found"', async () => {
    await open('/lists/8');
    tasksReq('8').flush(onePage([]));
    await stable();
    const input = el.querySelector<HTMLInputElement>('#task-add-title')!;
    input.value = 'Milk';
    input.dispatchEvent(new Event('input'));
    await stable();
    el.querySelector<HTMLFormElement>('.add-form')!.dispatchEvent(new Event('submit'));
    http
      .expectOne({ method: 'POST', url: '/api/tasks' })
      .flush(problem(404, 'LIST_NOT_FOUND'), { status: 404, statusText: 'Not Found' });
    await stable();
    expect(heading()).toBe('List not found');
    http.expectOne('/api/lists').flush([MINE, WORK]);
  });

  it('a move removes the row and lowers the total', async () => {
    await open('/lists/8');
    tasksReq('8').flush(onePage([task(2, 8), task(1, 8)], 'c1', 60));
    await stable();
    expect(text()).toContain('Showing 2 of 60');
    el.querySelector<HTMLButtonElement>('.task-row__title')!.click();
    await stable();
    http.expectOne((r) => r.url === '/api/tags' && r.params.get('list') === '8').flush([]);
    await stable();
    const select = el.querySelector<HTMLSelectElement>('#task-edit-list')!;
    select.value = '9';
    select.dispatchEvent(new Event('change'));
    el.querySelector<HTMLFormElement>('dialog form')!.dispatchEvent(new Event('submit'));
    const req = http.expectOne({ method: 'PUT', url: '/api/tasks/2' });
    expect(req.request.body.listId).toBe(9);
    req.flush(task(2, 9));
    await stable();
    expect(titles()).toEqual(['Task 1']);
    expect(text()).toContain('Showing 1 of 59');
    expect(el.querySelector('dialog')).toBeNull();
  });
});
