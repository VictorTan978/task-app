package com.taskapp.reminder;

import java.util.Optional;

/**
 * When a reminder fires relative to the due date/time (contract 0.14.0). A timed task (has a due time) accepts
 * {@code AT_DUE}, {@code TEN_MINUTES}, {@code ONE_HOUR}, {@code ONE_DAY}; a date-only task accepts
 * {@code ON_THE_DAY} and {@code ONE_DAY} (both at 09:00). {@code NONE} exists in requests only (see
 * {@link #parseRequest}).
 */
public enum ReminderOffset {
    AT_DUE(true, false),
    TEN_MINUTES(true, false),
    ONE_HOUR(true, false),
    ONE_DAY(true, true),
    ON_THE_DAY(false, true);

    /** The request value that removes a reminder. */
    public static final String NONE = "NONE";

    private final boolean timed;
    private final boolean dateOnly;

    ReminderOffset(boolean timed, boolean dateOnly) {
        this.timed = timed;
        this.dateOnly = dateOnly;
    }

    /** Whether this offset is allowed on a task with ({@code hasTime}) or without a due time. */
    public boolean fits(boolean hasTime) {
        return hasTime ? timed : dateOnly;
    }

    /** The offset a request value names; empty for anything else, including {@code NONE} (case-sensitive). */
    public static Optional<ReminderOffset> parse(String value) {
        for (ReminderOffset offset : values()) {
            if (offset.name().equals(value)) {
                return Optional.of(offset);
            }
        }
        return Optional.empty();
    }

    /** Whether {@code value} is a valid request value: an offset name or {@value #NONE}. */
    public static boolean isRequestValue(String value) {
        return NONE.equals(value) || parse(value).isPresent();
    }
}
