package com.taskapp.auth;

/** The session refers to a user that can no longer be resolved. */
public class UnauthenticatedException extends RuntimeException {

    public UnauthenticatedException() {
        super("Authentication required");
    }
}
