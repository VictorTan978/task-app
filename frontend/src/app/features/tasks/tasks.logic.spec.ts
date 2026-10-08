import {
  applyTask,
  compareTasks,
  instantMicros,
  isOverdue,
  matchesFilter,
  normaliseFilter,
  sortTasks,
  todayLocal,
} from './tasks.logic';
import { Task } from './tasks.models';

function task(overrides: Partial<Task> & { id: number }): Task {
  return {
    title: `Task ${overrides.id}`,
    description: null,
    done: false,
    dueDate: null,
    createdAt: '2026-10-08T09:00:00Z',
    updatedAt: '2026-10-08T09:00:00Z',
    ...overrides,
  };
}

describe('tasks.logic', () => {
  describe('normaliseFilter', () => {
    it.each([
      ['all', 'all'],
      ['active', 'active'],
      ['done', 'done'],
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

  it('matchesFilter', () => {
    const open = task({ id: 1 });
    const closed = task({ id: 2, done: true });
    expect(matchesFilter(open, 'all')).toBe(true);
    expect(matchesFilter(closed, 'all')).toBe(true);
    expect(matchesFilter(open, 'active')).toBe(true);
    expect(matchesFilter(closed, 'active')).toBe(false);
    expect(matchesFilter(open, 'done')).toBe(false);
    expect(matchesFilter(closed, 'done')).toBe(true);
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
      const result = applyTask([b, a], { ...b, done: true }, 'all');
      expect(result.map((t) => [t.id, t.done])).toEqual([
        [1, false],
        [2, true],
      ]);
    });

    it('removes a task that no longer matches the filter', () => {
      expect(applyTask([b, a], { ...b, done: true }, 'active').map((t) => t.id)).toEqual([1]);
      expect(applyTask([], task({ id: 9 }), 'done')).toEqual([]);
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
    ])('%s -> %s', (_label, overrides, expected) => {
      expect(isOverdue(task({ id: 1, ...overrides }), today)).toBe(expected);
    });
  });
});
