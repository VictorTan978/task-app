package com.taskapp.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/** Only presence/format is validated; password rules are deliberately not checked at login. */
public record LoginRequest(
        @NotBlank(message = "Email is required")
        @Email(message = "Enter a valid email address")
        String email,

        @NotBlank(message = "Password is required")
        String password) {

    public LoginRequest {
        email = Normalize.email(email);
    }

    @Override
    public String toString() {
        return "LoginRequest[email=" + email + ", password=****]";
    }
}
