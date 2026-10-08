package com.taskapp.task.dto;

import jakarta.validation.constraints.NotNull;

/** Sets (does not toggle) the done state. */
public record SetTaskDoneRequest(
        @NotNull(message = "Done is required")
        Boolean done) {
}
