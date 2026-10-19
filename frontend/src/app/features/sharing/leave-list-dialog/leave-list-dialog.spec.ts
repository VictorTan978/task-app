import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TaskList } from '../../lists/lists.models';
import { LeaveListDialog } from './leave-list-dialog';

@Component({
  imports: [LeaveListDialog],
  template: `<app-leave-list-dialog
    [list]="list()"
    (left)="events.push('left:' + $event)"
    (closed)="events.push('closed')"
  />`,
})
class Host {
  readonly list = signal<TaskList>({
    id: 9,
    name: 'Team',
    personal: false,
    role: 'MEMBER',
    memberCount: 3,
  });
  readonly events: string[] = [];
}

function problem(status: number, code: string, detail = 'x') {
  return { type: 'about:blank', title: 'x', status, detail, code };
}

describe('LeaveListDialog', () => {
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
  const button = (name: string) =>
    Array.from(el.querySelectorAll('button')).find((b) => b.textContent?.trim() === name);

  it('asks to confirm and explains that my tasks stay', () => {
    expect(el.querySelector('dialog')?.hasAttribute('open')).toBe(true);
    expect(el.querySelector('h2')?.textContent?.trim()).toBe('Leave "Team"?');
    expect(el.textContent).toContain("You'll lose access to this list.");
    expect(el.textContent).toContain('The tasks you added stay in it.');
  });

  it('Cancel and Escape close without leaving', async () => {
    button('Cancel')!.click();
    el.querySelector('dialog')!.dispatchEvent(new Event('cancel', { cancelable: true }));
    expect(host.events).toEqual(['closed', 'closed']);
    http.expectNone('/api/lists/9/leave');
  });

  it('confirm sends POST /leave, disables the buttons and emits left', async () => {
    button('Leave list')!.click();
    await stable();
    expect(button('Leave list')!.disabled).toBe(true);
    expect(button('Cancel')!.disabled).toBe(true);
    const req = http.expectOne('/api/lists/9/leave');
    expect(req.request.method).toBe('POST');
    req.flush(null, { status: 204, statusText: 'No Content' });
    await stable();
    expect(host.events).toEqual(['left:9']);
  });

  it('already not a member (LIST_NOT_FOUND) counts as left', async () => {
    button('Leave list')!.click();
    http
      .expectOne('/api/lists/9/leave')
      .flush(problem(404, 'LIST_NOT_FOUND'), { status: 404, statusText: 'x' });
    await stable();
    expect(host.events).toEqual(['left:9']);
  });

  it('OWNER_CANNOT_LEAVE shows the message without Retry', async () => {
    button('Leave list')!.click();
    http
      .expectOne('/api/lists/9/leave')
      .flush(problem(409, 'OWNER_CANNOT_LEAVE', "The owner can't leave the list"), {
        status: 409,
        statusText: 'x',
      });
    await stable();
    expect(el.querySelector('[role=alert]')?.textContent).toContain(
      "The owner can't leave the list",
    );
    expect(button('Retry')).toBeUndefined();
    expect(host.events).toEqual([]);
  });

  it('a failure shows Retry, which leaves again', async () => {
    button('Leave list')!.click();
    http.expectOne('/api/lists/9/leave').flush(null, { status: 500, statusText: 'x' });
    await stable();
    expect(el.querySelector('[role=alert]')?.textContent).toContain("Couldn't leave the list.");
    button('Retry')!.click();
    http.expectOne('/api/lists/9/leave').flush(null, { status: 204, statusText: 'No Content' });
    await stable();
    expect(host.events).toEqual(['left:9']);
  });
});
