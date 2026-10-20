import { computed, DestroyRef, inject, Injectable, signal } from '@angular/core';
import { AuthService } from '../auth/auth.service';

/** The browser's IANA time zone (e.g. `Europe/Bucharest`), or null if it can't be read. */
export function browserTimeZone(): string | null {
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone || null;
  } catch {
    return null;
  }
}

export interface ZonedNow {
  /** `YYYY-MM-DD` */
  date: string;
  /** `HH:mm`, 24-hour */
  time: string;
}

function format(instant: Date, timeZone: string | undefined): ZonedNow {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hourCycle: 'h23',
  }).formatToParts(instant);
  const get = (type: string) => parts.find((p) => p.type === type)?.value ?? '00';
  return {
    date: `${get('year')}-${get('month')}-${get('day')}`,
    time: `${get('hour')}:${get('minute')}`,
  };
}

/** Date and `HH:mm` of `instant` in `timeZone`; an unknown or null zone uses the browser's. */
export function zonedNow(instant: Date, timeZone: string | null): ZonedNow {
  if (timeZone) {
    try {
      return format(instant, timeZone);
    } catch {
      // Unknown zone name: fall through to the browser zone.
    }
  }
  return format(instant, undefined);
}

/** How often "now" is re-read, so the Overdue badge changes within a minute. */
export const NOW_TICK_MS = 30_000;

/**
 * "Today" and the current time in the signed-in user's account zone (the browser's zone while
 * `timeZone` is null). Ticks every 30 s; read `today()` / `time()` in templates or computeds.
 */
@Injectable({ providedIn: 'root' })
export class NowService {
  private readonly auth = inject(AuthService);
  private readonly instant = signal(new Date());

  private readonly now = computed(() =>
    zonedNow(this.instant(), this.auth.currentUser()?.timeZone ?? null),
  );
  readonly today = computed(() => this.now().date);
  readonly time = computed(() => this.now().time);

  constructor() {
    const id = setInterval(() => this.instant.set(new Date()), NOW_TICK_MS);
    inject(DestroyRef).onDestroy(() => clearInterval(id));
  }

  /** Re-reads the clock now (e.g. after a list loads). */
  refresh(): void {
    this.instant.set(new Date());
  }
}
