package com.taskapp.task.dto;

import com.taskapp.task.TaskStatus;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * Body of {@code POST /api/tasks/{id}/undo-complete} (0.16.0). {@code status}: what the task was before it was
 * completed, {@code TODO} (default) or {@code IN_PROGRESS}; {@code DONE} is rejected by the controller.
 */
public record UndoCompleteRequest(
        @NotNull(message = "nextTaskId is required")
        @Min(value = 1, message = "nextTaskId must be a positive integer")
        @JsonDeserialize(using = StrictLongDeserializer.class)
        Long nextTaskId,

        TaskStatus status) {
}
