package com.taskapp.notification;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

import com.fasterxml.jackson.annotation.JsonFormat;

/**
 * Public view of one of the caller's notifications (contract 0.14.0). {@code title}, {@code dueDate} and
 * {@code dueTime} are a snapshot from when it fired. {@code taskId} / {@code listId} are null when the task is gone
 * or the caller is no longer a member of its list.
 */
public record NotificationResponse(
        long id,
        String type,
        boolean read,
        Instant createdAt,
        boolean late,
        String title,
        LocalDate dueDate,
        @JsonFormat(pattern = "HH:mm") LocalTime dueTime,
        Long taskId,
        Long listId) {
}
