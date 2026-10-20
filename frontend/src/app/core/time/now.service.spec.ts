import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { AuthService } from '../auth/auth.service';
import { NOW_TICK_MS, NowService, zonedNow } from './now.service';

describe('zonedNow', () => {
  const instant = new Date('2026-10-20T22:30:00Z');

  it('reads the date and time in the zone (the day can differ from UTC)', () => {
    expect(zonedNow(instant, 'Europe/Bucharest')).toEqual({ date: '2026-10-21', time: '01:30' });
    expect(zonedNow(instant, 'America/New_York')).toEqual({ date: '2026-10-20', time: '18:30' });
  });

  it('uses 24-hour time with midnight as 00:xx', () => {
    expect(zonedNow(new Date('2026-10-20T21:05:00Z'), 'Europe/Bucharest').time).toBe('00:05');
  });

  it('falls back to the browser zone for null or unknown zones', () => {
    const browser = zonedNow(instant, null);
    expect(zonedNow(instant, 'Nowhere/Land')).toEqual(browser);
    expect(browser.date).toMatch(/^\d{4}-\d{2}-\d{2}$/);
    expect(browser.time).toMatch(/^\d{2}:\d{2}$/);
  });
});

describe('NowService', () => {
  const user = signal<{ timeZone: string | null } | null>({ timeZone: 'Europe/Bucharest' });

  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-10-20T22:30:00Z'));
    user.set({ timeZone: 'Europe/Bucharest' });
    TestBed.configureTestingModule({
      providers: [{ provide: AuthService, useValue: { currentUser: user } }],
    });
  });

  afterEach(() => vi.useRealTimers());

  it('gives today and the time in the account zone and follows a zone change', () => {
    const now = TestBed.inject(NowService);
    expect(now.today()).toBe('2026-10-21');
    expect(now.time()).toBe('01:30');
    user.set({ timeZone: 'America/New_York' });
    expect(now.today()).toBe('2026-10-20');
    expect(now.time()).toBe('18:30');
  });

  it('ticks every 30 seconds', () => {
    const now = TestBed.inject(NowService);
    expect(now.time()).toBe('01:30');
    vi.advanceTimersByTime(NOW_TICK_MS * 2);
    expect(now.time()).toBe('01:31');
  });
});
