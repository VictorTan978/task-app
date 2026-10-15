package com.taskapp.admin;

/**
 * 403 {@code FORBIDDEN}: the caller's role, as locked in the action's transaction, is not an admin role (it was lowered
 * after the security chain let the request through). Same response as the URL rule on {@code /api/admin/**}.
 */
public class NotAdminException extends RuntimeException {

    public NotAdminException() {
        super("Access denied", null, false, false);
    }
}
