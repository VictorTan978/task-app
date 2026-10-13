package com.taskapp.auth;

/** The requested display name belongs to another account (ignoring letter case and accents). */
public class DisplayNameTakenException extends RuntimeException {

    public DisplayNameTakenException() {
        super("Display name already registered", null, false, false);
    }
}
