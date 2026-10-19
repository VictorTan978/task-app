import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { User } from '../../../core/auth/auth.models';
import { AuthService } from '../../../core/auth/auth.service';
import { TaskList } from '../../lists/lists.models';
import { ListsStore } from '../../lists/lists.store';
import { Task } from '../tasks.models';
import { TaskEditDialog } from './task-edit-dialog';

const MINE: TaskList = { id: 3, name: 'My tasks', personal: true };
const HOME: TaskList = { id: 8, name: 'Home', personal: false };
const WORK: TaskList = { id: 9, name: 'Work', personal: false };

const TASK: Task = {
  id: 7,
  listId: 8,
  title: 'Plan trip',
  description: null,
  priority: 'NONE',
  status: 'TODO',
  tags: ['Travel'],
  done: false,
  dueDate: null,
  createdAt: '2026-10-08T09:00:00Z',
  updatedAt: '2026-10-08T09:00:00Z',
};

@Component({
  imports: [TaskEditDialog],
  template: `<app-task-edit-dialog
    [task]="task()"
    [tagsListId]="8"
    (saved)="events.push('saved:' + $event.listId)"
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

describe('TaskEditDialog: List select (move)', () => {
  let fixture: ComponentFixture<Host>;
  let host: Host;
  let el: HTMLElement;
  let http: HttpTestingController;
  let store: ListsStore;

  beforeAll(() => {
    HTMLDialogElement.prototype.showModal = function (this: HTMLDialogElement) {
      this.setAttribute('open', '');
    };
  });

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: AuthService, useValue: { currentUser: signal({ id: 1 } as User) } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    store = TestBed.inject(ListsStore);
  });

  afterEach(() => http.verify());

  const stable = () => fixture.whenStable();
  const select = () => el.querySelector<HTMLSelectElement>('#task-edit-list')!;
  const submit = () => el.querySelector('form')!.dispatchEvent(new Event('submit'));

  async function create(lists: TaskList[] | 'fail' = [MINE, HOME, WORK]) {
    fixture = TestBed.createComponent(Host);
    host = fixture.componentInstance;
    el = fixture.nativeElement;
    await fixture.whenStable();
    // Tags are the open list's (8), whatever list is picked.
    http
      .expectOne((r) => r.url === '/api/tags' && r.params.get('list') === '8')
      .flush([{ name: 'Travel' }]);
    const req = http.expectOne('/api/lists');
    if (lists === 'fail') {
      req.flush(null, { status: 500, statusText: 'Error' });
    } else {
      req.flush(lists);
    }
    await stable();
  }

  async function pick(id: number) {
    select().value = String(id);
    select().dispatchEvent(new Event('change'));
    await stable();
  }

  it("lists my lists with the task's list preselected", async () => {
    await create();
    expect(Array.from(select().options).map((o) => o.text.trim())).toEqual([
      'My tasks',
      'Home',
      'Work',
    ]);
    expect(select().value).toBe('8');
    expect(select().disabled).toBe(false);
  });

  it('does not send listId when the list is unchanged', async () => {
    await create();
    submit();
    const req = http.expectOne({ method: 'PUT', url: '/api/tasks/7' });
    expect('listId' in req.request.body).toBe(false);
    req.flush(TASK);
    await stable();
    expect(host.events).toEqual(['saved:8']);
  });

  it('sends listId when another list is picked', async () => {
    await create();
    await pick(9);
    submit();
    const req = http.expectOne({ method: 'PUT', url: '/api/tasks/7' });
    expect(req.request.body.listId).toBe(9);
    expect(req.request.body.tags).toEqual(['Travel']);
    req.flush({ ...TASK, listId: 9 });
    await stable();
    expect(host.events).toEqual(['saved:9']);
  });

  it('TAG_LIMIT on a move is shown next to the List select, naming the target list', async () => {
    await create();
    await pick(9);
    submit();
    http
      .expectOne({ method: 'PUT', url: '/api/tasks/7' })
      .flush(
        problem(400, 'VALIDATION_FAILED', [
          { field: 'tags', code: 'TAG_LIMIT', message: 'A list can have at most 200 tags' },
        ]),
        { status: 400, statusText: 'Bad Request' },
      );
    await stable();
    expect(el.querySelector('#task-edit-list-error')?.textContent?.trim()).toBe(
      'Can\'t move to "Work": a list can have at most 200 tags',
    );
    expect(select().getAttribute('aria-invalid')).toBe('true');
    expect(el.querySelector('#task-edit-tags-error')).toBeNull();
    expect(host.events).toEqual([]);
  });

  it('TAG_LIMIT without a move stays under Tags', async () => {
    await create();
    submit();
    http
      .expectOne({ method: 'PUT', url: '/api/tasks/7' })
      .flush(
        problem(400, 'VALIDATION_FAILED', [
          { field: 'tags', code: 'TAG_LIMIT', message: 'A list can have at most 200 tags' },
        ]),
        { status: 400, statusText: 'Bad Request' },
      );
    await stable();
    expect(el.querySelector('#task-edit-tags-error')?.textContent?.trim()).toBe(
      'A list can have at most 200 tags',
    );
    expect(el.querySelector('#task-edit-list-error')).toBeNull();
  });

  it('a target list deleted meanwhile (LIST_NOT_FOUND): message, select reset, lists refetched', async () => {
    await create();
    await pick(9);
    submit();
    http
      .expectOne({ method: 'PUT', url: '/api/tasks/7' })
      .flush(problem(404, 'LIST_NOT_FOUND'), { status: 404, statusText: 'Not Found' });
    await stable();
    expect(el.querySelector('#task-edit-list-error')?.textContent).toContain(
      'This list no longer exists',
    );
    expect(host.events).toEqual([]);
    http.expectOne('/api/lists').flush([MINE, HOME]);
    await stable();
    expect(select().value).toBe('8');
    expect(Array.from(select().options).map((o) => o.value)).toEqual(['3', '8']);
  });

  it('the task gone (NOT_FOUND) during a move emits notFound', async () => {
    await create();
    await pick(9);
    submit();
    http
      .expectOne({ method: 'PUT', url: '/api/tasks/7' })
      .flush(problem(404, 'NOT_FOUND'), { status: 404, statusText: 'Not Found' });
    await stable();
    expect(host.events).toEqual(['notFound']);
  });

  it('disabled with a note when the lists failed to load; saving still works', async () => {
    await create('fail');
    expect(select().disabled).toBe(true);
    expect(el.querySelector('#task-edit-list-note')?.textContent).toContain(
      "Couldn't load your lists",
    );
    expect(store.error()).toBe(true);
    submit();
    const req = http.expectOne({ method: 'PUT', url: '/api/tasks/7' });
    expect('listId' in req.request.body).toBe(false);
    req.flush(TASK);
  });
});
