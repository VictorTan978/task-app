import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { User } from '../../core/auth/auth.models';
import { AuthService } from '../../core/auth/auth.service';
import { InvitationsStore } from './invitations.store';

const ALICE = { id: 1 } as User;
const BOB = { id: 2 } as User;

describe('InvitationsStore', () => {
  let store: InvitationsStore;
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
    store = TestBed.inject(InvitationsStore);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('is 0 until refreshed, then shows the count', () => {
    expect(store.count()).toBe(0);
    store.refresh();
    http.expectOne('/api/invitations/count').flush({ count: 2 });
    expect(store.count()).toBe(2);
  });

  it('a newer refresh replaces a running one', () => {
    store.refresh();
    const first = http.expectOne('/api/invitations/count');
    store.refresh();
    expect(first.cancelled).toBe(true);
    http.expectOne('/api/invitations/count').flush({ count: 1 });
    expect(store.count()).toBe(1);
  });

  it('keeps the last count when a refresh fails', () => {
    store.refresh();
    http.expectOne('/api/invitations/count').flush({ count: 3 });
    store.refresh();
    http
      .expectOne('/api/invitations/count')
      .flush(null, { status: 500, statusText: 'Server Error' });
    expect(store.count()).toBe(3);
  });

  it('set() overrides the count (never below 0) and cancels a running refresh', () => {
    store.refresh();
    const req = http.expectOne('/api/invitations/count');
    store.set(4);
    expect(req.cancelled).toBe(true);
    expect(store.count()).toBe(4);
    store.set(-1);
    expect(store.count()).toBe(0);
  });

  it('does nothing when signed out and never shows another user’s count', () => {
    store.refresh();
    http.expectOne('/api/invitations/count').flush({ count: 5 });
    user.set(BOB);
    expect(store.count()).toBe(0);
    user.set(null);
    TestBed.tick();
    store.refresh();
    http.expectNone('/api/invitations/count');
    user.set(ALICE);
    expect(store.count()).toBe(0);
  });
});
