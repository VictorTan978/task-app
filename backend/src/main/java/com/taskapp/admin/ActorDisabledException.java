package com.taskapp.admin;

/**
 * 401 {@code ACCOUNT_DISABLED}: the calling admin's own account was disabled after the security chain let the request
 * through. Their session ends on the next request anyway.
 */
public class ActorDisabledException extends RuntimeException {

    public ActorDisabledException() {
        super("This account has been disabled.", null, false, false);
    }
}
