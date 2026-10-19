import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { User } from '../../../core/auth/auth.models';
import { AuthService } from '../../../core/auth/auth.service';
import { TaskList } from '../lists.models';
import { ListsStore } from '../lists.store';
import { ListHeader } from './list-header';

const MINE: TaskList = { id: 3, name: 'My tasks', personal: true };
const HOME: TaskList = { id: 8, name: 'Home', personal: false };

@Component({
  imports: [ListHeader],
  template: `<app-list-header [list]="list()" (notFound)="events.push('notFound')" />`,
})
class Host {
  readonly list = signal<TaskList | null>(HOME);
  readonly events: string[] = [];
}

function problem(status: number, code: string, errors?: unknown[]) {
  return { type: 'about:blank', title: 'x', status, detail: 'x', code, errors };
}

describe('ListHeader', () => {
  let fixture: ComponentFixture<Host>;
  let host: Host;
  let el: HTMLElement;
  let http: HttpTestingController;
  let router: Router;
  let store: ListsStore;

  beforeAll(() => {
    HTMLDialogElement.prototype.showModal = function (this: HTMLDialogElement) {
      this.setAttribute('open', '');
    };
  });

  beforeEach(async () => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: AuthService, useValue: { currentUser: signal({ id: 1 } as User) } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    router = TestBed.inject(Router);
    store = TestBed.inject(ListsStore);
    vi.spyOn(router, 'navigateByUrl').mockResolvedValue(true);
    store.load();
    http.expectOne('/api/lists').flush([MINE, HOME]);
    fixture = TestBed.createComponent(Host);
    host = fixture.componentInstance;
    el = fixture.nativeElement;
    await fixture.whenStable();
  });

  afterEach(() => http.verify());

  const stable = () => fixture.whenStable();
  const q = <T extends Element = HTMLInputElement>(sel: string) => el.querySelector<T>(sel);
  const button = (name: string) =>
    Array.from(el.querySelectorAll('button')).find((b) => b.textContent?.trim() === name)!;

  async function menu(action: 'Rename' | 'Delete') {
    q<HTMLButtonElement>('.list-menu__toggle')!.click();
    await stable();
    button(action).click();
    await stable();
  }

  async function rename(name: string) {
    await menu('Rename');
    const input = q('#list-rename-name')!;
    input.value = name;
    input.dispatchEvent(new Event('input'));
    q<HTMLFormElement>('form')!.dispatchEvent(new Event('submit'));
    await stable();
  }

  it('shows the list name as the heading, with the menu', () => {
    expect(q('h1')?.textContent?.trim()).toBe('Home');
    expect(q('h1')?.id).toBe('tasks-heading');
    expect(q('.list-menu__toggle')).not.toBeNull();
  });

  it('shows the fallback and no menu while the list is unknown', async () => {
    host.list.set(null);
    await stable();
    expect(q('h1')?.textContent?.trim()).toBe('My tasks');
    expect(q('.list-menu__toggle')).toBeNull();
  });

  it('Rename opens an inline form prefilled with the name', async () => {
    await menu('Rename');
    expect(q('#list-rename-name')?.value).toBe('Home');
  });

  it('renames (trimmed), updates the store and closes the form', async () => {
    await rename('  Groceries ');
    const req = http.expectOne({ method: 'PATCH', url: '/api/lists/8' });
    expect(req.request.body).toEqual({ name: 'Groceries' });
    req.flush({ ...HOME, name: 'Groceries' });
    await stable();
    expect(store.byId().get(8)?.name).toBe('Groceries');
    http
      .expectOne({ method: 'GET', url: '/api/lists' })
      .flush([MINE, { ...HOME, name: 'Groceries' }]);
    await stable();
    expect(q('#list-rename-name')).toBeNull();
  });

  it('"My tasks" can be renamed', async () => {
    host.list.set(MINE);
    await stable();
    await rename('Inbox');
    http.expectOne({ method: 'PATCH', url: '/api/lists/3' }).flush({ ...MINE, name: 'Inbox' });
    http.expectOne({ method: 'GET', url: '/api/lists' }).flush([{ ...MINE, name: 'Inbox' }, HOME]);
    await stable();
    expect(store.personal()?.name).toBe('Inbox');
  });

  it('a blank name is refused next to the field without a request', async () => {
    await rename('  ');
    expect(q('#list-rename-name-error')?.textContent).toContain('Name is required');
    http.expectNone({ method: 'PATCH' });
  });

  it('a server name error appears next to the field', async () => {
    await rename('x');
    http
      .expectOne({ method: 'PATCH', url: '/api/lists/8' })
      .flush(
        problem(400, 'VALIDATION_FAILED', [
          { field: 'name', code: 'TOO_LONG', message: 'Name must be 100 characters or fewer' },
        ]),
        { status: 400, statusText: 'Bad Request' },
      );
    await stable();
    expect(q('#list-rename-name-error')?.textContent).toContain(
      'Name must be 100 characters or fewer',
    );
  });

  it('a failed rename offers Retry', async () => {
    await rename('Groceries');
    http
      .expectOne({ method: 'PATCH', url: '/api/lists/8' })
      .flush(null, { status: 500, statusText: 'Error' });
    await stable();
    expect(q('[role=alert]')?.textContent).toContain("Couldn't rename the list.");
    button('Retry').click();
    http.expectOne({ method: 'PATCH', url: '/api/lists/8' }).flush({ ...HOME, name: 'Groceries' });
    http.expectOne({ method: 'GET', url: '/api/lists' }).flush([MINE, HOME]);
    await stable();
    expect(q('#list-rename-name')).toBeNull();
  });

  it('LIST_NOT_FOUND on rename emits notFound', async () => {
    await rename('Groceries');
    http
      .expectOne({ method: 'PATCH', url: '/api/lists/8' })
      .flush(problem(404, 'LIST_NOT_FOUND'), { status: 404, statusText: 'Not Found' });
    await stable();
    expect(host.events).toEqual(['notFound']);
  });

  it('Escape cancels a rename', async () => {
    await menu('Rename');
    q('#list-rename-name')!.dispatchEvent(
      new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }),
    );
    await stable();
    expect(q('#list-rename-name')).toBeNull();
  });

  it('Delete opens the dialog; confirming removes the list and goes to /tasks', async () => {
    await menu('Delete');
    http.expectOne({ method: 'GET', url: '/api/lists/8' }).flush({ ...HOME, taskCount: 12 });
    await stable();
    expect(q('dialog h2')?.textContent?.trim()).toBe('Delete "Home" and its 12 tasks?');
    button('Delete').click();
    http
      .expectOne({ method: 'DELETE', url: '/api/lists/8' })
      .flush(null, { status: 204, statusText: 'No Content' });
    await stable();
    expect(store.byId().has(8)).toBe(false);
    http.expectOne({ method: 'GET', url: '/api/lists' }).flush([MINE]);
    expect(router.navigateByUrl).toHaveBeenCalledWith('/tasks');
    expect(q('dialog')).toBeNull();
  });

  it('cancelling the delete changes nothing', async () => {
    await menu('Delete');
    http.expectOne({ method: 'GET', url: '/api/lists/8' }).flush({ ...HOME, taskCount: 1 });
    await stable();
    button('Cancel').click();
    await stable();
    expect(q('dialog')).toBeNull();
    expect(store.byId().has(8)).toBe(true);
    expect(router.navigateByUrl).not.toHaveBeenCalled();
  });

  it('another list opening closes the rename form', async () => {
    await menu('Rename');
    host.list.set(MINE);
    await stable();
    expect(q('#list-rename-name')).toBeNull();
  });
});
