package com.taskapp.reminder;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.zone.ZoneOffsetTransition;
import java.time.zone.ZoneRules;
import java.util.Set;

/**
 * Pure time arithmetic of reminders. A due time is a wall-clock value, so a reminder's fire time is computed in the
 * reminder owner's zone: the due date and time are read as that zone's clock, then the offset is taken off.
 * <ul>
 * <li>Timed task: {@code AT_DUE}, {@code TEN_MINUTES}, {@code ONE_HOUR} subtract an exact duration from the due
 * instant; {@code ONE_DAY} is the same wall-clock time on the previous calendar day.</li>
 * <li>Date-only task: {@code ON_THE_DAY} is 09:00 on the date, {@code ONE_DAY} 09:00 the day before.</li>
 * <li>A wall-clock time that does not exist (spring-forward gap) is the instant the clocks jump; one that happens
 * twice (fall-back overlap) is the first (earlier offset).</li>
 * </ul>
 */
public final class FireTimes {

    /** The wall-clock time of date-only reminders. */
    public static final LocalTime DATE_ONLY_TIME = LocalTime.of(9, 0);

    private static final Set<String> REGION_IDS = ZoneId.getAvailableZoneIds();

    private FireTimes() {
    }

    /** The reminder owner's zone: {@code timeZone} (a stored region id), or UTC when they have none (or it is unusable). */
    public static ZoneId zoneOrUtc(String timeZone) {
        if (timeZone == null || !isRegionId(timeZone)) {
            return ZoneOffset.UTC;
        }
        try {
            return ZoneId.of(timeZone);
        } catch (DateTimeException e) {
            return ZoneOffset.UTC;
        }
    }

    /** An IANA region id the JDK knows, case-sensitive: not an offset ({@code +02:00}, {@code UTC+2}) or abbreviation. */
    public static boolean isRegionId(String id) {
        return id != null && id.length() <= 64 && REGION_IDS.contains(id);
    }

    /** {@code dueTime} null = a date-only task. */
    public static Instant fireAt(LocalDate dueDate, LocalTime dueTime, ReminderOffset offset, ZoneId zone) {
        if (dueTime == null) {
            LocalDate day = offset == ReminderOffset.ONE_DAY ? dueDate.minusDays(1) : dueDate;
            return instantOf(day.atTime(DATE_ONLY_TIME), zone);
        }
        LocalDateTime due = dueDate.atTime(dueTime);
        return switch (offset) {
            case AT_DUE, ON_THE_DAY -> instantOf(due, zone);
            case TEN_MINUTES -> instantOf(due, zone).minus(Duration.ofMinutes(10));
            case ONE_HOUR -> instantOf(due, zone).minus(Duration.ofHours(1));
            case ONE_DAY -> instantOf(due.minusDays(1), zone);
        };
    }

    /** The instant of a wall-clock time in {@code zone}: gap = the transition instant, overlap = the earlier offset. */
    static Instant instantOf(LocalDateTime local, ZoneId zone) {
        ZoneRules rules = zone.getRules();
        ZoneOffsetTransition transition = rules.getTransition(local);
        if (transition != null && transition.isGap()) {
            return transition.getInstant();
        }
        // Overlap: ofLocal keeps the earlier offset by default.
        return ZonedDateTime.ofLocal(local, zone, null).toInstant();
    }
}
