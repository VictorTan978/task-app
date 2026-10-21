package com.taskapp.task.validation;

import com.taskapp.task.TaskStatus;

/** A request that may carry both {@code status} and its {@code done} mirror. */
public interface StatusAndDone {

    TaskStatus status();

    Boolean done();

    /**
     * Something is being changed (PATCH: {@code DoneOrStatusRequired} fails when this is false). By default
     * {@code status} or {@code done}; {@code SetTaskDoneRequest} adds the other fields (0.16.0).
     */
    default boolean hasChanges() {
        return status() != null || done() != null;
    }
}
