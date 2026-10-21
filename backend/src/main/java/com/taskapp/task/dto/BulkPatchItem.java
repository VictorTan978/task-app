package com.taskapp.task.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.annotation.JsonDeserialize;

/** One task of {@code POST /api/tasks/bulk-patch}: the id and its own PATCH body. */
public record BulkPatchItem(
        @NotNull(message = "id is required")
        @JsonDeserialize(using = StrictLongDeserializer.class)
        Long id,

        @NotNull(message = "patch is required")
        @Valid
        SetTaskDoneRequest patch) {
}
