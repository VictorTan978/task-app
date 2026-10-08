package com.taskapp.auth.dto;

import com.taskapp.auth.validation.PasswordLength;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Email is trimmed + lowercased and displayName trimmed on construction, i.e. before validation. */
public record RegisterRequest(
        @NotBlank(message = "Display name is required")
        @Size(max = 50, message = "Display name must be at most 50 characters")
        String displayName,

        @NotBlank(message = "Email is required")
        @Email(message = "Enter a valid email address")
        @Size(max = 254, message = "Email must be at most 254 characters")
        String email,

        @NotBlank(message = "Password is required")
        @PasswordLength(message = "Password must be 8 to 72 characters")
        String password) {

    public RegisterRequest {
        displayName = Normalize.trim(displayName);
        email = Normalize.email(email);
    }

    @Override
    public String toString() {
        return "RegisterRequest[displayName=" + displayName + ", email=" + email + ", password=****]";
    }
}
