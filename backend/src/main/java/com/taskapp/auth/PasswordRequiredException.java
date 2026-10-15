package com.taskapp.auth;

/**
 * The account has no password, so the action is refused: 409 {@code PASSWORD_REQUIRED}. Nothing was changed or
 * counted.
 */
public class PasswordRequiredException extends RuntimeException {

    /** Which action was refused: decides the message shown to the user. */
    public enum Action {
        CHANGE_PASSWORD("Your account has no password. Set one with 'Forgot password?' on the sign-in page."),
        DISCONNECT_GOOGLE("Set a password first so you don't lose access to your account.");

        private final String message;

        Action(String message) {
            this.message = message;
        }

        public String message() {
            return message;
        }
    }

    public PasswordRequiredException(Action action) {
        super(action.message(), null, false, false);
    }
}
