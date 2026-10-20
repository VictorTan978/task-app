package com.taskapp.notification;

import java.util.List;

/** One page of {@code GET /api/notifications}: newest first; {@code nextCursor} is null on the last page. */
public record NotificationPage(List<NotificationResponse> items, String nextCursor) {

    public NotificationPage {
        items = List.copyOf(items);
    }
}
