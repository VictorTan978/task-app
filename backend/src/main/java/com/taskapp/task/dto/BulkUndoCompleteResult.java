package com.taskapp.task.dto;

import java.util.List;

/** Schema {@code BulkUndoCompleteResult} (0.17.0): one result per item, in request order. */
public record BulkUndoCompleteResult(List<UndoCompleteResult> results) {
}
