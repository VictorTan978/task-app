package com.taskapp.task;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * A repeat rule (contract 0.15.0). {@code weekdayMask} is the V13 bitmask (MON = 1 ... SUN = 64) and is 0 unless the
 * frequency is {@code WEEKLY}. Pure date arithmetic: no zone, no clock; the caller passes "today".
 */
public record RepeatRule(RepeatFrequency frequency, int weekdayMask) {

    private static final String[] NAMES = {"MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN"};

    public RepeatRule {
        if (frequency == RepeatFrequency.WEEKLY ? weekdayMask < 1 || weekdayMask > 127 : weekdayMask != 0) {
            throw new IllegalArgumentException("weekdays do not fit the frequency");
        }
    }

    public static RepeatRule of(RepeatFrequency frequency, Set<DayOfWeek> weekdays) {
        int mask = 0;
        if (frequency == RepeatFrequency.WEEKLY) {
            for (DayOfWeek day : weekdays) {
                mask |= 1 << (day.getValue() - 1);
            }
        }
        return new RepeatRule(frequency, mask);
    }

    /** Weekday names (MON..SUN), Monday first; empty unless WEEKLY. */
    public List<String> weekdayNames() {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            if ((weekdayMask & (1 << i)) != 0) {
                names.add(NAMES[i]);
            }
        }
        return names;
    }

    /** The weekday for a contract name, or null. Case-sensitive. */
    public static DayOfWeek weekdayOf(String name) {
        for (int i = 0; i < 7; i++) {
            if (NAMES[i].equals(name)) {
                return DayOfWeek.of(i + 1);
            }
        }
        return null;
    }

    public Set<DayOfWeek> weekdays() {
        Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
        for (int i = 0; i < 7; i++) {
            if ((weekdayMask & (1 << i)) != 0) {
                days.add(DayOfWeek.of(i + 1));
            }
        }
        return days;
    }

    /**
     * The due date of the next task after one due on {@code oldDue} was completed on {@code today}: the first
     * occurrence strictly after {@code oldDue} and not before {@code today}, so a late completion creates no backlog.
     * Monthly / yearly occurrences are counted from {@code anchor} (clamped to the end of shorter months).
     */
    public LocalDate next(LocalDate anchor, LocalDate oldDue, LocalDate today) {
        LocalDate start = oldDue.plusDays(1);
        if (today.isAfter(start)) {
            start = today;
        }
        return firstOnOrAfter(start, anchor);
    }

    private LocalDate firstOnOrAfter(LocalDate start, LocalDate anchor) {
        switch (frequency) {
            case DAILY:
                return start;
            case WEEKLY:
                LocalDate day = start;
                while ((weekdayMask & (1 << (day.getDayOfWeek().getValue() - 1))) == 0) {
                    day = day.plusDays(1);
                }
                return day;
            case MONTHLY: {
                YearMonth month = YearMonth.from(start);
                LocalDate candidate = clamp(month, anchor.getDayOfMonth());
                return candidate.isBefore(start) ? clamp(month.plusMonths(1), anchor.getDayOfMonth()) : candidate;
            }
            default: {
                YearMonth month = YearMonth.of(start.getYear(), anchor.getMonth());
                LocalDate candidate = clamp(month, anchor.getDayOfMonth());
                return candidate.isBefore(start) ? clamp(month.plusYears(1), anchor.getDayOfMonth()) : candidate;
            }
        }
    }

    private static LocalDate clamp(YearMonth month, int day) {
        return month.atDay(Math.min(day, month.lengthOfMonth()));
    }
}
