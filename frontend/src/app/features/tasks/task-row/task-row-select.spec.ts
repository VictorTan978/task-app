import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Task } from '../tasks.models';
import { RowSelect, TaskRow } from './task-row';

const BASE: Task = {
  id: 1,
  listId: 3,
  addedBy: null,
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
    [task]="task"
    today="2026-10-08"
    [selecting]="selecting()"
    [selected]="selected()"
    (selectChange)="picks.push($event)"
    (edit)="edits = edits + 1"
  />`,
})
class Host {
  readonly task = BASE;
  readonly selecting = signal(false);
  readonly selected = signal(false);
  readonly picks: RowSelect[] = [];
  edits = 0;
}

describe('TaskRow selection', () => {
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

  const boxes = () => el.querySelectorAll<HTMLInputElement>('input[type=checkbox]');

  it('outside selection mode there is exactly one checkbox: Done', () => {
    expect(boxes()).toHaveLength(1);
    expect(boxes()[0].getAttribute('aria-label')).toBe('Done: Buy milk');
  });

  it('in selection mode the one checkbox is "Select <title>" and Edit / Delete are hidden', async () => {
    host.selecting.set(true);
    await fixture.whenStable();
    expect(boxes()).toHaveLength(1);
    expect(boxes()[0].getAttribute('aria-label')).toBe('Select Buy milk');
    expect(el.querySelector('.task-row__actions')).toBeNull();
  });

  it('shows the selected state', async () => {
    host.selecting.set(true);
    host.selected.set(true);
    await fixture.whenStable();
    expect(boxes()[0].checked).toBe(true);
    expect(el.querySelector('.task-row--selected')).not.toBeNull();
  });

  it('a click emits checked and whether Shift was held', async () => {
    host.selecting.set(true);
    await fixture.whenStable();
    boxes()[0].click();
    boxes()[0].dispatchEvent(new MouseEvent('click', { shiftKey: true, bubbles: true }));
    expect(host.picks[0]).toEqual({ checked: true, shift: false });
    expect(host.picks[1].shift).toBe(true);
  });

  it('clicking the title selects instead of editing, and toggles the current state', async () => {
    host.selecting.set(true);
    host.selected.set(true);
    await fixture.whenStable();
    el.querySelector<HTMLButtonElement>('.task-row__title')!.click();
    expect(host.picks).toEqual([{ checked: false, shift: false }]);
    expect(host.edits).toBe(0);
  });

  it('the checkbox shows the model after a click the page does not honour', async () => {
    host.selecting.set(true);
    host.selected.set(true);
    await fixture.whenStable();
    boxes()[0].dispatchEvent(new MouseEvent('click', { shiftKey: true, bubbles: true }));
    expect(host.picks).toEqual([{ checked: false, shift: true }]);
    await fixture.whenStable();
    expect(boxes()[0].checked).toBe(true);
  });
});
