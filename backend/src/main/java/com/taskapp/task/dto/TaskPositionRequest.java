package com.taskapp.task.dto;

import jakarta.validation.constraints.Min;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * Body of {@code PUT /api/tasks/{id}/position} (0.18.0). {@code afterId}: the task that must come right before
 * {@code id}; null or missing = the top of its group. A value that is not an integer is a malformed body; below 1 is
 * {@code afterId} / {@code OUT_OF_RANGE}.
 */
public record TaskPositionRequest(
        @Min(value = 1, message = "afterId must be a positive integer")
        @JsonDeserialize(using = StrictLongDeserializer.class)
        Long afterId) {
}
