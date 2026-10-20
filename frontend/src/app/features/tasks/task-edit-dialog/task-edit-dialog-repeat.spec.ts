import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Task, TaskResult } from '../tasks.models';
import { TaskEditDialog } from './task-edit-dialog';

// 2026-11-02 is a Monday.
const TASK: Task = {
  id: 7,
  listId: 3,
  addedBy: null,
  title: 'Water plants',
  description: null,
  priority: 'NONE',
  status: 'TODO',
  tags: [],
  done: false,
  dueDate: '2026-11-02',
  dueTime: null,
  myReminder: null,
  myReminderPassed: false,
  repeat: null,
  createdAt: '2026-10-08T09:00:00Z',
  updatedAt: '2026-10-08T09:00:00Z',
};

@Component({
  imports: [TaskEditDialog],
  template: `<app-task-edit-dialog
    [task]="task()"
    (saved)="saved.push($event)"
    (closed)="closed = true"
  />`,
})
class Host {
  readonly task = signal<Task>(TASK);
  readonly saved: TaskResult[] = [];
  closed = false;
}

describe('TaskEditDialog repeat', () => {
  let fixture: ComponentFixture<Host>;
  let host: Host;
  let el: HTMLElement;
  let http: HttpTestingController;

  beforeAll(() => {
    HTMLDialogElement.prototype.showModal = function (this: HTMLDialogElement) {
      this.setAttribute('open', '');
    };
  });

  async function open(task: Task) {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(Host);
    host = fixture.componentInstance;
    host.task.set(task);
    el = fixture.nativeElement;
    await fixture.whenStable();
    http.expectOne('/api/tags').flush([]);
    await fixture.whenStable();
  }

  afterEach(() => http.verify());

  const q = <T extends Element = HTMLInputElement>(sel: string) => el.querySelector<T>(sel)!;
  const tid = <T extends Element = HTMLElement>(id: string) =>
    el.querySelector<T>(`[data-testid=${id}]`);
  const select = () => tid<HTMLSelectElement>('repeat-select')!;
  const submit = () => q<HTMLFormElement>('form').dispatchEvent(new Event('submit'));
  const day = (d: string) => tid<HTMLButtonElement>(`repeat-day-${d}`)!;

  async function choose(value: string) {
    select().value = value;
    select().dispatchEvent(new Event('change'));
    await fixture.whenStable();
  }

  async function click(button: HTMLElement) {
    button.click();
    await fixture.whenStable();
  }

  it('offers Never/Daily/Weekly/Monthly/Yearly, Never by default', async () => {
    await open(TASK);
    expect(Array.from(select().options).map((o) => [o.value, o.text.trim()])).toEqual([
      ['NEVER', 'Never'],
      ['DAILY', 'Daily'],
      ['WEEKLY', 'Weekly'],
      ['MONTHLY', 'Monthly'],
      ['YEARLY', 'Yearly'],
    ]);
    expect(select().value).toBe('NEVER');
    expect(select().disabled).toBe(false);
    expect(tid('repeat-weekdays')).toBeNull();
  });

  it('is disabled with a hint when there is no due date', async () => {
    await open({ ...TASK, dueDate: null });
    expect(select().disabled).toBe(true);
    expect(tid('repeat-hint')?.textContent).toContain('Set a due date to repeat');
  });

  it('clearing the date resets Repeat to Never and disables it', async () => {
    await open({ ...TASK, repeat: { frequency: 'DAILY', weekdays: [] } });
    expect(select().value).toBe('DAILY');
    q('#task-edit-due').value = '';
    q('#task-edit-due').dispatchEvent(new Event('input'));
    await fixture.whenStable();
    expect(select().value).toBe('NEVER');
    expect(select().disabled).toBe(true);
    submit();
    const req = http.expectOne({ method: 'PUT', url: '/api/tasks/7' });
    expect(req.request.body.dueDate).toBeNull();
    expect('repeat' in req.request.body).toBe(false);
  });

  it('does not send repeat when it was and stays Never', async () => {
    await open(TASK);
    submit();
    const req = http.expectOne({ method: 'PUT', url: '/api/tasks/7' });
    expect('repeat' in req.request.body).toBe(false);
  });

  it('sends NEVER to remove an existing rule', async () => {
    await open({ ...TASK, repeat: { frequency: 'MONTHLY', weekdays: [] } });
    await choose('NEVER');
    submit();
    expect(http.expectOne('/api/tasks/7').request.body.repeat).toBe('NEVER');
  });

  it('sends an empty weekdays array for non-weekly rules', async () => {
    await open(TASK);
    await choose('DAILY');
    submit();
    expect(http.expectOne('/api/tasks/7').request.body.repeat).toEqual({
      frequency: 'DAILY',
      weekdays: [],
    });
  });

  it('weekly preselects the due date weekday and sends the chosen days Monday first', async () => {
    await open(TASK);
    await choose('WEEKLY');
    expect(day('MON').getAttribute('aria-pressed')).toBe('true');
    expect(day('TUE').getAttribute('aria-pressed')).toBe('false');
    await click(day('FRI'));
    await click(day('WED'));
    submit();
    const req = http.expectOne('/api/tasks/7');
    expect(req.request.body.repeat).toEqual({
      frequency: 'WEEKLY',
      weekdays: ['MON', 'WED', 'FRI'],
    });
    req.flush({ ...TASK, nextTask: null });
    await fixture.whenStable();
    expect(host.saved.length).toBe(1);
  });

  it('prefills the current rule and re-sends it unchanged', async () => {
    await open({ ...TASK, repeat: { frequency: 'WEEKLY', weekdays: ['TUE', 'THU'] } });
    expect(day('TUE').getAttribute('aria-pressed')).toBe('true');
    expect(day('MON').getAttribute('aria-pressed')).toBe('false');
    submit();
    expect(http.expectOne('/api/tasks/7').request.body.repeat).toEqual({
      frequency: 'WEEKLY',
      weekdays: ['TUE', 'THU'],
    });
  });

  it('blocks Save and shows an error for weekly with no weekday', async () => {
    await open(TASK);
    await choose('WEEKLY');
    await click(day('MON'));
    expect(tid('repeat-weekday-error')).toBeNull();
    submit();
    await fixture.whenStable();
    expect(tid('repeat-weekday-error')?.textContent).toContain('Pick at least one weekday');
    http.expectNone('/api/tasks/7');
    await click(day('SUN'));
    expect(tid('repeat-weekday-error')).toBeNull();
    submit();
    http.expectOne('/api/tasks/7');
  });

  it('shows monthly and yearly hints', async () => {
    await open({ ...TASK, dueDate: '2026-01-31' });
    await choose('MONTHLY');
    expect(tid('repeat-hint')?.textContent).toContain(
      'Repeats on the 31st; shorter months use the last day',
    );
    await choose('YEARLY');
    expect(tid('repeat-hint')?.textContent).toContain('Repeats every January 31');
  });

  it('maps a 400 on repeat to the Repeat field, not the banner', async () => {
    await open(TASK);
    await choose('DAILY');
    submit();
    http.expectOne('/api/tasks/7').flush(
      {
        type: 'about:blank',
        title: 'x',
        status: 400,
        detail: 'x',
        code: 'VALIDATION_FAILED',
        errors: [{ field: 'repeat', code: 'INVALID_REPEAT', message: 'Bad rule' }],
      },
      { status: 400, statusText: 'Bad Request' },
    );
    await fixture.whenStable();
    expect(tid('repeat-error')?.textContent).toContain('Bad rule');
    expect(q('[role=alert].notice')).toBeNull();
  });
});
