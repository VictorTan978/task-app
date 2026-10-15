package com.taskapp.auth;

/**
 * Delete account for an account without a password: the typed email isn't the account's. Rendered as 400
 * {@code EMAIL_MISMATCH} with one field error. {@link #email()} (the account's email) is only for the security log.
 */
public class EmailMismatchException extends RuntimeException {

    public static final String MESSAGE = "Email doesn't match";

    private final transient String email;

    public EmailMismatchException(String email) {
        super(MESSAGE, null, false, false);
        this.email = email;
    }

    public String email() {
        return email;
    }
}
