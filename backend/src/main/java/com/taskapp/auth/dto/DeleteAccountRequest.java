package com.taskapp.auth.dto;

import jakarta.validation.constraints.NotBlank;

/** The signed-in user's current password. Not trimmed; only presence is checked. */
public record DeleteAccountRequest(
        @NotBlank(message = "Password is required")
        String password) {

    @Override
    public String toString() {
        return "DeleteAccountRequest[password=****]";
    }
}
