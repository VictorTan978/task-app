package com.taskapp.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Same rules as {@link RegisterRequest#displayName()}: trimmed on construction, i.e. before validation. */
public record ChangeDisplayNameRequest(
        @NotBlank(message = "Display name is required")
        @Size(max = 50, message = "Display name must be at most 50 characters")
        String displayName) {

    public ChangeDisplayNameRequest {
        displayName = Normalize.trim(displayName);
    }
}
