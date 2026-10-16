import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Task } from '../tasks.models';
import { TaskEditDialog } from './task-edit-dialog';

const TASK: Task = {
  id: 7,
  title: 'Plan trip',
  description: 'Book hotel',
  priority: 'MEDIUM',
  status: 'IN_PROGRESS',
  tags: ['Home', 'Travel'],
  done: false,
  dueDate: '2026-11-01',
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
    ['TAG_LIMIT', 'You can have at most 200 tags'],
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
