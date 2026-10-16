import {
  applyTask,
  compareTasks,
  instantMicros,
  isOverdue,
  isDone,
  matchesFilter,
  matchesTag,
  normaliseFilter,
  normaliseTagParam,
  sortTasks,
  todayLocal,
} from './tasks.logic';
import { Task } from './tasks.models';

/** `done: true` alone means status DONE; `status` wins and `done` always mirrors it. */
function task(overrides: Partial<Task> & { id: number }): Task {
  const status = overrides.status ?? (overrides.done ? 'DONE' : 'TODO');
  return {
    title: `Task ${overrides.id}`,
    description: null,
    priority: 'NONE',
    tags: [],
    dueDate: null,
    createdAt: '2026-10-08T09:00:00Z',
    updatedAt: '2026-10-08T09:00:00Z',
    ...overrides,
    status,
    done: status === 'DONE',
  };
}

const markDone = (t: Task): Task => ({ ...t, status: 'DONE', done: true });

describe('tasks.logic', () => {
  describe('normaliseFilter', () => {
    it.each([
      ['all', 'all'],
      ['active', 'active'],
      ['in_progress', 'in_progress'],
      ['done', 'done'],
      ['In_progress', 'all'],
      ['in-progress', 'all'],
      [null, 'all'],
      [undefined, 'all'],
      ['', 'all'],
      ['foo', 'all'],
      ['Done', 'all'],
      [' active', 'all'],
    ])('%s -> %s', (input, expected) => {
      expect(normaliseFilter(input)).toBe(expected);
    });
  });

  it('matchesFilter reads the status', () => {
    const open = task({ id: 1 });
    const started = task({ id: 3, status: 'IN_PROGRESS' });
    const closed = task({ id: 2, done: true });
    expect([open, started, closed].map((t) => matchesFilter(t, 'all'))).toEqual([true, true, true]);
    expect([open, started, closed].map((t) => matchesFilter(t, 'active'))).toEqual([
      true,
      true,
      false,
    ]);
    expect([open, started, closed].map((t) => matchesFilter(t, 'in_progress'))).toEqual([
      false,
      true,
      false,
    ]);
    expect([open, started, closed].map((t) => matchesFilter(t, 'done'))).toEqual([
      false,
      false,
      true,
    ]);
    expect(isDone(closed)).toBe(true);
    expect(isDone(started)).toBe(false);
  });

  it.each([
    [null, null],
    [undefined, null],
    ['', null],
    ['   ', null],
    ['Work', 'Work'],
    [' work ', ' work '],
  ])('normaliseTagParam(%s) -> %s', (input, expected) => {
    expect(normaliseTagParam(input)).toBe(expected);
  });

  it('matchesTag ignores case, accents and extra spaces; null matches everything', () => {
    const t = task({ id: 1, tags: ['Errands', 'Work'] });
    expect(matchesTag(t, null)).toBe(true);
    expect(matchesTag(t, 'work')).toBe(true);
    expect(matchesTag(t, ' WÖRK ')).toBe(true);
    expect(matchesTag(t, 'Home')).toBe(false);
    expect(matchesTag(task({ id: 2 }), 'Work')).toBe(false);
  });

  describe('instantMicros', () => {
    it('parses 0, 3 and 6 fractional digits as instants', () => {
      const base = Date.UTC(2026, 9, 8, 9, 15, 2) * 1000;
      expect(instantMicros('2026-10-08T09:15:02Z')).toBe(base);
      expect(instantMicros('2026-10-08T09:15:02.5Z')).toBe(base + 500_000);
      expect(instantMicros('2026-10-08T09:15:02.123Z')).toBe(base + 123_000);
      expect(instantMicros('2026-10-08T09:15:02.123456Z')).toBe(base + 123_456);
    });

    it('orders by instant, not by string', () => {
      // As strings "02Z" would sort after "02.5Z" (because "Z" > "."); as instants it is earlier.
      expect(instantMicros('2026-10-08T09:15:02Z')).toBeLessThan(
        instantMicros('2026-10-08T09:15:02.5Z'),
      );
      expect(instantMicros('2026-10-08T09:15:02.000001Z')).toBeGreaterThan(
        instantMicros('2026-10-08T09:15:02Z'),
      );
    });
  });

  describe('sorting', () => {
    it('puts not-done first, then done; each newest first, then id desc', () => {
      const tasks = [
        task({ id: 1, done: true, createdAt: '2026-10-01T00:00:00Z' }),
        task({ id: 2, createdAt: '2026-10-01T00:00:00Z' }),
        task({ id: 3, done: true, createdAt: '2026-10-05T00:00:00.000001Z' }),
        task({ id: 4, createdAt: '2026-10-05T00:00:00Z' }),
        task({ id: 5, createdAt: '2026-10-05T00:00:00Z' }),
        task({ id: 6, createdAt: '2026-10-05T00:00:00.5Z' }),
      ];
      expect(sortTasks(tasks).map((t) => t.id)).toEqual([6, 5, 4, 2, 3, 1]);
    });

    it('mixes To do and In progress; priority never changes the order', () => {
      const tasks = [
        task({ id: 1, priority: 'HIGH', createdAt: '2026-10-01T00:00:00Z' }),
        task({ id: 2, status: 'IN_PROGRESS', createdAt: '2026-10-02T00:00:00Z' }),
        task({ id: 3, priority: 'LOW', done: true, createdAt: '2026-10-04T00:00:00Z' }),
        task({ id: 4, priority: 'MEDIUM', createdAt: '2026-10-03T00:00:00Z' }),
      ];
      expect(sortTasks(tasks).map((t) => t.id)).toEqual([4, 2, 1, 3]);
    });

    it('does not mutate the input', () => {
      const tasks = [task({ id: 1 }), task({ id: 2 })];
      sortTasks(tasks);
      expect(tasks.map((t) => t.id)).toEqual([1, 2]);
      expect(compareTasks(tasks[0], tasks[1])).toBeGreaterThan(0);
    });
  });

  describe('applyTask', () => {
    const a = task({ id: 1, createdAt: '2026-10-01T00:00:00Z' });
    const b = task({ id: 2, createdAt: '2026-10-02T00:00:00Z' });

    it('inserts a new task in sorted position', () => {
      const c = task({ id: 3, createdAt: '2026-10-03T00:00:00Z' });
      expect(applyTask([b, a], c, 'all').map((t) => t.id)).toEqual([3, 2, 1]);
    });

    it('replaces an updated task and moves it into the done group', () => {
      const result = applyTask([b, a], markDone(b), 'all');
      expect(result.map((t) => [t.id, t.done])).toEqual([
        [1, false],
        [2, true],
      ]);
    });

    it('removes a task that no longer matches the filter', () => {
      expect(applyTask([b, a], markDone(b), 'active').map((t) => t.id)).toEqual([1]);
      expect(applyTask([], task({ id: 9 }), 'done')).toEqual([]);
      expect(applyTask([b, a], markDone(b), 'in_progress').map((t) => t.id)).toEqual([1]);
    });

    it('removes a task that no longer has the filtered tag, keeps one that does', () => {
      const tagged = { ...b, tags: ['Work'] };
      expect(applyTask([tagged, a], { ...b, tags: [] }, 'all', 'work').map((t) => t.id)).toEqual([
        1,
      ]);
      expect(applyTask([a], tagged, 'all', 'WORK').map((t) => t.id)).toEqual([2, 1]);
      expect(applyTask([a], tagged, 'done', 'Work').map((t) => t.id)).toEqual([1]);
    });
  });

  it('todayLocal formats the local date with padding', () => {
    expect(todayLocal(new Date(2026, 0, 5, 23, 59))).toBe('2026-01-05');
    expect(todayLocal(new Date(2026, 11, 31, 0, 0))).toBe('2026-12-31');
  });

  describe('isOverdue', () => {
    const today = '2026-10-08';
    it.each([
      ['due yesterday, not done', { dueDate: '2026-10-07' }, true],
      ['due last year, not done', { dueDate: '2025-12-31' }, true],
      ['due today', { dueDate: '2026-10-08' }, false],
      ['due tomorrow', { dueDate: '2026-10-09' }, false],
      ['no due date', { dueDate: null }, false],
      ['done and past due', { dueDate: '2026-10-01', done: true }, false],
      ['in progress and past due', { dueDate: '2026-10-01', status: 'IN_PROGRESS' as const }, true],
    ])('%s -> %s', (_label, overrides, expected) => {
      expect(isOverdue(task({ id: 1, ...overrides }), today)).toBe(expected);
    });
  });
});
