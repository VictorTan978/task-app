package com.taskapp.auth.dto;

import com.taskapp.auth.validation.PasswordLength;

import jakarta.validation.constraints.NotBlank;

/**
 * Body of reset-password. Deliberately NOT validated with {@code @Valid}: the token is checked first (400
 * {@code TOKEN_INVALID}), and only then {@code newPassword}, by the service, with these rules (same as register,
 * not trimmed). {@link #toString()} never shows the token or the password.
 */
public record ResetPasswordRequest(
        String token,

        @NotBlank(message = "New password is required")
        @PasswordLength(message = "Password must be 8 to 72 characters")
        String newPassword) {

    @Override
    public String toString() {
        return "ResetPasswordRequest[token=****, newPassword=****]";
    }
}
