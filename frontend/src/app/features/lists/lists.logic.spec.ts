import { HttpErrorResponse } from '@angular/common/http';
import { FormControl } from '@angular/forms';
import {
  applyListNameError,
  compareLists,
  deleteListQuestion,
  isListNotFound,
  listNameMessage,
  listNameValidators,
  parseListId,
} from './lists.logic';
import { TaskList } from './lists.models';

function httpError(status: number, code: string, errors?: unknown[]) {
  return new HttpErrorResponse({
    status,
    error: { type: 'about:blank', title: 'x', status, detail: 'x', code, errors },
  });
}

describe('lists.logic', () => {
  it.each([
    ['5', 5],
    ['123456', 123456],
    ['0', null],
    ['-1', null],
    ['05', null],
    ['1.5', null],
    ['abc', null],
    ['', null],
    [' 5', null],
    ['99999999999999999999', null],
    [null, null],
  ])('parseListId(%s) = %s', (raw, expected) => {
    expect(parseListId(raw)).toBe(expected);
  });

  it('deleteListQuestion names the list and counts its tasks', () => {
    expect(deleteListQuestion('Home', 12)).toBe('Delete "Home" and its 12 tasks?');
    expect(deleteListQuestion('Home', 1)).toBe('Delete "Home" and its 1 task?');
    expect(deleteListQuestion('Home', 0)).toBe('Delete "Home"?');
  });

  it('compareLists: personal first, then name ignoring case and accents, then id', () => {
    const lists: TaskList[] = [
      { id: 11, name: 'work', personal: false, role: 'OWNER', memberCount: 1 },
      { id: 9, name: 'Work', personal: false, role: 'OWNER', memberCount: 1 },
      { id: 12, name: 'Äpfel', personal: false, role: 'OWNER', memberCount: 1 },
      { id: 3, name: 'Zed', personal: true, role: 'OWNER', memberCount: 1 },
    ];
    expect([...lists].sort(compareLists).map((l) => l.id)).toEqual([3, 12, 9, 11]);
  });

  it('name validators and messages (trimmed, 1–100)', () => {
    const control = new FormControl('   ', { nonNullable: true, validators: listNameValidators });
    expect(listNameMessage(control)).toBe('Name is required');
    control.setValue(' ' + 'a'.repeat(100) + ' ');
    expect(listNameMessage(control)).toBeNull();
    control.setValue('a'.repeat(101));
    expect(listNameMessage(control)).toBe('Name must be 100 characters or fewer');
  });

  it('applyListNameError puts the server name error on the control', () => {
    const control = new FormControl('x', { nonNullable: true });
    const error = httpError(400, 'VALIDATION_FAILED', [
      { field: 'name', code: 'TOO_LONG', message: 'Name must be 100 characters or fewer' },
    ]);
    expect(applyListNameError(error, control)).toBe(true);
    expect(listNameMessage(control)).toBe('Name must be 100 characters or fewer');
    expect(applyListNameError(httpError(409, 'LIST_LIMIT'), control)).toBe(false);
  });

  it('isListNotFound needs 404 and the LIST_NOT_FOUND code', () => {
    expect(isListNotFound(httpError(404, 'LIST_NOT_FOUND'))).toBe(true);
    expect(isListNotFound(httpError(404, 'NOT_FOUND'))).toBe(false);
    expect(isListNotFound(new Error('x'))).toBe(false);
  });
});
