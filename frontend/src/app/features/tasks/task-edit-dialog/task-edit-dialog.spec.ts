import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Task } from '../tasks.models';
import { TaskEditDialog } from './task-edit-dialog';

const TASK: Task = {
  id: 7,
  listId: 3,
  addedBy: { displayName: 'Ana' },
  title: 'Plan trip',
  description: 'Book hotel',
  priority: 'MEDIUM',
  status: 'IN_PROGRESS',
  tags: ['Home', 'Travel'],
  done: false,
  dueDate: '2026-11-01',
  dueTime: null,
  myReminder: null,
  myReminderPassed: false,
  createdAt: '2026-10-08T09:00:00Z',
  updatedAt: '2026-10-08T09:00:00Z',
};

@Component({
  imports: [TaskEditDialog],
  template: `<app-task-edit-dialog
    [task]="task()"
    (saved)="events.push('saved:' + $event.id)"
    (closed)="events.push('closed')"
    (notFound)="events.push('notFound')"
  />`,
})
class Host {
  readonly task = signal<Task>(TASK);
  readonly events: string[] = [];
}

function problem(status: number, code: string, errors?: unknown[]) {
  return { type: 'about:blank', title: 'x', status, detail: 'x', code, errors };
}

describe('TaskEditDialog', () => {
  let fixture: ComponentFixture<Host>;
  let host: Host;
  let el: HTMLElement;
  let http: HttpTestingController;

  beforeAll(() => {
    HTMLDialogElement.prototype.showModal = function (this: HTMLDialogElement) {
      this.setAttribute('open', '');
    };
  });

  beforeEach(async () => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(Host);
    host = fixture.componentInstance;
    el = fixture.nativeElement;
    await fixture.whenStable();
  });

  afterEach(() => http.verify());

  const stable = () => fixture.whenStable();
  const q = <T extends Element = HTMLInputElement>(sel: string) => el.querySelector<T>(sel)!;
  const chips = () =>
    Array.from(el.querySelectorAll('.tag-chip__name')).map((c) => c.textContent?.trim());
  const submit = () => q<HTMLFormElement>('form').dispatchEvent(new Event('submit'));
  const flushTags = (names: string[] = []) =>
    http.expectOne('/api/tags').flush(names.map((name) => ({ name })));

  function button(name: string): HTMLButtonElement {
    return Array.from(el.querySelectorAll('button')).find((b) => b.textContent?.trim() === name)!;
  }

  it('prefills every field from the task, including priority, status and tags', async () => {
    flushTags(['Home', 'Travel', 'Work']);
    await stable();
    expect(q('#task-edit-title').value).toBe('Plan trip');
    expect(q('#task-edit-description').value).toBe('Book hotel');
    expect(q<HTMLSelectElement>('#task-edit-priority').value).toBe('MEDIUM');
    expect(q<HTMLSelectElement>('#task-edit-status').value).toBe('IN_PROGRESS');
    expect(q('#task-edit-due').value).toBe('2026-11-01');
    expect(chips()).toEqual(['Home', 'Travel']);
    const options = (id: string) =>
      Array.from(q<HTMLSelectElement>(id).options).map((o) => [o.value, o.text.trim()]);
    expect(options('#task-edit-priority')).toEqual([
      ['NONE', 'None'],
      ['LOW', 'Low'],
      ['MEDIUM', 'Medium'],
      ['HIGH', 'High'],
    ]);
    expect(options('#task-edit-status')).toEqual([
      ['TODO', 'To do'],
      ['IN_PROGRESS', 'In progress'],
      ['DONE', 'Done'],
    ]);
  });

  it('sends unchanged values as they are and never sends done', async () => {
    flushTags();
    submit();
    const req = http.expectOne({ method: 'PUT', url: '/api/tasks/7' });
    expect(req.request.body).toEqual({
      title: 'Plan trip',
      description: 'Book hotel',
      dueDate: '2026-11-01',
      dueTime: null,
      priority: 'MEDIUM',
      status: 'IN_PROGRESS',
      tags: ['Home', 'Travel'],
    });
    expect('done' in req.request.body).toBe(false);
    req.flush(TASK);
    await stable();
    expect(host.events).toEqual(['saved:7']);
  });

  it('disables every control while saving and re-enables them on failure, keeping input', async () => {
    flushTags();
    q('#task-edit-title').value = 'Plan trip v2';
    q('#task-edit-title').dispatchEvent(new Event('input'));
    submit();
    await stable();
    const req = http.expectOne({ method: 'PUT', url: '/api/tasks/7' });
    for (const id of ['title', 'description', 'priority', 'status', 'due', 'tags']) {
      expect(q(`#task-edit-${id}`).disabled).toBe(true);
    }
    expect(
      Array.from(el.querySelectorAll<HTMLButtonElement>('.tag-chip__remove')).every(
        (b) => b.disabled,
      ),
    ).toBe(true);
    // Escape does nothing while saving.
    const cancel = new Event('cancel', { cancelable: true });
    q('dialog').dispatchEvent(cancel);
    expect(host.events).toEqual([]);

    req.error(new ProgressEvent('error'));
    await stable();
    expect(el.querySelector('[role=alert]')?.textContent).toContain("Couldn't save the task");
    expect(q('#task-edit-title').disabled).toBe(false);
    expect(q('#task-edit-tags').disabled).toBe(false);
    expect(q('#task-edit-title').value).toBe('Plan trip v2');
    expect(chips()).toEqual(['Home', 'Travel']);

    button('Retry').click();
    const retry = http.expectOne({ method: 'PUT', url: '/api/tasks/7' });
    expect(retry.request.body.title).toBe('Plan trip v2');
    retry.flush({ ...TASK, title: 'Plan trip v2' });
    await stable();
    expect(host.events).toEqual(['saved:7']);
  });

  it('shows a done/REQUIRED or status error under Status', async () => {
    flushTags();
    submit();
    http
      .expectOne({ method: 'PUT', url: '/api/tasks/7' })
      .flush(
        problem(400, 'VALIDATION_FAILED', [
          { field: 'done', code: 'REQUIRED', message: 'Done or status is required' },
        ]),
        { status: 400, statusText: 'Bad Request' },
      );
    await stable();
    expect(el.querySelector('#task-edit-status-error')?.textContent).toContain(
      'Done or status is required',
    );
    expect(q('#task-edit-status').getAttribute('aria-describedby')).toBe('task-edit-status-error');
    expect(el.querySelector('[role=alert]')).toBeNull();
  });

  it.each([
    ['REQUIRED', "Tags can't be blank"],
    ['INVALID_CHARACTERS', "Tags can't contain commas or control characters"],
    ['TOO_LONG', 'Tags must be 30 characters or fewer'],
    ['TOO_MANY', 'A task can have at most 10 tags'],
    ['TAG_LIMIT', 'A list can have at most 200 tags'],
  ])('shows tags/%s from the server under Tags', async (code, message) => {
    flushTags();
    submit();
    http
      .expectOne({ method: 'PUT', url: '/api/tasks/7' })
      .flush(problem(400, 'VALIDATION_FAILED', [{ field: 'tags', code, message }]), {
        status: 400,
        statusText: 'Bad Request',
      });
    await stable();
    expect(el.querySelector('#task-edit-tags-error')?.textContent?.trim()).toBe(message);
    expect(el.querySelector('[role=alert]')).toBeNull();
  });

  it('a bad enum (MALFORMED_REQUEST) shows the save banner', async () => {
    flushTags();
    submit();
    http
      .expectOne({ method: 'PUT', url: '/api/tasks/7' })
      .flush(problem(400, 'MALFORMED_REQUEST'), { status: 400, statusText: 'Bad Request' });
    await stable();
    expect(el.querySelector('[role=alert]')?.textContent).toContain("Couldn't save the task");
  });

  it('404 emits notFound; 401 stays quiet', async () => {
    flushTags();
    submit();
    http
      .expectOne({ method: 'PUT', url: '/api/tasks/7' })
      .flush(problem(404, 'NOT_FOUND'), { status: 404, statusText: 'Not Found' });
    await stable();
    expect(host.events).toEqual(['notFound']);

    submit();
    http
      .expectOne({ method: 'PUT', url: '/api/tasks/7' })
      .flush(problem(401, 'UNAUTHENTICATED'), { status: 401, statusText: 'Unauthorized' });
    await stable();
    expect(el.querySelector('[role=alert]')).toBeNull();
    expect(host.events).toEqual(['notFound']);
  });

  it('Escape in the tag input with suggestions open does not close the dialog', async () => {
    flushTags(['Work']);
    await stable();
    const tags = q('#task-edit-tags');
    tags.value = 'w';
    tags.dispatchEvent(new Event('input'));
    await stable();
    const esc = new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true });
    let reachedDialog = false;
    q('dialog').addEventListener('keydown', () => (reachedDialog = true));
    tags.dispatchEvent(esc);
    expect(esc.defaultPrevented).toBe(true);
    expect(reachedDialog).toBe(false);
    expect(host.events).toEqual([]);
  });
});

describe('TaskEditDialog due time and reminder', () => {
  let fixture: ComponentFixture<Host>;
  let host: Host;
  let el: HTMLElement;
  let http: HttpTestingController;

  beforeAll(() => {
    HTMLDialogElement.prototype.showModal = function (this: HTMLDialogElement) {
      this.setAttribute('open', '');
    };
  });

  async function open(task: Task) {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(Host);
    host = fixture.componentInstance;
    host.task.set(task);
    el = fixture.nativeElement;
    await fixture.whenStable();
    http.expectOne('/api/tags').flush([]);
    await fixture.whenStable();
  }

  afterEach(() => http.verify());

  const q = <T extends Element = HTMLInputElement>(sel: string) => el.querySelector<T>(sel)!;
  const time = () => q('#task-edit-due-time');
  const reminder = () => q<HTMLSelectElement>('#task-edit-reminder');
  const choices = () => Array.from(reminder().options).map((o) => [o.value, o.text.trim()]);
  const submit = () => q<HTMLFormElement>('form').dispatchEvent(new Event('submit'));
  async function set(sel: string, value: string, event = 'input') {
    q(sel).value = value;
    q(sel).dispatchEvent(new Event(event));
    await fixture.whenStable();
  }

  const NO_DATE: Task = { ...TASK, dueDate: null };
  const TIMED: Task = {
    ...TASK,
    dueTime: '09:30',
    myReminder: 'ONE_HOUR',
    myReminderPassed: false,
  };

  it('disables time and reminder without a due date', async () => {
    await open(NO_DATE);
    expect(time().disabled).toBe(true);
    expect(reminder().disabled).toBe(true);
    await set('#task-edit-due', '2026-11-02');
    expect(time().disabled).toBe(false);
    expect(reminder().disabled).toBe(false);
  });

  it('prefills time and reminder and offers the timed choices', async () => {
    await open(TIMED);
    expect(time().value).toBe('09:30');
    expect(reminder().value).toBe('ONE_HOUR');
    expect(choices()).toEqual([
      ['NONE', 'None'],
      ['AT_DUE', 'At due time'],
      ['TEN_MINUTES', '10 minutes before'],
      ['ONE_HOUR', '1 hour before'],
      ['ONE_DAY', '1 day before'],
    ]);
  });

  it('offers the date-only choices without a time', async () => {
    await open(TASK);
    expect(choices()).toEqual([
      ['NONE', 'None'],
      ['ON_THE_DAY', 'On the day at 09:00'],
      ['ONE_DAY', '1 day before at 09:00'],
    ]);
  });

  it('resets a reminder that no longer fits when the time is removed', async () => {
    await open(TIMED);
    await set('#task-edit-due-time', '');
    expect(reminder().value).toBe('NONE');
    submit();
    const req = http.expectOne({ method: 'PUT', url: '/api/tasks/7' });
    expect(req.request.body.dueTime).toBeNull();
    expect(req.request.body.reminder).toBe('NONE');
    req.flush({ ...TASK, myReminder: null });
  });

  it('clearing the date clears time and reminder and sends no reminder', async () => {
    await open(TIMED);
    el.querySelector<HTMLButtonElement>('.dialog__inline button')!.click();
    await fixture.whenStable();
    expect(time().value).toBe('');
    expect(time().disabled).toBe(true);
    expect(reminder().disabled).toBe(true);
    submit();
    const req = http.expectOne({ method: 'PUT', url: '/api/tasks/7' });
    expect(req.request.body.dueDate).toBeNull();
    expect(req.request.body.dueTime).toBeNull();
    expect('reminder' in req.request.body).toBe(false);
    req.flush({ ...NO_DATE });
  });

  it('sends dueTime and a changed reminder; leaves an unchanged reminder out', async () => {
    await open(TASK);
    await set('#task-edit-due-time', '14:15');
    await set('#task-edit-reminder', 'TEN_MINUTES', 'change');
    submit();
    const req = http.expectOne({ method: 'PUT', url: '/api/tasks/7' });
    expect(req.request.body.dueTime).toBe('14:15');
    expect(req.request.body.reminder).toBe('TEN_MINUTES');
    req.flush({ ...TASK, dueTime: '14:15', myReminder: 'TEN_MINUTES' });
  });

  it('leaves an unchanged reminder out of the request', async () => {
    await open(TIMED);
    await set('#task-edit-title', 'Other');
    submit();
    const req = http.expectOne({ method: 'PUT', url: '/api/tasks/7' });
    expect('reminder' in req.request.body).toBe(false);
    expect(req.request.body.dueTime).toBe('09:30');
    req.flush(TIMED);
  });

  it('shows "This reminder time has passed" on open', async () => {
    await open({ ...TIMED, myReminderPassed: true });
    expect(q('[data-testid=reminder-passed]').textContent).toContain(
      'This reminder time has passed',
    );
  });

  it('shows the note after a save that made the reminder past, and keeps controls enabled', async () => {
    await open(TASK);
    await set('#task-edit-due-time', '08:00');
    await set('#task-edit-reminder', 'AT_DUE', 'change');
    submit();
    http
      .expectOne({ method: 'PUT', url: '/api/tasks/7' })
      .flush({ ...TASK, dueTime: '08:00', myReminder: 'AT_DUE', myReminderPassed: true });
    await fixture.whenStable();
    expect(host.events).toEqual(['saved:7']);
    expect(q('[data-testid=reminder-passed]').textContent).toContain('has passed');
    expect(time().disabled).toBe(false);
    expect(
      Array.from(el.querySelectorAll('button')).some((b) => b.textContent?.trim() === 'Close'),
    ).toBe(true);
  });

  it('shows server field errors for the reminder', async () => {
    await open(TASK);
    await set('#task-edit-reminder', 'ON_THE_DAY', 'change');
    submit();
    http
      .expectOne({ method: 'PUT', url: '/api/tasks/7' })
      .flush(
        problem(400, 'VALIDATION_FAILED', [
          { field: 'reminder', code: 'INVALID_REMINDER', message: 'Not valid for this task' },
        ]),
        { status: 400, statusText: 'Bad Request' },
      );
    await fixture.whenStable();
    expect(q('#task-edit-reminder-error').textContent).toContain('Not valid for this task');
  });
});
