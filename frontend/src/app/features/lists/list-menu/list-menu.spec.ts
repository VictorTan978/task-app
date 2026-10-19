import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TaskList } from '../lists.models';
import { ListMenu } from './list-menu';

@Component({
  imports: [ListMenu],
  template: `<app-list-menu
      [list]="list()"
      (rename)="events.push('rename')"
      (remove)="events.push('remove')"
    />
    <p id="outside">outside</p>`,
})
class Host {
  readonly list = signal<TaskList>({ id: 8, name: 'Home', personal: false });
  readonly events: string[] = [];
}

describe('ListMenu', () => {
  let fixture: ComponentFixture<Host>;
  let el: HTMLElement;

  beforeEach(async () => {
    fixture = TestBed.createComponent(Host);
    el = fixture.nativeElement;
    await fixture.whenStable();
  });

  const stable = () => fixture.whenStable();
  const toggle = () => el.querySelector<HTMLButtonElement>('.list-menu__toggle')!;
  const items = () =>
    Array.from(el.querySelectorAll<HTMLButtonElement>('.list-menu__item')).map((b) =>
      b.textContent?.trim(),
    );
  const item = (name: string) =>
    Array.from(el.querySelectorAll<HTMLButtonElement>('.list-menu__item')).find(
      (b) => b.textContent?.trim() === name,
    )!;

  it('is closed until the "⋯" button is clicked', async () => {
    expect(toggle().getAttribute('aria-label')).toBe('List actions');
    expect(toggle().getAttribute('aria-expanded')).toBe('false');
    expect(items()).toEqual([]);
    toggle().click();
    await stable();
    expect(toggle().getAttribute('aria-expanded')).toBe('true');
    expect(items()).toEqual(['Rename', 'Delete']);
  });

  it('"My tasks" has Rename but no Delete', async () => {
    fixture.componentInstance.list.set({ id: 3, name: 'My tasks', personal: true });
    toggle().click();
    await stable();
    expect(items()).toEqual(['Rename']);
  });

  it('emits rename / remove and closes', async () => {
    toggle().click();
    await stable();
    item('Rename').click();
    await stable();
    expect(items()).toEqual([]);
    toggle().click();
    await stable();
    item('Delete').click();
    await stable();
    expect(fixture.componentInstance.events).toEqual(['rename', 'remove']);
  });

  it('Escape and a click outside close it', async () => {
    toggle().click();
    await stable();
    toggle().dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    await stable();
    expect(items()).toEqual([]);
    toggle().click();
    await stable();
    el.querySelector<HTMLElement>('#outside')!.click();
    await stable();
    expect(items()).toEqual([]);
    expect(fixture.componentInstance.events).toEqual([]);
  });
});
