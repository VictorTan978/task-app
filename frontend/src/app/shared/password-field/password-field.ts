import { Component, computed, contentChild, ElementRef, signal } from '@angular/core';
import { NgControl } from '@angular/forms';

/**
 * Wraps one password `<input>` (bound with a forms directive, e.g. `formControlName`) and adds a
 * show/hide button inside the field. Starts hidden; call `hide()` after a successful submit.
 *
 *   <app-password-field>
 *     <input id="x" type="password" formControlName="password" />
 *   </app-password-field>
 */
@Component({
  selector: 'app-password-field',
  templateUrl: './password-field.html',
  host: { class: 'password-field' },
})
export class PasswordField {
  private readonly input = contentChild.required(NgControl, { read: ElementRef });

  protected readonly visible = signal(false);
  protected readonly label = computed(() => (this.visible() ? 'Hide password' : 'Show password'));
  protected readonly inputId = computed(
    () => (this.input().nativeElement as HTMLInputElement).id || null,
  );

  protected toggle(): void {
    this.visible.update((v) => !v);
    this.apply();
  }

  /** Masks the value again. */
  hide(): void {
    this.visible.set(false);
    this.apply();
  }

  private apply(): void {
    const input = this.input().nativeElement as HTMLInputElement;
    // Keep shown passwords out of spellcheck / autocapitalize.
    input.setAttribute('spellcheck', 'false');
    input.setAttribute('autocapitalize', 'off');
    input.type = this.visible() ? 'text' : 'password';
  }
}
