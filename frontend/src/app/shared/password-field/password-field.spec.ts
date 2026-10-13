import { Component, viewChild } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { PasswordField } from './password-field';

@Component({
  imports: [ReactiveFormsModule, PasswordField],
  template: `
    <form [formGroup]="form" (ngSubmit)="submits = submits + 1">
      <app-password-field #first>
        <input id="first" type="password" formControlName="first" />
      </app-password-field>
      <app-password-field>
        <input id="second" type="password" formControlName="second" />
      </app-password-field>
      <button type="submit">Go</button>
    </form>
  `,
})
class HostComponent {
  readonly form = new FormGroup({ first: new FormControl('abc'), second: new FormControl('') });
  readonly first = viewChild.required<PasswordField>('first');
  submits = 0;
}

describe('PasswordField', () => {
  let fixture: ComponentFixture<HostComponent>;
  let el: HTMLElement;

  beforeEach(async () => {
    fixture = TestBed.createComponent(HostComponent);
    el = fixture.nativeElement;
    await fixture.whenStable();
  });

  const input = (id: string) => el.querySelector<HTMLInputElement>(`#${id}`)!;
  const toggle = (id: string) =>
    el.querySelector<HTMLButtonElement>(`button[aria-controls=${id}]`)!;

  it('renders a non-submit toggle per field, starting hidden', () => {
    const buttons = el.querySelectorAll<HTMLButtonElement>('[data-testid=password-toggle]');
    expect(buttons.length).toBe(2);
    for (const id of ['first', 'second']) {
      expect(input(id).type).toBe('password');
      expect(toggle(id).type).toBe('button');
      expect(toggle(id).getAttribute('aria-label')).toBe('Show password');
      expect(toggle(id).getAttribute('aria-pressed')).toBe('false');
      expect(toggle(id).tabIndex).toBe(0);
      expect(toggle(id).querySelector('svg')?.getAttribute('aria-hidden')).toBe('true');
    }
  });

  it('shows and hides the value, switching the label and aria-pressed', async () => {
    toggle('first').click();
    await fixture.whenStable();
    expect(input('first').type).toBe('text');
    expect(input('first').value).toBe('abc');
    expect(toggle('first').getAttribute('aria-label')).toBe('Hide password');
    expect(toggle('first').getAttribute('aria-pressed')).toBe('true');

    toggle('first').click();
    await fixture.whenStable();
    expect(input('first').type).toBe('password');
    expect(toggle('first').getAttribute('aria-label')).toBe('Show password');
    expect(toggle('first').getAttribute('aria-pressed')).toBe('false');
  });

  it('toggles each field on its own and never submits the form', async () => {
    toggle('first').click();
    await fixture.whenStable();
    expect(input('first').type).toBe('text');
    expect(input('second').type).toBe('password');
    expect(toggle('second').getAttribute('aria-pressed')).toBe('false');
    expect(fixture.componentInstance.submits).toBe(0);
  });

  it('hide() masks the value again', async () => {
    toggle('first').click();
    await fixture.whenStable();
    fixture.componentInstance.first().hide();
    await fixture.whenStable();
    expect(input('first').type).toBe('password');
    expect(toggle('first').getAttribute('aria-label')).toBe('Show password');
    expect(toggle('first').getAttribute('aria-pressed')).toBe('false');
  });
});
