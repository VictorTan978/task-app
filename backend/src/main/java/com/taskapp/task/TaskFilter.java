package com.taskapp.task;

import java.util.List;

/** List filter from {@code ?filter=}. Matching is exact; anything else (or nothing) means {@link #ALL}. */
public enum TaskFilter {
    ALL(List.of(TaskStatus.values())),
    ACTIVE(List.of(TaskStatus.TODO, TaskStatus.IN_PROGRESS)),
    IN_PROGRESS(List.of(TaskStatus.IN_PROGRESS)),
    DONE(List.of(TaskStatus.DONE));

    private final List<TaskStatus> statuses;

    TaskFilter(List<TaskStatus> statuses) {
        this.statuses = statuses;
    }

    /** The statuses this filter keeps. */
    public List<TaskStatus> statuses() {
        return statuses;
    }

    public static TaskFilter parse(String value) {
        if (value == null) {
            return ALL;
        }
        return switch (value) {
            case "active" -> ACTIVE;
            case "in_progress" -> IN_PROGRESS;
            case "done" -> DONE;
            default -> ALL;
        };
    }
}
