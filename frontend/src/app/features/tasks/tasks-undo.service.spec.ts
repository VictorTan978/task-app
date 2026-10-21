import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { Task } from './tasks.models';
import { TasksUndoService } from './tasks-undo.service';

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
  dueTime: null,
  myReminder: null,
  myReminderPassed: false,
  repeat: null,
  createdAt: '2026-10-08T09:15:02.123456Z',
  updatedAt: '2026-10-08T09:15:02.123456Z',
};

describe('TasksUndoService', () => {
  let service: TasksUndoService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(TasksUndoService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('patch sends PATCH with exactly the given fields (null clears, missing is unchanged)', async () => {
    const result = firstValueFrom(
      service.patch(12, { description: null, reminder: 'NONE', repeat: 'NEVER', tags: [] }),
    );
    const req = http.expectOne('/api/tasks/12');
    expect(req.request.method).toBe('PATCH');
    expect(req.request.body).toEqual({
      description: null,
      reminder: 'NONE',
      repeat: 'NEVER',
      tags: [],
    });
    req.flush({ ...TASK, nextTask: null });
    expect((await result).id).toBe(12);
  });

  it('undoComplete posts nextTaskId and status and returns the outcome', async () => {
    const result = firstValueFrom(
      service.undoComplete(12, { nextTaskId: 15, status: 'IN_PROGRESS' }),
    );
    const req = http.expectOne('/api/tasks/12/undo-complete');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ nextTaskId: 15, status: 'IN_PROGRESS' });
    req.flush({ ...TASK, nextTaskOutcome: 'KEPT' });
    expect((await result).nextTaskOutcome).toBe('KEPT');
  });

  it('propagates errors', async () => {
    const result = firstValueFrom(service.undoComplete(42, { nextTaskId: 43 }));
    http
      .expectOne('/api/tasks/42/undo-complete')
      .flush({ status: 404, code: 'NOT_FOUND' }, { status: 404, statusText: 'Not Found' });
    await expect(result).rejects.toMatchObject({ status: 404 });
  });
});
