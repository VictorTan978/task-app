import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { Task } from './tasks.models';
import { TasksService } from './tasks.service';

const TASK: Task = {
  id: 12,
  title: 'Buy milk',
  description: null,
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

  it('list("all") sends GET /api/tasks without a filter param', async () => {
    const result = firstValueFrom(service.list('all'));
    const req = http.expectOne('/api/tasks');
    expect(req.request.method).toBe('GET');
    expect(req.request.params.has('filter')).toBe(false);
    req.flush([TASK]);
    expect(await result).toEqual([TASK]);
  });

  it.each(['active', 'done'] as const)('list("%s") sends ?filter=%s', async (filter) => {
    const result = firstValueFrom(service.list(filter));
    const req = http.expectOne(`/api/tasks?filter=${filter}`);
    expect(req.request.method).toBe('GET');
    req.flush([]);
    expect(await result).toEqual([]);
  });

  it('create sends POST /api/tasks and returns the 201 task', async () => {
    const result = firstValueFrom(service.create({ title: 'Buy milk' }));
    const req = http.expectOne('/api/tasks');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ title: 'Buy milk' });
    req.flush(TASK, { status: 201, statusText: 'Created' });
    expect(await result).toEqual(TASK);
  });

  it('replace sends PUT /api/tasks/{id} with all four fields', async () => {
    const body = { title: 'Buy oat milk', description: null, dueDate: null, done: true };
    const result = firstValueFrom(service.replace(12, body));
    const req = http.expectOne('/api/tasks/12');
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual(body);
    req.flush({ ...TASK, ...body });
    expect((await result).title).toBe('Buy oat milk');
  });

  it('setDone sends PATCH /api/tasks/{id} with {done}', async () => {
    const result = firstValueFrom(service.setDone(12, true));
    const req = http.expectOne('/api/tasks/12');
    expect(req.request.method).toBe('PATCH');
    expect(req.request.body).toEqual({ done: true });
    req.flush({ ...TASK, done: true });
    expect((await result).done).toBe(true);
  });

  it('delete sends DELETE /api/tasks/{id} and completes on 204', async () => {
    const result = firstValueFrom(service.delete(12), { defaultValue: 'none' });
    const req = http.expectOne('/api/tasks/12');
    expect(req.request.method).toBe('DELETE');
    req.flush(null, { status: 204, statusText: 'No Content' });
    expect(await result).toBeUndefined();
  });

  it('propagates errors', async () => {
    const result = firstValueFrom(service.setDone(42, true));
    http
      .expectOne('/api/tasks/42')
      .flush({ status: 404, code: 'NOT_FOUND' }, { status: 404, statusText: 'Not Found' });
    await expect(result).rejects.toMatchObject({ status: 404 });
  });
});
