import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { BulkDeleteDialog } from './bulk-delete-dialog';

@Component({
  imports: [BulkDeleteDialog],
  template: `<app-bulk-delete-dialog
    [count]="count()"
    [pending]="pending()"
    (confirmed)="events.push('yes')"
    (closed)="events.push('closed')"
  />`,
})
class Host {
  readonly count = signal(12);
  readonly pending = signal(false);
  readonly events: string[] = [];
}

describe('BulkDeleteDialog', () => {
  let fixture: ComponentFixture<Host>;
  let host: Host;
  let el: HTMLElement;

  beforeAll(() => {
    HTMLDialogElement.prototype.showModal = function (this: HTMLDialogElement) {
      this.setAttribute('open', '');
    };
  });

  beforeEach(async () => {
    fixture = TestBed.createComponent(Host);
    host = fixture.componentInstance;
    el = fixture.nativeElement;
    await fixture.whenStable();
  });

  const buttons = () => Array.from(el.querySelectorAll('button'));
  const button = (label: string) => buttons().find((b) => b.textContent?.trim() === label)!;

  it('names the count and opens modally', () => {
    expect(el.querySelector('h2')?.textContent).toBe('Delete 12 tasks?');
    expect(el.querySelector('dialog')?.hasAttribute('open')).toBe(true);
  });

  it('uses the singular for one task', async () => {
    host.count.set(1);
    await fixture.whenStable();
    expect(el.querySelector('h2')?.textContent).toBe('Delete 1 task?');
  });

  it('Delete confirms, Cancel closes', () => {
    button('Delete').click();
    button('Cancel').click();
    expect(host.events).toEqual(['yes', 'closed']);
  });

  it('while pending nothing can be pressed again', async () => {
    host.pending.set(true);
    await fixture.whenStable();
    expect(button('Delete').disabled).toBe(true);
    expect(button('Cancel').disabled).toBe(true);
    el.querySelector('dialog')!.dispatchEvent(new Event('cancel', { cancelable: true }));
    expect(host.events).toEqual([]);
  });
});
