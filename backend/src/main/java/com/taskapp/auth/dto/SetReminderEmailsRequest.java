package com.taskapp.auth.dto;

import jakarta.validation.constraints.NotNull;

/** {@code PUT /api/account/reminder-emails} (contract 0.14.0). */
public record SetReminderEmailsRequest(
        @NotNull(message = "Enabled is required")
        Boolean enabled) {
}
