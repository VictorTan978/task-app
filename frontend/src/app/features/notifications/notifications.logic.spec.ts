import { isAvailable, notificationLink, notificationText } from './notifications.logic';
import { AppNotification } from './notifications.models';

const N: AppNotification = {
  id: 1,
  type: 'REMINDER',
  read: false,
  createdAt: '2026-10-21T06:30:00Z',
  late: false,
  title: 'Pay rent',
  dueDate: '2026-10-21',
  dueTime: '09:30',
  taskId: 4,
  listId: 9,
};

describe('notifications.logic', () => {
  it('builds the text with date and time, or date only, or none', () => {
    expect(notificationText(N)).toBe('Reminder: Pay rent — due 2026-10-21 09:30');
    expect(notificationText({ ...N, dueTime: null })).toBe('Reminder: Pay rent — due 2026-10-21');
  });

  it('is available only with both task and list', () => {
    expect(isAvailable(N)).toBe(true);
    expect(isAvailable({ ...N, taskId: null, listId: null })).toBe(false);
  });

  it('links my personal list to /tasks and others to /lists/<id>', () => {
    expect(notificationLink(9, 9)).toBe('/tasks');
    expect(notificationLink(9, 2)).toBe('/lists/9');
    expect(notificationLink(9, null)).toBe('/lists/9');
  });
});
