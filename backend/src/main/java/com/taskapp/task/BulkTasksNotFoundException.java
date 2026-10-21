package com.taskapp.task;

import java.util.List;

/**
 * A bulk call named tasks that are not available to the caller (missing, trashed, or in a list they are not a member of;
 * all look the same). {@code failedIds} is every such id, ascending. Nothing was changed (contract 0.17.0).
 */
public class BulkTasksNotFoundException extends RuntimeException {

    private final transient List<Long> failedIds;

    public BulkTasksNotFoundException(List<Long> failedIds) {
        super("Some tasks were not found", null, false, false);
        this.failedIds = failedIds.stream().sorted().toList();
    }

    public List<Long> failedIds() {
        return failedIds;
    }
}
