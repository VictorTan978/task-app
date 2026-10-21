import { appendPage, applyChange, firstPage, removeTask } from './task-list';
import { Task, TaskListQuery, TaskPage, TaskResult } from './tasks.models';
import { isEmptyPatch, movedMessage, planUndo, reinstate, reversePatch } from './undo.logic';

const TASK: Task = {
  id: 5,
  listId: 3,
  addedBy: { displayName: 'Ana' },
  title: 'Water plants',
  description: 'Daily',
  priority: 'LOW',
  status: 'IN_PROGRESS',
  tags: ['Home', 'Plants'],
  done: false,
  dueDate: '2026-10-10',
  dueTime: '09:30',
  myReminder: 'TEN_MINUTES',
  myReminderPassed: false,
  repeat: { frequency: 'DAILY', weekdays: [] },
  createdAt: '2026-10-08T09:15:02Z',
  updatedAt: '2026-10-08T09:15:02Z',
};

const result = (changes: Partial<Task>, nextTask: Task | null = null): TaskResult => ({
  ...TASK,
  ...changes,
  nextTask,
});

describe('reversePatch', () => {
  it('is empty when nothing changed', () => {
    expect(reversePatch(TASK, TASK)).toEqual({});
    expect(isEmptyPatch(reversePatch(TASK, TASK))).toBe(true);
  });

  it('a tick sends only the old status', () => {
    expect(reversePatch(TASK, { ...TASK, status: 'DONE', done: true })).toEqual({
      status: 'IN_PROGRESS',
    });
  });

  it('sends only the fields that changed, with their old values', () => {
    const after = { ...TASK, title: 'New', priority: 'HIGH' as const, dueTime: '10:00' };
    expect(reversePatch(TASK, after)).toEqual({
      title: 'Water plants',
      priority: 'LOW',
      dueTime: '09:30',
    });
  });

  it('clears with null for description, due date and due time', () => {
    const bare = { ...TASK, description: null, dueDate: null, dueTime: null };
    expect(reversePatch(bare, TASK)).toEqual({ description: null, dueDate: null, dueTime: null });
  });

  it('clears the reminder with NONE and the rule with NEVER, the tags with []', () => {
    const bare = { ...TASK, myReminder: null, repeat: null, tags: [] };
    expect(reversePatch(bare, TASK)).toEqual({ reminder: 'NONE', repeat: 'NEVER', tags: [] });
  });

  it('restores the reminder, the rule and the date that clearing the date removed', () => {
    const cleared = {
      ...TASK,
      dueDate: null,
      dueTime: null,
      myReminder: null,
      repeat: null,
    };
    expect(reversePatch(TASK, cleared)).toEqual({
      dueDate: '2026-10-10',
      dueTime: '09:30',
      reminder: 'TEN_MINUTES',
      repeat: { frequency: 'DAILY', weekdays: [] },
    });
  });

  it('compares the rule by value (frequency and weekdays)', () => {
    const weekly = {
      ...TASK,
      repeat: { frequency: 'WEEKLY' as const, weekdays: ['MON' as const] },
    };
    expect(reversePatch(weekly, { ...weekly, repeat: { ...weekly.repeat } })).toEqual({});
    expect(
      reversePatch(weekly, { ...weekly, repeat: { frequency: 'WEEKLY', weekdays: ['TUE'] } }),
    ).toEqual({ repeat: { frequency: 'WEEKLY', weekdays: ['MON'] } });
  });

  it('a move sends the old list and the old tag names', () => {
    expect(reversePatch(TASK, { ...TASK, listId: 8, tags: ['home', 'Plants'] })).toEqual({
      listId: 3,
      tags: ['Home', 'Plants'],
    });
    expect(reversePatch(TASK, { ...TASK, listId: 8 })).toEqual({
      listId: 3,
      tags: ['Home', 'Plants'],
    });
  });
});

describe('planUndo', () => {
  it('is null when the save changed nothing', () => {
    expect(planUndo(TASK, result({}))).toBeNull();
  });

  it('a normal save is a PATCH of the changed fields', () => {
    expect(planUndo(TASK, result({ title: 'X' }))).toEqual({
      kind: 'patch',
      id: 5,
      patch: { title: 'Water plants' },
    });
  });

  it('completing a repeating task is undone with undo-complete and the old status', () => {
    const next = { ...TASK, id: 6 };
    const done = { status: 'DONE' as const, done: true, repeat: null };
    expect(planUndo(TASK, result(done, next))).toEqual({
      kind: 'complete',
      id: 5,
      nextTaskId: 6,
      status: 'IN_PROGRESS',
      followUp: null,
    });
    expect(planUndo({ ...TASK, status: 'TODO' }, result(done, next))).toMatchObject({
      status: 'TODO',
    });
  });

  it('an edit that also completed it patches the other fields after, without status or repeat', () => {
    const next = { ...TASK, id: 6 };
    const plan = planUndo(
      TASK,
      result({ status: 'DONE', done: true, repeat: null, title: 'Changed' }, next),
    );
    expect(plan).toMatchObject({ kind: 'complete', followUp: { title: 'Water plants' } });
    expect(plan?.kind === 'complete' && 'status' in (plan.followUp ?? {})).toBe(false);
    expect(plan?.kind === 'complete' && 'repeat' in (plan.followUp ?? {})).toBe(false);
  });
});

describe('movedMessage', () => {
  it('names the list, with a fallback', () => {
    expect(movedMessage('Work')).toBe('Moved to Work');
    expect(movedMessage(null)).toBe('Moved to another list');
  });
});

const Q: TaskListQuery = { filter: 'all', tag: null, q: null, sort: 'newest' };
const page = (items: Task[], total: number, nextCursor: string | null = null): TaskPage => ({
  items,
  total,
  nextCursor,
});
const t = (id: number): Task => ({
  ...TASK,
  id,
  createdAt: `2026-10-${10 + id}T09:00:00Z`,
  updatedAt: `2026-10-${10 + id}T09:00:00Z`,
});
const ids = (items: Task[]) => items.map((x) => x.id);

describe('reinstate (undo of a delete or a move)', () => {
  it('lets a removed task be shown again, including when a later page has it', () => {
    const removed = removeTask(firstPage(page([t(9), t(8)], 5, 'c1')), 8);
    expect(removed.deleted.has(8)).toBe(true);
    const back = reinstate(removed, 8);
    expect(back.deleted.has(8)).toBe(false);
    const applied = applyChange(back, t(8), Q, 'updated');
    expect(ids(applied.state.items)).toEqual([9, 8]);
    expect(applied.state.total).toBe(removed.total + 1);
    expect(applied.addedHidden).toBe(false);
    expect(ids(appendPage(back, page([t(8)], 5)).items)).toEqual([9, 8]);
  });

  it('does not change a task that was not removed', () => {
    const state = firstPage(page([t(9)], 1));
    expect(reinstate(state, 9)).toBe(state);
  });
});
