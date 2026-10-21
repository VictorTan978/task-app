import { LiveAnnouncer } from '@angular/cdk/a11y';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { provideRouter } from '@angular/router';
import { ListDrop, ReorderDrop } from '../reorder.logic';
import { Task } from '../tasks.models';
import { TaskSortList } from './task-sort-list';

function task(id: number, done = false): Task {
  return {
    id,
    listId: 8,
    addedBy: null,
    title: `Task ${id}`,
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
// Not-done 1 2 3, done 7 8.
const LIST = [task(1), task(2), task(3), task(7, true), task(8, true)];

describe('TaskSortList', () => {
  let fixture: ComponentFixture<TaskSortList>;
  let el: HTMLElement;
  let announce: ReturnType<typeof vi.fn>;
  let reordered: ReorderDrop[];
  let dropped: ListDrop[];

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideRouter([])] });
    announce = vi.fn();
    vi.spyOn(TestBed.inject(LiveAnnouncer), 'announce').mockImplementation(
      announce as unknown as LiveAnnouncer['announce'],
    );
    fixture = TestBed.createComponent(TaskSortList);
    fixture.componentRef.setInput('tasks', LIST);
    fixture.componentRef.setInput('today', '2026-10-21');
    fixture.componentRef.setInput('ownListId', 8);
    reordered = [];
    dropped = [];
    fixture.componentInstance.reordered.subscribe((e) => reordered.push(e));
    fixture.componentInstance.droppedOnList.subscribe((e) => dropped.push(e));
    el = fixture.nativeElement;
    fixture.detectChanges();
  });

  afterEach(() => {
    delete (document as { elementsFromPoint?: unknown }).elementsFromPoint;
    document.querySelectorAll('[data-test-target]').forEach((n) => n.remove());
  });

  const handles = () =>
    Array.from(el.querySelectorAll<HTMLButtonElement>('[data-testid="drag-handle"]'));
  const handle = (id: number) => el.querySelector<HTMLButtonElement>(`[data-handle-for="${id}"]`)!;
  const titles = () =>
    Array.from(el.querySelectorAll('.task-row__title')).map((b) => b.textContent?.trim());
  const key = (id: number, k: string) => {
    const e = new KeyboardEvent('keydown', { key: k, bubbles: true, cancelable: true });
    handle(id).dispatchEvent(e);
    fixture.detectChanges();
    return e;
  };
  const lists = () => fixture.debugElement.queryAll(By.css('ul'));

  it('renders one list per group with a handle on every row', () => {
    expect(lists().map((l) => l.nativeElement.getAttribute('aria-label'))).toEqual([
      'Tasks',
      'Done tasks',
    ]);
    expect(handles()).toHaveLength(5);
    expect(titles()).toEqual(['Task 1', 'Task 2', 'Task 3', 'Task 7', 'Task 8']);
  });

  it('omits an empty group', () => {
    fixture.componentRef.setInput('tasks', [task(1), task(2)]);
    fixture.detectChanges();
    expect(lists()).toHaveLength(1);
  });

  describe('keyboard mode', () => {
    it('Space picks up and announces the position and the keys', () => {
      const e = key(2, ' ');
      expect(e.defaultPrevented).toBe(true);
      expect(handle(2).getAttribute('aria-pressed')).toBe('true');
      expect(announce).toHaveBeenCalledWith(
        expect.stringContaining('Picked up Task 2, position 2 of 3'),
      );
    });

    it('Up and Down move the row locally and announce each step, without emitting', () => {
      key(2, ' ');
      key(2, 'ArrowDown');
      expect(titles()).toEqual(['Task 1', 'Task 3', 'Task 2', 'Task 7', 'Task 8']);
      expect(announce).toHaveBeenLastCalledWith('Moved to position 3 of 3');
      key(2, 'ArrowUp');
      key(2, 'ArrowUp');
      expect(titles().slice(0, 3)).toEqual(['Task 2', 'Task 1', 'Task 3']);
      expect(announce).toHaveBeenLastCalledWith('Moved to position 1 of 3');
      expect(reordered).toEqual([]);
    });

    it('stops at the group edges and says so', () => {
      key(3, ' ');
      key(3, 'ArrowDown');
      expect(announce).toHaveBeenLastCalledWith('Already at the bottom');
      expect(titles()).toEqual(['Task 1', 'Task 2', 'Task 3', 'Task 7', 'Task 8']);
      key(7, ' ');
      key(7, 'ArrowUp');
      expect(announce).toHaveBeenLastCalledWith('Already at the top');
    });

    it('moves only inside the done group', () => {
      key(7, ' ');
      key(7, 'ArrowDown');
      expect(titles()).toEqual(['Task 1', 'Task 2', 'Task 3', 'Task 8', 'Task 7']);
      key(7, 'ArrowDown');
      expect(announce).toHaveBeenLastCalledWith('Already at the bottom');
    });

    it('Space drops: one event with the row above, and the new order', () => {
      key(3, ' ');
      key(3, 'ArrowUp');
      key(3, 'ArrowUp');
      key(3, ' ');
      expect(reordered).toHaveLength(1);
      expect(reordered[0].id).toBe(3);
      expect(reordered[0].afterId).toBeNull();
      expect(reordered[0].items.map((t) => t.id)).toEqual([3, 1, 2, 7, 8]);
      expect(announce).toHaveBeenLastCalledWith('Dropped at position 1 of 3');
      expect(handle(3).getAttribute('aria-pressed')).toBe('false');
    });

    it('afterId is the row above for a move in the middle', () => {
      key(1, ' ');
      key(1, 'ArrowDown');
      key(1, ' ');
      expect(reordered[0].afterId).toBe(2);
    });

    it('dropping where it started sends nothing', () => {
      key(2, ' ');
      key(2, 'ArrowDown');
      key(2, 'ArrowUp');
      key(2, ' ');
      expect(reordered).toEqual([]);
      expect(announce).toHaveBeenLastCalledWith('Dropped, still at position 2 of 3');
    });

    it('Esc cancels, restores the old place and does not reach the page', () => {
      const onDoc = vi.fn();
      document.addEventListener('keydown', onDoc);
      key(2, ' ');
      key(2, 'ArrowDown');
      const e = key(2, 'Escape');
      document.removeEventListener('keydown', onDoc);
      expect(e.defaultPrevented).toBe(true);
      expect(onDoc).not.toHaveBeenCalledWith(expect.objectContaining({ key: 'Escape' }));
      expect(titles()).toEqual(['Task 1', 'Task 2', 'Task 3', 'Task 7', 'Task 8']);
      expect(reordered).toEqual([]);
      expect(announce).toHaveBeenLastCalledWith('Cancelled. Back at position 2 of 3');
    });

    it('arrow keys do nothing before pick-up, and Esc is left to the page', () => {
      const e = key(2, 'ArrowDown');
      expect(e.defaultPrevented).toBe(false);
      expect(key(2, 'Escape').defaultPrevented).toBe(false);
      expect(titles()[1]).toBe('Task 2');
    });

    it('moving focus to another control cancels; a DOM move (no related target) does not', () => {
      key(2, ' ');
      handle(2).dispatchEvent(new FocusEvent('blur'));
      fixture.detectChanges();
      expect(handle(2).getAttribute('aria-pressed')).toBe('true');
      handle(2).dispatchEvent(new FocusEvent('blur', { relatedTarget: handle(1) }));
      fixture.detectChanges();
      expect(handle(2).getAttribute('aria-pressed')).toBe('false');
    });

    it('a new task list cancels a pick-up', () => {
      key(2, ' ');
      key(2, 'ArrowDown');
      fixture.componentRef.setInput('tasks', [...LIST]);
      fixture.detectChanges();
      expect(handle(2).getAttribute('aria-pressed')).toBe('false');
      expect(titles()[1]).toBe('Task 2');
    });

    it('does nothing while disabled', () => {
      fixture.componentRef.setInput('disabled', true);
      fixture.detectChanges();
      key(2, ' ');
      expect(announce).not.toHaveBeenCalled();
    });
  });

  describe('Move up / Move down', () => {
    const btn = (id: number, which: 'move-up' | 'move-down') =>
      el.querySelector<HTMLButtonElement>(
        `app-task-row[data-task-id="${id}"] [data-testid="${which}"]`,
      )!;

    it('are disabled at the edge of each group', () => {
      expect(btn(1, 'move-up').disabled).toBe(true);
      expect(btn(1, 'move-down').disabled).toBe(false);
      expect(btn(3, 'move-down').disabled).toBe(true);
      expect(btn(7, 'move-up').disabled).toBe(true);
      expect(btn(8, 'move-down').disabled).toBe(true);
      expect(btn(8, 'move-up').disabled).toBe(false);
    });

    it('Move down emits the new order, afterId and announces', () => {
      btn(1, 'move-down').click();
      expect(reordered).toHaveLength(1);
      expect(reordered[0]).toMatchObject({ id: 1, afterId: 2 });
      expect(reordered[0].items.map((t) => t.id)).toEqual([2, 1, 3, 7, 8]);
      expect(announce).toHaveBeenCalledWith('Moved to position 2 of 3');
    });

    it('Move up on the first done row would cross groups: disabled, nothing sent', () => {
      btn(7, 'move-up').click();
      expect(reordered).toEqual([]);
    });

    it('are disabled while a save runs', () => {
      fixture.componentRef.setInput('disabled', true);
      fixture.detectChanges();
      expect(btn(2, 'move-up').disabled).toBe(true);
      expect(btn(2, 'move-down').disabled).toBe(true);
    });
  });

  describe('drop (mouse and touch)', () => {
    const dropList = (index: number) => lists()[index];
    const drop = (
      index: number,
      event: Partial<{
        previousIndex: number;
        currentIndex: number;
        isPointerOverContainer: boolean;
        dropPoint: { x: number; y: number };
        data: Task;
      }>,
    ) => {
      const item = event.data ?? LIST[index === 0 ? (event.previousIndex ?? 0) : 3];
      dropList(index).triggerEventHandler('cdkDropListDropped', {
        previousIndex: 0,
        currentIndex: 0,
        isPointerOverContainer: true,
        dropPoint: { x: 10, y: 10 },
        item: { data: item },
        ...event,
      });
      fixture.detectChanges();
    };

    function target(listId: number) {
      const a = document.createElement('a');
      a.setAttribute('data-drop-list-id', String(listId));
      a.setAttribute('data-test-target', '');
      document.body.appendChild(a);
      const inner = document.createElement('span');
      a.appendChild(inner);
      return { a, inner };
    }

    it('emits the row now above after a drop inside the list', () => {
      drop(0, { previousIndex: 0, currentIndex: 2 });
      expect(reordered).toHaveLength(1);
      expect(reordered[0].id).toBe(1);
      expect(reordered[0].afterId).toBe(3);
      expect(reordered[0].items.map((t) => t.id)).toEqual([2, 3, 1, 7, 8]);
    });

    it('a drop on the last loaded row puts the task right after it', () => {
      drop(1, { previousIndex: 0, currentIndex: 1 });
      expect(reordered[0]).toMatchObject({ id: 7, afterId: 8 });
    });

    it('a drop at the top sends afterId null', () => {
      drop(0, { previousIndex: 2, currentIndex: 0, data: LIST[2] });
      expect(reordered[0]).toMatchObject({ id: 3, afterId: null });
    });

    it('a drop where it started sends nothing', () => {
      drop(0, { previousIndex: 1, currentIndex: 1, data: LIST[1] });
      expect(reordered).toEqual([]);
    });

    it('a drop outside the list and the sidebar sends nothing', () => {
      (document as unknown as { elementsFromPoint: () => Element[] }).elementsFromPoint = () => [];
      drop(0, { isPointerOverContainer: false, currentIndex: 2 });
      expect(reordered).toEqual([]);
      expect(dropped).toEqual([]);
    });

    it('a drop on a sidebar list moves the task there and never reorders', () => {
      const { inner } = target(9);
      const hit = vi.fn(() => [inner]);
      (document as unknown as { elementsFromPoint: typeof hit }).elementsFromPoint = hit;
      drop(0, {
        isPointerOverContainer: false,
        currentIndex: 2,
        dropPoint: { x: 5, y: 7 },
        data: LIST[1],
      });
      expect(hit).toHaveBeenCalledWith(5, 7);
      expect(dropped).toEqual([{ task: LIST[1], listId: 9 }]);
      expect(reordered).toEqual([]);
    });

    it('finds the target below the drag preview in the element stack', () => {
      const { inner } = target(9);
      const preview = document.createElement('div');
      (document as unknown as { elementsFromPoint: () => Element[] }).elementsFromPoint = () => [
        preview,
        inner,
      ];
      drop(0, { isPointerOverContainer: false, data: LIST[0] });
      expect(dropped).toEqual([{ task: LIST[0], listId: 9 }]);
    });

    it("a drop on the task's own list does nothing", () => {
      const { inner } = target(8);
      (document as unknown as { elementsFromPoint: () => Element[] }).elementsFromPoint = () => [
        inner,
      ];
      drop(0, { isPointerOverContainer: false, data: LIST[0] });
      expect(dropped).toEqual([]);
      expect(reordered).toEqual([]);
    });

    it('an element that is not a marked list (Trash) is not a target', () => {
      const trash = document.createElement('a');
      trash.setAttribute('data-test-target', '');
      document.body.appendChild(trash);
      (document as unknown as { elementsFromPoint: () => Element[] }).elementsFromPoint = () => [
        trash,
      ];
      drop(0, { isPointerOverContainer: false, data: LIST[0] });
      expect(dropped).toEqual([]);
    });

    it('highlights the sidebar list under the pointer while dragging, and clears it', () => {
      const { a, inner } = target(9);
      (document as unknown as { elementsFromPoint: () => Element[] }).elementsFromPoint = () => [
        inner,
      ];
      vi.useFakeTimers({ toFake: ['requestAnimationFrame', 'cancelAnimationFrame'] });
      try {
        const item = fixture.debugElement.query(By.css('li'));
        item.triggerEventHandler('cdkDragMoved', { pointerPosition: { x: 1, y: 2 } });
        vi.advanceTimersByTime(50);
        expect(a.classList.contains('list-picker__link--drop')).toBe(true);
        item.triggerEventHandler('cdkDragEnded', {});
        expect(a.classList.contains('list-picker__link--drop')).toBe(false);
      } finally {
        vi.useRealTimers();
      }
    });

    it("does not highlight the task's own list", () => {
      const { a, inner } = target(8);
      (document as unknown as { elementsFromPoint: () => Element[] }).elementsFromPoint = () => [
        inner,
      ];
      vi.useFakeTimers({ toFake: ['requestAnimationFrame', 'cancelAnimationFrame'] });
      try {
        fixture.debugElement
          .query(By.css('li'))
          .triggerEventHandler('cdkDragMoved', { pointerPosition: { x: 1, y: 2 } });
        vi.advanceTimersByTime(50);
        expect(a.classList.contains('list-picker__link--drop')).toBe(false);
      } finally {
        vi.useRealTimers();
      }
    });
  });

  it('relays tick, edit and delete from the rows', () => {
    const done: { task: Task; done: boolean }[] = [];
    const edits: Task[] = [];
    const removes: Task[] = [];
    fixture.componentInstance.doneChange.subscribe((e) => done.push(e));
    fixture.componentInstance.edit.subscribe((t) => edits.push(t));
    fixture.componentInstance.remove.subscribe((t) => removes.push(t));
    const row = el.querySelector('app-task-row[data-task-id="2"]')!;
    row.querySelector<HTMLInputElement>('input[type=checkbox]')!.click();
    row.querySelector<HTMLButtonElement>('.task-row__title')!.click();
    Array.from(row.querySelectorAll<HTMLButtonElement>('.task-row__actions button'))
      .find((b) => b.textContent?.trim() === 'Delete')!
      .click();
    expect(done).toEqual([{ task: LIST[1], done: true }]);
    expect(edits).toEqual([LIST[1]]);
    expect(removes).toEqual([LIST[1]]);
  });
});
