import {
  applyTask,
  compareTasks,
  matchesQuery,
  matchesView,
  normaliseQuery,
  normaliseSort,
  searchKey,
  titleOrderUncertain,
  instantMicros,
  formatDue,
  isOverdue,
  reminderJustPassed,
  reminderOptions,
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
    listId: 3,
    addedBy: { displayName: 'Ana' },
    title: `Task ${overrides.id}`,
    description: null,
    priority: 'NONE',
    tags: [],
    dueDate: null,
    dueTime: null,
    myReminder: null,
    myReminderPassed: false,
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

  describe('normaliseSort', () => {
    it.each([
      ['newest', 'newest'],
      ['oldest', 'oldest'],
      ['due', 'due'],
      ['priority', 'priority'],
      ['title', 'title'],
      ['Due', 'newest'],
      ['foo', 'newest'],
      ['', 'newest'],
      [null, 'newest'],
      [undefined, 'newest'],
    ])('%s -> %s', (input, expected) => {
      expect(normaliseSort(input)).toBe(expected);
    });
  });

  describe('normaliseQuery', () => {
    it.each([
      [null, null],
      [undefined, null],
      ['', null],
      ['   ', null],
      ['  MILK ', 'MILK'],
      ['50%', '50%'],
    ])('%j -> %j', (input, expected) => {
      expect(normaliseQuery(input)).toBe(expected);
    });

    it('cuts a hand-edited value to 100 characters', () => {
      expect(normaliseQuery('x'.repeat(150))).toBe('x'.repeat(100));
      expect(normaliseQuery('  ' + 'y'.repeat(100) + '  ')).toBe('y'.repeat(100));
      // The cut can leave a trailing space, which is trimmed again.
      expect(normaliseQuery('a'.repeat(99) + ' bcd')).toBe('a'.repeat(99));
    });

    it('never splits a surrogate pair when cutting', () => {
      const q = normaliseQuery('a'.repeat(99) + '😀😀');
      expect(q).toBe('a'.repeat(99));
      expect(() => encodeURIComponent(q!)).not.toThrow();
    });
  });

  describe('search hint', () => {
    it('searchKey folds case and accents', () => {
      expect(searchKey('Café MÜNCHEN')).toBe('cafe munchen');
    });

    it('matches the title or the description, ignoring case and accents, literally', () => {
      const milk = task({ id: 1, title: 'Buy milk' });
      const bob = task({ id: 2, title: 'Call Bob' });
      const desc = task({ id: 3, title: 'Shop', description: 'oat MILK please' });
      const cafe = task({ id: 4, title: 'Café meeting' });
      const pct = task({ id: 5, title: 'Save 50% now', description: 'a_b' });
      expect([milk, bob, desc].filter((t) => matchesQuery(t, 'MILK'))).toEqual([milk, desc]);
      expect(matchesQuery(cafe, 'cafe')).toBe(true);
      expect(matchesQuery(cafe, 'CAFÉ M')).toBe(true);
      expect(matchesQuery(pct, '50%')).toBe(true);
      expect(matchesQuery(pct, 'a_b')).toBe(true);
      expect(matchesQuery(milk, '50%')).toBe(false);
      expect(matchesQuery(milk, 'b_y')).toBe(false);
      expect(matchesQuery(milk, null)).toBe(true);
    });

    it('matchesView combines tab, tag and search', () => {
      const t = task({ id: 1, title: 'Buy milk', tags: ['Home'] });
      const q = { filter: 'active', tag: 'home', q: 'milk', sort: 'newest' } as const;
      expect(matchesView(t, q)).toBe(true);
      expect(matchesView(t, { ...q, q: 'bread' })).toBe(false);
      expect(matchesView(t, { ...q, tag: 'Work' })).toBe(false);
      expect(matchesView(markDone(t), q)).toBe(false);
    });
  });

  describe('compareTasks by sort', () => {
    const at = (day: number) => `2026-10-${String(day).padStart(2, '0')}T09:00:00Z`;
    const mk = (id: number, o: Partial<Task> = {}) =>
      task({ id, createdAt: at(id), updatedAt: at(id), ...o });
    const ids = (tasks: Task[]) => tasks.map((t) => t.id);

    it('newest: createdAt desc, then id desc; oldest is the exact reverse', () => {
      const a = mk(1);
      const b = mk(2);
      const c = mk(3, { createdAt: at(2) }); // same instant as b, higher id
      expect(ids(sortTasks([a, b, c], 'newest'))).toEqual([3, 2, 1]);
      expect(ids(sortTasks([a, b, c], 'oldest'))).toEqual([1, 2, 3]);
      expect(ids(sortTasks([a, b, c]))).toEqual([3, 2, 1]);
    });

    it('due: soonest first, no date last, ties newest first', () => {
      const tasks = [
        mk(1, { dueDate: null }),
        mk(2, { dueDate: '2026-12-01' }),
        mk(3, { dueDate: '2026-11-01' }),
        mk(4, { dueDate: '2026-12-01' }),
        mk(5, { dueDate: null }),
      ];
      expect(ids(sortTasks(tasks, 'due'))).toEqual([3, 4, 2, 5, 1]);
    });

    it('priority: High, Medium, Low, None, ties newest first', () => {
      const tasks = [
        mk(1, { priority: 'NONE' }),
        mk(2, { priority: 'LOW' }),
        mk(3, { priority: 'HIGH' }),
        mk(4, { priority: 'MEDIUM' }),
        mk(5, { priority: 'HIGH' }),
      ];
      expect(ids(sortTasks(tasks, 'priority'))).toEqual([5, 3, 4, 2, 1]);
    });

    it('title: A-Z ignoring case and accents, equal titles newest first', () => {
      const tasks = [
        mk(1, { title: 'banana' }),
        mk(2, { title: 'Café' }),
        mk(3, { title: 'apple' }),
        mk(4, { title: 'cafe' }),
        mk(5, { title: 'Apple pie' }),
      ];
      expect(ids(sortTasks(tasks, 'title'))).toEqual([3, 5, 1, 4, 2]);
    });

    it.each(['newest', 'oldest', 'due', 'priority', 'title'] as const)(
      '%s: not-done group first, done after, each in the sort order',
      (sort) => {
        const tasks = [
          mk(1, { title: 'a', priority: 'HIGH', dueDate: '2026-01-01', done: true }),
          mk(2, { title: 'z', priority: 'NONE', dueDate: null }),
          mk(3, { title: 'b', priority: 'HIGH', dueDate: '2026-01-02', status: 'IN_PROGRESS' }),
          mk(4, { title: 'c', priority: 'LOW', dueDate: '2026-01-03', done: true }),
        ];
        const sorted = sortTasks(tasks, sort);
        expect(sorted.slice(0, 2).every((t) => t.status !== 'DONE')).toBe(true);
        expect(sorted.slice(2).every((t) => t.status === 'DONE')).toBe(true);
      },
    );

    it('titleOrderUncertain only for title sort in the same group, on equal or unclear titles', () => {
      const a = mk(1, { title: 'Café' });
      const b = mk(2, { title: 'cafe' });
      const c = mk(3, { title: 'Zebra' });
      const p = mk(4, { title: 'a-b' });
      const q = mk(5, { title: 'ab' });
      expect(titleOrderUncertain(a, b, 'title')).toBe(true);
      expect(titleOrderUncertain(a, c, 'title')).toBe(false);
      expect(titleOrderUncertain(a, b, 'newest')).toBe(false);
      expect(titleOrderUncertain(a, markDone(b), 'title')).toBe(false);
      // Collator and plain comparison may disagree on punctuation: uncertain.
      const collated = Math.sign(
        new Intl.Collator('en', { sensitivity: 'base' }).compare('a-b', 'ab'),
      );
      expect(titleOrderUncertain(p, q, 'title')).toBe(collated !== -1);
    });
  });
});

describe('due time (T4a)', () => {
  const timed = (id: number, dueDate: string | null, dueTime: string | null) =>
    task({ id, dueDate, dueTime });

  it('sorts by due: date, then timed by time, then date-only, then no date', () => {
    const tasks = [
      timed(1, null, null),
      timed(2, '2026-10-21', null),
      timed(3, '2026-10-21', '14:00'),
      timed(4, '2026-10-21', '09:30'),
      timed(5, '2026-10-20', null),
    ];
    expect(sortTasks(tasks, 'due').map((t) => t.id)).toEqual([5, 4, 3, 2, 1]);
  });

  it('a timed task is overdue once its date and time have passed', () => {
    const t = timed(1, '2026-10-21', '09:30');
    expect(isOverdue(t, '2026-10-21', '09:29')).toBe(false);
    expect(isOverdue(t, '2026-10-21', '09:30')).toBe(false);
    expect(isOverdue(t, '2026-10-21', '09:31')).toBe(true);
    expect(isOverdue(t, '2026-10-22', '00:00')).toBe(true);
    expect(isOverdue(t, '2026-10-20', '23:59')).toBe(false);
  });

  it('a date-only task is overdue only on later days, whatever the time', () => {
    const t = timed(1, '2026-10-21', null);
    expect(isOverdue(t, '2026-10-21', '23:59')).toBe(false);
    expect(isOverdue(t, '2026-10-22', '00:00')).toBe(true);
    expect(isOverdue(task({ id: 2, dueDate: '2026-10-01', status: 'DONE' }), '2026-10-22')).toBe(
      false,
    );
  });

  it('formats the due text and the reminder choices', () => {
    expect(formatDue('2026-10-21', null)).toBe('2026-10-21');
    expect(formatDue('2026-10-21', '09:30')).toBe('2026-10-21 09:30');
    expect(reminderOptions(true).map((o) => o.value)).toEqual([
      'AT_DUE',
      'TEN_MINUTES',
      'ONE_HOUR',
      'ONE_DAY',
    ]);
    expect(reminderOptions(false)).toEqual([
      { value: 'ON_THE_DAY', label: 'On the day at 09:00' },
      { value: 'ONE_DAY', label: '1 day before at 09:00' },
    ]);
  });

  it('reminderJustPassed is true only when a passed reminder is new or changed', () => {
    const base = task({ id: 1, dueDate: '2026-10-21', dueTime: '09:00' });
    const passed = { ...base, myReminder: 'AT_DUE' as const, myReminderPassed: true };
    expect(reminderJustPassed(base, passed)).toBe(true);
    expect(reminderJustPassed(passed, { ...passed, title: 'x' })).toBe(false);
    expect(reminderJustPassed(passed, { ...passed, myReminder: 'ONE_HOUR' })).toBe(true);
    expect(reminderJustPassed(base, { ...passed, myReminderPassed: false })).toBe(false);
    expect(reminderJustPassed(base, base)).toBe(false);
  });
});
