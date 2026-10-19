package com.taskapp.task.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.taskapp.task.Priority;
import com.taskapp.task.Task;
import com.taskapp.task.TaskStatus;

/**
 * Public view of a task. Never exposes a user id. {@code tags} are sorted by the database collation. {@code addedBy}
 * (0.13.0): the display name of the user who added it, null when that account was deleted.
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
        Instant createdAt,
        Instant updatedAt) {

    public TaskResponse {
        tags = List.copyOf(tags);
    }

    /** Schema {@code Task.addedBy}. */
    public record AddedBy(String displayName) {
    }

    /** {@code addedByName}: the display name of the task's adder, null when that account is gone. */
    public static TaskResponse from(Task task, List<String> tags, String addedByName) {
        return new TaskResponse(task.getId(), task.getListId(), addedByName == null ? null : new AddedBy(addedByName),
                task.getTitle(), task.getDescription(), task.getPriority(), task.getStatus(), tags, task.isDone(),
                task.getDueDate(), task.getCreatedAt(), task.getUpdatedAt());
    }
}
