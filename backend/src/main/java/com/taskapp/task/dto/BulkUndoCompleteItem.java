package com.taskapp.task.dto;

import com.taskapp.task.TaskStatus;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * One task of {@code POST /api/tasks/bulk-undo-complete} (0.17.0). {@code nextTaskId} may be missing: the task is then
 * just reopened (outcome {@code NONE}). {@code status} {@code DONE} is rejected by the controller.
 */
public record BulkUndoCompleteItem(
        @NotNull(message = "id is required")
        @Min(value = 1, message = "id must be a positive integer")
        @JsonDeserialize(using = StrictLongDeserializer.class)
        Long id,

        @Min(value = 1, message = "nextTaskId must be a positive integer")
        @JsonDeserialize(using = StrictLongDeserializer.class)
        Long nextTaskId,

        TaskStatus status) {
}
