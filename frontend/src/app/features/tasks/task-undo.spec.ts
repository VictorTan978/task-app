import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { TaskUndo } from './task-undo';
import { Task } from './tasks.models';

const TASK: Task = {
  id: 5,
  listId: 3,
  addedBy: null,
  title: 'Water plants',
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
  createdAt: '2026-10-08T09:15:02Z',
  updatedAt: '2026-10-08T09:15:02Z',
};

describe('TaskUndo', () => {
  let undo: TaskUndo;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    undo = TestBed.inject(TaskUndo);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('restore posts to the trash', async () => {
    const result = firstValueFrom(undo.run({ kind: 'restore', id: 5 }));
    http.expectOne({ method: 'POST', url: '/api/trash/5/restore' }).flush(TASK);
    expect(await result).toEqual({
      task: TASK,
      removedId: null,
      kept: false,
      message: 'Undone',
    });
  });

  it('patch sends only the planned fields', async () => {
    const result = firstValueFrom(undo.run({ kind: 'patch', id: 5, patch: { status: 'TODO' } }));
    const req = http.expectOne({ method: 'PATCH', url: '/api/tasks/5' });
    expect(req.request.body).toEqual({ status: 'TODO' });
    req.flush({ ...TASK, nextTask: null });
    expect((await result).task.id).toBe(5);
  });

  const complete = { kind: 'complete', id: 5, nextTaskId: 6, status: 'TODO' } as const;

  it('complete DELETED removes the copy', async () => {
    const result = firstValueFrom(undo.run({ ...complete, followUp: null }));
    const req = http.expectOne({ method: 'POST', url: '/api/tasks/5/undo-complete' });
    expect(req.request.body).toEqual({ nextTaskId: 6, status: 'TODO' });
    req.flush({ ...TASK, nextTaskOutcome: 'DELETED' });
    expect(await result).toMatchObject({ removedId: 6, kept: false });
  });

  it('complete GONE also removes the copy from view', async () => {
    const result = firstValueFrom(undo.run({ ...complete, followUp: null }));
    http.expectOne('/api/tasks/5/undo-complete').flush({ ...TASK, nextTaskOutcome: 'GONE' });
    expect(await result).toMatchObject({ removedId: 6, kept: false });
  });

  it('complete KEPT keeps the copy and says so', async () => {
    const result = firstValueFrom(undo.run({ ...complete, followUp: null }));
    http.expectOne('/api/tasks/5/undo-complete').flush({ ...TASK, nextTaskOutcome: 'KEPT' });
    expect(await result).toMatchObject({
      removedId: null,
      kept: true,
      message: 'Undone: the next copy was already changed, so it was kept',
    });
  });

  it('complete with a follow-up patches the other fields after, and returns that task', async () => {
    const result = firstValueFrom(undo.run({ ...complete, followUp: { title: 'Old' } }));
    http.expectOne('/api/tasks/5/undo-complete').flush({ ...TASK, nextTaskOutcome: 'DELETED' });
    const req = http.expectOne({ method: 'PATCH', url: '/api/tasks/5' });
    expect(req.request.body).toEqual({ title: 'Old' });
    req.flush({ ...TASK, title: 'Old', nextTask: null });
    expect(await result).toMatchObject({ removedId: 6, task: { title: 'Old' } });
  });

  it('maps a 404 to the "deleted or no access" message', async () => {
    const result = firstValueFrom(undo.run({ kind: 'restore', id: 5 }));
    http.expectOne('/api/trash/5/restore').flush(null, { status: 404, statusText: 'x' });
    await expect(result).rejects.toEqual({
      message: "Couldn't undo: the task was deleted or you no longer have access",
    });
  });

  it('maps another error to the generic message, and a 401 to no message', async () => {
    const generic = firstValueFrom(undo.run({ kind: 'restore', id: 5 }));
    http.expectOne('/api/trash/5/restore').flush(null, { status: 500, statusText: 'x' });
    await expect(generic).rejects.toEqual({ message: "Couldn't undo. Please try again." });
    const silent = firstValueFrom(undo.run({ kind: 'restore', id: 5 }));
    http.expectOne('/api/trash/5/restore').flush(null, { status: 401, statusText: 'x' });
    await expect(silent).rejects.toEqual({ message: null });
  });
});
