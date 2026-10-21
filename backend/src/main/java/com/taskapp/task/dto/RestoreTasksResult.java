package com.taskapp.task.dto;

import java.util.List;

/** Schema {@code RestoreTasksResult} (0.17.0). */
public record RestoreTasksResult(List<TaskResponse> tasks) {
}
