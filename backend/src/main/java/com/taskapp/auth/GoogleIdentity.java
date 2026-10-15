package com.taskapp.auth;

/**
 * Who the user is at Google, taken ONLY from Google's verified ID token (never from the browser).
 *
 * @param subject       Google's stable account id ({@code sub})
 * @param email         the {@code email} claim as sent (may be null)
 * @param emailVerified the {@code email_verified} claim ({@code false} when absent)
 * @param name          the {@code name} claim (may be null)
 */
public record GoogleIdentity(String subject, String email, boolean emailVerified, String name) {
}
