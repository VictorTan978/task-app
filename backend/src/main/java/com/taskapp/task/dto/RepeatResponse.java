package com.taskapp.task.dto;

import java.util.List;

import com.taskapp.task.RepeatFrequency;
import com.taskapp.task.RepeatRule;

/** Schema {@code RepeatRule}: {@code weekdays} is Monday first, {@code []} unless WEEKLY. The anchor is not exposed. */
public record RepeatResponse(RepeatFrequency frequency, List<String> weekdays) {

    public static RepeatResponse of(RepeatRule rule) {
        return rule == null ? null : new RepeatResponse(rule.frequency(), rule.weekdayNames());
    }
}
