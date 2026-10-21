package com.taskapp.task.dto;

import java.util.List;

/** Schema {@code BulkResult} (0.17.0). {@code tasks} is in request order; empty for {@code DELETE}. */
public record BulkResult(int count, List<TaskResult> tasks) {
}
