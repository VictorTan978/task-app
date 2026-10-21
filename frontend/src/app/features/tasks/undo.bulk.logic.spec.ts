import { Task, TaskResult } from './tasks.models';
import { bulkMessage, planBulkUndo } from './undo.logic';

function task(id: number, overrides: Partial<Task> = {}): Task {
  return {
    id,
    listId: 3,
    addedBy: null,
    title: `Task ${id}`,
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
    ...overrides,
  };
}
const res = (t: Task, nextTask: Task | null = null): TaskResult => ({ ...t, nextTask });
const done = (t: Task, o: Partial<Task> = {}): Task => ({ ...t, status: 'DONE', done: true, ...o });

describe('bulkMessage', () => {
  it('names the count and the action', () => {
    expect(bulkMessage('COMPLETE', 12, undefined, null)).toBe('12 tasks completed');
    expect(bulkMessage('COMPLETE', 1, undefined, null)).toBe('1 task completed');
    expect(bulkMessage('REOPEN', 2, undefined, null)).toBe('2 tasks reopened');
    expect(bulkMessage('SET_STATUS', 2, 'DONE', null)).toBe('Status set on 2 tasks');
    expect(bulkMessage('SET_PRIORITY', 2, 'HIGH', null)).toBe('Priority set on 2 tasks');
    expect(bulkMessage('ADD_TAG', 2, 'Home', null)).toBe("Tag 'Home' added to 2 tasks");
    expect(bulkMessage('REMOVE_TAG', 2, 'Home', null)).toBe("Tag 'Home' removed from 2 tasks");
    expect(bulkMessage('MOVE', 3, 8, 'Work')).toBe('3 tasks moved to Work');
    expect(bulkMessage('MOVE', 3, 8, null)).toBe('3 tasks moved to another list');
    expect(bulkMessage('DELETE', 3, undefined, null)).toBe('3 tasks moved to trash');
  });
});

describe('planBulkUndo', () => {
  it('DELETE restores all ids', () => {
    expect(planBulkUndo('DELETE', undefined, [task(1), task(2)], [])).toEqual({
      kind: 'bulk-restore',
      ids: [1, 2],
    });
  });

  it('COMPLETE: one call for mixed repeating and plain tasks, skipping those already done', () => {
    const rule = { frequency: 'DAILY' as const, weekdays: [] };
    const a = task(1, { repeat: rule, dueDate: '2026-10-10' });
    const b = task(2, { status: 'IN_PROGRESS' });
    const c = done(task(3));
    const copy = task(9, { repeat: rule });
    const plan = planBulkUndo(
      'COMPLETE',
      undefined,
      [a, b, c],
      [res(done(a, { repeat: null }), copy), res(done(b)), res(c)],
    );
    expect(plan).toEqual({
      kind: 'bulk-complete',
      items: [
        { id: 1, nextTaskId: 9, status: 'TODO' },
        { id: 2, status: 'IN_PROGRESS' },
      ],
    });
  });

  it('SET_STATUS DONE follows the complete path; other statuses patch the old status', () => {
    const a = task(1);
    expect(planBulkUndo('SET_STATUS', 'DONE', [a], [res(done(a))])).toMatchObject({
      kind: 'bulk-complete',
    });
    const b = done(task(2));
    expect(planBulkUndo('SET_STATUS', 'TODO', [b], [res(task(2))])).toEqual({
      kind: 'bulk-patch',
      items: [{ id: 2, patch: { status: 'DONE' } }],
    });
  });

  it('REOPEN patches only the tasks that were reopened', () => {
    const a = done(task(1));
    const b = task(2);
    expect(planBulkUndo('REOPEN', undefined, [a, b], [res(task(1)), res(b)])).toEqual({
      kind: 'bulk-patch',
      items: [{ id: 1, patch: { status: 'DONE' } }],
    });
  });

  it('SET_PRIORITY patches each task with its own old priority', () => {
    const a = task(1, { priority: 'LOW' });
    const b = task(2);
    const c = task(3, { priority: 'HIGH' });
    const plan = planBulkUndo(
      'SET_PRIORITY',
      'HIGH',
      [a, b, c],
      [res({ ...a, priority: 'HIGH' }), res({ ...b, priority: 'HIGH' }), res(c)],
    );
    expect(plan).toEqual({
      kind: 'bulk-patch',
      items: [
        { id: 1, patch: { priority: 'LOW' } },
        { id: 2, patch: { priority: 'NONE' } },
      ],
    });
  });

  it('MOVE sends the old list and the old tags per task', () => {
    const a = task(1, { tags: ['Home'] });
    const b = task(2);
    const plan = planBulkUndo(
      'MOVE',
      8,
      [a, b],
      [res({ ...a, listId: 8 }), res({ ...b, listId: 8, tags: ['Odd'] })],
    );
    expect(plan).toEqual({
      kind: 'bulk-patch',
      items: [
        { id: 1, patch: { listId: 3, tags: ['Home'] } },
        { id: 2, patch: { listId: 3, tags: [] } },
      ],
    });
  });

  it('ADD_TAG undoes with REMOVE_TAG only on ids whose tags changed', () => {
    const a = task(1);
    const b = task(2, { tags: ['Home'] });
    const plan = planBulkUndo('ADD_TAG', 'Home', [a, b], [res({ ...a, tags: ['Home'] }), res(b)]);
    expect(plan).toEqual({ kind: 'bulk-tag', action: 'REMOVE_TAG', ids: [1], tag: 'Home' });
  });

  it('REMOVE_TAG undoes with ADD_TAG only on ids that had the tag', () => {
    const a = task(1, { tags: ['Home'] });
    const b = task(2);
    const plan = planBulkUndo('REMOVE_TAG', 'Home', [a, b], [res({ ...a, tags: [] }), res(b)]);
    expect(plan).toEqual({ kind: 'bulk-tag', action: 'ADD_TAG', ids: [1], tag: 'Home' });
  });

  it('returns null when nothing changed', () => {
    const a = task(1, { priority: 'HIGH' });
    expect(planBulkUndo('SET_PRIORITY', 'HIGH', [a], [res(a)])).toBeNull();
    expect(planBulkUndo('ADD_TAG', 'Home', [a], [res(a)])).toBeNull();
    expect(planBulkUndo('COMPLETE', undefined, [done(a)], [res(done(a))])).toBeNull();
    expect(planBulkUndo('DELETE', undefined, [], [])).toBeNull();
  });
});
