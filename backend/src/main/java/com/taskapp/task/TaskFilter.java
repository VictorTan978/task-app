package com.taskapp.task;

/** List filter from {@code ?filter=}. Matching is exact; anything else (or nothing) means {@link #ALL}. */
public enum TaskFilter {
    ALL, ACTIVE, DONE;

    public static TaskFilter parse(String value) {
        if (value == null) {
            return ALL;
        }
        return switch (value) {
            case "active" -> ACTIVE;
            case "done" -> DONE;
            default -> ALL;
        };
    }
}
