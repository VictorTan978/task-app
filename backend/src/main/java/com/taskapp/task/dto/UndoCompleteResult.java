package com.taskapp.task.dto;

import com.fasterxml.jackson.annotation.JsonUnwrapped;

/** Schema {@code UndoCompleteResult} (0.16.0): the reopened task plus what happened to the copy. */
public record UndoCompleteResult(@JsonUnwrapped TaskResponse task, Outcome nextTaskOutcome) {

    /**
     * {@code DELETED}: copy deleted, rule back. {@code KEPT}: copy left alone. {@code GONE}: copy not found.
     * {@code NONE} (0.17.0, bulk only): no copy was named, the task was just reopened.
     */
    public enum Outcome {
        DELETED, KEPT, GONE, NONE
    }
}
