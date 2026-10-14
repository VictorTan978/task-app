package com.taskapp.auth;

/**
 * An emailed link token was refused. The client always gets 400 {@code TOKEN_INVALID}; {@link #reason()} and
 * {@link #email()} are only for the security log. Never carries the token.
 */
public class EmailTokenInvalidException extends RuntimeException {

    /** {@code missing|malformed|unknown|wrong_purpose|used|expired}. */
    private final transient String reason;
    private final transient String email;

    public EmailTokenInvalidException(String reason, String email) {
        super("This link is invalid or has expired.", null, false, false);
        this.reason = reason;
        this.email = email;
    }

    public String reason() {
        return reason;
    }

    /** The account the token belongs to, or null when unknown. */
    public String email() {
        return email;
    }
}
