package com.taskapp.task.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.taskapp.reminder.ReminderOffset;
import com.taskapp.reminder.ReminderService;
import com.taskapp.task.Priority;
import com.taskapp.task.Task;
import com.taskapp.task.TaskStatus;

/**
 * Public view of a task. Never exposes a user id. {@code tags} are sorted by the database collation. {@code addedBy}
 * (0.13.0): the display name of the user who added it, null when that account was deleted. {@code dueTime}
 * (0.14.0): {@code "HH:mm"} or null; {@code myReminder}: the caller's own reminder offset or null;
 * {@code repeat} (0.15.0): the repeat rule or null; {@code myReminderPassed}: that reminder's fire time is not in the future.
 */
public record TaskResponse(
        long id,
        long listId,
        AddedBy addedBy,
        String title,
        String description,
        Priority priority,
        TaskStatus status,
        List<String> tags,
        boolean done,
        LocalDate dueDate,
        @JsonFormat(pattern = "HH:mm") LocalTime dueTime,
        RepeatResponse repeat,
        ReminderOffset myReminder,
        boolean myReminderPassed,
        Instant createdAt,
        Instant updatedAt) {

    public TaskResponse {
        tags = List.copyOf(tags);
    }

    /** A task without a repeat rule. */
    public TaskResponse(long id, long listId, AddedBy addedBy, String title, String description, Priority priority,
            TaskStatus status, List<String> tags, boolean done, LocalDate dueDate, LocalTime dueTime,
            ReminderOffset myReminder, boolean myReminderPassed, Instant createdAt, Instant updatedAt) {
        this(id, listId, addedBy, title, description, priority, status, tags, done, dueDate, dueTime, null,
                myReminder, myReminderPassed, createdAt, updatedAt);
    }

    /** Schema {@code Task.addedBy}. */
    public record AddedBy(String displayName) {
    }

    /** {@code addedByName}: the display name of the task's adder, null when that account is gone. No reminder. */
    public static TaskResponse from(Task task, List<String> tags, String addedByName) {
        return from(task, tags, addedByName, null);
    }

    /** {@code mine}: the caller's reminder on the task, null when they have none. */
    public static TaskResponse from(Task task, List<String> tags, String addedByName, ReminderService.Mine mine) {
        return new TaskResponse(task.getId(), task.getListId(), addedByName == null ? null : new AddedBy(addedByName),
                task.getTitle(), task.getDescription(), task.getPriority(), task.getStatus(), tags, task.isDone(),
                task.getDueDate(), task.getDueTime(), RepeatResponse.of(task.getRepeat()), mine == null ? null : mine.offset(),
                mine != null && mine.passed(), task.getCreatedAt(), task.getUpdatedAt());
    }
}
