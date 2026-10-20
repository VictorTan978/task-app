package com.taskapp.task.dto;

import java.util.Locale;

import tools.jackson.databind.annotation.JsonDeserialize;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/lists/{id}/invitations} (0.13.0). Same email rules and messages as register: trimmed and
 * lowercased on construction, i.e. before validation. A non-string {@code email} is a 400 MALFORMED_REQUEST.
 */
public record CreateInvitationRequest(
        @NotBlank(message = "Email is required")
        @Email(message = "Enter a valid email address")
        @Size(max = 254, message = "Email must be at most 254 characters")
        @JsonDeserialize(using = StrictStringDeserializer.class)
        String email) {

    public CreateInvitationRequest {
        email = email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }
}
