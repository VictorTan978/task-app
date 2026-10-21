import { HttpErrorResponse } from '@angular/common/http';
import { addCapped, BULK_ERROR, BULK_MAX, bulkFailure, rangeIds, tagChoices } from './bulk.logic';
import { Task } from './tasks.models';

function task(id: number, tags: string[] = []): Task {
  return {
    id,
    listId: 3,
    addedBy: null,
    title: `Task ${id}`,
    description: null,
    priority: 'NONE',
    status: 'TODO',
    tags,
    done: false,
    dueDate: null,
    dueTime: null,
    myReminder: null,
    myReminderPassed: false,
    repeat: null,
    createdAt: '2026-10-08T09:15:02Z',
    updatedAt: '2026-10-08T09:15:02Z',
  };
}

function problem(status: number, code: string, extra: Record<string, unknown> = {}) {
  return new HttpErrorResponse({
    status,
    error: { type: 'about:blank', title: 'x', status, detail: 'x', code, ...extra },
  });
}

describe('bulk.logic', () => {
  const items = [task(1), task(2), task(3), task(4)];

  it('rangeIds covers both ends in list order, either direction', () => {
    expect(rangeIds(items, 2, 4)).toEqual([2, 3, 4]);
    expect(rangeIds(items, 4, 2)).toEqual([2, 3, 4]);
  });

  it('rangeIds falls back to the target when the anchor is not shown', () => {
    expect(rangeIds(items, null, 3)).toEqual([3]);
    expect(rangeIds(items, 99, 3)).toEqual([3]);
  });

  it('addCapped never goes over the 200 cap', () => {
    const many = Array.from({ length: 250 }, (_, i) => i + 1);
    expect(addCapped(new Set(), many).size).toBe(BULK_MAX);
    expect(addCapped(new Set([1]), [1, 2]).size).toBe(2);
  });

  it('tagChoices lists each tag once, ignoring case, sorted', () => {
    expect(tagChoices([task(1, ['Home', 'work']), task(2, ['home', 'Errands'])])).toEqual([
      'Errands',
      'Home',
      'work',
    ]);
    expect(tagChoices([task(1)])).toEqual([]);
  });

  describe('bulkFailure', () => {
    it('names the count of unavailable tasks and offers a reload', () => {
      const f = bulkFailure(problem(404, 'NOT_FOUND', { failedIds: [1, 2, 3] }));
      expect(f).toMatchObject({
        message: '3 tasks are no longer available. Reload and try again.',
        reload: true,
      });
      expect(bulkFailure(problem(404, 'NOT_FOUND', { failedIds: [1] })).message).toBe(
        '1 task is no longer available. Reload and try again.',
      );
      expect(bulkFailure(problem(404, 'NOT_FOUND')).message).toBe(
        'Some tasks are no longer available. Reload and try again.',
      );
    });

    it('LIST_NOT_FOUND is about the target list and refreshes the picker', () => {
      const f = bulkFailure(problem(404, 'LIST_NOT_FOUND'));
      expect(f.refreshLists).toBe(true);
      expect(f.reload).toBe(false);
      expect(f.message).toContain('list');
    });

    it('MIXED_LISTS asks to reload', () => {
      const f = bulkFailure(
        problem(400, 'VALIDATION_FAILED', {
          errors: [{ field: 'ids', code: 'MIXED_LISTS', message: 'x' }],
        }),
      );
      expect(f).toMatchObject({ reload: true });
      expect(f.message).toContain('Reload and try again');
    });

    it('TOO_MANY (10 tags) counts the tasks; TAG_LIMIT (200 tags) is about the list', () => {
      const many = bulkFailure(
        problem(400, 'VALIDATION_FAILED', {
          errors: [{ field: 'tags', code: 'TOO_MANY', message: 'x' }],
          failedIds: [12, 14],
        }),
      );
      expect(many.message).toBe(
        '2 tasks are already at the limit of 10 tags, so nothing was changed.',
      );
      const limit = bulkFailure(
        problem(400, 'VALIDATION_FAILED', {
          errors: [{ field: 'tags', code: 'TAG_LIMIT', message: 'x' }],
        }),
      );
      expect(limit.message).toContain('200 tags');
    });

    it('OUT_OF_RANGE and a bad tag name get their own messages', () => {
      expect(
        bulkFailure(
          problem(400, 'VALIDATION_FAILED', {
            errors: [{ field: 'ids', code: 'OUT_OF_RANGE', message: 'x' }],
          }),
        ).message,
      ).toBe('Select 1 to 200 tasks.');
      expect(
        bulkFailure(
          problem(400, 'VALIDATION_FAILED', {
            errors: [{ field: 'value', code: 'INVALID_CHARACTERS', message: 'x' }],
          }),
        ).message,
      ).toBe("That tag name isn't valid.");
    });

    it('anything else is the generic message; 401 stays silent', () => {
      expect(bulkFailure(new HttpErrorResponse({ status: 500 })).message).toBe(BULK_ERROR);
      expect(bulkFailure(problem(500, 'INTERNAL_ERROR')).message).toBe(BULK_ERROR);
      expect(bulkFailure(new HttpErrorResponse({ status: 401 })).silent).toBe(true);
    });
  });
});
