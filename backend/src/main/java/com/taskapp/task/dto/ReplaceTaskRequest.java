package com.taskapp.task.dto;

import java.time.LocalDate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Full replacement: a null/omitted description or dueDate clears the field. */
public record ReplaceTaskRequest(
        @NotBlank(message = "Title is required")
        @Size(max = 200, message = "Title must be 200 characters or fewer")
        String title,

        @Size(max = 2000, message = "Description must be 2,000 characters or fewer")
        String description,

        LocalDate dueDate,

        @NotNull(message = "Done is required")
        Boolean done) {

    public ReplaceTaskRequest {
        title = TaskText.title(title);
        description = TaskText.description(description);
    }
}
