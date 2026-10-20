import { Component, computed, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NonNullableFormBuilder, ReactiveFormsModule } from '@angular/forms';
import { finalize } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { browserTimeZone } from '../../../core/time/now.service';
import { fieldMessage, requiredTrimmed } from '../../auth/auth-form';
import { accountErrorBanner } from '../account-form';
import { AccountService } from '../account.service';

export const TIME_ZONE_UPDATED_MESSAGE = 'Time zone updated';

/** Every IANA zone the browser knows, plus `extra` zones (stored / browser) if missing. */
export function timeZoneOptions(extra: readonly (string | null)[]): string[] {
  const supported = (Intl as unknown as { supportedValuesOf?: (key: string) => string[] })
    .supportedValuesOf;
  let zones: string[];
  try {
    zones = supported?.('timeZone') ?? [];
  } catch {
    zones = [];
  }
  const all = new Set(zones);
  for (const zone of extra) {
    if (zone) {
      all.add(zone);
    }
  }
  return [...all].sort();
}

@Component({
  selector: 'app-time-zone-form',
  imports: [ReactiveFormsModule],
  templateUrl: './time-zone-form.html',
  styleUrl: '../account-form.scss',
})
export class TimeZoneForm {
  private readonly account = inject(AccountService);
  private readonly auth = inject(AuthService);
  private readonly destroyRef = inject(DestroyRef);

  private readonly stored = computed(() => this.auth.currentUser()?.timeZone ?? null);
  protected readonly browserZone = browserTimeZone();
  protected readonly options = computed(() => timeZoneOptions([this.stored(), this.browserZone]));

  protected readonly form = inject(NonNullableFormBuilder).group({
    timeZone: [
      inject(AuthService).currentUser()?.timeZone ?? this.browserZone ?? '',
      [requiredTrimmed],
    ],
  });

  protected readonly pending = signal(false);
  /** Page-level busy lock: no request while this session's id is being renewed. */
  protected readonly locked = this.account.sessionRenewing;
  protected readonly submitted = signal(false);
  protected readonly banner = signal<string | null>(null);
  protected readonly status = signal<string | null>(null);

  /** The browser's zone, when it differs from what is stored (offered as a one-click change). */
  protected readonly hint = computed(() =>
    this.browserZone && this.stored() !== null && this.stored() !== this.browserZone
      ? this.browserZone
      : null,
  );

  protected error(): string | null {
    const control = this.form.controls.timeZone;
    if (!this.submitted() && !control.touched) {
      return null;
    }
    return fieldMessage('timeZone', control);
  }

  protected useBrowserZone(): void {
    const zone = this.hint();
    if (zone) {
      this.form.controls.timeZone.setValue(zone);
      this.submit();
    }
  }

  protected submit(): void {
    if (this.pending() || this.locked()) {
      return;
    }
    this.submitted.set(true);
    this.banner.set(null);
    this.status.set(null);
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const timeZone = this.form.controls.timeZone.value.trim();
    this.pending.set(true);
    this.account
      .setTimeZone({ timeZone })
      .pipe(
        finalize(() => this.pending.set(false)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: (user) => {
          this.form.reset({ timeZone: user.timeZone ?? timeZone });
          this.submitted.set(false);
          this.status.set(TIME_ZONE_UPDATED_MESSAGE);
        },
        error: (error: unknown) => this.banner.set(accountErrorBanner(error, this.form.controls)),
      });
  }
}
