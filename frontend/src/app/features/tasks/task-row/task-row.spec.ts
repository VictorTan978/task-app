import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Task } from '../tasks.models';
import { TaskRow } from './task-row';

const BASE: Task = {
  id: 1,
  listId: 3,
  addedBy: { displayName: 'Ana' },
  title: 'Buy milk',
  description: null,
  priority: 'NONE',
  status: 'TODO',
  tags: [],
  done: false,
  dueDate: null,
  dueTime: null,
  myReminder: null,
  myReminderPassed: false,
  repeat: null,
  createdAt: '2026-10-08T09:00:00Z',
  updatedAt: '2026-10-08T09:00:00Z',
};

@Component({
  imports: [TaskRow],
  template: `<app-task-row
    [task]="task()"
    today="2026-10-08"
    [time]="time()"
    [busy]="busy()"
    [showAddedBy]="shared()"
    (doneChange)="events.push('done:' + $event)"
    (edit)="events.push('edit')"
    (remove)="events.push('remove')"
  />`,
})
class Host {
  readonly task = signal<Task>(BASE);
  readonly time = signal('');
  readonly busy = signal(false);
  readonly shared = signal(false);
  readonly events: string[] = [];
}

describe('TaskRow', () => {
  let fixture: ComponentFixture<Host>;
  let host: Host;
  let el: HTMLElement;

  beforeEach(async () => {
    TestBed.configureTestingModule({ providers: [provideRouter([])] });
    fixture = TestBed.createComponent(Host);
    host = fixture.componentInstance;
    el = fixture.nativeElement;
    await fixture.whenStable();
  });

  const checkbox = () => el.querySelector<HTMLInputElement>('input[type=checkbox]')!;
  const buttons = () => Array.from(el.querySelectorAll('button'));
  const button = (name: string) => buttons().find((b) => b.textContent?.trim() === name)!;

  /** `done: true` alone means status DONE; `done` always mirrors `status`. */
  async function setTask(overrides: Partial<Task>) {
    const status = overrides.status ?? (overrides.done ? 'DONE' : 'TODO');
    host.task.set({ ...BASE, ...overrides, status, done: status === 'DONE' });
    await fixture.whenStable();
  }

  const priority = () => el.querySelector<HTMLElement>('[data-testid=task-priority]');
  const inProgress = () => el.querySelector('[data-testid=task-in-progress]');
  const tagLinks = () => Array.from(el.querySelectorAll<HTMLAnchorElement>('a.task-row__tag'));

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

  it('shows no badges, tags or meta line for a plain task', () => {
    expect(priority()).toBeNull();
    expect(inProgress()).toBeNull();
    expect(tagLinks()).toEqual([]);
    expect(el.querySelector('.task-row__meta')).toBeNull();
  });

  it.each([
    ['LOW', 'Low', 'task-row__priority--low'],
    ['MEDIUM', 'Medium', 'task-row__priority--medium'],
    ['HIGH', 'High', 'task-row__priority--high'],
  ] as const)('shows a %s priority badge', async (value, label, cls) => {
    await setTask({ priority: value });
    expect(priority()?.textContent?.trim()).toBe(label);
    expect(priority()?.classList).toContain(cls);
    expect(priority()?.classList).toContain('task-row__badge');
    expect(priority()?.getAttribute('title')).toBe(`Priority: ${label}`);
  });

  it('keeps the priority badge (faded via the done row) on Done tasks', async () => {
    await setTask({ priority: 'HIGH', done: true });
    expect(priority()?.textContent?.trim()).toBe('High');
    expect(el.querySelector('.task-row--done')).not.toBeNull();
  });

  it('shows "In progress" unticked, with Overdue when past due; Done has neither', async () => {
    await setTask({ status: 'IN_PROGRESS', dueDate: '2026-10-01' });
    expect(inProgress()?.textContent?.trim()).toBe('In progress');
    expect(checkbox().checked).toBe(false);
    expect(el.querySelector('.task-row--done')).toBeNull();
    expect(el.textContent).toContain('Overdue');

    await setTask({ status: 'DONE', dueDate: '2026-10-01' });
    expect(inProgress()).toBeNull();
    expect(el.textContent).not.toContain('Overdue');
    expect(checkbox().checked).toBe(true);
  });

  it('shows badges in order: priority, In progress, due, Overdue, tags', async () => {
    await setTask({
      priority: 'MEDIUM',
      status: 'IN_PROGRESS',
      dueDate: '2026-10-01',
      tags: ['Errands', 'Work'],
    });
    const parts = Array.from(el.querySelector('.task-row__meta')!.children).map((c) =>
      c.textContent?.trim(),
    );
    expect(parts).toEqual([
      'Medium',
      'In progress',
      'Due 2026-10-01',
      'Overdue',
      'Errands',
      'Work',
    ]);
  });

  it('shows tags as links that set ?tag', async () => {
    await setTask({ tags: ['Errands', 'Wörk & co'] });
    expect(tagLinks().map((a) => a.textContent?.trim())).toEqual(['Errands', 'Wörk & co']);
    expect(tagLinks()[0].getAttribute('href')).toBe('/?tag=Errands');
    expect(tagLinks()[1].getAttribute('href')).toBe('/?tag=W%C3%B6rk%20%26%20co');
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

  describe('Added by', () => {
    const addedBy = () => el.querySelector('[data-testid=task-added-by]');

    it('is hidden on a list with one member', () => {
      expect(addedBy()).toBeNull();
      expect(el.querySelector('.task-row__meta')).toBeNull();
    });

    it('shows "Added by <name>" on a shared list', async () => {
      host.shared.set(true);
      await fixture.whenStable();
      expect(addedBy()?.textContent?.trim()).toBe('Added by Ana');
    });

    it('shows "Added by a deleted user" when the account is gone', async () => {
      host.shared.set(true);
      await setTask({ addedBy: null });
      expect(addedBy()?.textContent?.trim()).toBe('Added by a deleted user');
    });
  });
});

describe('TaskRow due time and reminder', () => {
  let fixture: ComponentFixture<Host>;
  let host: Host;
  let el: HTMLElement;

  beforeEach(async () => {
    TestBed.configureTestingModule({ providers: [provideRouter([])] });
    fixture = TestBed.createComponent(Host);
    host = fixture.componentInstance;
    el = fixture.nativeElement;
    await fixture.whenStable();
  });

  const due = () => el.querySelector('.task-row__due')?.textContent?.replace(/\s+/g, ' ').trim();

  it('shows date and time for a timed task, and the date only otherwise', async () => {
    host.task.set({ ...BASE, dueDate: '2026-10-21', dueTime: '09:30' });
    await fixture.whenStable();
    expect(due()).toBe('Due 2026-10-21 09:30');
    expect(el.querySelector('time')?.getAttribute('datetime')).toBe('2026-10-21T09:30');
    host.task.set({ ...BASE, dueDate: '2026-10-21' });
    await fixture.whenStable();
    expect(due()).toBe('Due 2026-10-21');
    expect(el.querySelector('time')?.getAttribute('datetime')).toBe('2026-10-21');
  });

  it('shows a bell only when I have a reminder', async () => {
    host.task.set({ ...BASE, dueDate: '2026-10-21', dueTime: '09:30' });
    await fixture.whenStable();
    expect(el.querySelector('[data-testid=task-reminder]')).toBeNull();
    host.task.set({ ...BASE, dueDate: '2026-10-21', dueTime: '09:30', myReminder: 'AT_DUE' });
    await fixture.whenStable();
    expect(el.querySelector('[data-testid=task-reminder]')?.getAttribute('aria-label')).toBe(
      'Reminder set',
    );
  });

  it('marks a timed task overdue once its time has passed today', async () => {
    host.task.set({ ...BASE, dueDate: '2026-10-08', dueTime: '09:30' });
    host.time.set('09:00');
    await fixture.whenStable();
    expect(el.querySelector('.task-row__overdue')).toBeNull();
    host.time.set('09:31');
    await fixture.whenStable();
    expect(el.querySelector('.task-row__overdue')).not.toBeNull();
  });

  it('shows a repeat icon only for repeating tasks, naming the weekdays', async () => {
    host.task.set({ ...BASE, dueDate: '2026-10-21' });
    await fixture.whenStable();
    expect(el.querySelector('[data-testid=task-repeat]')).toBeNull();
    host.task.set({
      ...BASE,
      dueDate: '2026-10-21',
      repeat: { frequency: 'WEEKLY', weekdays: ['MON', 'WED'] },
    });
    await fixture.whenStable();
    expect(el.querySelector('[data-testid=task-repeat]')?.getAttribute('aria-label')).toBe(
      'Repeats weekly (Mon, Wed)',
    );
  });
});
