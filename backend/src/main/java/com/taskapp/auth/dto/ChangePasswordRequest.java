package com.taskapp.auth.dto;

import com.taskapp.auth.validation.PasswordLength;

import jakarta.validation.constraints.NotBlank;

/**
 * Passwords are never trimmed. Only presence is checked for {@code currentPassword} (like login);
 * {@code newPassword} follows the register password rules.
 */
public record ChangePasswordRequest(
        @NotBlank(message = "Current password is required")
        String currentPassword,

        @NotBlank(message = "New password is required")
        @PasswordLength(message = "Password must be 8 to 72 characters")
        String newPassword) {

    @Override
    public String toString() {
        return "ChangePasswordRequest[currentPassword=****, newPassword=****]";
    }
}
