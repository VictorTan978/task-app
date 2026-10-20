package com.taskapp.task.dto;

import java.time.DayOfWeek;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.taskapp.task.RepeatFrequency;
import com.taskapp.task.RepeatRule;

/**
 * The {@code repeat} of a task replacement as it was sent (contract 0.15.0), unvalidated: either the JSON string
 * {@code text} (valid only as {@code "NEVER"}) or an object with a raw {@code frequency} and {@code weekdays}
 * (null = not sent). Absent or JSON null is Java {@code null} on the request (unchanged). See {@link RepeatDeserializer}.
 */
public record RepeatInput(String text, String frequency, List<String> weekdays) {

    public static final String NEVER = "NEVER";

    public static RepeatInput ofText(String text) {
        return new RepeatInput(text, null, null);
    }

    public static RepeatInput ofObject(String frequency, List<String> weekdays) {
        return new RepeatInput(null, frequency, weekdays);
    }

    public boolean isText() {
        return text != null;
    }

    public boolean isNever() {
        return NEVER.equals(text);
    }

    private RepeatFrequency parsedFrequency() {
        if (frequency == null) {
            return null;
        }
        for (RepeatFrequency f : RepeatFrequency.values()) {
            if (f.name().equals(frequency)) {
                return f;
            }
        }
        return null;
    }

    /** REQUIRED: an object without a frequency, or WEEKLY without weekdays. */
    public boolean missingParts() {
        if (isText()) {
            return false;
        }
        if (frequency == null || frequency.isBlank()) {
            return true;
        }
        return "WEEKLY".equals(frequency) && (weekdays == null || weekdays.isEmpty());
    }

    /** INVALID_REPEAT: a text other than NEVER, an unknown frequency or weekday, weekdays on a non-WEEKLY rule. */
    public boolean invalid() {
        if (isText()) {
            return !isNever();
        }
        if (frequency == null || frequency.isBlank()) {
            return false;
        }
        RepeatFrequency f = parsedFrequency();
        if (f == null) {
            return true;
        }
        if (weekdays != null) {
            if (f != RepeatFrequency.WEEKLY && !weekdays.isEmpty()) {
                return true;
            }
            for (String day : weekdays) {
                if (RepeatRule.weekdayOf(day) == null) {
                    return true;
                }
            }
        }
        return false;
    }

    /** A well-formed rule (not NEVER). */
    public boolean isRule() {
        return !isText() && !missingParts() && !invalid();
    }

    /** The rule of a well-formed object; duplicate weekdays are collapsed. */
    public Optional<RepeatRule> rule() {
        if (!isRule()) {
            return Optional.empty();
        }
        Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
        if (weekdays != null) {
            weekdays.forEach(d -> days.add(RepeatRule.weekdayOf(d)));
        }
        return Optional.of(RepeatRule.of(parsedFrequency(), days));
    }
}
