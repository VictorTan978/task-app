package com.taskapp.task.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/** Body of {@code POST /api/tasks/bulk-patch} (0.17.0). 1-200 items with unique ids, checked by the controller. */
public record BulkPatchRequest(
        @NotNull(message = "items is required")
        List<@NotNull(message = "item is required") @Valid BulkPatchItem> items) {
}
