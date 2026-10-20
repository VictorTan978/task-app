import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { NotificationsService } from './notifications.service';

describe('NotificationsService', () => {
  let service: NotificationsService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(NotificationsService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('lists the first page with limit and no cursor', () => {
    let page: unknown;
    service.list(null).subscribe((p) => (page = p));
    const req = http.expectOne((r) => r.url === '/api/notifications');
    expect(req.request.method).toBe('GET');
    expect(req.request.params.get('limit')).toBe('20');
    expect(req.request.params.has('cursor')).toBe(false);
    req.flush({ items: [], nextCursor: null });
    expect(page).toEqual({ items: [], nextCursor: null });
  });

  it('sends the cursor for the next page', () => {
    service.list('abc').subscribe();
    const req = http.expectOne((r) => r.url === '/api/notifications');
    expect(req.request.params.get('cursor')).toBe('abc');
    req.flush({ items: [], nextCursor: null });
  });

  it('reads the unread count', () => {
    let count = -1;
    service.unreadCount().subscribe((c) => (count = c));
    http.expectOne('/api/notifications/count').flush({ count: 4 });
    expect(count).toBe(4);
  });

  it('marks one read with POST and no body', () => {
    service.markRead(5).subscribe();
    const req = http.expectOne('/api/notifications/5/read');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toBeNull();
    req.flush({ id: 5, read: true });
  });

  it('marks all read (204)', () => {
    let done = false;
    service.markAllRead().subscribe(() => (done = true));
    const req = http.expectOne('/api/notifications/read-all');
    expect(req.request.method).toBe('POST');
    req.flush(null, { status: 204, statusText: 'No Content' });
    expect(done).toBe(true);
  });
});
