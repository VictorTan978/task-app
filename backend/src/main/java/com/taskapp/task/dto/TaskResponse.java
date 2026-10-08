package com.taskapp.task.dto;

import java.time.Instant;
import java.time.LocalDate;

import com.taskapp.task.Task;

/** Public view of a task. Never exposes the owner. */
public record TaskResponse(
        long id,
        String title,
        String description,
        boolean done,
        LocalDate dueDate,
        Instant createdAt,
        Instant updatedAt) {

    public static TaskResponse from(Task task) {
        return new TaskResponse(task.getId(), task.getTitle(), task.getDescription(), task.isDone(),
                task.getDueDate(), task.getCreatedAt(), task.getUpdatedAt());
    }
}
