package com.taskapp.auth.dto;

/**
 * Confirms deleting the signed-in account. Which field is checked depends on the account (contract 0.8.0), so the
 * service validates it, not {@code @Valid}:
 * <ul>
 * <li>an account with a password: {@code password} (not trimmed; only presence is checked);</li>
 * <li>an account without a password: {@code email} (trimmed on construction; compared like login).</li>
 * </ul>
 * The other field is ignored.
 */
public record DeleteAccountRequest(String password, String email) {

    public DeleteAccountRequest {
        email = Normalize.trim(email);
    }

    @Override
    public String toString() {
        return "DeleteAccountRequest[password=****, email=" + email + "]";
    }
}
