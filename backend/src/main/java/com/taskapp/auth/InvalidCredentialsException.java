package com.taskapp.auth;

/**
 * Login failed. The client always gets the same answer; {@link #reason()} and {@link #email()} are only for the
 * server-side security log.
 */
public class InvalidCredentialsException extends RuntimeException {

    public enum Reason {
        UNKNOWN_EMAIL("unknown_email"),
        BAD_PASSWORD("bad_password");

        private final String logValue;

        Reason(String logValue) {
            this.logValue = logValue;
        }

        public String logValue() {
            return logValue;
        }
    }

    private final transient Reason reason;
    private final transient String email;

    public InvalidCredentialsException(Reason reason, String email) {
        super("Invalid email or password", null, false, false);
        this.reason = reason;
        this.email = email;
    }

    public Reason reason() {
        return reason;
    }

    /** The normalized email the login was attempted with. */
    public String email() {
        return email;
    }
}
