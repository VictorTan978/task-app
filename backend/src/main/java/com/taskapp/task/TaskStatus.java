package com.taskapp.task;

import com.fasterxml.jackson.annotation.JsonCreator;

/** Task status (contract 0.10.0, schema {@code Status}). {@code done} is a mirror of {@code status == DONE}. */
public enum TaskStatus {
    TODO, IN_PROGRESS, DONE;

    /** Strict JSON input, see {@link Priority#fromJson(String)}. */
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static TaskStatus fromJson(String value) {
        return valueOf(value);
    }

    /**
     * The status after a PUT/PATCH, given the request's optional {@code status} and {@code done} (already checked to
     * agree when both are sent): {@code status} wins; {@code done: true} means DONE; {@code done: false} only turns
     * DONE into TODO; neither keeps the current status.
     */
    public static TaskStatus resolve(TaskStatus current, TaskStatus status, Boolean done) {
        if (status != null) {
            return status;
        }
        if (done == null) {
            return current;
        }
        if (done) {
            return DONE;
        }
        return current == DONE ? TODO : current;
    }
}
