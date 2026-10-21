import { agoText, daysLeftText, deletedText, emptyTrashQuestion, tasksText } from './trash.logic';
import { TrashItem } from './trash.models';

const NOW = Date.parse('2026-10-21T12:00:00Z');
const ago = (ms: number) => new Date(NOW - ms).toISOString();
const MIN = 60_000;
const HOUR = 60 * MIN;
const DAY = 24 * HOUR;

describe('agoText', () => {
  it('uses just now, minutes, hours and days', () => {
    expect(agoText(ago(10_000), NOW)).toBe('just now');
    expect(agoText(ago(MIN), NOW)).toBe('1 minute ago');
    expect(agoText(ago(5 * MIN), NOW)).toBe('5 minutes ago');
    expect(agoText(ago(HOUR), NOW)).toBe('1 hour ago');
    expect(agoText(ago(3 * HOUR), NOW)).toBe('3 hours ago');
    expect(agoText(ago(DAY), NOW)).toBe('1 day ago');
    expect(agoText(ago(2 * DAY + HOUR), NOW)).toBe('2 days ago');
  });

  it('never goes negative (clock skew) and keeps unparseable text', () => {
    expect(agoText(ago(-5 * MIN), NOW)).toBe('just now');
    expect(agoText('nonsense', NOW)).toBe('nonsense');
  });

  it('reads the 6-digit fractions the API sends', () => {
    expect(agoText('2026-10-19T11:00:00.123456Z', NOW)).toBe('2 days ago');
  });
});

describe('deletedText', () => {
  const item = (deletedBy: TrashItem['deletedBy']) =>
    ({ deletedAt: ago(2 * DAY), deletedBy }) as TrashItem;

  it('names who deleted it and when', () => {
    expect(deletedText(item({ displayName: 'Ana' }), NOW)).toBe('Deleted by Ana · 2 days ago');
  });

  it('says "a deleted user" when the account is gone', () => {
    expect(deletedText(item(null), NOW)).toBe('Deleted by a deleted user · 2 days ago');
  });
});

describe('daysLeftText', () => {
  it('counts the days left, rounding up', () => {
    expect(daysLeftText(new Date(NOW + 28 * DAY).toISOString(), NOW)).toBe('28 days left');
    expect(daysLeftText(new Date(NOW + 27 * DAY + HOUR).toISOString(), NOW)).toBe('28 days left');
    expect(daysLeftText(new Date(NOW + HOUR).toISOString(), NOW)).toBe('1 day left');
  });

  it('says "Deleted soon" when the time is up', () => {
    expect(daysLeftText(new Date(NOW - HOUR).toISOString(), NOW)).toBe('Deleted soon');
  });
});

describe('tasksText and emptyTrashQuestion', () => {
  it('pluralises', () => {
    expect(tasksText(1)).toBe('1 task');
    expect(tasksText(7)).toBe('7 tasks');
  });

  it('names the count, and the list when filtered', () => {
    expect(emptyTrashQuestion(7, null)).toContain('all 7 tasks in the trash');
    expect(emptyTrashQuestion(1, null)).toContain('the 1 task in the trash');
    expect(emptyTrashQuestion(3, 'Work')).toContain('all 3 tasks in the trash of "Work"');
  });
});
