package com.taskapp.task.dto;

import java.util.List;

import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * Body of {@code POST /api/tasks/bulk} (0.17.0). {@code ids}: 1-200 unique, checked by the controller (needs codes
 * bean validation can't give). {@code value}: a string (status, priority, tag name) or an integer (list id), by action.
 */
public record BulkActionRequest(
        @JsonDeserialize(contentUsing = StrictLongDeserializer.class)
        List<Long> ids,

        @NotNull(message = "Action is required")
        BulkAction action,

        JsonNode value) {
}
