package com.taskapp.task.dto;

import java.util.List;

/**
 * One page of {@code GET /api/tasks} (contract 0.11.0 {@code TaskPage}).
 *
 * @param items      at most 50 tasks in list order
 * @param total      matching tasks on all pages, counted for this page
 * @param nextCursor send back as {@code ?cursor=} for the next page; null on the last page (always serialised)
 */
public record TaskPage(List<TaskResponse> items, long total, String nextCursor) {

    public TaskPage {
        items = List.copyOf(items);
    }

    public static TaskPage empty() {
        return new TaskPage(List.of(), 0, null);
    }
}
