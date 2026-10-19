import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { Task, TaskListQuery, TaskPage } from './tasks.models';
import { TasksService } from './tasks.service';

const TASK: Task = {
  id: 12,
  listId: 3,
  addedBy: { displayName: 'Ana' },
  title: 'Buy milk',
  description: null,
  priority: 'NONE',
  status: 'TODO',
  tags: [],
  done: false,
  dueDate: '2026-10-10',
  createdAt: '2026-10-08T09:15:02.123456Z',
  updatedAt: '2026-10-08T09:15:02.123456Z',
};

describe('TasksService', () => {
  let service: TasksService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(TasksService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  const Q: TaskListQuery = { filter: 'all', tag: null, q: null, sort: 'newest' };
  const PAGE: TaskPage = { items: [TASK], total: 120, nextCursor: 'abc_-1' };

  it('list at the defaults sends GET /api/tasks without params and returns the page', async () => {
    const result = firstValueFrom(service.list(Q));
    const req = http.expectOne('/api/tasks');
    expect(req.request.method).toBe('GET');
    expect(req.request.params.keys()).toEqual([]);
    req.flush(PAGE);
    expect(await result).toEqual(PAGE);
  });

  it.each(['active', 'in_progress', 'done'] as const)(
    'list with filter %s sends ?filter=%s',
    async (filter) => {
      const result = firstValueFrom(service.list({ ...Q, filter }));
      const req = http.expectOne(`/api/tasks?filter=${filter}`);
      expect(req.request.method).toBe('GET');
      req.flush({ items: [], total: 0, nextCursor: null });
      expect(await result).toEqual({ items: [], total: 0, nextCursor: null });
    },
  );

  it.each(['oldest', 'due', 'priority', 'title'] as const)('list sends ?sort=%s', (sort) => {
    service.list({ ...Q, sort }).subscribe();
    http.expectOne(`/api/tasks?sort=${sort}`).flush(PAGE);
  });

  it('list with a tag sends ?tag= (combined with filter) and leaves out blank tags', async () => {
    service.list({ ...Q, filter: 'done', tag: 'Wörk' }).subscribe();
    const req = http.expectOne((r) => r.url === '/api/tasks');
    expect(req.request.params.get('filter')).toBe('done');
    expect(req.request.params.get('tag')).toBe('Wörk');
    req.flush(PAGE);

    service.list({ ...Q, tag: '  ' }).subscribe();
    const blank = http.expectOne('/api/tasks');
    expect(blank.request.params.keys()).toEqual([]);
    blank.flush(PAGE);

    service.list({ ...Q, tag: 'Work' }).subscribe();
    http.expectOne('/api/tasks?tag=Work').flush(PAGE);
  });

  it('list sends q (encoded) with every other param and the cursor', () => {
    service
      .list({ filter: 'active', tag: 'Home', q: '50% a_b & c', sort: 'title' }, 'eyJ2IjoxfQ')
      .subscribe();
    const req = http.expectOne((r) => r.url === '/api/tasks');
    expect(req.request.params.get('q')).toBe('50% a_b & c');
    expect(req.request.params.get('filter')).toBe('active');
    expect(req.request.params.get('tag')).toBe('Home');
    expect(req.request.params.get('sort')).toBe('title');
    expect(req.request.params.get('cursor')).toBe('eyJ2IjoxfQ');
    expect(req.request.urlWithParams).toContain('q=50%25%20a_b%20%26%20c');
    req.flush(PAGE);
  });

  it('list leaves out a blank q and an empty cursor', () => {
    service.list({ ...Q, q: '   ' }, '').subscribe();
    const req = http.expectOne('/api/tasks');
    expect(req.request.params.keys()).toEqual([]);
    req.flush(PAGE);
  });

  it('create sends POST /api/tasks and returns the 201 task', async () => {
    const result = firstValueFrom(service.create({ title: 'Buy milk' }));
    const req = http.expectOne('/api/tasks');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ title: 'Buy milk' });
    req.flush(TASK, { status: 201, statusText: 'Created' });
    expect(await result).toEqual(TASK);
  });

  it('replace sends PUT /api/tasks/{id} with the given body', async () => {
    const body = {
      title: 'Buy oat milk',
      description: null,
      dueDate: null,
      priority: 'HIGH' as const,
      status: 'IN_PROGRESS' as const,
      tags: ['Home', 'Errands'],
    };
    const result = firstValueFrom(service.replace(12, body));
    const req = http.expectOne('/api/tasks/12');
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual(body);
    req.flush({ ...TASK, ...body, tags: ['Errands', 'Home'] });
    expect((await result).tags).toEqual(['Errands', 'Home']);
  });

  it.each(['DONE', 'TODO'] as const)('setStatus sends PATCH {status: %s}', async (status) => {
    const result = firstValueFrom(service.setStatus(12, status));
    const req = http.expectOne('/api/tasks/12');
    expect(req.request.method).toBe('PATCH');
    expect(req.request.body).toEqual({ status });
    req.flush({ ...TASK, status, done: status === 'DONE' });
    expect((await result).status).toBe(status);
  });

  it('listTags sends GET /api/tags and returns the objects', async () => {
    const result = firstValueFrom(service.listTags());
    const req = http.expectOne('/api/tags');
    expect(req.request.method).toBe('GET');
    req.flush([{ name: 'Errands' }, { name: 'Work' }]);
    expect(await result).toEqual([{ name: 'Errands' }, { name: 'Work' }]);
  });

  it('delete sends DELETE /api/tasks/{id} and completes on 204', async () => {
    const result = firstValueFrom(service.delete(12), { defaultValue: 'none' });
    const req = http.expectOne('/api/tasks/12');
    expect(req.request.method).toBe('DELETE');
    req.flush(null, { status: 204, statusText: 'No Content' });
    expect(await result).toBeUndefined();
  });

  it('propagates errors', async () => {
    const result = firstValueFrom(service.setStatus(42, 'DONE'));
    http
      .expectOne('/api/tasks/42')
      .flush({ status: 404, code: 'NOT_FOUND' }, { status: 404, statusText: 'Not Found' });
    await expect(result).rejects.toMatchObject({ status: 404 });
  });
});
