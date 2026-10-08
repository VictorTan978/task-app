import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Task } from '../tasks.models';
import { TaskRow } from './task-row';

const BASE: Task = {
  id: 1,
  title: 'Buy milk',
  description: null,
  done: false,
  dueDate: null,
  createdAt: '2026-10-08T09:00:00Z',
  updatedAt: '2026-10-08T09:00:00Z',
};

@Component({
  imports: [TaskRow],
  template: `<app-task-row
    [task]="task()"
    today="2026-10-08"
    [busy]="busy()"
    (doneChange)="events.push('done:' + $event)"
    (edit)="events.push('edit')"
    (remove)="events.push('remove')"
  />`,
})
class Host {
  readonly task = signal<Task>(BASE);
  readonly busy = signal(false);
  readonly events: string[] = [];
}

describe('TaskRow', () => {
  let fixture: ComponentFixture<Host>;
  let host: Host;
  let el: HTMLElement;

  beforeEach(async () => {
    fixture = TestBed.createComponent(Host);
    host = fixture.componentInstance;
    el = fixture.nativeElement;
    await fixture.whenStable();
  });

  const checkbox = () => el.querySelector<HTMLInputElement>('input[type=checkbox]')!;
  const buttons = () => Array.from(el.querySelectorAll('button'));
  const button = (name: string) => buttons().find((b) => b.textContent?.trim() === name)!;

  async function setTask(overrides: Partial<Task>) {
    host.task.set({ ...BASE, ...overrides });
    await fixture.whenStable();
  }

  it('renders title, checkbox label and Edit/Delete buttons', () => {
    expect(button('Buy milk')).toBeTruthy();
    expect(checkbox().getAttribute('aria-label')).toBe('Done: Buy milk');
    expect(checkbox().checked).toBe(false);
    expect(button('Edit')).toBeTruthy();
    expect(button('Delete')).toBeTruthy();
  });

  it('shows the due date and a red Overdue label only for past-due, not-done tasks', async () => {
    expect(el.textContent).not.toContain('Due');

    await setTask({ dueDate: '2026-10-07' });
    expect(el.querySelector('time')?.textContent).toBe('2026-10-07');
    expect(el.querySelector('.task-row__overdue')?.textContent).toContain('Overdue');

    await setTask({ dueDate: '2026-10-08' });
    expect(el.textContent).toContain('2026-10-08');
    expect(el.textContent).not.toContain('Overdue');

    await setTask({ dueDate: '2026-10-07', done: true });
    expect(el.textContent).toContain('2026-10-07');
    expect(el.textContent).not.toContain('Overdue');
  });

  it('shows done tasks struck through and checked', async () => {
    await setTask({ done: true });
    expect(el.querySelector('.task-row--done')).not.toBeNull();
    expect(checkbox().checked).toBe(true);
  });

  it('emits the requested done value, edit and remove; title click opens edit', () => {
    checkbox().click();
    button('Buy milk').click();
    button('Edit').click();
    button('Delete').click();
    expect(host.events).toEqual(['done:true', 'edit', 'edit', 'remove']);
  });

  it('disables every control while busy', async () => {
    host.busy.set(true);
    await fixture.whenStable();
    expect(checkbox().disabled).toBe(true);
    expect(buttons().every((b) => b.disabled)).toBe(true);
  });

  it('reverts the checkbox when the request finishes without a change', async () => {
    checkbox().click();
    host.busy.set(true);
    await fixture.whenStable();
    expect(checkbox().checked).toBe(true);

    host.busy.set(false);
    await fixture.whenStable();
    expect(checkbox().checked).toBe(false);
  });
});
