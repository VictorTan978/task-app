package com.taskapp.task.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.taskapp.task.Priority;
import com.taskapp.task.Task;
import com.taskapp.task.TaskStatus;

/** Public view of a task. Never exposes the owner. {@code tags} are sorted by the database collation. */
public record TaskResponse(
        long id,
        long listId,
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

    public static TaskResponse from(Task task, List<String> tags) {
        return new TaskResponse(task.getId(), task.getListId(), task.getTitle(), task.getDescription(), task.getPriority(),
                task.getStatus(), tags, task.isDone(), task.getDueDate(), task.getCreatedAt(), task.getUpdatedAt());
    }
}
