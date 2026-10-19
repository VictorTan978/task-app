import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TaskList } from '../lists.models';
import { DeleteListDialog } from './delete-list-dialog';

@Component({
  imports: [DeleteListDialog],
  template: `<app-delete-list-dialog
    [list]="list()"
    (deleted)="events.push('deleted:' + $event)"
    (closed)="events.push('closed')"
    (notFound)="events.push('notFound')"
  />`,
})
class Host {
  readonly list = signal<TaskList>({ id: 8, name: 'Home', personal: false });
  readonly events: string[] = [];
}

function problem(status: number, code: string) {
  return { type: 'about:blank', title: 'x', status, detail: 'x', code };
}

describe('DeleteListDialog', () => {
  let fixture: ComponentFixture<Host>;
  let host: Host;
  let el: HTMLElement;
  let http: HttpTestingController;

  beforeAll(() => {
    HTMLDialogElement.prototype.showModal = function (this: HTMLDialogElement) {
      this.setAttribute('open', '');
    };
  });

  beforeEach(async () => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(Host);
    host = fixture.componentInstance;
    el = fixture.nativeElement;
    await fixture.whenStable();
  });

  afterEach(() => http.verify());

  const stable = () => fixture.whenStable();
  const heading = () => el.querySelector('h2')?.textContent?.trim();
  const button = (name: string) =>
    Array.from(el.querySelectorAll('button')).find((b) => b.textContent?.trim() === name)!;

  async function flushCount(taskCount: number) {
    http
      .expectOne({ method: 'GET', url: '/api/lists/8' })
      .flush({ id: 8, name: 'Home', personal: false, taskCount });
    await stable();
  }

  it('reads the task count on open; Delete waits for it', async () => {
    expect(el.textContent).toContain('Counting tasks…');
    expect(button('Delete').disabled).toBe(true);
    await flushCount(12);
    expect(heading()).toBe('Delete "Home" and its 12 tasks?');
    expect(button('Delete').disabled).toBe(false);
  });

  it('a list without tasks just asks to delete it', async () => {
    await flushCount(0);
    expect(heading()).toBe('Delete "Home"?');
  });

  it('Cancel closes without deleting', async () => {
    await flushCount(3);
    button('Cancel').click();
    expect(host.events).toEqual(['closed']);
    http.expectNone({ method: 'DELETE' });
  });

  it('confirm sends DELETE and emits deleted', async () => {
    await flushCount(3);
    button('Delete').click();
    await stable();
    expect(button('Delete').getAttribute('aria-busy')).toBe('true');
    http
      .expectOne({ method: 'DELETE', url: '/api/lists/8' })
      .flush(null, { status: 204, statusText: 'No Content' });
    await stable();
    expect(host.events).toEqual(['deleted:8']);
  });

  it('a failed delete shows Retry, which deletes again', async () => {
    await flushCount(3);
    button('Delete').click();
    http
      .expectOne({ method: 'DELETE', url: '/api/lists/8' })
      .flush(null, { status: 500, statusText: 'Error' });
    await stable();
    expect(el.querySelector('[role=alert]')?.textContent).toContain("Couldn't delete the list.");
    button('Retry').click();
    http
      .expectOne({ method: 'DELETE', url: '/api/lists/8' })
      .flush(null, { status: 204, statusText: 'No Content' });
    await stable();
    expect(host.events).toEqual(['deleted:8']);
  });

  it('a list already deleted elsewhere (404 on DELETE) counts as deleted', async () => {
    await flushCount(3);
    button('Delete').click();
    http
      .expectOne({ method: 'DELETE', url: '/api/lists/8' })
      .flush(problem(404, 'LIST_NOT_FOUND'), { status: 404, statusText: 'Not Found' });
    await stable();
    expect(host.events).toEqual(['deleted:8']);
  });

  it('LIST_NOT_FOUND when counting emits notFound', async () => {
    http
      .expectOne({ method: 'GET', url: '/api/lists/8' })
      .flush(problem(404, 'LIST_NOT_FOUND'), { status: 404, statusText: 'Not Found' });
    await stable();
    expect(host.events).toEqual(['notFound']);
  });

  it('a failed count shows Retry, which reads it again', async () => {
    http
      .expectOne({ method: 'GET', url: '/api/lists/8' })
      .flush(null, { status: 500, statusText: 'Error' });
    await stable();
    expect(el.querySelector('[role=alert]')?.textContent).toContain("Couldn't load the list.");
    expect(button('Delete').disabled).toBe(true);
    button('Retry').click();
    await flushCount(2);
    expect(heading()).toBe('Delete "Home" and its 2 tasks?');
    expect(el.querySelector('[role=alert]')).toBeNull();
  });
});
