import { HttpClient, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { map, Observable } from 'rxjs';
import { NOTIFICATIONS_API } from './notifications-api';
import { AppNotification, NotificationCount, NotificationPage } from './notifications.models';

/** Page size asked for (the contract allows up to 50, default 20). */
export const NOTIFICATIONS_PAGE_SIZE = 20;

/** Typed HTTP access to `/api/notifications` (0.14.0). Stateless. */
@Injectable({ providedIn: 'root' })
export class NotificationsService {
  private readonly http = inject(HttpClient);

  /** One page, newest first; pass the previous page's `nextCursor` for the next one. */
  list(cursor: string | null): Observable<NotificationPage> {
    let params = new HttpParams().set('limit', NOTIFICATIONS_PAGE_SIZE);
    if (cursor !== null) {
      params = params.set('cursor', cursor);
    }
    return this.http.get<NotificationPage>(NOTIFICATIONS_API.list, { params });
  }

  /** How many unread notifications I have (header bell). */
  unreadCount(): Observable<number> {
    return this.http
      .get<NotificationCount>(NOTIFICATIONS_API.count)
      .pipe(map((body) => body.count));
  }

  /** Marks one read (idempotent). 404 `NOTIFICATION_NOT_FOUND` for someone else's or unknown. */
  markRead(id: number): Observable<AppNotification> {
    return this.http.post<AppNotification>(NOTIFICATIONS_API.read(id), null);
  }

  /** Marks all my notifications read (204). */
  markAllRead(): Observable<void> {
    return this.http.post<unknown>(NOTIFICATIONS_API.readAll, null).pipe(map(() => undefined));
  }
}
