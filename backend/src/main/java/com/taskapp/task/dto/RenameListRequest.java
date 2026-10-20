package com.taskapp.task.dto;

import tools.jackson.databind.annotation.JsonDeserialize;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body of {@code PATCH /api/lists/{id}}: same {@code name} rules as {@link CreateListRequest}. */
public record RenameListRequest(
        @NotBlank(message = ListName.REQUIRED_MESSAGE)
        @Size(max = ListName.MAX_LENGTH, message = ListName.TOO_LONG_MESSAGE)
        @JsonDeserialize(using = StrictStringDeserializer.class)
        String name) {

    public RenameListRequest {
        name = TaskText.title(name);
    }
}
