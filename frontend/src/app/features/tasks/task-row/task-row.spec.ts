import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Task } from '../tasks.models';
import { TaskRow } from './task-row';

const BASE: Task = {
  id: 1,
  title: 'Buy milk',
  description: null,
  priority: 'NONE',
  status: 'TODO',
  tags: [],
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
});
