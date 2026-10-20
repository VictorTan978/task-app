import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
  TestRequest,
} from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { Task, TaskPage, TaskStatus } from '../tasks.models';
import { TasksPage } from './tasks-page';

const PAST = '2000-01-01';
const FUTURE = '2999-12-31';

/** `done: true` alone means status DONE; `status` wins and `done` always mirrors it. */
function task(overrides: Partial<Task> & { id: number }): Task {
  const status = overrides.status ?? (overrides.done ? 'DONE' : 'TODO');
  return {
    listId: 3,
    addedBy: { displayName: 'Ana' },
    title: `Task ${overrides.id}`,
    description: null,
    priority: 'NONE',
    tags: [],
    dueDate: null,
    dueTime: null,
    myReminder: null,
    myReminderPassed: false,
    createdAt: `2026-10-0${overrides.id}T09:00:00Z`,
    updatedAt: `2026-10-0${overrides.id}T09:00:00Z`,
    ...overrides,
    status,
    done: status === 'DONE',
  };
}

const withStatus = (t: Task, status: TaskStatus): Task => ({
  ...t,
  status,
  done: status === 'DONE',
});

const MILK = task({ id: 3, title: 'Buy milk', dueDate: PAST });
const TAXES = task({ id: 2, title: 'File taxes', description: 'Old folder', dueDate: FUTURE });
const DONE = task({ id: 1, title: 'Walk dog', done: true, dueDate: PAST });

function problem(status: number, code: string, errors?: unknown[]) {
  return { type: 'about:blank', title: 'x', status, detail: 'x', instance: '/api', code, errors };
}

describe('TasksPage', () => {
  let harness: RouterTestingHarness;
  let http: HttpTestingController;
  let el: HTMLElement;
  let showModal: ReturnType<typeof vi.fn>;

  beforeAll(() => {
    // jsdom has no showModal.
    showModal = vi.fn(function (this: HTMLDialogElement) {
      this.setAttribute('open', '');
    });
    HTMLDialogElement.prototype.showModal = showModal as unknown as () => void;
  });

  beforeEach(() => {
    showModal.mockClear();
    TestBed.configureTestingModule({
      providers: [
        provideRouter([{ path: 'tasks', component: TasksPage }]),
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  async function open(url = '/tasks') {
    harness = await RouterTestingHarness.create();
    await harness.navigateByUrl(url);
    el = harness.routeNativeElement!;
  }

  const stable = () => harness.fixture.whenStable();
  const text = () => el.textContent ?? '';

  interface ListParams {
    q?: string;
    sort?: string;
    cursor?: string;
  }

  /** The pending GET /api/tasks whose params are exactly these (absent = undefined). */
  function listReq(filter?: string, tag?: string, extra: ListParams = {}): TestRequest {
    return http.expectOne(
      (r) =>
        r.method === 'GET' &&
        r.url === '/api/tasks' &&
        (r.params.get('filter') ?? undefined) === filter &&
        (r.params.get('tag') ?? undefined) === tag &&
        (r.params.get('q') ?? undefined) === extra.q &&
        (r.params.get('sort') ?? undefined) === extra.sort &&
        (r.params.get('cursor') ?? undefined) === extra.cursor,
    );
  }

  /** A whole list on one page (`total` = its length, no next page). */
  const onePage = (tasks: Task[]): TaskPage => ({
    items: tasks,
    total: tasks.length,
    nextCursor: null,
  });

  async function flushList(tasks: Task[], filter?: string, tag?: string, extra: ListParams = {}) {
    listReq(filter, tag, extra).flush(onePage(tasks));
    await stable();
  }

  async function openWith(tasks: Task[], url = '/tasks', filter?: string, tag?: string) {
    await open(url);
    await flushList(tasks, filter, tag);
  }

  function button(name: string, scope: ParentNode = el): HTMLButtonElement {
    const found = Array.from(scope.querySelectorAll('button')).find(
      (b) => b.textContent?.trim() === name,
    );
    if (!found) {
      throw new Error(`No button "${name}"`);
    }
    return found;
  }

  const link = (name: string) =>
    Array.from(el.querySelectorAll('a')).find((a) => a.textContent?.trim() === name)!;
  const titles = () =>
    Array.from(el.querySelectorAll('.task-row__title')).map((b) => b.textContent?.trim());
  const row = (title: string) =>
    Array.from(el.querySelectorAll('li')).find((li) =>
      li.querySelector('.task-row__title')?.textContent?.includes(title),
    )!;
  const checkbox = (title: string) =>
    row(title).querySelector<HTMLInputElement>('input[type=checkbox]')!;
  const banner = () => el.querySelector('.tasks-page > [role=alert]');
  const dialog = () => el.querySelector('dialog');

  function type(selector: string, value: string) {
    const input = el.querySelector<HTMLInputElement | HTMLTextAreaElement>(selector)!;
    input.value = value;
    input.dispatchEvent(new Event('input'));
  }

  function submit(form: HTMLFormElement) {
    form.dispatchEvent(new Event('submit'));
  }

  describe('loading, empty and list', () => {
    it('shows a loading indicator, then the empty text for All', async () => {
      await open();
      expect(el.querySelector('[role=status]')?.textContent).toContain('Loading tasks');
      await flushList([]);
      expect(el.querySelector('[role=status]')).toBeNull();
      expect(text()).toContain('No tasks yet — add your first one above');
      expect(el.querySelector('h1')?.textContent).toContain('My tasks');
    });

    it('shows the tasks in the order the server sent them', async () => {
      await openWith([MILK, TAXES, DONE]);
      expect(titles()).toEqual(['Buy milk', 'File taxes', 'Walk dog']);
      expect(el.querySelector('ul')?.getAttribute('aria-label')).toBe('Tasks');
    });

    it('shows due dates, and Overdue only for not-done past-due tasks', async () => {
      await openWith([MILK, TAXES, DONE]);
      expect(row('Buy milk').textContent).toContain(PAST);
      expect(row('Buy milk').textContent).toContain('Overdue');
      expect(row('File taxes').textContent).toContain(FUTURE);
      expect(row('File taxes').textContent).not.toContain('Overdue');
      expect(row('Walk dog').textContent).not.toContain('Overdue');
      expect(row('Walk dog').querySelector('.task-row--done')).not.toBeNull();
    });
  });

  describe('filter', () => {
    it.each([
      ['/tasks?filter=active', 'active', 'Nothing left to do', 'Active'],
      ['/tasks?filter=done', 'done', 'Nothing done yet', 'Done'],
    ])('%s loads with the filter and shows its empty text', async (url, f, empty, tab) => {
      await openWith([], url, f);
      expect(text()).toContain(empty);
      expect(link(tab).getAttribute('aria-current')).toBe('page');
    });

    it.each(['/tasks?filter=foo', '/tasks?filter=Done', '/tasks?filter='])(
      'normalises %s to All',
      async (url) => {
        await openWith([], url);
        expect(link('All').getAttribute('aria-current')).toBe('page');
        expect(text()).toContain('No tasks yet');
      },
    );

    it('tabs update the URL and reload with a loading indicator', async () => {
      await openWith([MILK, DONE]);
      const router = TestBed.inject(Router);

      link('Done').click();
      await stable();
      expect(router.url).toBe('/tasks?filter=done');
      expect(el.querySelector('[role=status]')).not.toBeNull();
      await flushList([DONE], 'done');
      expect(titles()).toEqual(['Walk dog']);

      link('Active').click();
      await stable();
      expect(router.url).toBe('/tasks?filter=active');
      await flushList([MILK], 'active');

      link('All').click();
      await stable();
      expect(router.url).toBe('/tasks');
      await flushList([MILK, DONE]);
      expect(titles()).toEqual(['Buy milk', 'Walk dog']);
    });

    it('a failed initial load shows a banner; Retry reloads', async () => {
      await open();
      listReq().flush(problem(500, 'INTERNAL_ERROR'), { status: 500, statusText: 'x' });
      await stable();
      expect(banner()?.textContent).toContain("Couldn't load your tasks");
      expect(text()).not.toContain('No tasks yet');

      button('Retry').click();
      await stable();
      await flushList([MILK]);
      expect(banner()).toBeNull();
      expect(titles()).toEqual(['Buy milk']);
    });

    it('a failed reload keeps the list as it was', async () => {
      await openWith([MILK, DONE]);
      link('Done').click();
      await stable();
      listReq('done').error(new ProgressEvent('error'));
      await stable();
      expect(banner()?.textContent).toContain("Couldn't load your tasks");
      expect(titles()).toEqual(['Buy milk', 'Walk dog']);

      button('Retry').click();
      await stable();
      await flushList([DONE], 'done');
      expect(titles()).toEqual(['Walk dog']);
    });
  });

  describe('In progress tab and tag filter', () => {
    const tagBar = () => el.querySelector<HTMLElement>('[data-testid=tag-filter-bar]');
    const clearTag = () =>
      el.querySelector<HTMLButtonElement>('button[aria-label="Clear tag filter"]')!;
    const tagLink = (title: string, name: string) =>
      Array.from(row(title).querySelectorAll<HTMLAnchorElement>('a.task-row__tag')).find(
        (a) => a.textContent?.trim() === name,
      )!;
    const WORK = task({ id: 4, title: 'Write report', status: 'IN_PROGRESS', tags: ['Work'] });

    it('the In progress tab loads ?filter=in_progress and has its own empty text', async () => {
      await openWith([MILK]);
      expect(Array.from(el.querySelectorAll('nav a')).map((a) => a.textContent?.trim())).toEqual([
        'All',
        'Active',
        'In progress',
        'Done',
      ]);
      link('In progress').click();
      await stable();
      expect(TestBed.inject(Router).url).toBe('/tasks?filter=in_progress');
      await flushList([], 'in_progress');
      expect(text()).toContain('Nothing in progress');
      expect(link('In progress').getAttribute('aria-current')).toBe('page');
    });

    it('a ticked In progress task becomes Done and leaves the In progress tab', async () => {
      await openWith([WORK], '/tasks?filter=in_progress', 'in_progress');
      checkbox('Write report').click();
      await stable();
      const req = http.expectOne({ method: 'PATCH', url: '/api/tasks/4' });
      expect(req.request.body).toEqual({ status: 'DONE' });
      req.flush(withStatus(WORK, 'DONE'));
      await stable();
      expect(titles()).toEqual([]);
      expect(text()).toContain('Nothing in progress');
    });

    it('loads ?tag with the filter, shows the stored spelling in the bar', async () => {
      await openWith([WORK], '/tasks?filter=active&tag=w%C3%B6rk', 'active', 'wörk');
      expect(tagBar()?.textContent?.replace(/\s+/g, ' ').trim()).toBe('Tag: Work ×');
      expect(titles()).toEqual(['Write report']);
    });

    it('falls back to the raw value and shows "No tasks with this tag" on every tab', async () => {
      await openWith([], '/tasks?tag=Nope', undefined, 'Nope');
      expect(tagBar()?.textContent).toContain('Nope');
      expect(text()).toContain('No tasks with this tag');
      expect(text()).not.toContain('No tasks yet');

      link('Done').click();
      await stable();
      expect(TestBed.inject(Router).url).toBe('/tasks?tag=Nope&filter=done');
      await flushList([], 'done', 'Nope');
      expect(text()).toContain('No tasks with this tag');
    });

    it.each(['/tasks?tag=', '/tasks?tag=%20%20'])('%s means no tag filter', async (url) => {
      await openWith([], url);
      expect(tagBar()).toBeNull();
      expect(text()).toContain('No tasks yet');
    });

    it('clicking a row tag filters by it, keeping the tab; × clears it, keeping the tab', async () => {
      const router = TestBed.inject(Router);
      await openWith([WORK, MILK], '/tasks?filter=active', 'active');
      expect(tagBar()).toBeNull();
      tagLink('Write report', 'Work').click();
      await stable();
      expect(router.url).toBe('/tasks?filter=active&tag=Work');
      await flushList([WORK], 'active', 'Work');
      expect(titles()).toEqual(['Write report']);
      expect(tagBar()?.textContent).toContain('Tag: Work');

      clearTag().click();
      await stable();
      expect(router.url).toBe('/tasks?filter=active');
      await flushList([WORK, MILK], 'active');
      expect(tagBar()).toBeNull();
      expect(titles()).toEqual(['Write report', 'Buy milk']);
    });

    it('a new (untagged) task does not appear in a tag view', async () => {
      await openWith([WORK], '/tasks?tag=Work', undefined, 'Work');
      type('#task-add-title', 'Untagged');
      await stable();
      submit(el.querySelector<HTMLFormElement>('app-task-add-form form')!);
      http
        .expectOne({ method: 'POST', url: '/api/tasks' })
        .flush(task({ id: 5, title: 'Untagged' }));
      await stable();
      expect(titles()).toEqual(['Write report']);
    });
  });

  describe('add', () => {
    const addForm = () => el.querySelector<HTMLFormElement>('app-task-add-form form')!;
    const addInput = () => el.querySelector<HTMLInputElement>('#task-add-title')!;

    it('has a labelled input and Add is disabled while blank', async () => {
      await openWith([]);
      expect(el.querySelector('label[for=task-add-title]')?.textContent).toContain('New task');
      expect(button('Add').disabled).toBe(true);
      type('#task-add-title', '   ');
      await stable();
      expect(button('Add').disabled).toBe(true);
      submit(addForm());
      await stable();
      http.expectNone('/api/tasks');
      expect(el.querySelector('.field-error')).toBeNull();
    });

    it('creates a trimmed task, disables Add while pending, clears the box', async () => {
      await openWith([TAXES]);
      type('#task-add-title', '  New thing ');
      await stable();
      submit(addForm());
      await stable();
      const req = http.expectOne((r) => r.method === 'POST' && r.url === '/api/tasks');
      expect(req.request.body).toEqual({ title: 'New thing' });
      expect(button('Add').disabled).toBe(true);
      expect(addInput().readOnly).toBe(true);

      req.flush(task({ id: 4, title: 'New thing' }), { status: 201, statusText: 'Created' });
      await stable();
      expect(titles()).toEqual(['New thing', 'File taxes']);
      expect(addInput().value).toBe('');
      expect(button('Add').disabled).toBe(true);
    });

    it('does not show a new (not done) task in the Done view', async () => {
      await openWith([DONE], '/tasks?filter=done', 'done');
      type('#task-add-title', 'X');
      await stable();
      submit(addForm());
      http.expectOne({ method: 'POST', url: '/api/tasks' }).flush(task({ id: 5, title: 'X' }));
      await stable();
      expect(titles()).toEqual(['Walk dog']);
    });

    it('rejects a title over 200 characters (after trimming) without a request', async () => {
      await openWith([]);
      type('#task-add-title', ' ' + 'x'.repeat(201) + ' ');
      await stable();
      button('Add').click();
      await stable();
      http.expectNone('/api/tasks');
      expect(el.querySelector('#task-add-title-error')?.textContent).toContain(
        'Title must be 200 characters or fewer',
      );
      expect(addInput().getAttribute('aria-invalid')).toBe('true');
    });

    it('shows server field errors under the title', async () => {
      await openWith([]);
      type('#task-add-title', 'abc');
      await stable();
      submit(addForm());
      http
        .expectOne({ method: 'POST', url: '/api/tasks' })
        .flush(
          problem(400, 'VALIDATION_FAILED', [
            { field: 'title', code: 'TOO_LONG', message: 'Title must be 200 characters or fewer' },
          ]),
          { status: 400, statusText: 'Bad Request' },
        );
      await stable();
      expect(el.querySelector('#task-add-title-error')?.textContent).toContain(
        'Title must be 200 characters or fewer',
      );
      expect(banner()).toBeNull();
    });

    it.each([
      ['network error', 0, 'x'],
      ['server error', 500, 'INTERNAL_ERROR'],
      ['malformed request', 400, 'MALFORMED_REQUEST'],
    ])('on %s shows a banner whose Retry re-sends; input is kept', async (_l, status, code) => {
      await openWith([TAXES]);
      type('#task-add-title', 'Retry me');
      await stable();
      submit(addForm());
      const req = http.expectOne({ method: 'POST', url: '/api/tasks' });
      if (status === 0) {
        req.error(new ProgressEvent('error'));
      } else {
        req.flush(problem(status, code), { status, statusText: 'x' });
      }
      await stable();
      expect(banner()?.textContent).toContain("Couldn't add the task");
      expect(addInput().value).toBe('Retry me');
      expect(titles()).toEqual(['File taxes']);

      button('Retry').click();
      await stable();
      const retry = http.expectOne({ method: 'POST', url: '/api/tasks' });
      expect(retry.request.body).toEqual({ title: 'Retry me' });
      expect(banner()).toBeNull();
      retry.flush(task({ id: 4, title: 'Retry me' }));
      await stable();
      expect(titles()).toEqual(['Retry me', 'File taxes']);
      expect(addInput().value).toBe('');
    });
  });

  describe('toggle', () => {
    it('PATCHes {done}, disables only the busy row, then moves the task', async () => {
      await openWith([MILK, TAXES, DONE]);
      checkbox('File taxes').click();
      await stable();
      const req = http.expectOne({ method: 'PATCH', url: '/api/tasks/2' });
      expect(req.request.body).toEqual({ status: 'DONE' });
      expect(checkbox('File taxes').disabled).toBe(true);
      expect(checkbox('Buy milk').disabled).toBe(false);
      expect(button('Edit', row('Buy milk')).disabled).toBe(false);
      // Not moved until the server confirms.
      expect(titles()).toEqual(['Buy milk', 'File taxes', 'Walk dog']);

      req.flush({ ...withStatus(TAXES, 'DONE'), updatedAt: '2026-10-09T00:00:00Z' });
      await stable();
      expect(titles()).toEqual(['Buy milk', 'File taxes', 'Walk dog']);
      expect(checkbox('File taxes').checked).toBe(true);
      expect(row('File taxes').querySelector('.task-row--done')).not.toBeNull();
      // Done group is newest first: File taxes (Oct 2) before Walk dog (Oct 1).

      checkbox('Walk dog').click();
      await stable();
      const untick = http.expectOne({ method: 'PATCH', url: '/api/tasks/1' });
      expect(untick.request.body).toEqual({ status: 'TODO' });
      untick.flush(withStatus(DONE, 'TODO'));
      await stable();
      expect(titles()).toEqual(['Buy milk', 'Walk dog', 'File taxes']);
    });

    it('removes a ticked task from the Active view once confirmed', async () => {
      await openWith([MILK, TAXES], '/tasks?filter=active', 'active');
      checkbox('Buy milk').click();
      await stable();
      http.expectOne({ method: 'PATCH', url: '/api/tasks/3' }).flush(withStatus(MILK, 'DONE'));
      await stable();
      expect(titles()).toEqual(['File taxes']);
    });

    it('on failure shows a banner, reverts the checkbox, and Retry re-sends', async () => {
      await openWith([MILK]);
      checkbox('Buy milk').click();
      await stable();
      http
        .expectOne({ method: 'PATCH', url: '/api/tasks/3' })
        .flush(problem(500, 'INTERNAL_ERROR'), { status: 500, statusText: 'x' });
      await stable();
      expect(banner()?.textContent).toContain("Couldn't update the task");
      expect(checkbox('Buy milk').checked).toBe(false);
      expect(checkbox('Buy milk').disabled).toBe(false);

      button('Retry').click();
      await stable();
      const retry = http.expectOne({ method: 'PATCH', url: '/api/tasks/3' });
      expect(retry.request.body).toEqual({ status: 'DONE' });
      retry.flush(withStatus(MILK, 'DONE'));
      await stable();
      expect(checkbox('Buy milk').checked).toBe(true);
    });

    it('on 404 shows "This task no longer exists" without Retry and reloads', async () => {
      await openWith([MILK, TAXES]);
      checkbox('Buy milk').click();
      await stable();
      http
        .expectOne({ method: 'PATCH', url: '/api/tasks/3' })
        .flush(problem(404, 'NOT_FOUND'), { status: 404, statusText: 'Not Found' });
      await stable();
      expect(banner()?.textContent).toContain('This task no longer exists');
      expect(banner()?.querySelector('button')).toBeNull();
      await flushList([TAXES]);
      expect(titles()).toEqual(['File taxes']);
      expect(banner()?.textContent).toContain('This task no longer exists');
    });
  });

  describe('edit dialog', () => {
    const editForm = () => el.querySelector<HTMLFormElement>('dialog form')!;
    const value = (sel: string) => el.querySelector<HTMLInputElement>(sel)!;

    async function openEdit(title: string, via: 'title' | 'button' = 'title', tags: string[] = []) {
      (via === 'title' ? button(title, row(title)) : button('Edit', row(title))).click();
      await stable();
      http.expectOne({ method: 'GET', url: '/api/tags' }).flush(tags.map((name) => ({ name })));
      await stable();
    }

    function select(selector: string, optionValue: string) {
      const sel = el.querySelector<HTMLSelectElement>(selector)!;
      sel.value = optionValue;
      sel.dispatchEvent(new Event('change'));
    }

    it.each(['title', 'button'] as const)('opens prefilled via the %s', async (via) => {
      await openWith([TAXES]);
      await openEdit('File taxes', via);
      expect(showModal).toHaveBeenCalledTimes(1);
      expect(dialog()?.getAttribute('aria-labelledby')).toBe('task-edit-heading');
      expect(value('#task-edit-title').value).toBe('File taxes');
      expect(value('#task-edit-description').value).toBe('Old folder');
      expect(value('#task-edit-due').value).toBe(FUTURE);
      expect(value('#task-edit-priority').value).toBe('NONE');
      expect(value('#task-edit-status').value).toBe('TODO');
      expect(el.querySelector('#task-edit-done')).toBeNull();
      const labels = Array.from(dialog()!.querySelectorAll('label')).map((l) => [
        l.textContent?.trim(),
        l.htmlFor,
      ]);
      expect(labels).toEqual([
        ['Title', 'task-edit-title'],
        ['Description', 'task-edit-description'],
        ['Priority', 'task-edit-priority'],
        ['Status', 'task-edit-status'],
        ['Due date', 'task-edit-due'],
        ['Due time', 'task-edit-due-time'],
        ['Reminder', 'task-edit-reminder'],
        ['Tags', 'task-edit-tags'],
        ['List', 'task-edit-list'],
      ]);
    });

    it('does not open when the checkbox is ticked', async () => {
      await openWith([TAXES]);
      checkbox('File taxes').click();
      await stable();
      expect(dialog()).toBeNull();
      http.expectOne({ method: 'PATCH', url: '/api/tasks/2' }).flush(TAXES);
    });

    it('Save PUTs title, description, dueDate, priority, status and tags (never done)', async () => {
      await openWith([MILK, TAXES]);
      await openEdit('File taxes');
      type('#task-edit-title', '  File taxes now ');
      type('#task-edit-description', '   ');
      button('Clear date').click();
      select('#task-edit-status', 'DONE');
      await stable();
      submit(editForm());
      await stable();

      const req = http.expectOne({ method: 'PUT', url: '/api/tasks/2' });
      expect(req.request.body).toEqual({
        title: 'File taxes now',
        description: null,
        dueDate: null,
        dueTime: null,
        priority: 'NONE',
        status: 'DONE',
        tags: [],
      });
      expect(button('Save').disabled).toBe(true);
      expect(button('Cancel', dialog()!).disabled).toBe(true);
      // Every control is disabled while saving.
      for (const id of ['title', 'description', 'priority', 'status', 'due', 'tags']) {
        expect(value(`#task-edit-${id}`).disabled).toBe(true);
      }

      req.flush({
        ...withStatus(TAXES, 'DONE'),
        title: 'File taxes now',
        description: null,
        dueDate: null,
      });
      await stable();
      expect(dialog()).toBeNull();
      expect(titles()).toEqual(['Buy milk', 'File taxes now']);
      expect(row('File taxes now').textContent).not.toContain('Due');
    });

    it('sends a newly picked date as YYYY-MM-DD', async () => {
      await openWith([MILK]);
      await openEdit('Buy milk');
      type('#task-edit-due', '1999-05-04');
      submit(editForm());
      const req = http.expectOne({ method: 'PUT', url: '/api/tasks/3' });
      expect(req.request.body).toEqual({
        title: 'Buy milk',
        description: null,
        dueDate: '1999-05-04',
        dueTime: null,
        priority: 'NONE',
        status: 'TODO',
        tags: [],
      });
      req.flush({ ...MILK, dueDate: '1999-05-04' });
      await stable();
      expect(row('Buy milk').textContent).toContain('1999-05-04');
    });

    it('validates title and description and sends nothing', async () => {
      await openWith([TAXES]);
      await openEdit('File taxes');
      type('#task-edit-title', '   ');
      type('#task-edit-description', 'x'.repeat(2001));
      submit(editForm());
      await stable();
      http.expectNone({ method: 'PUT', url: '/api/tasks/2' });
      expect(el.querySelector('#task-edit-title-error')?.textContent).toContain(
        'Title is required',
      );
      expect(el.querySelector('#task-edit-description-error')?.textContent).toContain(
        'Description must be 2,000 characters or fewer',
      );

      type('#task-edit-title', 'x'.repeat(201));
      await stable();
      expect(el.querySelector('#task-edit-title-error')?.textContent).toContain(
        'Title must be 200 characters or fewer',
      );
      expect(dialog()).not.toBeNull();
    });

    it('Cancel and Escape close without changes', async () => {
      await openWith([TAXES]);
      await openEdit('File taxes');
      type('#task-edit-title', 'Changed');
      button('Cancel', dialog()!).click();
      await stable();
      expect(dialog()).toBeNull();
      expect(titles()).toEqual(['File taxes']);

      await openEdit('File taxes');
      expect(value('#task-edit-title').value).toBe('File taxes');
      const cancel = new Event('cancel', { cancelable: true });
      dialog()!.dispatchEvent(cancel);
      await stable();
      expect(cancel.defaultPrevented).toBe(true);
      expect(dialog()).toBeNull();
    });

    it('maps server field errors onto the fields', async () => {
      await openWith([TAXES]);
      await openEdit('File taxes');
      submit(editForm());
      http.expectOne({ method: 'PUT', url: '/api/tasks/2' }).flush(
        problem(400, 'VALIDATION_FAILED', [
          { field: 'description', code: 'TOO_LONG', message: 'Server description text' },
          { field: 'status', code: 'STATUS_CONFLICT', message: "Status and done don't match" },
          { field: 'tags', code: 'TOO_MANY', message: 'A task can have at most 10 tags' },
        ]),
        { status: 400, statusText: 'Bad Request' },
      );
      await stable();
      expect(el.querySelector('#task-edit-description-error')?.textContent).toContain(
        'Server description text',
      );
      expect(el.querySelector('#task-edit-status-error')?.textContent).toContain(
        "Status and done don't match",
      );
      expect(el.querySelector('#task-edit-tags-error')?.textContent).toContain(
        'A task can have at most 10 tags',
      );
      expect(value('#task-edit-tags').getAttribute('aria-invalid')).toBe('true');
      expect(value('#task-edit-tags').disabled).toBe(false);
      expect(dialog()?.querySelector('[role=alert]')).toBeNull();
    });

    it('on failure stays open with its own Retry and keeps the input', async () => {
      await openWith([TAXES]);
      await openEdit('File taxes');
      type('#task-edit-title', 'Edited');
      submit(editForm());
      http
        .expectOne({ method: 'PUT', url: '/api/tasks/2' })
        .flush(problem(400, 'MALFORMED_REQUEST'), { status: 400, statusText: 'Bad Request' });
      await stable();
      expect(dialog()).not.toBeNull();
      expect(dialog()?.querySelector('[role=alert]')?.textContent).toContain(
        "Couldn't save the task",
      );
      expect(value('#task-edit-title').value).toBe('Edited');
      expect(banner()).toBeNull();

      button('Retry', dialog()!).click();
      await stable();
      const retry = http.expectOne({ method: 'PUT', url: '/api/tasks/2' });
      expect(retry.request.body.title).toBe('Edited');
      retry.flush({ ...TAXES, title: 'Edited' });
      await stable();
      expect(dialog()).toBeNull();
      expect(titles()).toEqual(['Edited']);
    });

    it('on 404 closes, shows "This task no longer exists" and reloads', async () => {
      await openWith([MILK, TAXES]);
      await openEdit('File taxes');
      submit(editForm());
      http
        .expectOne({ method: 'PUT', url: '/api/tasks/2' })
        .flush(problem(404, 'NOT_FOUND'), { status: 404, statusText: 'Not Found' });
      await stable();
      expect(dialog()).toBeNull();
      expect(banner()?.textContent).toContain('This task no longer exists');
      expect(banner()?.querySelector('button')).toBeNull();
      await flushList([MILK]);
      expect(titles()).toEqual(['Buy milk']);
    });
    function key(selector: string, k: string) {
      const ev = new KeyboardEvent('keydown', { key: k, bubbles: true, cancelable: true });
      el.querySelector(selector)!.dispatchEvent(ev);
      return ev;
    }

    const removeTag = (name: string) =>
      dialog()!
        .querySelector<HTMLButtonElement>(`button[aria-label="Remove tag ${name}"]`)!
        .click();

    const chips = () =>
      Array.from(el.querySelectorAll('dialog .tag-chip__name')).map((c) => c.textContent?.trim());

    it('sets priority and In progress; the row shows both badges and stays unticked', async () => {
      await openWith([TAXES]);
      await openEdit('File taxes');
      select('#task-edit-priority', 'HIGH');
      select('#task-edit-status', 'IN_PROGRESS');
      submit(editForm());
      const req = http.expectOne({ method: 'PUT', url: '/api/tasks/2' });
      expect(req.request.body).toMatchObject({ priority: 'HIGH', status: 'IN_PROGRESS' });
      expect(req.request.body.done).toBeUndefined();
      req.flush({ ...withStatus(TAXES, 'IN_PROGRESS'), priority: 'HIGH' });
      await stable();
      expect(row('File taxes').querySelector('[data-testid=task-priority]')?.textContent).toContain(
        'High',
      );
      expect(row('File taxes').textContent).toContain('In progress');
      expect(checkbox('File taxes').checked).toBe(false);
    });

    it('a Done task set back to To do in the dialog moves to the not-done group', async () => {
      await openWith([MILK, DONE]);
      await openEdit('Walk dog');
      expect(value('#task-edit-status').value).toBe('DONE');
      select('#task-edit-status', 'TODO');
      submit(editForm());
      http.expectOne({ method: 'PUT', url: '/api/tasks/1' }).flush(withStatus(DONE, 'TODO'));
      await stable();
      expect(titles()).toEqual(['Buy milk', 'Walk dog']);
      expect(row('Walk dog').querySelector('.task-row--done')).toBeNull();
    });

    it('adds tags with Enter and comma, reuses the known spelling, and saves them', async () => {
      await openWith([TAXES]);
      await openEdit('File taxes', 'title', ['Errands', 'Work']);
      type('#task-edit-tags', 'wörk');
      expect(key('#task-edit-tags', 'Enter').defaultPrevented).toBe(true);
      type('#task-edit-tags', ' Home ');
      key('#task-edit-tags', ',');
      type('#task-edit-tags', 'WORK');
      key('#task-edit-tags', 'Enter');
      await stable();
      expect(chips()).toEqual(['Work', 'Home']);
      // Text still typed when saving counts as a tag.
      type('#task-edit-tags', 'Later');
      submit(editForm());
      const req = http.expectOne({ method: 'PUT', url: '/api/tasks/2' });
      expect(req.request.body.tags).toEqual(['Work', 'Home', 'Later']);
      req.flush({ ...TAXES, tags: ['Home', 'Later', 'Work'] });
      await stable();
      const rowTags = Array.from(row('File taxes').querySelectorAll('a.task-row__tag')).map((a) =>
        a.textContent?.trim(),
      );
      expect(rowTags).toEqual(['Home', 'Later', 'Work']);
    });

    it('a rejected typed tag blocks Save and shows the error under Tags', async () => {
      await openWith([TAXES]);
      await openEdit('File taxes');
      type('#task-edit-tags', 'x'.repeat(31));
      submit(editForm());
      await stable();
      http.expectNone({ method: 'PUT', url: '/api/tasks/2' });
      expect(el.querySelector('#task-edit-tags-error')?.textContent).toContain(
        'Tags must be 30 characters or fewer',
      );
    });

    it('shows a TAG_LIMIT server error under Tags; it clears when the tags change', async () => {
      await openWith([TAXES]);
      await openEdit('File taxes');
      type('#task-edit-tags', 'New');
      key('#task-edit-tags', 'Enter');
      submit(editForm());
      http
        .expectOne({ method: 'PUT', url: '/api/tasks/2' })
        .flush(
          problem(400, 'VALIDATION_FAILED', [
            { field: 'tags', code: 'TAG_LIMIT', message: 'A list can have at most 200 tags' },
          ]),
          { status: 400, statusText: 'Bad Request' },
        );
      await stable();
      expect(el.querySelector('#task-edit-tags-error')?.textContent).toContain(
        'A list can have at most 200 tags',
      );
      expect(chips()).toEqual(['New']);
      removeTag('New');
      await stable();
      expect(chips()).toEqual([]);
      expect(el.querySelector('#task-edit-tags-error')).toBeNull();
    });

    it('works without suggestions when loading tags fails', async () => {
      await openWith([TAXES]);
      button('File taxes', row('File taxes')).click();
      await stable();
      http
        .expectOne('/api/tags')
        .flush(problem(500, 'INTERNAL_ERROR'), { status: 500, statusText: 'x' });
      await stable();
      expect(dialog()?.querySelector('[role=alert]')).toBeNull();
      type('#task-edit-tags', 'wo');
      await stable();
      expect(el.querySelectorAll('[role=option]').length).toBe(0);
      key('#task-edit-tags', 'Enter');
      submit(editForm());
      const req = http.expectOne({ method: 'PUT', url: '/api/tasks/2' });
      expect(req.request.body.tags).toEqual(['wo']);
      req.flush({ ...TAXES, tags: ['wo'] });
      await stable();
      expect(dialog()).toBeNull();
    });

    it('fetches the tags each time the dialog opens', async () => {
      await openWith([TAXES]);
      await openEdit('File taxes', 'title', ['Work']);
      button('Cancel', dialog()!).click();
      await stable();
      await openEdit('File taxes', 'button', ['Work', 'Home']);
      type('#task-edit-tags', 'o');
      await stable();
      expect(
        Array.from(el.querySelectorAll('[role=option]')).map((o) => o.textContent?.trim()),
      ).toEqual(['Work', 'Home']);
    });

    it('in a tag view, a task that loses the tag disappears after save', async () => {
      const tagged = { ...TAXES, tags: ['Work'] };
      await openWith([tagged], '/tasks?tag=Work', undefined, 'Work');
      await openEdit('File taxes', 'title', ['Work']);
      removeTag('Work');
      submit(editForm());
      const req = http.expectOne({ method: 'PUT', url: '/api/tasks/2' });
      expect(req.request.body.tags).toEqual([]);
      req.flush(TAXES);
      await stable();
      expect(titles()).toEqual([]);
      expect(text()).toContain('No tasks with this tag');
    });
  });

  describe('delete dialog', () => {
    async function openDelete(title: string) {
      button('Delete', row(title)).click();
      await stable();
    }

    it('asks "Delete \'<title>\'?"; Cancel closes without a request', async () => {
      await openWith([TAXES]);
      await openDelete('File taxes');
      expect(showModal).toHaveBeenCalledTimes(1);
      expect(dialog()?.querySelector('h2')?.textContent?.trim()).toBe("Delete 'File taxes'?");
      button('Cancel', dialog()!).click();
      await stable();
      expect(dialog()).toBeNull();
      expect(titles()).toEqual(['File taxes']);
    });

    it('Delete sends DELETE, disables while pending and removes the task', async () => {
      await openWith([MILK, TAXES]);
      await openDelete('File taxes');
      button('Delete', dialog()!).click();
      await stable();
      const req = http.expectOne({ method: 'DELETE', url: '/api/tasks/2' });
      expect(button('Delete', dialog()!).disabled).toBe(true);
      expect(button('Cancel', dialog()!).disabled).toBe(true);
      req.flush(null, { status: 204, statusText: 'No Content' });
      await stable();
      expect(dialog()).toBeNull();
      expect(titles()).toEqual(['Buy milk']);
    });

    it('removing the last task shows the empty text', async () => {
      await openWith([TAXES]);
      await openDelete('File taxes');
      button('Delete', dialog()!).click();
      http
        .expectOne({ method: 'DELETE', url: '/api/tasks/2' })
        .flush(null, { status: 204, statusText: 'No Content' });
      await stable();
      expect(text()).toContain('No tasks yet — add your first one above');
    });

    it('on failure stays open with its own Retry', async () => {
      await openWith([TAXES]);
      await openDelete('File taxes');
      button('Delete', dialog()!).click();
      http.expectOne({ method: 'DELETE', url: '/api/tasks/2' }).error(new ProgressEvent('error'));
      await stable();
      expect(dialog()?.querySelector('[role=alert]')?.textContent).toContain(
        "Couldn't delete the task",
      );
      button('Retry', dialog()!).click();
      await stable();
      http
        .expectOne({ method: 'DELETE', url: '/api/tasks/2' })
        .flush(null, { status: 204, statusText: 'No Content' });
      await stable();
      expect(dialog()).toBeNull();
      expect(titles()).toEqual([]);
    });

    it('on 404 closes, shows "This task no longer exists" and reloads', async () => {
      await openWith([MILK, TAXES]);
      await openDelete('File taxes');
      button('Delete', dialog()!).click();
      http
        .expectOne({ method: 'DELETE', url: '/api/tasks/2' })
        .flush(problem(404, 'NOT_FOUND'), { status: 404, statusText: 'Not Found' });
      await stable();
      expect(dialog()).toBeNull();
      expect(banner()?.textContent).toContain('This task no longer exists');
      await flushList([MILK]);
      expect(titles()).toEqual(['Buy milk']);
    });
  });

  describe('search', () => {
    const box = () => el.querySelector<HTMLInputElement>('#task-search')!;
    const clearBtn = () => el.querySelector<HTMLButtonElement>('button[aria-label="Clear search"]');
    const wait = (ms: number) => new Promise((r) => setTimeout(r, ms));
    const enter = () =>
      box().dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));

    it('has a labelled search box with a placeholder and a 100 character limit', async () => {
      await openWith([MILK]);
      expect(el.querySelector('label[for=task-search]')?.textContent?.trim()).toBe('Search tasks');
      expect(box().placeholder).toBe('Search tasks');
      expect(box().getAttribute('maxlength')).toBe('100');
      expect(el.querySelector('[role=search]')).not.toBeNull();
      expect(clearBtn()).toBeNull();
    });

    it('searches 300 ms after the last key, replacing the URL entry', async () => {
      await openWith([MILK, TAXES]);
      const router = TestBed.inject(Router);
      const navigate = vi.spyOn(router, 'navigate');
      type('#task-search', 'MI');
      await wait(150);
      type('#task-search', 'MILK');
      await wait(150);
      http.expectNone((r) => r.url === '/api/tasks' && r.params.has('q'));
      await wait(250);
      await stable();
      expect(router.url).toBe('/tasks?q=MILK');
      expect(navigate).toHaveBeenCalledTimes(1);
      expect(navigate.mock.calls[0][1]).toMatchObject({ replaceUrl: true });
      expect(el.querySelector('[role=status]')?.textContent).toContain('Loading tasks');
      await flushList([MILK], undefined, undefined, { q: 'MILK' });
      expect(titles()).toEqual(['Buy milk']);
      expect(box().value).toBe('MILK');
    });

    it('Enter searches at once (trimmed); typing more spaces does not search again', async () => {
      await openWith([MILK]);
      const router = TestBed.inject(Router);
      type('#task-search', '  milk ');
      enter();
      await stable();
      expect(router.url).toBe('/tasks?q=milk');
      await flushList([MILK], undefined, undefined, { q: 'milk' });
      expect(box().value).toBe('  milk ');
      type('#task-search', '  milk  ');
      enter();
      await stable();
      http.expectNone('/api/tasks');
    });

    it('?q= in the URL is searched and shown in the box, combined with tab, tag and sort', async () => {
      await open('/tasks?filter=active&tag=Home&q=caf%C3%A9&sort=due');
      listReq('active', 'Home', { q: 'café', sort: 'due' }).flush(onePage([]));
      await stable();
      expect(box().value).toBe('café');
      expect(text()).toContain('No tasks match "café"');
      expect(text()).not.toContain('No tasks with this tag');
    });

    it('a blank ?q= is no search; a hand-edited one over 100 characters is cut to 100', async () => {
      await openWith([], '/tasks?q=%20%20');
      expect(box().value).toBe('');
      expect(text()).toContain('No tasks yet');

      await harness.navigateByUrl(`/tasks?q=${'a'.repeat(120)}`);
      await flushList([], undefined, undefined, { q: 'a'.repeat(100) });
      expect(box().value).toBe('a'.repeat(100));
    });

    it('"×" clears the search, removes q from the URL and focuses the box', async () => {
      await open('/tasks?filter=done&q=milk');
      await flushList([], 'done', undefined, { q: 'milk' });
      expect(clearBtn()).not.toBeNull();
      clearBtn()!.click();
      await stable();
      expect(TestBed.inject(Router).url).toBe('/tasks?filter=done');
      expect(box().value).toBe('');
      expect(document.activeElement).toBe(box());
      await flushList([DONE], 'done');
      expect(titles()).toEqual(['Walk dog']);
      expect(clearBtn()).toBeNull();
    });

    it('emptying the box by typing clears the search after the pause', async () => {
      await open('/tasks?q=milk');
      await flushList([MILK], undefined, undefined, { q: 'milk' });
      type('#task-search', '');
      await wait(350);
      await stable();
      expect(TestBed.inject(Router).url).toBe('/tasks');
      await flushList([MILK, TAXES]);
    });

    it('Back (a URL change) updates the box and cancels a pending search', async () => {
      await open('/tasks?q=milk');
      await flushList([MILK], undefined, undefined, { q: 'milk' });
      type('#task-search', 'milkshake');
      await harness.navigateByUrl('/tasks?q=tax');
      await flushList([TAXES], undefined, undefined, { q: 'tax' });
      expect(box().value).toBe('tax');
      await wait(350);
      await stable();
      expect(TestBed.inject(Router).url).toBe('/tasks?q=tax');
      http.expectNone('/api/tasks');
    });
  });

  describe('sort', () => {
    const sortSelect = () => el.querySelector<HTMLSelectElement>('#task-sort')!;
    function pick(value: string) {
      sortSelect().value = value;
      sortSelect().dispatchEvent(new Event('change'));
    }

    it('offers the five sorts with a "Sort" label; Newest is the default', async () => {
      await openWith([MILK]);
      expect(el.querySelector('label[for=task-sort]')?.textContent?.trim()).toBe('Sort');
      expect(Array.from(sortSelect().options).map((o) => [o.value, o.textContent?.trim()])).toEqual(
        [
          ['newest', 'Newest'],
          ['oldest', 'Oldest'],
          ['due', 'Due date'],
          ['priority', 'Priority'],
          ['title', 'Title A–Z'],
        ],
      );
      expect(sortSelect().value).toBe('newest');
    });

    it('picking a sort adds a history entry with ?sort= and reloads from page 1', async () => {
      await open('/tasks?filter=active&q=milk');
      await flushList([MILK], 'active', undefined, { q: 'milk' });
      const router = TestBed.inject(Router);
      const navigate = vi.spyOn(router, 'navigate');
      pick('due');
      await stable();
      expect(router.url).toBe('/tasks?filter=active&q=milk&sort=due');
      expect(navigate.mock.calls[0][1]?.replaceUrl).toBeUndefined();
      await flushList([MILK], 'active', undefined, { q: 'milk', sort: 'due' });
      expect(sortSelect().value).toBe('due');

      pick('newest');
      await stable();
      expect(router.url).toBe('/tasks?filter=active&q=milk');
      await flushList([MILK], 'active', undefined, { q: 'milk' });
    });

    it.each([
      ['/tasks?sort=title', 'title'],
      ['/tasks?sort=priority', 'priority'],
    ])('%s loads with that sort and selects it', async (url, sort) => {
      await open(url);
      await flushList([MILK], undefined, undefined, { sort });
      expect(sortSelect().value).toBe(sort);
    });

    it.each(['/tasks?sort=foo', '/tasks?sort=Due', '/tasks?sort='])(
      '%s shows Newest without rewriting the URL',
      async (url) => {
        await openWith([MILK], url);
        expect(sortSelect().value).toBe('newest');
        expect(TestBed.inject(Router).url).toBe(url);
      },
    );

    it('tabs and tag links keep the sort and search', async () => {
      const WORK = task({ id: 4, title: 'Write report', tags: ['Work'] });
      await open('/tasks?sort=title&q=r');
      await flushList([WORK], undefined, undefined, { sort: 'title', q: 'r' });
      link('Done').click();
      await stable();
      expect(TestBed.inject(Router).url).toBe('/tasks?sort=title&q=r&filter=done');
      await flushList([], 'done', undefined, { sort: 'title', q: 'r' });
      expect(text()).toContain('No tasks match "r"');
    });
  });

  describe('pagination', () => {
    const many = (from: number, count: number) =>
      Array.from({ length: count }, (_, i) =>
        task({
          id: from - i,
          title: `T${from - i}`,
          createdAt: `2026-01-01T00:00:${String(from - i).padStart(2, '0')}Z`,
          updatedAt: `2026-01-01T00:00:${String(from - i).padStart(2, '0')}Z`,
        }),
      );
    const footer = () =>
      el.querySelector('[data-testid=tasks-footer]')?.textContent?.replace(/\s+/g, ' ').trim();
    const loadMore = () => el.querySelector<HTMLButtonElement>('.tasks-footer__more');
    const footerAlert = () => el.querySelector('.tasks-footer [role=alert]');
    const added = () => el.querySelector('[data-testid=task-added]');

    async function openPaged(items: Task[], total: number, cursor = 'c1', url = '/tasks') {
      await open(url);
      listReq().flush({ items, total, nextCursor: cursor });
      await stable();
    }

    it('shows "Showing X of Y" and Load more; the last page shows "N tasks"', async () => {
      await openPaged(many(50, 3), 5);
      expect(titles()).toEqual(['T50', 'T49', 'T48']);
      expect(footer()).toBe('Showing 3 of 5 Load more');

      loadMore()!.click();
      await stable();
      const req = listReq(undefined, undefined, { cursor: 'c1' });
      expect(loadMore()!.getAttribute('aria-disabled')).toBe('true');
      expect(loadMore()!.textContent).toContain('Loading more…');
      loadMore()!.click(); // ignored while running
      await stable();
      req.flush({ items: many(47, 2), total: 5, nextCursor: null });
      await stable();
      expect(titles()).toEqual(['T50', 'T49', 'T48', 'T47', 'T46']);
      expect(footer()).toBe('5 tasks');
      expect(loadMore()).toBeNull();
    });

    it('says "1 task" for a single task and shows no button', async () => {
      await openWith([MILK]);
      expect(footer()).toBe('1 task');
      expect(loadMore()).toBeNull();
    });

    it('sends the cursor with the same filter, tag, q and sort', async () => {
      await open('/tasks?filter=active&tag=Work&q=t&sort=priority');
      const query = { q: 't', sort: 'priority' };
      listReq('active', 'Work', query).flush({ items: many(50, 2), total: 4, nextCursor: 'abc' });
      await stable();
      loadMore()!.click();
      await stable();
      listReq('active', 'Work', { ...query, cursor: 'abc' }).flush({
        items: many(48, 2),
        total: 4,
        nextCursor: null,
      });
      await stable();
      expect(titles().length).toBe(4);
    });

    it('a failed Load more keeps the items and offers Retry', async () => {
      await openPaged(many(50, 2), 4);
      loadMore()!.click();
      await stable();
      listReq(undefined, undefined, { cursor: 'c1' }).error(new ProgressEvent('error'));
      await stable();
      expect(titles()).toEqual(['T50', 'T49']);
      expect(footerAlert()?.textContent).toContain("Couldn't load more tasks");
      expect(banner()).toBeNull();

      button('Retry', el.querySelector('.tasks-footer')!).click();
      await stable();
      expect(footerAlert()).toBeNull();
      listReq(undefined, undefined, { cursor: 'c1' }).flush({
        items: many(48, 2),
        total: 4,
        nextCursor: null,
      });
      await stable();
      expect(titles()).toEqual(['T50', 'T49', 'T48', 'T47']);
    });

    it('INVALID_CURSOR restarts from page 1 without a Retry', async () => {
      await openPaged(many(50, 2), 4);
      loadMore()!.click();
      await stable();
      listReq(undefined, undefined, { cursor: 'c1' }).flush(
        problem(400, 'VALIDATION_FAILED', [
          { field: 'cursor', code: 'INVALID_CURSOR', message: 'The list changed' },
        ]),
        { status: 400, statusText: 'Bad Request' },
      );
      await stable();
      expect(footerAlert()).toBeNull();
      listReq().flush({ items: many(51, 3), total: 5, nextCursor: 'c2' });
      await stable();
      expect(titles()).toEqual(['T51', 'T50', 'T49']);
      expect(footer()).toBe('Showing 3 of 5 Load more');
    });

    it('changing the tab after Load more restarts from page 1', async () => {
      await openPaged(many(50, 2), 4);
      loadMore()!.click();
      await stable();
      listReq(undefined, undefined, { cursor: 'c1' }).flush({
        items: many(48, 1),
        total: 4,
        nextCursor: 'c2',
      });
      await stable();
      link('Active').click();
      await stable();
      await flushList([MILK], 'active');
      expect(titles()).toEqual(['Buy milk']);
      expect(footer()).toBe('1 task');
    });

    it('re-syncs the total from every page', async () => {
      await openPaged(many(50, 2), 4);
      loadMore()!.click();
      await stable();
      listReq(undefined, undefined, { cursor: 'c1' }).flush({
        items: many(48, 1),
        total: 7,
        nextCursor: 'c2',
      });
      await stable();
      expect(footer()).toBe('Showing 3 of 7 Load more');
    });

    it('delete removes the task in place and lowers the total; it is skipped later', async () => {
      await openPaged(many(50, 3), 6);
      button('Delete', row('T49')).click();
      await stable();
      button('Delete', dialog()!).click();
      http
        .expectOne({ method: 'DELETE', url: '/api/tasks/49' })
        .flush(null, { status: 204, statusText: 'No Content' });
      await stable();
      expect(titles()).toEqual(['T50', 'T48']);
      expect(footer()).toBe('Showing 2 of 5 Load more');
      loadMore()!.click();
      await stable();
      listReq(undefined, undefined, { cursor: 'c1' }).flush({
        items: [...many(49, 1), ...many(47, 2)],
        total: 5,
        nextCursor: null,
      });
      await stable();
      expect(titles()).toEqual(['T50', 'T48', 'T47', 'T46']);
    });

    it('a ticked task leaves the Active view (total − 1) without a reload', async () => {
      await open('/tasks?filter=active');
      listReq('active').flush({ items: many(50, 3), total: 9, nextCursor: 'c1' });
      await stable();
      checkbox('T49').click();
      await stable();
      const [t49] = many(49, 1);
      http.expectOne({ method: 'PATCH', url: '/api/tasks/49' }).flush(withStatus(t49, 'DONE'));
      await stable();
      expect(titles()).toEqual(['T50', 'T48']);
      expect(footer()).toBe('Showing 2 of 8 Load more');
    });

    it('All: a task ticked before the done group is loaded comes back once via Load more', async () => {
      await openPaged(many(50, 3), 6);
      checkbox('T49').click();
      await stable();
      const done49 = { ...withStatus(many(49, 1)[0], 'DONE'), updatedAt: '2026-02-01T00:00:00Z' };
      http.expectOne({ method: 'PATCH', url: '/api/tasks/49' }).flush(done49);
      await stable();
      expect(titles()).toEqual(['T50', 'T48']);
      expect(footer()).toBe('Showing 2 of 6 Load more');
      loadMore()!.click();
      await stable();
      listReq(undefined, undefined, { cursor: 'c1' }).flush({
        items: [...many(47, 2), done49, done49],
        total: 6,
        nextCursor: null,
      });
      await stable();
      expect(titles()).toEqual(['T50', 'T48', 'T47', 'T46', 'T49']);
    });

    describe('quick-add', () => {
      async function add(created: Task) {
        type('#task-add-title', created.title);
        await stable();
        el.querySelector<HTMLFormElement>('app-task-add-form form')!.dispatchEvent(
          new Event('submit'),
        );
        http.expectOne({ method: 'POST', url: '/api/tasks' }).flush(created);
        await stable();
      }
      const NEW = task({ id: 60, title: 'Brand new', createdAt: '2026-11-01T00:00:00Z' });

      it('Newest: the new task goes to the top and the total goes up', async () => {
        await openPaged(many(50, 2), 4);
        await add(NEW);
        expect(titles()).toEqual(['Brand new', 'T50', 'T49']);
        expect(footer()).toBe('Showing 3 of 5 Load more');
        expect(added()).toBeNull();
      });

      it('Oldest with more to load: created, total + 1, "Task added" instead of a row', async () => {
        await open('/tasks?sort=oldest');
        listReq(undefined, undefined, { sort: 'oldest' }).flush({
          items: many(2, 2).reverse(),
          total: 4,
          nextCursor: 'c1',
        });
        await stable();
        await add(NEW);
        expect(titles()).toEqual(['T1', 'T2']);
        expect(footer()).toBe('Showing 2 of 5 Load more');
        expect(added()?.textContent).toContain('Task added');
        expect(added()?.getAttribute('role')).toBe('status');
      });

      it('a search the new task does not match: "Task added", total unchanged', async () => {
        await open('/tasks?q=milk');
        listReq(undefined, undefined, { q: 'milk' }).flush({
          items: [MILK],
          total: 3,
          nextCursor: 'c1',
        });
        await stable();
        await add(NEW);
        expect(titles()).toEqual(['Buy milk']);
        expect(footer()).toBe('Showing 1 of 3 Load more');
        expect(added()).not.toBeNull();
      });

      it('a search the new task matches: shown on Newest', async () => {
        await open('/tasks?q=brand');
        await flushList([], undefined, undefined, { q: 'brand' });
        expect(text()).toContain('No tasks match "brand"');
        await add(NEW);
        expect(titles()).toEqual(['Brand new']);
        expect(footer()).toBe('1 task');
      });

      it('everything loaded: a matching task is shown in its sorted place', async () => {
        await open('/tasks?sort=title');
        await flushList([MILK, TAXES], undefined, undefined, { sort: 'title' });
        await add(task({ id: 7, title: 'Cook dinner' }));
        expect(titles()).toEqual(['Buy milk', 'Cook dinner', 'File taxes']);
        expect(footer()).toBe('3 tasks');
      });
    });
  });
});
