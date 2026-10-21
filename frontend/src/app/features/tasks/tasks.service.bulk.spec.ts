import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { TrashService } from '../trash/trash.service';
import { TasksUndoService } from './tasks-undo.service';
import { TasksService } from './tasks.service';

describe('bulk requests', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('TasksService.bulk posts ids, action and value', () => {
    TestBed.inject(TasksService)
      .bulk({ ids: [1, 2], action: 'SET_PRIORITY', value: 'HIGH' })
      .subscribe((r) => expect(r.count).toBe(2));
    const req = http.expectOne({ method: 'POST', url: '/api/tasks/bulk' });
    expect(req.request.body).toEqual({ ids: [1, 2], action: 'SET_PRIORITY', value: 'HIGH' });
    req.flush({ count: 2, tasks: [] });
  });

  it('TasksUndoService.bulkPatch posts the items', () => {
    const items = [{ id: 1, patch: { status: 'TODO' as const } }];
    TestBed.inject(TasksUndoService).bulkPatch({ items }).subscribe();
    const req = http.expectOne({ method: 'POST', url: '/api/tasks/bulk-patch' });
    expect(req.request.body).toEqual({ items });
    req.flush({ count: 1, tasks: [] });
  });

  it('TasksUndoService.bulkUndoComplete posts items with and without nextTaskId', () => {
    const items = [{ id: 1, nextTaskId: 9, status: 'TODO' as const }, { id: 2 }];
    TestBed.inject(TasksUndoService).bulkUndoComplete({ items }).subscribe();
    const req = http.expectOne({ method: 'POST', url: '/api/tasks/bulk-undo-complete' });
    expect(req.request.body).toEqual({ items });
    req.flush({ results: [] });
  });

  it('TrashService.restoreMany posts the ids', () => {
    TestBed.inject(TrashService).restoreMany([4, 5]).subscribe();
    const req = http.expectOne({ method: 'POST', url: '/api/trash/restore' });
    expect(req.request.body).toEqual({ ids: [4, 5] });
    req.flush({ tasks: [] });
  });
});
