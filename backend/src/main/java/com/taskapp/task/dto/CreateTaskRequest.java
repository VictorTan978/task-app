package com.taskapp.task.dto;

import java.time.LocalDate;

import tools.jackson.databind.annotation.JsonDeserialize;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Title and description are normalized on construction, i.e. before validation. Unknown properties are ignored.
 * {@code listId} (0.12.0): the list to create the task in; null = "My tasks". Must be a JSON integer.
 */
public record CreateTaskRequest(
        @NotBlank(message = "Title is required")
        @Size(max = 200, message = "Title must be 200 characters or fewer")
        String title,

        @Size(max = 2000, message = "Description must be 2,000 characters or fewer")
        String description,

        LocalDate dueDate,

        @JsonDeserialize(using = StrictLongDeserializer.class)
        Long listId) {

    public CreateTaskRequest {
        title = TaskText.title(title);
        description = TaskText.description(description);
    }
}
