package com.taskapp.task.dto;

import java.util.List;

/** Schema {@code TrashPage} (0.16.0): one page of {@code GET /api/trash}. */
public record TrashPage(List<TrashItem> items, long total, String nextCursor) {

    public TrashPage {
        items = List.copyOf(items);
    }
}
