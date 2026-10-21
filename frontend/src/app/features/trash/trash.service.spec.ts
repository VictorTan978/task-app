import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { Task } from '../tasks/tasks.models';
import { TrashService } from './trash.service';

const TASK: Task = {
  id: 12,
  listId: 3,
  addedBy: { displayName: 'Ana' },
  title: 'Water plants',
  description: null,
  priority: 'NONE',
  status: 'TODO',
  tags: [],
  done: false,
  dueDate: null,
  dueTime: null,
  myReminder: null,
  myReminderPassed: false,
  repeat: null,
  createdAt: '2026-10-08T09:15:02.123456Z',
  updatedAt: '2026-10-08T09:15:02.123456Z',
};

describe('TrashService', () => {
  let service: TrashService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(TrashService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('list with no list leaves list out (every list) and cursor out', async () => {
    const result = firstValueFrom(service.list(null));
    const req = http.expectOne('/api/trash');
    expect(req.request.method).toBe('GET');
    expect(req.request.params.keys()).toEqual([]);
    const page = { items: [], total: 0, nextCursor: null };
    req.flush(page);
    expect(await result).toEqual(page);
  });

  it('list sends list and cursor when given', () => {
    service.list(7, 'c1').subscribe();
    const req = http.expectOne((r) => r.url === '/api/trash');
    expect(req.request.params.get('list')).toBe('7');
    expect(req.request.params.get('cursor')).toBe('c1');
    req.flush({ items: [], total: 0, nextCursor: null });
  });

  it('restore posts to /api/trash/{id}/restore and returns the task', async () => {
    const result = firstValueFrom(service.restore(12));
    const req = http.expectOne('/api/trash/12/restore');
    expect(req.request.method).toBe('POST');
    req.flush(TASK);
    expect(await result).toEqual(TASK);
  });

  it('deleteForever sends DELETE /api/trash/{id} and completes on 204', async () => {
    const result = firstValueFrom(service.deleteForever(12), { defaultValue: 'none' });
    const req = http.expectOne('/api/trash/12');
    expect(req.request.method).toBe('DELETE');
    req.flush(null, { status: 204, statusText: 'No Content' });
    expect(await result).toBeUndefined();
  });

  it('empty posts to /api/trash/empty, with list only when filtered', async () => {
    const all = firstValueFrom(service.empty(null));
    const req = http.expectOne('/api/trash/empty');
    expect(req.request.method).toBe('POST');
    expect(req.request.params.keys()).toEqual([]);
    req.flush({ deleted: 7 });
    expect(await all).toEqual({ deleted: 7 });

    service.empty(4).subscribe();
    const filtered = http.expectOne((r) => r.url === '/api/trash/empty');
    expect(filtered.request.params.get('list')).toBe('4');
    filtered.flush({ deleted: 1 });
  });

  it('propagates errors', async () => {
    const result = firstValueFrom(service.restore(99));
    http
      .expectOne('/api/trash/99/restore')
      .flush({ status: 404, code: 'NOT_FOUND' }, { status: 404, statusText: 'Not Found' });
    await expect(result).rejects.toMatchObject({ status: 404 });
  });
});
