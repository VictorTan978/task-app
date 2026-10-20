package com.taskapp.reminder;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/** Conversions between instants and the UTC {@code DATETIME(6)} columns (the JDBC connection is pinned to UTC). */
public final class ReminderTimes {

    private ReminderTimes() {
    }

    public static LocalDateTime toDb(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    public static Instant fromDb(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }
}
