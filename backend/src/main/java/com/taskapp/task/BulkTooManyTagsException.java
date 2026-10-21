package com.taskapp.task;

import java.util.List;

/** {@code ADD_TAG} would give these tasks more than 10 tags (ascending ids). Nothing was changed (contract 0.17.0). */
public class BulkTooManyTagsException extends RuntimeException {

    public static final String MESSAGE = "A task can have at most 10 tags";

    private final transient List<Long> failedIds;

    public BulkTooManyTagsException(List<Long> failedIds) {
        super(MESSAGE, null, false, false);
        this.failedIds = failedIds.stream().sorted().toList();
    }

    public List<Long> failedIds() {
        return failedIds;
    }
}
