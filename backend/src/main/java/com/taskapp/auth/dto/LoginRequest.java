package com.taskapp.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/** Only presence/format is validated; password rules are deliberately not checked at login. */
public record LoginRequest(
        @NotBlank(message = "Email is required")
        @Email(message = "Enter a valid email address")
        String email,

        @NotBlank(message = "Password is required")
        String password,

        /** Optional; absent or null means false. */
        Boolean rememberMe) {

    public LoginRequest {
        email = Normalize.email(email);
    }

    public LoginRequest(String email, String password) {
        this(email, password, null);
    }

    /** Whether to create a remember-me login. */
    public boolean remember() {
        return Boolean.TRUE.equals(rememberMe);
    }

    @Override
    public String toString() {
        return "LoginRequest[email=" + email + ", password=****, rememberMe=" + rememberMe + "]";
    }
}
