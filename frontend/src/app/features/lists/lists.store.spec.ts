import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { User } from '../../core/auth/auth.models';
import { AuthService } from '../../core/auth/auth.service';
import { TaskList } from './lists.models';
import { ListsStore } from './lists.store';

const ALICE = { id: 1, displayName: 'Alice' } as User;
const BOB = { id: 2, displayName: 'Bob' } as User;

const MINE: TaskList = { id: 3, name: 'My tasks', personal: true, role: 'OWNER', memberCount: 1 };
const HOME: TaskList = { id: 8, name: 'Home', personal: false, role: 'OWNER', memberCount: 1 };
const WORK: TaskList = { id: 9, name: 'Work', personal: false, role: 'OWNER', memberCount: 1 };

describe('ListsStore', () => {
  let store: ListsStore;
  let http: HttpTestingController;
  let user: ReturnType<typeof signal<User | null>>;

  beforeEach(() => {
    user = signal<User | null>(ALICE);
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: AuthService, useValue: { currentUser: user } },
      ],
    });
    store = TestBed.inject(ListsStore);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  const flushLists = (lists: TaskList[]) => http.expectOne('/api/lists').flush(lists);

  it('starts empty and loads once', () => {
    expect(store.lists()).toEqual([]);
    expect(store.loaded()).toBe(false);
    store.load();
    expect(store.loading()).toBe(true);
    store.load(); // no second request while loading
    flushLists([MINE, HOME]);
    expect(store.loaded()).toBe(true);
    expect(store.loading()).toBe(false);
    expect(store.lists()).toEqual([MINE, HOME]);
    expect(store.personal()).toEqual(MINE);
    expect(store.byId().get(8)).toEqual(HOME);
    store.load(); // already loaded: no request
    http.expectNone('/api/lists');
  });

  it('does nothing when nobody is signed in', () => {
    user.set(null);
    store.load();
    store.refresh();
    http.expectNone('/api/lists');
  });

  it('refresh keeps the shown lists until the answer arrives', () => {
    store.load();
    flushLists([MINE]);
    store.refresh();
    expect(store.lists()).toEqual([MINE]);
    expect(store.loading()).toBe(true);
    flushLists([MINE, HOME]);
    expect(store.lists()).toEqual([MINE, HOME]);
  });

  it('a failed load sets error; Retry (refresh) clears it', () => {
    store.load();
    http.expectOne('/api/lists').flush(null, { status: 500, statusText: 'Error' });
    expect(store.error()).toBe(true);
    expect(store.loaded()).toBe(false);
    store.refresh();
    expect(store.error()).toBe(false);
    flushLists([MINE]);
    expect(store.loaded()).toBe(true);
  });

  it('401 is not shown as an error', () => {
    store.load();
    http.expectOne('/api/lists').flush(null, { status: 401, statusText: 'Unauthorized' });
    expect(store.error()).toBe(false);
  });

  it('upsert keeps picker order ("My tasks" first, then name, then id) and remove drops', () => {
    store.load();
    flushLists([MINE, WORK]);
    store.upsert(HOME);
    expect(store.lists().map((l) => l.id)).toEqual([3, 8, 9]);
    store.upsert({ ...WORK, name: 'Apples' });
    expect(store.lists().map((l) => l.name)).toEqual(['My tasks', 'Apples', 'Home']);
    store.upsert({ ...MINE, name: 'Zed' });
    expect(store.lists()[0].name).toBe('Zed');
    store.remove(8);
    expect(store.lists().map((l) => l.id)).toEqual([3, 9]);
  });

  it('never shows one user the lists of another (same tab)', () => {
    store.load();
    flushLists([MINE, HOME]);
    user.set(BOB);
    expect(store.lists()).toEqual([]);
    expect(store.loaded()).toBe(false);
    store.load();
    flushLists([{ id: 20, name: 'My tasks', personal: true, role: 'OWNER', memberCount: 1 }]);
    expect(store.lists().map((l) => l.id)).toEqual([20]);
  });

  it('drops an answer that arrives after the user changed', () => {
    store.load();
    const req = http.expectOne('/api/lists');
    user.set(BOB);
    req.flush([MINE, HOME]);
    expect(store.lists()).toEqual([]);
  });

  it('sign-out clears everything, so the same user signing in again reloads', () => {
    store.load();
    flushLists([MINE, HOME]);
    user.set(null);
    TestBed.tick();
    user.set(ALICE);
    expect(store.lists()).toEqual([]);
    store.load();
    flushLists([MINE]);
    expect(store.lists()).toEqual([MINE]);
  });

  it('sign-out cancels a load in flight', () => {
    store.load();
    const req = http.expectOne('/api/lists');
    user.set(null);
    TestBed.tick();
    expect(req.cancelled).toBe(true);
  });
});
