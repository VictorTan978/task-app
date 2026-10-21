import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { User } from '../../../core/auth/auth.models';
import { AuthService } from '../../../core/auth/auth.service';
import { TaskList } from '../lists.models';
import { ListsStore } from '../lists.store';
import { ListPicker, listLink } from './list-picker';

const MINE: TaskList = { id: 3, name: 'My tasks', personal: true, role: 'OWNER', memberCount: 1 };
const HOME: TaskList = { id: 8, name: 'Home', personal: false, role: 'OWNER', memberCount: 1 };
const WORK: TaskList = { id: 9, name: 'Work', personal: false, role: 'OWNER', memberCount: 1 };

@Component({
  imports: [ListPicker],
  template: `<app-list-picker [activeId]="activeId()" [trashActive]="trashActive()" />`,
})
class Host {
  readonly activeId = signal<number | null>(3);
  readonly trashActive = signal(false);
}

function problem(status: number, code: string, errors?: unknown[]) {
  return {
    type: 'about:blank',
    title: 'x',
    status,
    detail: 'You can have at most 50 lists',
    code,
    errors,
  };
}

describe('ListPicker', () => {
  let fixture: ComponentFixture<Host>;
  let el: HTMLElement;
  let http: HttpTestingController;
  let router: Router;
  let store: ListsStore;

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
    fixture = TestBed.createComponent(Host);
    el = fixture.nativeElement;
    await fixture.whenStable();
  });

  afterEach(() => http.verify());

  const stable = () => fixture.whenStable();
  const q = <T extends Element = HTMLInputElement>(sel: string) => el.querySelector<T>(sel);
  const links = () => Array.from(el.querySelectorAll<HTMLAnchorElement>('nav[aria-label=Lists] a'));
  const button = (name: string) =>
    Array.from(el.querySelectorAll('button')).find((b) => b.textContent?.trim() === name)!;

  async function loadLists(lists: TaskList[] = [MINE, HOME, WORK]) {
    store.load();
    http.expectOne('/api/lists').flush(lists);
    await stable();
  }

  async function openForm(name: string) {
    button('+ New list').click();
    await stable();
    const input = q('#list-create-name')!;
    input.value = name;
    input.dispatchEvent(new Event('input'));
    q<HTMLFormElement>('form')!.dispatchEvent(new Event('submit'));
    await stable();
  }

  it('listLink: "My tasks" is /tasks, others /lists/<id>', () => {
    expect(listLink(MINE)).toBe('/tasks');
    expect(listLink(HOME)).toBe('/lists/8');
  });

  it('shows loading, then every list in store order with the active one marked', async () => {
    store.load();
    await stable();
    expect(el.textContent).toContain('Loading lists…');
    http.expectOne('/api/lists').flush([MINE, HOME, WORK]);
    await stable();
    expect(links().map((a) => a.textContent?.trim())).toEqual(['My tasks', 'Home', 'Work']);
    expect(links().map((a) => a.getAttribute('href'))).toEqual(['/tasks', '/lists/8', '/lists/9']);
    expect(links()[0].getAttribute('aria-current')).toBe('page');
    expect(links()[1].getAttribute('aria-current')).toBeNull();
  });

  it('links carry no query params', async () => {
    await loadLists();
    expect(links().every((a) => !a.getAttribute('href')!.includes('?'))).toBe(true);
  });

  it('phone select lists the same lists and navigates on change', async () => {
    await loadLists();
    const select = q<HTMLSelectElement>('#list-picker-select')!;
    expect(Array.from(select.options).map((o) => o.textContent?.trim())).toEqual([
      'My tasks',
      'Home',
      'Work',
      'Trash',
    ]);
    select.value = '8';
    select.dispatchEvent(new Event('change'));
    expect(router.navigateByUrl).toHaveBeenCalledWith('/lists/8');
  });

  describe('Trash entry', () => {
    const trashLink = () => q<HTMLAnchorElement>('a[href="/trash"]');

    it('is a link to /trash under the lists (not among the lists), not active on a list page', async () => {
      await loadLists();
      expect(links().map((a) => a.getAttribute('href'))).not.toContain('/trash');
      expect(trashLink()?.getAttribute('href')).toBe('/trash');
      expect(trashLink()?.textContent).toContain('Trash');
      expect(trashLink()?.getAttribute('aria-current')).toBeNull();
    });

    it('the phone select has a Trash option that opens /trash', async () => {
      await loadLists();
      const select = q<HTMLSelectElement>('#list-picker-select')!;
      select.value = 'trash';
      select.dispatchEvent(new Event('change'));
      expect(router.navigateByUrl).toHaveBeenCalledWith('/trash');
    });

    it('on the Trash page the link is current and the phone select shows Trash', async () => {
      fixture.componentInstance.trashActive.set(true);
      fixture.componentInstance.activeId.set(null);
      await loadLists();
      expect(trashLink()?.getAttribute('aria-current')).toBe('page');
      expect(links().every((a) => a.getAttribute('aria-current') === null)).toBe(true);
      const select = q<HTMLSelectElement>('#list-picker-select')!;
      expect(select.selectedOptions[0].textContent?.trim()).toBe('Trash');
      // Choosing Trash again does nothing.
      select.dispatchEvent(new Event('change'));
      expect(router.navigateByUrl).not.toHaveBeenCalled();
    });
  });

  it('phone select shows a placeholder when the open list is unknown', async () => {
    fixture.componentInstance.activeId.set(null);
    await loadLists();
    const select = q<HTMLSelectElement>('#list-picker-select')!;
    expect(select.options[0].textContent?.trim()).toBe('Choose a list');
  });

  it('a failed load shows an error with Retry', async () => {
    store.load();
    http.expectOne('/api/lists').flush(null, { status: 500, statusText: 'Error' });
    await stable();
    expect(q('[role=alert]')?.textContent).toContain("Couldn't load your lists.");
    button('Retry').click();
    http.expectOne('/api/lists').flush([MINE]);
    await stable();
    expect(q('[role=alert]')).toBeNull();
    expect(links()).toHaveLength(1);
  });

  it('creates a list (trimmed), refreshes the store and opens it', async () => {
    await loadLists([MINE]);
    await openForm('  Home ');
    const req = http.expectOne({ method: 'POST', url: '/api/lists' });
    expect(req.request.body).toEqual({ name: 'Home' });
    req.flush(HOME, { status: 201, statusText: 'Created' });
    await stable();
    expect(links().map((a) => a.textContent?.trim())).toEqual(['My tasks', 'Home']);
    http.expectOne({ method: 'GET', url: '/api/lists' }).flush([MINE, HOME]);
    expect(router.navigateByUrl).toHaveBeenCalledWith('/lists/8');
    await stable();
    expect(q('#list-create-name')).toBeNull();
  });

  it('blank and 101-character names are refused without a request', async () => {
    await loadLists([MINE]);
    await openForm('   ');
    expect(q('#list-create-name-error')?.textContent).toContain('Name is required');
    const input = q('#list-create-name')!;
    input.value = 'a'.repeat(101);
    input.dispatchEvent(new Event('input'));
    await stable();
    expect(q('#list-create-name-error')?.textContent).toContain(
      'Name must be 100 characters or fewer',
    );
    expect(input.getAttribute('aria-invalid')).toBe('true');
    http.expectNone({ method: 'POST', url: '/api/lists' });
  });

  it('shows a server name error next to the field', async () => {
    await loadLists([MINE]);
    await openForm('x');
    http
      .expectOne({ method: 'POST', url: '/api/lists' })
      .flush(
        problem(400, 'VALIDATION_FAILED', [
          { field: 'name', code: 'REQUIRED', message: 'Name is required' },
        ]),
        { status: 400, statusText: 'Bad Request' },
      );
    await stable();
    expect(q('#list-create-name-error')?.textContent).toContain('Name is required');
  });

  it('LIST_LIMIT shows the limit message without Retry; nothing is added', async () => {
    await loadLists([MINE]);
    await openForm('Fifty-one');
    http
      .expectOne({ method: 'POST', url: '/api/lists' })
      .flush(problem(409, 'LIST_LIMIT'), { status: 409, statusText: 'Conflict' });
    await stable();
    expect(q('#list-create-error')?.textContent).toContain('You can have at most 50 lists');
    expect(q('#list-create-error button')).toBeNull();
    expect(links()).toHaveLength(1);
    expect(router.navigateByUrl).not.toHaveBeenCalled();
  });

  it('a server error offers Retry, which resends the same name', async () => {
    await loadLists([MINE]);
    await openForm('Home');
    http
      .expectOne({ method: 'POST', url: '/api/lists' })
      .flush(null, { status: 500, statusText: 'Error' });
    await stable();
    expect(q('#list-create-error')?.textContent).toContain("Couldn't create the list.");
    button('Retry').click();
    const req = http.expectOne({ method: 'POST', url: '/api/lists' });
    expect(req.request.body).toEqual({ name: 'Home' });
    req.flush(HOME, { status: 201, statusText: 'Created' });
    http.expectOne({ method: 'GET', url: '/api/lists' }).flush([MINE, HOME]);
    await stable();
  });

  it('Cancel and Escape close the form', async () => {
    await loadLists([MINE]);
    button('+ New list').click();
    await stable();
    button('Cancel').click();
    await stable();
    expect(q('#list-create-name')).toBeNull();
    button('+ New list').click();
    await stable();
    q('#list-create-name')!.dispatchEvent(
      new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }),
    );
    await stable();
    expect(q('#list-create-name')).toBeNull();
  });

  it('shows a member count on shared lists only (sidebar and phone select)', async () => {
    const team: TaskList = {
      id: 10,
      name: 'Team',
      personal: false,
      role: 'MEMBER',
      memberCount: 3,
    };
    await loadLists([MINE, HOME, team]);
    const counts = Array.from(el.querySelectorAll('[data-testid=list-member-count]'));
    expect(counts.length).toBe(1);
    const teamLink = links().find((a) => a.getAttribute('href') === '/lists/10')!;
    expect(teamLink.textContent?.replace(/\s+/g, ' ').trim()).toBe('Team 3, 3 members');
    expect(teamLink.querySelector('[aria-hidden=true]')?.textContent).toBe('3');
    const options = Array.from(el.querySelectorAll('option')).map((o) => o.textContent?.trim());
    expect(options).toEqual(['My tasks', 'Home', 'Team (3 members)', 'Trash']);
  });
});
