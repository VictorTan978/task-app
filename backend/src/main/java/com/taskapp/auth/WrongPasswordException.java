package com.taskapp.auth;

/**
 * The password given to confirm an account action is wrong. Rendered as 400 {@code WRONG_PASSWORD} (never 401,
 * which always means "not signed in"). {@link #email()} is only for the server-side security log.
 */
public class WrongPasswordException extends RuntimeException {

    /** Which account action was being confirmed: decides the reported field, the message and the log value. */
    public enum Action {
        CHANGE_PASSWORD("currentPassword", "Current password is incorrect", "change_password"),
        DELETE_ACCOUNT("password", "Password is incorrect", "delete_account");

        private final String field;
        private final String message;
        private final String logValue;

        Action(String field, String message, String logValue) {
            this.field = field;
            this.message = message;
            this.logValue = logValue;
        }

        public String field() {
            return field;
        }

        public String message() {
            return message;
        }

        public String logValue() {
            return logValue;
        }
    }

    private final transient Action action;
    private final transient String email;

    public WrongPasswordException(Action action, String email) {
        super(action.message(), null, false, false);
        this.action = action;
        this.email = email;
    }

    public Action action() {
        return action;
    }

    public String email() {
        return email;
    }
}
