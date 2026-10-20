package com.taskapp.task.dto;

import tools.jackson.databind.annotation.JsonDeserialize;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/lists}. {@code name} follows the task-title rules: stripped on construction (before
 * validation), then 1-100 UTF-16 code units. A non-string {@code name} is a malformed body. Unknown properties are
 * ignored.
 */
public record CreateListRequest(
        @NotBlank(message = ListName.REQUIRED_MESSAGE)
        @Size(max = ListName.MAX_LENGTH, message = ListName.TOO_LONG_MESSAGE)
        @JsonDeserialize(using = StrictStringDeserializer.class)
        String name) {

    public CreateListRequest {
        name = TaskText.title(name);
    }
}
