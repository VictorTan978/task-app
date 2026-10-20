import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { RepeatRule, Task, TaskResult } from '../tasks.models';
import { TasksPage } from './tasks-page';

function task(overrides: Partial<Task> & { id: number }): Task {
  return {
    listId: 3,
    addedBy: null,
    title: `Task ${overrides.id}`,
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
    createdAt: `2026-10-0${overrides.id}T09:00:00Z`,
    updatedAt: `2026-10-0${overrides.id}T09:00:00Z`,
    ...overrides,
  };
}

const RULE: RepeatRule = { frequency: 'DAILY', weekdays: [] };
const WATER = task({ id: 5, title: 'Water plants', dueDate: '2999-01-01', repeat: RULE });

describe('TasksPage repeating tasks', () => {
  let harness: RouterTestingHarness;
  let http: HttpTestingController;
  let el: HTMLElement;

  beforeEach(async () => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([{ path: 'tasks', component: TasksPage }]),
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    });
    http = TestBed.inject(HttpTestingController);
    harness = await RouterTestingHarness.create();
    await harness.navigateByUrl('/tasks');
    el = harness.routeNativeElement!;
    http
      .expectOne((r) => r.method === 'GET' && r.url === '/api/tasks')
      .flush({ items: [WATER], total: 1, nextCursor: null });
    await harness.fixture.whenStable();
  });

  afterEach(() => http.verify());

  const titles = () =>
    Array.from(el.querySelectorAll('.task-row__title')).map((b) => b.textContent?.trim());
  const notice = () => el.querySelector('[data-testid=task-next-scheduled]');

  async function complete(result: TaskResult) {
    el.querySelector<HTMLInputElement>('input.task-row__check')!.click();
    await harness.fixture.whenStable();
    const req = http.expectOne({ method: 'PATCH', url: '/api/tasks/5' });
    expect(req.request.body).toEqual({ status: 'DONE' });
    req.flush(result);
    await harness.fixture.whenStable();
  }

  it('shows the repeat icon on a repeating row', () => {
    expect(el.querySelector('[data-testid=task-repeat]')?.getAttribute('aria-label')).toBe(
      'Repeats daily',
    );
  });

  it('inserts the nextTask after a completion and announces it', async () => {
    const next = task({ id: 6, title: 'Water plants', dueDate: '2999-01-02', repeat: RULE });
    await complete({
      ...WATER,
      status: 'DONE',
      done: true,
      repeat: null,
      nextTask: next,
    });
    expect(titles()).toEqual(['Water plants', 'Water plants']);
    expect(el.querySelectorAll('[data-testid=task-repeat]').length).toBe(1);
    expect(notice()?.textContent).toContain('Next one scheduled for 2999-01-02');
  });

  it('inserts nothing when nextTask is null', async () => {
    await complete({ ...WATER, status: 'DONE', done: true, repeat: null, nextTask: null });
    expect(titles()).toEqual(['Water plants']);
    expect(notice()).toBeNull();
  });
});
