import {
  afterIdOf,
  canDrag,
  CLEAR_FILTERS_HINT,
  dragHint,
  groupIndex,
  moveInGroup,
  placeAfter,
  positionText,
  SWITCH_TO_MANUAL_HINT,
} from './reorder.logic';
import { Task, TaskListQuery } from './tasks.models';

function task(id: number, done = false): Task {
  return {
    id,
    listId: 1,
    addedBy: null,
    title: `T${id}`,
    description: null,
    priority: 'NONE',
    status: done ? 'DONE' : 'TODO',
    tags: [],
    done,
    dueDate: null,
    dueTime: null,
    myReminder: null,
    myReminderPassed: false,
    repeat: null,
    createdAt: '2026-10-01T09:00:00Z',
    updatedAt: '2026-10-01T09:00:00Z',
  };
}
const ids = (items: readonly Task[]) => items.map((t) => t.id);
// Not-done group 1 2 3, done group 7 8.
const LIST = [task(1), task(2), task(3), task(7, true), task(8, true)];
const Q: TaskListQuery = { filter: 'all', tag: null, q: null, sort: 'manual' };

describe('reorder.logic', () => {
  describe('canDrag / dragHint', () => {
    it('allows dragging only in My order with All, no tag and no search', () => {
      expect(canDrag(Q)).toBe(true);
      expect(canDrag({ ...Q, sort: 'newest' })).toBe(false);
      expect(canDrag({ ...Q, filter: 'active' })).toBe(false);
      expect(canDrag({ ...Q, tag: 'home' })).toBe(false);
      expect(canDrag({ ...Q, q: 'milk' })).toBe(false);
    });

    it('hints how to get handles back', () => {
      expect(dragHint(Q)).toBeNull();
      expect(dragHint({ ...Q, sort: 'oldest' })).toEqual({
        kind: 'switch',
        text: SWITCH_TO_MANUAL_HINT,
        blockers: [],
        actionLabel: 'Use My order',
      });
      expect(SWITCH_TO_MANUAL_HINT).toBe('Drag to reorder is only available in "My order".');
      expect(CLEAR_FILTERS_HINT).toBe('To drag, show all tasks. Now filtered by:');
    });

    it('lists exactly the active blockers in My order', () => {
      const clear = (q: Partial<TaskListQuery>, label: string | null = null) =>
        dragHint({ ...Q, ...q }, label);
      expect(clear({ filter: 'done' })).toMatchObject({
        kind: 'clear',
        text: CLEAR_FILTERS_HINT,
        actionLabel: 'Clear filters',
        blockers: ['Done tab'],
      });
      expect(clear({ filter: 'active' })!.blockers).toEqual(['Active tab']);
      expect(clear({ filter: 'in_progress' })!.blockers).toEqual(['In progress tab']);
      expect(clear({ tag: 'home' })!.blockers).toEqual(['tag "home"']);
      expect(clear({ tag: 'home' }, 'Home')!.blockers).toEqual(['tag "Home"']);
      expect(clear({ q: 'milk' })!.blockers).toEqual(['search "milk"']);
      expect(clear({ filter: 'active', tag: 'home', q: 'milk' })!.blockers.join(' · ')).toBe(
        'Active tab · tag "home" · search "milk"',
      );
      expect(clear({ tag: 'a', q: 'b' })!.blockers).toEqual(['tag "a"', 'search "b"']);
    });

    it('ignores filters when the sort is not My order', () => {
      expect(dragHint({ ...Q, sort: 'newest', filter: 'done', q: 'x' })!.blockers).toEqual([]);
    });
  });

  describe('afterIdOf', () => {
    it('is the row directly above in the same group', () => {
      expect(afterIdOf(LIST, 2)).toBe(1);
      expect(afterIdOf(LIST, 3)).toBe(2);
      expect(afterIdOf(LIST, 8)).toBe(7);
    });

    it('is null at the top of either group (never the other group)', () => {
      expect(afterIdOf(LIST, 1)).toBeNull();
      expect(afterIdOf(LIST, 7)).toBeNull();
    });

    it('is null for an unknown id', () => {
      expect(afterIdOf(LIST, 99)).toBeNull();
      expect(groupIndex(LIST, 99)).toBe(-1);
    });
  });

  describe('moveInGroup', () => {
    it('moves inside the not-done group and leaves the done group alone', () => {
      expect(ids(moveInGroup(LIST, 3, 0))).toEqual([3, 1, 2, 7, 8]);
      expect(ids(moveInGroup(LIST, 1, 2))).toEqual([2, 3, 1, 7, 8]);
    });

    it('moves inside the done group', () => {
      expect(ids(moveInGroup(LIST, 8, 0))).toEqual([1, 2, 3, 8, 7]);
    });

    it('clamps to the group edges, so the last row can be dropped at the end', () => {
      expect(ids(moveInGroup(LIST, 1, 99))).toEqual([2, 3, 1, 7, 8]);
      expect(ids(moveInGroup(LIST, 3, -4))).toEqual([3, 1, 2, 7, 8]);
    });

    it('returns the same array when nothing changes', () => {
      expect(moveInGroup(LIST, 2, 1)).toBe(LIST);
    });

    it('afterId follows the moved row', () => {
      expect(afterIdOf(moveInGroup(LIST, 3, 1), 3)).toBe(1);
      expect(afterIdOf(moveInGroup(LIST, 3, 0), 3)).toBeNull();
      expect(afterIdOf(moveInGroup(LIST, 1, 2), 1)).toBe(3);
    });
  });

  describe('placeAfter (snap-back)', () => {
    it('puts a task back after its old predecessor', () => {
      const moved = moveInGroup(LIST, 3, 0);
      expect(ids(placeAfter(moved, 3, 2))).toEqual([1, 2, 3, 7, 8]);
    });

    it('null puts it on top of its group', () => {
      expect(ids(placeAfter(LIST, 3, null))).toEqual([3, 1, 2, 7, 8]);
      expect(ids(placeAfter(LIST, 8, null))).toEqual([1, 2, 3, 8, 7]);
    });

    it('only moves that task: a tick that landed meanwhile stays', () => {
      const moved = moveInGroup(LIST, 3, 0); // 3 1 2 | 7 8
      const ticked = moved.map((t) => (t.id === 1 ? task(1, true) : t)); // 1 is now done
      const back = placeAfter(ticked, 3, 2);
      expect(back.find((t) => t.id === 1)?.status).toBe('DONE');
      expect(ids(back).filter((i) => i !== 1)).toEqual([2, 3, 1, 7, 8].filter((i) => i !== 1));
    });

    it('a predecessor that is gone or in the other group means the top', () => {
      expect(ids(placeAfter(LIST, 3, 99))).toEqual([3, 1, 2, 7, 8]);
      expect(ids(placeAfter(LIST, 3, 7))).toEqual([3, 1, 2, 7, 8]);
    });
  });

  describe('positionText', () => {
    it('counts inside the group', () => {
      expect(positionText(LIST, 3)).toBe('Moved to position 3 of 3');
      expect(positionText(LIST, 8)).toBe('Moved to position 2 of 2');
      expect(positionText(LIST, 7, 'Dropped at')).toBe('Dropped at position 1 of 2');
    });

    it('is empty for an unknown id', () => {
      expect(positionText(LIST, 99)).toBe('');
    });
  });
});
