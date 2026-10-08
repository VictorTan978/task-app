import { HttpErrorResponse } from '@angular/common/http';
import { FormControl } from '@angular/forms';
import {
  applyTaskFieldErrors,
  descriptionValidators,
  isNotFound,
  normaliseDescription,
  taskFieldMessage,
  taskValidationProblem,
  titleValidators,
} from './task-form';
import { TaskProblem } from './tasks.models';

function problem(status: number, code: string, errors?: unknown[]) {
  return new HttpErrorResponse({
    status,
    error: { type: 'about:blank', title: 'x', status, detail: 'x', code, errors },
  });
}

describe('task-form', () => {
  describe('title validation (trimmed)', () => {
    const msg = (value: string) =>
      taskFieldMessage('title', new FormControl(value, titleValidators));

    it('requires a non-blank title', () => {
      expect(msg('')).toBe('Title is required');
      expect(msg('   ')).toBe('Title is required');
      expect(msg('a')).toBeNull();
    });

    it('allows 200 characters after trimming and rejects 201', () => {
      expect(msg('  ' + 'x'.repeat(200) + '  ')).toBeNull();
      expect(msg('x'.repeat(201))).toBe('Title must be 200 characters or fewer');
    });
  });

  describe('description validation (trimmed)', () => {
    const msg = (value: string) =>
      taskFieldMessage('description', new FormControl(value, descriptionValidators));

    it('allows empty and 2,000 characters, rejects 2,001', () => {
      expect(msg('')).toBeNull();
      expect(msg(' ' + 'x'.repeat(2000) + '\n')).toBeNull();
      expect(msg('x'.repeat(2001))).toBe('Description must be 2,000 characters or fewer');
    });
  });

  it('normaliseDescription trims and turns blank into null', () => {
    expect(normaliseDescription('  hi ')).toBe('hi');
    expect(normaliseDescription('   ')).toBeNull();
    expect(normaliseDescription('')).toBeNull();
  });

  it('applyTaskFieldErrors shows the server message under the matching field', () => {
    const title = new FormControl('ok', titleValidators);
    const done = new FormControl(false);
    const p: TaskProblem = {
      type: 'about:blank',
      title: 'Bad Request',
      status: 400,
      detail: 'Validation failed',
      code: 'VALIDATION_FAILED',
      errors: [
        { field: 'done', code: 'REQUIRED', message: 'Done is required' },
        { field: 'title', code: 'TOO_LONG', message: 'Server title text' },
      ],
    };
    expect(applyTaskFieldErrors(p, { title, done })).toBe(true);
    expect(taskFieldMessage('title', title)).toBe('Server title text');
    expect(taskFieldMessage('done', done)).toBe('Done is required');
    expect(title.touched).toBe(true);

    // Changing the value clears the server error.
    title.setValue('changed');
    expect(taskFieldMessage('title', title)).toBeNull();
  });

  it('applyTaskFieldErrors returns false when no field matches', () => {
    const p = { errors: [{ field: 'description', code: 'TOO_LONG', message: 'x' }] } as TaskProblem;
    expect(applyTaskFieldErrors(p, { title: new FormControl('') })).toBe(false);
  });

  it('taskValidationProblem only accepts 400 VALIDATION_FAILED with errors', () => {
    const errors = [{ field: 'title', code: 'REQUIRED', message: 'x' }];
    expect(taskValidationProblem(problem(400, 'VALIDATION_FAILED', errors))).not.toBeNull();
    expect(taskValidationProblem(problem(400, 'MALFORMED_REQUEST'))).toBeNull();
    expect(taskValidationProblem(problem(400, 'VALIDATION_FAILED', []))).toBeNull();
    expect(taskValidationProblem(new Error('x'))).toBeNull();
  });

  it('isNotFound', () => {
    expect(isNotFound(problem(404, 'NOT_FOUND'))).toBe(true);
    expect(isNotFound(problem(500, 'INTERNAL_ERROR'))).toBe(false);
  });
});
