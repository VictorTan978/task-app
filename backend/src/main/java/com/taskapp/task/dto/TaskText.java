package com.taskapp.task.dto;

/** Normalization applied to task text fields before validation. */
final class TaskText {

    private TaskText() {
    }

    static String title(String value) {
        return value == null ? null : value.strip();
    }

    /** Stripped; blank becomes {@code null}. */
    static String description(String value) {
        if (value == null) {
            return null;
        }
        String stripped = value.strip();
        return stripped.isEmpty() ? null : stripped;
    }
}
