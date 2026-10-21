package com.taskapp.task;

/**
 * List order from {@code ?sort=} (contract 0.11.0). Matching is exact and case-sensitive; anything else (or nothing)
 * means {@link #NEWEST}. Inside the done group (filter {@code all} only) the tasks are ordered by the sort; ties of
 * {@link #DUE}, {@link #PRIORITY} and {@link #TITLE} are broken newest first ({@code createdAt} DESC, {@code id}
 * DESC).
 */
public enum TaskSort {
    /** {@code createdAt} DESC, {@code id} DESC. */
    NEWEST,
    /** {@code createdAt} ASC, {@code id} ASC: the exact reverse of {@link #NEWEST}. */
    OLDEST,
    /** Due date soonest first, tasks without one last. */
    DUE,
    /** HIGH, MEDIUM, LOW, NONE. */
    PRIORITY,
    /** Title A-Z by the column collation (utf8mb4_0900_ai_ci: case and accents ignored). */
    TITLE,
    /** "My order" (0.18.0): positioned tasks by position ASC, then the rest newest first. Appended: ordinals stay. */
    MANUAL;

    public static TaskSort parse(String value) {
        if (value == null) {
            return NEWEST;
        }
        return switch (value) {
            case "oldest" -> OLDEST;
            case "due" -> DUE;
            case "priority" -> PRIORITY;
            case "title" -> TITLE;
            case "manual" -> MANUAL;
            default -> NEWEST;
        };
    }
}
