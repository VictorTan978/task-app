package com.taskapp.auth;

/**
 * 404 {@code NOT_FOUND} on the connection endpoints: Google sign-in isn't configured, or the user has no Google
 * connection.
 */
public class ConnectionNotFoundException extends RuntimeException {

    public ConnectionNotFoundException(String detail) {
        super(detail, null, false, false);
    }
}
