import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
  TestRequest,
} from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { Task } from '../tasks.models';
import { TasksPage } from './tasks-page';

const PAST = '2000-01-01';
const FUTURE = '2999-12-31';

function task(overrides: Partial<Task> & { id: number }): Task {
  return {
    title: `Task ${overrides.id}`,
    description: null,
    done: false,
    dueDate: null,
    createdAt: `2026-10-0${overrides.id}T09:00:00Z`,
    updatedAt: `2026-10-0${overrides.id}T09:00:00Z`,
    ...overrides,
  };
}

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

  function listReq(filter?: string): TestRequest {
    return http.expectOne(
      (r) =>
        r.method === 'GET' &&
        r.url === '/api/tasks' &&
        (r.params.get('filter') ?? undefined) === filter,
    );
  }

  async function flushList(tasks: Task[], filter?: string) {
    listReq(filter).flush(tasks);
    await stable();
  }

  async function openWith(tasks: Task[], url = '/tasks', filter?: string) {
    await open(url);
    await flushList(tasks, filter);
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
      expect(el.querySelector('h1')?.textContent).toContain('Your tasks');
    });

    it('re-sorts the list (not done first, then done, each newest first)', async () => {
      await openWith([DONE, TAXES, MILK]);
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
      expect(req.request.body).toEqual({ done: true });
      expect(checkbox('File taxes').disabled).toBe(true);
      expect(checkbox('Buy milk').disabled).toBe(false);
      expect(button('Edit', row('Buy milk')).disabled).toBe(false);
      // Not moved until the server confirms.
      expect(titles()).toEqual(['Buy milk', 'File taxes', 'Walk dog']);

      req.flush({ ...TAXES, done: true, updatedAt: '2026-10-09T00:00:00Z' });
      await stable();
      expect(titles()).toEqual(['Buy milk', 'File taxes', 'Walk dog']);
      expect(checkbox('File taxes').checked).toBe(true);
      expect(row('File taxes').querySelector('.task-row--done')).not.toBeNull();
      // Done group is newest first: File taxes (Oct 2) before Walk dog (Oct 1).

      checkbox('Walk dog').click();
      await stable();
      http.expectOne({ method: 'PATCH', url: '/api/tasks/1' }).flush({ ...DONE, done: false });
      await stable();
      expect(titles()).toEqual(['Buy milk', 'Walk dog', 'File taxes']);
    });

    it('removes a ticked task from the Active view once confirmed', async () => {
      await openWith([MILK, TAXES], '/tasks?filter=active', 'active');
      checkbox('Buy milk').click();
      await stable();
      http.expectOne({ method: 'PATCH', url: '/api/tasks/3' }).flush({ ...MILK, done: true });
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
      expect(retry.request.body).toEqual({ done: true });
      retry.flush({ ...MILK, done: true });
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

    async function openEdit(title: string, via: 'title' | 'button' = 'title') {
      (via === 'title' ? button(title, row(title)) : button('Edit', row(title))).click();
      await stable();
    }

    it.each(['title', 'button'] as const)('opens prefilled via the %s', async (via) => {
      await openWith([TAXES]);
      await openEdit('File taxes', via);
      expect(showModal).toHaveBeenCalledTimes(1);
      expect(dialog()?.getAttribute('aria-labelledby')).toBe('task-edit-heading');
      expect(value('#task-edit-title').value).toBe('File taxes');
      expect(value('#task-edit-description').value).toBe('Old folder');
      expect(value('#task-edit-due').value).toBe(FUTURE);
      expect(value('#task-edit-done').checked).toBe(false);
      for (const label of ['Title', 'Description', 'Due date', 'Done']) {
        expect(dialog()?.textContent).toContain(label);
      }
    });

    it('does not open when the checkbox is ticked', async () => {
      await openWith([TAXES]);
      checkbox('File taxes').click();
      await stable();
      expect(dialog()).toBeNull();
      http.expectOne({ method: 'PATCH', url: '/api/tasks/2' }).flush(TAXES);
    });

    it('Save PUTs all four fields (trimmed, blank description and cleared date as null)', async () => {
      await openWith([MILK, TAXES]);
      await openEdit('File taxes');
      type('#task-edit-title', '  File taxes now ');
      type('#task-edit-description', '   ');
      button('Clear date').click();
      value('#task-edit-done').click();
      await stable();
      submit(editForm());
      await stable();

      const req = http.expectOne({ method: 'PUT', url: '/api/tasks/2' });
      expect(req.request.body).toEqual({
        title: 'File taxes now',
        description: null,
        dueDate: null,
        done: true,
      });
      expect(button('Save').disabled).toBe(true);
      expect(button('Cancel', dialog()!).disabled).toBe(true);

      req.flush({
        ...TAXES,
        title: 'File taxes now',
        description: null,
        dueDate: null,
        done: true,
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
        done: false,
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
          { field: 'done', code: 'REQUIRED', message: 'Done is required' },
        ]),
        { status: 400, statusText: 'Bad Request' },
      );
      await stable();
      expect(el.querySelector('#task-edit-description-error')?.textContent).toContain(
        'Server description text',
      );
      expect(el.querySelector('#task-edit-done-error')?.textContent).toContain('Done is required');
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
});
