import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { ListsService } from './lists.service';

describe('ListsService', () => {
  let service: ListsService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(ListsService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('list sends GET /api/lists', async () => {
    const result = firstValueFrom(service.list());
    const req = http.expectOne('/api/lists');
    expect(req.request.method).toBe('GET');
    req.flush([{ id: 3, name: 'My tasks', personal: true, role: 'OWNER', memberCount: 1 }]);
    expect(await result).toEqual([
      { id: 3, name: 'My tasks', personal: true, role: 'OWNER', memberCount: 1 },
    ]);
  });

  it('get sends GET /api/lists/{id} and returns the task count', async () => {
    const result = firstValueFrom(service.get(8));
    const req = http.expectOne('/api/lists/8');
    expect(req.request.method).toBe('GET');
    req.flush({
      id: 8,
      name: 'Home',
      personal: false,
      role: 'OWNER',
      memberCount: 1,
      taskCount: 12,
    });
    expect((await result).taskCount).toBe(12);
  });

  it('create sends POST /api/lists {name}', async () => {
    const result = firstValueFrom(service.create({ name: 'Home' }));
    const req = http.expectOne('/api/lists');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ name: 'Home' });
    req.flush(
      { id: 8, name: 'Home', personal: false, role: 'OWNER', memberCount: 1 },
      { status: 201, statusText: 'Created' },
    );
    expect((await result).id).toBe(8);
  });

  it('rename sends PATCH /api/lists/{id} {name}', async () => {
    const result = firstValueFrom(service.rename(8, { name: 'Groceries' }));
    const req = http.expectOne('/api/lists/8');
    expect(req.request.method).toBe('PATCH');
    expect(req.request.body).toEqual({ name: 'Groceries' });
    req.flush({ id: 8, name: 'Groceries', personal: false, role: 'OWNER', memberCount: 1 });
    expect((await result).name).toBe('Groceries');
  });

  it('delete sends DELETE /api/lists/{id} and completes on 204', async () => {
    const result = firstValueFrom(service.delete(8));
    const req = http.expectOne('/api/lists/8');
    expect(req.request.method).toBe('DELETE');
    req.flush(null, { status: 204, statusText: 'No Content' });
    expect(await result).toBeUndefined();
  });
});
