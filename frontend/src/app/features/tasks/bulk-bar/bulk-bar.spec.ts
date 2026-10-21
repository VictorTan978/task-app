import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { BulkRequest } from '../bulk.logic';
import { BulkBar } from './bulk-bar';

@Component({
  imports: [BulkBar],
  template: `<app-bulk-bar
    [count]="count()"
    [busy]="busy()"
    [atMax]="atMax()"
    [tags]="['Home', 'Work']"
    [lists]="lists"
    (run)="runs.push($event)"
    (selectAll)="events.push('all')"
    (cancelled)="events.push('cancel')"
  />`,
})
class Host {
  readonly count = signal(2);
  readonly busy = signal(false);
  readonly atMax = signal(false);
  readonly lists = [{ id: 8, name: 'Home list' }];
  readonly runs: BulkRequest[] = [];
  readonly events: string[] = [];
}

describe('BulkBar', () => {
  let fixture: ComponentFixture<Host>;
  let host: Host;
  let el: HTMLElement;

  beforeEach(async () => {
    fixture = TestBed.createComponent(Host);
    host = fixture.componentInstance;
    el = fixture.nativeElement;
    await fixture.whenStable();
  });

  const byId = (id: string) => el.querySelector<HTMLElement>(`[data-testid="${id}"]`)!;
  const click = async (id: string) => {
    byId(id).click();
    await fixture.whenStable();
  };

  it('announces the count with a polite live region', () => {
    expect(byId('bulk-count').textContent?.trim()).toBe('2 selected');
    expect(byId('bulk-count').getAttribute('role')).toBe('status');
    expect(byId('bulk-count').getAttribute('aria-live')).toBe('polite');
  });

  it('Select all loaded and Cancel emit', async () => {
    await click('bulk-select-all');
    await click('bulk-cancel');
    expect(host.events).toEqual(['all', 'cancel']);
  });

  it('Complete, Reopen and Delete emit their action', async () => {
    await click('bulk-complete');
    await click('bulk-reopen');
    await click('bulk-delete');
    expect(host.runs).toEqual([{ action: 'COMPLETE' }, { action: 'REOPEN' }, { action: 'DELETE' }]);
  });

  it('Set status and Set priority open a menu and emit the value', async () => {
    await click('bulk-status');
    await click('bulk-status-IN_PROGRESS');
    await click('bulk-priority');
    await click('bulk-priority-HIGH');
    expect(host.runs).toEqual([
      { action: 'SET_STATUS', value: 'IN_PROGRESS' },
      { action: 'SET_PRIORITY', value: 'HIGH' },
    ]);
    expect(el.querySelector('.bulk-bar__panel')).toBeNull();
  });

  it('Add tag sends the trimmed name; blank is not sent', async () => {
    await click('bulk-add-tag');
    const input = byId('bulk-tag-input') as HTMLInputElement;
    expect((byId('bulk-tag-apply') as HTMLButtonElement).disabled).toBe(true);
    input.value = '  Errands ';
    input.dispatchEvent(new Event('input'));
    await fixture.whenStable();
    await click('bulk-tag-apply');
    expect(host.runs).toEqual([{ action: 'ADD_TAG', value: 'Errands' }]);
  });

  it('Remove tag offers the tags of the selected tasks', async () => {
    await click('bulk-remove-tag');
    const choices = el.querySelectorAll('[data-testid="bulk-remove-tag-choice"]');
    expect(Array.from(choices).map((c) => c.textContent?.trim())).toEqual(['Home', 'Work']);
    (choices[1] as HTMLElement).click();
    expect(host.runs).toEqual([{ action: 'REMOVE_TAG', value: 'Work' }]);
  });

  it('Move to list offers the given lists and emits the id', async () => {
    await click('bulk-move');
    byId('bulk-move-choice').click();
    expect(host.runs).toEqual([{ action: 'MOVE', value: 8 }]);
  });

  it('is disabled while busy, and the actions are disabled with nothing selected', async () => {
    host.busy.set(true);
    await fixture.whenStable();
    expect((byId('bulk-complete') as HTMLButtonElement).disabled).toBe(true);
    expect((byId('bulk-cancel') as HTMLButtonElement).disabled).toBe(true);
    expect(byId('bulk-busy')).not.toBeNull();
    host.busy.set(false);
    host.count.set(0);
    await fixture.whenStable();
    expect((byId('bulk-complete') as HTMLButtonElement).disabled).toBe(true);
    expect((byId('bulk-select-all') as HTMLButtonElement).disabled).toBe(false);
  });

  it('says so at the 200 cap', async () => {
    expect(el.querySelector('[data-testid="bulk-limit"]')).toBeNull();
    host.atMax.set(true);
    await fixture.whenStable();
    expect(byId('bulk-limit').textContent).toContain('200');
  });

  it('More toggles the action list and updates aria-expanded', async () => {
    expect(byId('bulk-more').getAttribute('aria-expanded')).toBe('false');
    await click('bulk-more');
    expect(byId('bulk-more').getAttribute('aria-expanded')).toBe('true');
    expect(el.querySelector('.bulk-bar__actions--open')).not.toBeNull();
  });

  it('Esc closes an open menu and stops there; with none open it bubbles', async () => {
    const seen: boolean[] = [];
    const listener = () => seen.push(true);
    document.addEventListener('keydown', listener);
    const esc = () => new KeyboardEvent('keydown', { key: 'Escape', bubbles: true });
    try {
      await click('bulk-status');
      byId('bulk-status').dispatchEvent(esc());
      await fixture.whenStable();
      expect(el.querySelector('.bulk-bar__panel')).toBeNull();
      expect(seen).toEqual([]);
      byId('bulk-status').dispatchEvent(esc());
      expect(seen).toEqual([true]);
    } finally {
      document.removeEventListener('keydown', listener);
    }
  });

  it('a click outside closes the menu', async () => {
    await click('bulk-status');
    document.body.click();
    await fixture.whenStable();
    expect(el.querySelector('.bulk-bar__panel')).toBeNull();
  });
});
