package com.taskapp.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Same email rules as {@link RegisterRequest#email()}: trimmed + lowercased on construction, before validation. */
public record ForgotPasswordRequest(
        @NotBlank(message = "Email is required")
        @Email(message = "Enter a valid email address")
        @Size(max = 254, message = "Email must be at most 254 characters")
        String email) {

    public ForgotPasswordRequest {
        email = Normalize.email(email);
    }
}
