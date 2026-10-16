import {
  Component,
  computed,
  ElementRef,
  forwardRef,
  input,
  signal,
  viewChild,
} from '@angular/core';
import { ControlValueAccessor, NG_VALUE_ACCESSOR } from '@angular/forms';
import {
  checkTagName,
  findTag,
  suggestTags,
  TAG_TOO_MANY_MESSAGE,
  TAGS_PER_TASK_MAX,
} from '../task-tags';

/**
 * Chip input for a task's tags (form value: `string[]`). Enter or comma adds the typed tag,
 * the chip's "×" or Backspace in an empty input removes one. Suggestions follow the ARIA 1.2
 * combobox pattern (list autocomplete): arrow keys move, Enter picks, Escape closes the list
 * first. Typing a variant of a known tag (case/accents) reuses the known spelling.
 */
@Component({
  selector: 'app-task-tag-input',
  templateUrl: './task-tag-input.html',
  styleUrl: './task-tag-input.scss',
  providers: [
    { provide: NG_VALUE_ACCESSOR, useExisting: forwardRef(() => TaskTagInput), multi: true },
  ],
})
export class TaskTagInput implements ControlValueAccessor {
  /** Id of the text input, for the outside `<label for>`. */
  readonly inputId = input.required<string>();
  /** The user's existing tags (server spelling and order), for suggestions and spelling reuse. */
  readonly knownTags = input<readonly string[]>([]);
  /** Error from outside (e.g. the server), shown when there is no local error. */
  readonly error = input<string | null>(null);

  protected readonly tags = signal<readonly string[]>([]);
  protected readonly text = signal('');
  protected readonly disabled = signal(false);
  private readonly listOpen = signal(false);
  protected readonly active = signal(-1);
  private readonly localError = signal<string | null>(null);
  protected readonly announcement = signal('');

  protected readonly suggestions = computed(() =>
    suggestTags(this.knownTags(), this.tags(), this.text()),
  );
  protected readonly open = computed(
    () => this.listOpen() && !this.disabled() && this.suggestions().length > 0,
  );
  protected readonly shownError = computed(() => this.localError() ?? this.error());

  protected readonly listId = computed(() => `${this.inputId()}-listbox`);
  protected readonly hintId = computed(() => `${this.inputId()}-hint`);
  protected readonly errorId = computed(() => `${this.inputId()}-error`);
  protected readonly describedBy = computed(() =>
    this.shownError() ? `${this.hintId()} ${this.errorId()}` : this.hintId(),
  );
  protected readonly activeId = computed(() =>
    this.open() && this.active() >= 0 ? this.optionId(this.active()) : null,
  );

  private readonly inputEl = viewChild.required<ElementRef<HTMLInputElement>>('input');

  private onChange: (value: string[]) => void = () => undefined;
  private onTouched: () => void = () => undefined;

  writeValue(value: readonly string[] | null): void {
    this.tags.set([...(value ?? [])]);
    this.localError.set(null);
  }

  registerOnChange(fn: (value: string[]) => void): void {
    this.onChange = fn;
  }

  registerOnTouched(fn: () => void): void {
    this.onTouched = fn;
  }

  setDisabledState(disabled: boolean): void {
    this.disabled.set(disabled);
  }

  /**
   * Adds whatever is still typed (e.g. before Save). Returns false, showing the error, if it was
   * rejected; true if it was added, already present, or blank.
   */
  commitPending(): boolean {
    const ok = this.commit(this.text());
    if (ok) {
      this.text.set('');
    }
    return ok;
  }

  protected optionId(index: number): string {
    return `${this.inputId()}-option-${index}`;
  }

  protected onInput(event: Event): void {
    const el = event.target as HTMLInputElement;
    let value = el.value;
    this.localError.set(null);
    if (value.includes(',')) {
      // Pasted or typed commas: every complete part becomes a tag; the rest stays typed.
      // If one is rejected, it and everything after it stay in the input with the error shown.
      const parts = value.split(',');
      const last = parts.pop() ?? '';
      const failed = parts.findIndex((part) => !this.commit(part));
      value = failed < 0 ? last : [...parts.slice(failed), last].join(',');
      el.value = value;
    }
    this.text.set(value);
    this.listOpen.set(value.trim() !== '');
    this.active.set(-1);
  }

  protected onKeydown(event: KeyboardEvent): void {
    if (event.isComposing) {
      return;
    }
    const count = this.open() ? this.suggestions().length : 0;
    switch (event.key) {
      case 'Enter':
        event.preventDefault();
        if (count && this.active() >= 0) {
          this.pick(this.suggestions()[this.active()]);
        } else {
          this.addTyped();
        }
        break;
      case ',':
        event.preventDefault();
        this.addTyped();
        break;
      case 'ArrowDown':
        event.preventDefault();
        if (!count) {
          this.listOpen.set(true);
          this.active.set(this.suggestions().length ? 0 : -1);
        } else {
          this.active.set((this.active() + 1) % count);
        }
        break;
      case 'ArrowUp':
        event.preventDefault();
        if (count) {
          this.active.set(this.active() <= 0 ? count - 1 : this.active() - 1);
        }
        break;
      case 'Escape':
        if (count) {
          // Close the suggestions only; the dialog stays open.
          event.preventDefault();
          event.stopPropagation();
          this.closeList();
        }
        break;
      case 'Backspace': {
        const tags = this.tags();
        if (this.text() === '' && tags.length) {
          event.preventDefault();
          this.remove(tags[tags.length - 1]);
        }
        break;
      }
      case 'Tab':
        this.closeList();
        break;
    }
  }

  protected onBlur(): void {
    this.closeList();
    this.onTouched();
  }

  /** Mouse/touch on a suggestion; `preventDefault` keeps focus in the input. */
  protected onOptionMouseDown(event: MouseEvent, name: string): void {
    event.preventDefault();
    this.pick(name);
  }

  protected remove(name: string): void {
    if (this.disabled()) {
      return;
    }
    this.setTags(this.tags().filter((t) => t !== name));
    this.localError.set(null);
    this.announcement.set(`Removed tag ${name}`);
    this.inputEl().nativeElement.focus();
  }

  private pick(name: string): void {
    if (this.commit(name)) {
      this.clearText();
    }
    this.inputEl().nativeElement.focus();
  }

  private addTyped(): void {
    if (this.commit(this.text())) {
      this.clearText();
    }
  }

  /** Adds one tag. False (with an error shown) if rejected. */
  private commit(raw: string): boolean {
    const check = checkTagName(raw);
    if (check.kind === 'blank') {
      return true;
    }
    if (check.kind === 'invalid') {
      this.localError.set(check.message);
      return false;
    }
    const existing = findTag(this.tags(), check.name);
    if (existing !== null) {
      this.announcement.set(`${existing} is already added`);
      return true;
    }
    if (this.tags().length >= TAGS_PER_TASK_MAX) {
      this.localError.set(TAG_TOO_MANY_MESSAGE);
      return false;
    }
    const name = findTag(this.knownTags(), check.name) ?? check.name;
    this.setTags([...this.tags(), name]);
    this.localError.set(null);
    this.announcement.set(`Added tag ${name}`);
    return true;
  }

  private setTags(tags: string[]): void {
    this.tags.set(tags);
    this.onChange(tags);
  }

  private clearText(): void {
    this.text.set('');
    this.inputEl().nativeElement.value = '';
    this.closeList();
  }

  private closeList(): void {
    this.listOpen.set(false);
    this.active.set(-1);
  }
}
