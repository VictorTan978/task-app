package com.taskapp.task.dto;

import java.time.LocalDate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Title and description are normalized on construction, i.e. before validation. Unknown properties are ignored. */
public record CreateTaskRequest(
        @NotBlank(message = "Title is required")
        @Size(max = 200, message = "Title must be 200 characters or fewer")
        String title,

        @Size(max = 2000, message = "Description must be 2,000 characters or fewer")
        String description,

        LocalDate dueDate) {

    public CreateTaskRequest {
        title = TaskText.title(title);
        description = TaskText.description(description);
    }
}
