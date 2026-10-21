package com.taskapp.task.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/** Body of {@code POST /api/tasks/bulk-undo-complete} (0.17.0). */
public record BulkUndoCompleteRequest(
        @NotNull(message = "items is required")
        List<@NotNull(message = "item is required") @Valid BulkUndoCompleteItem> items) {
}
