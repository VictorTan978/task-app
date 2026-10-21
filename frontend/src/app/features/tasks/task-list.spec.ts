import {
  appendPage,
  applyChange,
  emptyList,
  firstPage,
  fitsLoaded,
  hasMore,
  insertSorted,
  moveOut,
  removeTask,
  shownTotal,
  TaskListState,
} from './task-list';
import { Task, TaskListQuery, TaskPage } from './tasks.models';

const at = (day: number) => `2026-10-${String(day).padStart(2, '0')}T09:00:00Z`;

/** id n is created on day n, so higher ids are newer. */
function task(id: number, overrides: Partial<Task> = {}): Task {
  const status = overrides.status ?? (overrides.done ? 'DONE' : 'TODO');
  return {
    id,
    listId: 3,
    addedBy: { displayName: 'Ana' },
    title: `Task ${id}`,
    description: null,
    priority: 'NONE',
    tags: [],
    dueDate: null,
    dueTime: null,
    myReminder: null,
    myReminderPassed: false,
    repeat: null,
    createdAt: at(id),
    updatedAt: at(id),
    ...overrides,
    status,
    done: status === 'DONE',
  };
}

const page = (items: Task[], total: number, nextCursor: string | null = null): TaskPage => ({
  items,
  total,
  nextCursor,
});

const ids = (state: TaskListState) => state.items.map((t) => t.id);

const Q: TaskListQuery = { filter: 'all', tag: null, q: null, sort: 'newest' };

describe('task-list', () => {
  describe('pages', () => {
    it('firstPage takes items, total, cursor and the last item as boundary', () => {
      const s = firstPage(page([task(20), task(19)], 120, 'c1'));
      expect(ids(s)).toEqual([20, 19]);
      expect(s.total).toBe(120);
      expect(hasMore(s)).toBe(true);
      expect(s.boundary?.id).toBe(19);
    });

    it('appendPage appends, re-syncs total and cursor, moves the boundary', () => {
      const s1 = firstPage(page([task(20), task(19)], 120, 'c1'));
      const s2 = appendPage(s1, page([task(18), task(17)], 118, null));
      expect(ids(s2)).toEqual([20, 19, 18, 17]);
      expect(s2.total).toBe(118);
      expect(hasMore(s2)).toBe(false);
      expect(s2.boundary?.id).toBe(17);
    });

    it('an empty last page keeps the items and the old boundary', () => {
      const s1 = firstPage(page([task(20)], 5, 'c1'));
      const s2 = appendPage(s1, page([], 1, null));
      expect(ids(s2)).toEqual([20]);
      expect(s2.boundary?.id).toBe(20);
      expect(s2.total).toBe(1);
      expect(hasMore(s2)).toBe(false);
    });

    it('de-duplicates by id: a shown task with the same or newer version stays where it is', () => {
      const edited = task(19, { title: 'Edited', updatedAt: at(25) });
      const s1 = { ...firstPage(page([task(20), task(19)], 4, 'c1')) };
      const s1b = { ...s1, items: [task(20), edited] };
      const s2 = appendPage(s1b, page([task(19), task(18)], 4, null));
      expect(ids(s2)).toEqual([20, 19, 18]);
      expect(s2.items[1].title).toBe('Edited');
    });

    it('de-duplicates by id: a newer version on the page replaces the shown one at its place', () => {
      const s1 = firstPage(page([task(20), task(19)], 4, 'c1'));
      const newer = task(20, { title: 'Changed elsewhere', updatedAt: at(28) });
      const s2 = appendPage(s1, page([task(18), newer], 4, null));
      expect(ids(s2)).toEqual([19, 18, 20]);
      expect(s2.items[2].title).toBe('Changed elsewhere');
    });

    it('skips ids deleted in this paging session', () => {
      const s1 = removeTask(firstPage(page([task(20), task(19)], 4, 'c1')), 19);
      const s2 = appendPage(s1, page([task(19), task(18)], 3, null));
      expect(ids(s2)).toEqual([20, 18]);
    });

    it('shownTotal is never below the number shown', () => {
      expect(shownTotal({ ...emptyList(), items: [task(1), task(2)], total: 1 })).toBe(2);
      expect(shownTotal({ ...emptyList(), items: [task(1)], total: 9 })).toBe(9);
    });
  });

  describe('insertSorted', () => {
    it('inserts before the first item that sorts after it, keeping the rest', () => {
      const items = [task(9), task(5), task(2)];
      expect(insertSorted(items, task(6), 'newest').map((t) => t.id)).toEqual([9, 6, 5, 2]);
      expect(insertSorted(items, task(1), 'newest').map((t) => t.id)).toEqual([9, 5, 2, 1]);
      expect(insertSorted(items, task(10), 'newest').map((t) => t.id)).toEqual([10, 9, 5, 2]);
      expect(insertSorted([], task(1), 'title').map((t) => t.id)).toEqual([1]);
    });
  });

  describe('fitsLoaded', () => {
    const more = (items: Task[]) => firstPage(page(items, 100, 'c1'));

    it('everything fits when everything is loaded', () => {
      const s = firstPage(page([task(9), task(8)], 2, null));
      expect(fitsLoaded(s, task(1), 'newest')).toBe(true);
    });

    it('fits at or before the boundary, not after it', () => {
      const s = more([task(9), task(8)]);
      expect(fitsLoaded(s, task(10), 'newest')).toBe(true);
      expect(fitsLoaded(s, task(8), 'newest')).toBe(true);
      expect(fitsLoaded(s, task(7), 'newest')).toBe(false);
      expect(fitsLoaded(s, task(7), 'oldest')).toBe(true);
    });

    it('title: keeps a task whose place next to the boundary is uncertain', () => {
      const s = more([task(1, { title: 'Apple' }), task(2, { title: 'Café' })]);
      expect(fitsLoaded(s, task(3, { title: 'Banana' }), 'title')).toBe(true);
      expect(fitsLoaded(s, task(3, { title: 'Zebra' }), 'title')).toBe(false);
      // "cafe" equals "Café" for the collation: the server decides the order, so keep it.
      expect(fitsLoaded(s, task(4, { title: 'cafe', createdAt: at(1) }), 'title')).toBe(true);
    });

    it('All: a task in the done group sorts after a not-done boundary', () => {
      const s = more([task(9), task(8)]);
      expect(fitsLoaded(s, task(20, { done: true }), 'newest')).toBe(false);
    });
  });

  describe('applyChange', () => {
    const loadedAll = () => firstPage(page([task(9), task(8), task(7)], 3, null));
    const twoPages = () =>
      appendPage(
        firstPage(page([task(20), task(19)], 10, 'c1')),
        page([task(18), task(17)], 10, 'c2'),
      );

    it('Newest: a created task goes to the top and the total goes up', () => {
      const r = applyChange(twoPages(), task(30), Q, 'created');
      expect(ids(r.state)).toEqual([30, 20, 19, 18, 17]);
      expect(r.state.total).toBe(11);
      expect(r.addedHidden).toBe(false);
    });

    it('a created task that does not match the tab, tag or search: hidden, total unchanged', () => {
      for (const query of [
        { ...Q, filter: 'done' as const },
        { ...Q, tag: 'Work' },
        { ...Q, q: 'milk' },
      ]) {
        const r = applyChange(twoPages(), task(30, { title: 'Bread' }), query, 'created');
        expect(ids(r.state)).toEqual([20, 19, 18, 17]);
        expect(r.state.total).toBe(10);
        expect(r.addedHidden).toBe(true);
      }
    });

    it('a created task matching the search is shown on Newest', () => {
      const r = applyChange(
        twoPages(),
        task(30, { title: 'Café' }),
        { ...Q, q: 'cafe' },
        'created',
      );
      expect(ids(r.state)[0]).toBe(30);
      expect(r.state.total).toBe(11);
      expect(r.addedHidden).toBe(false);
    });

    it('Oldest with more to load: a created task sorts after the boundary — hidden, total + 1', () => {
      const s = firstPage(page([task(1), task(2)], 10, 'c1'));
      const r = applyChange(s, task(30), { ...Q, sort: 'oldest' }, 'created');
      expect(ids(r.state)).toEqual([1, 2]);
      expect(r.state.total).toBe(11);
      expect(r.addedHidden).toBe(true);
    });

    it('when everything is loaded a matching created task is always shown in its place', () => {
      const s = firstPage(page([task(1), task(2)], 2, null));
      const r = applyChange(s, task(30), { ...Q, sort: 'oldest' }, 'created');
      expect(ids(r.state)).toEqual([1, 2, 30]);
      expect(r.state.total).toBe(3);
      expect(r.addedHidden).toBe(false);
    });

    it('an update that no longer matches removes the task and lowers the total', () => {
      const r = applyChange(
        twoPages(),
        task(19, { done: true }),
        { ...Q, filter: 'active' },
        'updated',
      );
      expect(ids(r.state)).toEqual([20, 18, 17]);
      expect(r.state.total).toBe(9);
      expect(r.addedHidden).toBe(false);
    });

    it('All: a ticked task leaves the not-done group; not shown until the done group loads', () => {
      const r = applyChange(twoPages(), task(19, { done: true }), Q, 'updated');
      expect(ids(r.state)).toEqual([20, 18, 17]);
      expect(r.state.total).toBe(10);
      // It comes back via Load more, once.
      const next = appendPage(r.state, page([task(16), task(19, { done: true })], 10, null));
      expect(ids(next)).toEqual([20, 18, 17, 16, 19]);
    });

    it('All: a ticked task moves into the done group when it is loaded', () => {
      const s = firstPage(page([task(9), task(8), task(5, { done: true })], 3, null));
      const r = applyChange(s, task(8, { done: true }), Q, 'updated');
      expect(ids(r.state)).toEqual([9, 8, 5]);
      expect(r.state.items[1].status).toBe('DONE');
      expect(r.state.total).toBe(3);
    });

    it('an update moved before the boundary is moved in place (title sort)', () => {
      const s = firstPage(
        page(
          [task(1, { title: 'Apple' }), task(2, { title: 'Kiwi' }), task(3, { title: 'Pear' })],
          9,
          'c1',
        ),
      );
      const r = applyChange(s, task(3, { title: 'Banana' }), { ...Q, sort: 'title' }, 'updated');
      expect(ids(r.state)).toEqual([1, 3, 2]);
      expect(r.state.total).toBe(9);
    });

    it('an update moved after the boundary is removed, total kept; Load more brings it back', () => {
      const s = firstPage(
        page(
          [task(1, { title: 'Apple' }), task(2, { title: 'Kiwi' }), task(3, { title: 'Pear' })],
          9,
          'c1',
        ),
      );
      const moved = task(1, { title: 'Zucchini', updatedAt: at(28) });
      const r = applyChange(s, moved, { ...Q, sort: 'title' }, 'updated');
      expect(ids(r.state)).toEqual([2, 3]);
      expect(r.state.total).toBe(9);
      const next = appendPage(r.state, page([task(4, { title: 'Plum' }), moved], 9, null));
      expect(ids(next)).toEqual([2, 3, 4, 1]);
    });

    it('the boundary task itself, edited without moving, stays', () => {
      const r = applyChange(twoPages(), task(17, { description: 'x' }), Q, 'updated');
      expect(ids(r.state)).toEqual([20, 19, 18, 17]);
      expect(r.state.items[3].description).toBe('x');
    });

    it('an update of a shown task that still matches keeps the total', () => {
      const r = applyChange(loadedAll(), task(8, { title: 'New title' }), Q, 'updated');
      expect(ids(r.state)).toEqual([9, 8, 7]);
      expect(r.state.total).toBe(3);
    });
  });

  describe('removeTask', () => {
    it('removes the task, lowers the total and remembers the id', () => {
      const s = removeTask(firstPage(page([task(9), task(8)], 5, 'c1')), 8);
      expect(ids(s)).toEqual([9]);
      expect(s.total).toBe(4);
      expect(s.deleted.has(8)).toBe(true);
    });

    it('never goes below zero', () => {
      const s = removeTask({ ...firstPage(page([task(9)], 0, null)) }, 9);
      expect(s.total).toBe(0);
    });
  });

  describe('moveOut', () => {
    it('drops the moved task, lowers the total and skips it on later pages', () => {
      const s = moveOut(firstPage(page([task(9), task(8)], 5, 'c1')), 8);
      expect(ids(s)).toEqual([9]);
      expect(s.total).toBe(4);
      expect(s.deleted.has(8)).toBe(true);
      const next = appendPage(s, page([task(8), task(7)], 4, null));
      expect(ids(next)).toEqual([9, 7]);
    });

    it('a task that was not shown leaves the total alone', () => {
      const s = moveOut(firstPage(page([task(9)], 3, 'c1')), 2);
      expect(s.total).toBe(3);
    });
  });

  describe('My order (sort=manual)', () => {
    const M: TaskListQuery = { ...Q, sort: 'manual' };
    const doneTask = (id: number) => task(id, { status: 'DONE', done: true });

    it('appendPage skips ids that are already loaded and never moves them', () => {
      const s1 = firstPage(page([task(5), task(3), task(4)], 6, 'c1'), 'manual');
      // 4 is back on page 2 (a drop after the last row), with a newer updatedAt.
      const newer = task(4, { updatedAt: at(9) });
      const s2 = appendPage(s1, page([newer, task(2), task(1)], 6, null), 'manual');
      expect(ids(s2)).toEqual([5, 3, 4, 2, 1]);
      expect(s2.items[2].updatedAt).toBe(at(4));
      expect(s2.total).toBe(6);
    });

    it('other sorts still move a repeat with a newer updatedAt', () => {
      const s1 = firstPage(page([task(5), task(4)], 4, 'c1'));
      const s2 = appendPage(s1, page([task(4, { updatedAt: at(9) }), task(3)], 4, null));
      expect(ids(s2)).toEqual([5, 4, 3]);
      expect(s2.items[1].updatedAt).toBe(at(9));
    });

    it('a created task goes to the top of its group, not by createdAt', () => {
      const state = firstPage(page([task(2), task(8), doneTask(7)], 3), 'manual');
      const result = applyChange(state, task(1), M, 'created');
      expect(ids(result.state)).toEqual([1, 2, 8, 7]);
    });

    it('an edited task that stays in its group keeps its place', () => {
      const state = firstPage(page([task(2), task(8), task(5)], 3), 'manual');
      const result = applyChange(state, task(8, { title: 'New' }), M, 'updated');
      expect(ids(result.state)).toEqual([2, 8, 5]);
      expect(result.state.items[1].title).toBe('New');
    });

    it('a ticked task goes to the top of the done group', () => {
      const state = firstPage(page([task(2), task(8), doneTask(7)], 3), 'manual');
      const result = applyChange(state, doneTask(8), M, 'updated');
      expect(ids(result.state)).toEqual([2, 8, 7]);
      const first = applyChange(state, doneTask(2), M, 'updated');
      expect(ids(first.state)).toEqual([8, 2, 7]);
    });

    it('a done task fits only once the done group is loaded', () => {
      const open = firstPage(page([task(2), task(1)], 5, 'c1'), 'manual');
      expect(fitsLoaded(open, task(9), 'manual')).toBe(true);
      expect(fitsLoaded(open, doneTask(9), 'manual')).toBe(false);
      const withDone = firstPage(page([task(2), doneTask(1)], 5, 'c1'), 'manual');
      expect(fitsLoaded(withDone, doneTask(9), 'manual')).toBe(true);
    });
  });
});
