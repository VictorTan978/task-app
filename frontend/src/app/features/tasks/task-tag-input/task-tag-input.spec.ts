import { Component, signal, viewChild } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { TaskTagInput } from './task-tag-input';

@Component({
  imports: [ReactiveFormsModule, TaskTagInput],
  template: `<label for="tags">Tags</label>
    <app-task-tag-input
      inputId="tags"
      [formControl]="control"
      [knownTags]="known()"
      [error]="error()"
    />`,
})
class Host {
  readonly control = new FormControl<string[]>([], { nonNullable: true });
  readonly known = signal<readonly string[]>(['Errands', 'Home', 'Homework', 'Work']);
  readonly error = signal<string | null>(null);
  readonly input = viewChild.required(TaskTagInput);
}

describe('TaskTagInput', () => {
  let fixture: ComponentFixture<Host>;
  let host: Host;
  let el: HTMLElement;

  beforeEach(async () => {
    fixture = TestBed.createComponent(Host);
    host = fixture.componentInstance;
    el = fixture.nativeElement;
    await fixture.whenStable();
  });

  const stable = () => fixture.whenStable();
  const input = () => el.querySelector<HTMLInputElement>('#tags')!;
  const chips = () =>
    Array.from(el.querySelectorAll('.tag-chip__name')).map((c) => c.textContent?.trim());
  const options = () => Array.from(el.querySelectorAll<HTMLElement>('[role=option]'));
  const optionTexts = () => options().map((o) => o.textContent?.trim());
  const errorText = () => el.querySelector('#tags-error')?.textContent?.trim() ?? null;
  const live = () => el.querySelector('[aria-live]')?.textContent?.trim();
  const removeButton = (name: string) =>
    el.querySelector<HTMLButtonElement>(`button[aria-label="Remove tag ${name}"]`)!;

  async function type(value: string) {
    input().value = value;
    input().dispatchEvent(new Event('input'));
    await stable();
  }

  async function key(k: string, init: KeyboardEventInit = {}) {
    const ev = new KeyboardEvent('keydown', { key: k, bubbles: true, cancelable: true, ...init });
    input().dispatchEvent(ev);
    await stable();
    return ev;
  }

  async function add(value: string, k = 'Enter') {
    await type(value);
    return key(k);
  }

  it('renders an ARIA 1.2 combobox wired to its listbox and hint', () => {
    const i = input();
    expect(i.getAttribute('role')).toBe('combobox');
    expect(i.getAttribute('aria-autocomplete')).toBe('list');
    expect(i.getAttribute('aria-expanded')).toBe('false');
    expect(i.getAttribute('aria-controls')).toBe('tags-listbox');
    expect(el.querySelector('#tags-listbox')?.getAttribute('role')).toBe('listbox');
    expect(i.getAttribute('aria-describedby')).toBe('tags-hint');
    expect(el.querySelector('#tags-hint')?.textContent).toContain('Enter or comma');
    expect(i.getAttribute('aria-invalid')).toBeNull();
  });

  it('Enter and comma add a trimmed, collapsed chip, update the control and announce', async () => {
    const enter = await add('  Home   office ');
    expect(enter.defaultPrevented).toBe(true);
    const comma = await add('Garden', ',');
    expect(comma.defaultPrevented).toBe(true);
    expect(chips()).toEqual(['Home office', 'Garden']);
    expect(host.control.value).toEqual(['Home office', 'Garden']);
    expect(input().value).toBe('');
    expect(live()).toBe('Added tag Garden');
  });

  it('reuses the known spelling for a case/accent variant', async () => {
    await add('wörk');
    await add('ERRANDS');
    expect(chips()).toEqual(['Work', 'Errands']);
  });

  it('ignores blanks and duplicates (ignoring case and accents)', async () => {
    await add('   ');
    expect(chips()).toEqual([]);
    await add('Work');
    await add('WÖRK');
    expect(chips()).toEqual(['Work']);
    expect(input().value).toBe('');
    expect(errorText()).toBeNull();
    expect(live()).toBe('Work is already added');
  });

  it('rejects an 11th tag with the server message and keeps the text', async () => {
    host.control.setValue(['1', '2', '3', '4', '5', '6', '7', '8', '9', '10']);
    await stable();
    await add('Eleven');
    expect(chips().length).toBe(10);
    expect(errorText()).toBe('A task can have at most 10 tags');
    expect(input().value).toBe('Eleven');
    expect(input().getAttribute('aria-invalid')).toBe('true');
    expect(input().getAttribute('aria-describedby')).toBe('tags-hint tags-error');
    // Adding a duplicate at the limit is still fine.
    await add('10');
    expect(errorText()).toBeNull();
  });

  it('rejects names over 30 characters and control characters', async () => {
    await add('x'.repeat(31));
    expect(errorText()).toBe('Tags must be 30 characters or fewer');
    expect(chips()).toEqual([]);
    await type('ok');
    expect(errorText()).toBeNull();
    await add('bad\u0007');
    expect(errorText()).toBe("Tags can't contain commas or control characters");
    await add('  ' + 'y'.repeat(30) + '  ');
    expect(chips()).toEqual(['y'.repeat(30)]);
  });

  it('splits pasted text on commas, keeping the last part typed', async () => {
    await type('a, b ,c');
    expect(chips()).toEqual(['a', 'b']);
    expect(input().value).toBe('c');
  });

  it('× and Backspace in an empty input remove chips', async () => {
    host.control.setValue(['Work', 'Home', 'Errands']);
    await stable();
    removeButton('Home').click();
    await stable();
    expect(chips()).toEqual(['Work', 'Errands']);
    expect(live()).toBe('Removed tag Home');

    await type('x');
    const notEmpty = await key('Backspace');
    expect(notEmpty.defaultPrevented).toBe(false);
    expect(chips()).toEqual(['Work', 'Errands']);

    await type('');
    await key('Backspace');
    expect(chips()).toEqual(['Work']);
    expect(host.control.value).toEqual(['Work']);
  });

  it('suggests matching known tags not yet chosen; arrows + Enter pick one', async () => {
    host.control.setValue(['Home']);
    await stable();
    await type('wo');
    expect(input().getAttribute('aria-expanded')).toBe('true');
    expect(optionTexts()).toEqual(['Work', 'Homework']);
    expect(input().getAttribute('aria-activedescendant')).toBeNull();

    await key('ArrowDown');
    await key('ArrowDown');
    expect(input().getAttribute('aria-activedescendant')).toBe('tags-option-1');
    expect(options()[1].getAttribute('aria-selected')).toBe('true');
    await key('ArrowDown');
    expect(input().getAttribute('aria-activedescendant')).toBe('tags-option-0');
    await key('ArrowUp');
    expect(input().getAttribute('aria-activedescendant')).toBe('tags-option-1');

    await key('Enter');
    expect(chips()).toEqual(['Home', 'Homework']);
    expect(input().value).toBe('');
    expect(input().getAttribute('aria-expanded')).toBe('false');
  });

  it('Enter without an active option adds the typed text', async () => {
    await type('wo');
    expect(optionTexts()).toContain('Work');
    await key('Enter');
    expect(chips()).toEqual(['wo']);
  });

  it('a mouse pick adds the suggestion and keeps focus in the input', async () => {
    await type('err');
    const down = new MouseEvent('mousedown', { bubbles: true, cancelable: true });
    options()[0].dispatchEvent(down);
    await stable();
    expect(down.defaultPrevented).toBe(true);
    expect(chips()).toEqual(['Errands']);
  });

  it('ArrowDown on an empty input opens all suggestions', async () => {
    await key('ArrowDown');
    expect(optionTexts()).toEqual(['Errands', 'Home', 'Homework', 'Work']);
    expect(input().getAttribute('aria-activedescendant')).toBe('tags-option-0');
  });

  it('Escape closes the suggestions first (and stops there), then lets the dialog close', async () => {
    await type('wo');
    const first = await key('Escape');
    expect(first.defaultPrevented).toBe(true);
    expect(input().getAttribute('aria-expanded')).toBe('false');
    expect(options()).toEqual([]);
    const second = await key('Escape');
    expect(second.defaultPrevented).toBe(false);
  });

  it('ignores keys while an IME composition is running', async () => {
    await type('wo');
    const ev = await key('Enter', { isComposing: true });
    expect(ev.defaultPrevented).toBe(false);
    expect(chips()).toEqual([]);
  });

  it('blur closes the list and marks the control touched', async () => {
    await type('wo');
    input().dispatchEvent(new Event('blur'));
    await stable();
    expect(input().getAttribute('aria-expanded')).toBe('false');
    expect(host.control.touched).toBe(true);
  });

  it('shows an outside (server) error and links it', async () => {
    host.error.set('You can have at most 200 tags');
    await stable();
    expect(errorText()).toBe('You can have at most 200 tags');
    expect(input().getAttribute('aria-invalid')).toBe('true');
  });

  it('disables the input and remove buttons when the control is disabled', async () => {
    host.control.setValue(['Work']);
    host.control.disable();
    await stable();
    expect(input().disabled).toBe(true);
    expect(removeButton('Work').disabled).toBe(true);
    host.control.enable();
    await stable();
    expect(input().disabled).toBe(false);
  });

  it('commitPending adds typed text, or returns false with the error shown', async () => {
    await type('Later');
    expect(host.input().commitPending()).toBe(true);
    expect(host.control.value).toEqual(['Later']);

    await type('x'.repeat(31));
    expect(host.input().commitPending()).toBe(false);
    await stable();
    expect(errorText()).toBe('Tags must be 30 characters or fewer');

    await type('   ');
    expect(host.input().commitPending()).toBe(true);
    expect(host.control.value).toEqual(['Later']);
  });
});
