package com.taskapp.auth;

/** Registration rejected because the email and/or the display name already belong to another account. */
public class RegistrationConflictException extends RuntimeException {

    private final boolean emailTaken;
    private final boolean displayNameTaken;

    public RegistrationConflictException(boolean emailTaken, boolean displayNameTaken) {
        super(describe(emailTaken, displayNameTaken));
        if (!emailTaken && !displayNameTaken) {
            throw new IllegalArgumentException("At least one field must be taken");
        }
        this.emailTaken = emailTaken;
        this.displayNameTaken = displayNameTaken;
    }

    public boolean emailTaken() {
        return emailTaken;
    }

    public boolean displayNameTaken() {
        return displayNameTaken;
    }

    private static String describe(boolean emailTaken, boolean displayNameTaken) {
        if (emailTaken && displayNameTaken) {
            return "Email and display name already registered";
        }
        return emailTaken ? "Email already registered" : "Display name already registered";
    }
}
