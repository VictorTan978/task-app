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
  dueDate: null,
  dueTime: null,
  myReminder: null,
  myReminderPassed: false,
  repeat: null,
  createdAt: '2026-10-08T09:15:02Z',
  updatedAt: '2026-10-08T09:15:02Z',
};
const T6 = { ...TASK, id: 6 };

describe('TaskUndo bulk', () => {
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

  it('bulk-restore is one POST /api/trash/restore', async () => {
    const result = firstValueFrom(undo.runBulk({ kind: 'bulk-restore', ids: [5, 6] }));
    const req = http.expectOne({ method: 'POST', url: '/api/trash/restore' });
    expect(req.request.body).toEqual({ ids: [5, 6] });
    req.flush({ tasks: [TASK, T6] });
    expect(await result).toEqual({
      tasks: [TASK, T6],
      removedIds: [],
      kept: false,
      message: 'Undone',
    });
  });

  it('bulk-patch is one POST with each task patch', async () => {
    const items = [
      { id: 5, patch: { priority: 'LOW' as const } },
      { id: 6, patch: { priority: 'NONE' as const } },
    ];
    const result = firstValueFrom(undo.runBulk({ kind: 'bulk-patch', items }));
    const req = http.expectOne({ method: 'POST', url: '/api/tasks/bulk-patch' });
    expect(req.request.body).toEqual({ items });
    req.flush({
      count: 2,
      tasks: [
        { ...TASK, nextTask: null },
        { ...T6, nextTask: null },
      ],
    });
    expect((await result).tasks.map((t) => t.id)).toEqual([5, 6]);
  });

  it('bulk-tag sends the opposite tag action on the changed ids', async () => {
    const result = firstValueFrom(
      undo.runBulk({ kind: 'bulk-tag', action: 'REMOVE_TAG', ids: [5], tag: 'Home' }),
    );
    const req = http.expectOne({ method: 'POST', url: '/api/tasks/bulk' });
    expect(req.request.body).toEqual({ ids: [5], action: 'REMOVE_TAG', value: 'Home' });
    req.flush({ count: 1, tasks: [{ ...TASK, nextTask: null }] });
    expect((await result).message).toBe('Undone');
  });

  const items = [
    { id: 5, nextTaskId: 9, status: 'TODO' as const },
    { id: 6, status: 'IN_PROGRESS' as const },
  ];

  it('bulk-complete: ONE call; copies DELETED, GONE and NONE are removed or untouched', async () => {
    const result = firstValueFrom(undo.runBulk({ kind: 'bulk-complete', items }));
    const req = http.expectOne({ method: 'POST', url: '/api/tasks/bulk-undo-complete' });
    expect(req.request.body).toEqual({ items });
    req.flush({
      results: [
        { ...TASK, nextTaskOutcome: 'GONE' },
        { ...T6, nextTaskOutcome: 'NONE' },
      ],
    });
    expect(await result).toMatchObject({ removedIds: [9], kept: false, message: 'Undone' });
  });

  it('bulk-complete: a KEPT copy stays and the kept wording is used', async () => {
    const result = firstValueFrom(undo.runBulk({ kind: 'bulk-complete', items }));
    http.expectOne('/api/tasks/bulk-undo-complete').flush({
      results: [
        { ...TASK, nextTaskOutcome: 'KEPT' },
        { ...T6, nextTaskOutcome: 'NONE' },
      ],
    });
    expect(await result).toMatchObject({
      removedIds: [],
      kept: true,
      message: 'Undone: the next copy was already changed, so it was kept',
    });
  });

  it('maps errors like a single undo', async () => {
    const gone = firstValueFrom(undo.runBulk({ kind: 'bulk-restore', ids: [5] }));
    http.expectOne('/api/trash/restore').flush(null, { status: 404, statusText: 'x' });
    await expect(gone).rejects.toEqual({
      message: "Couldn't undo: the task was deleted or you no longer have access",
    });
    const generic = firstValueFrom(undo.runBulk({ kind: 'bulk-restore', ids: [5] }));
    http.expectOne('/api/trash/restore').flush(null, { status: 500, statusText: 'x' });
    await expect(generic).rejects.toEqual({ message: "Couldn't undo. Please try again." });
  });
});
