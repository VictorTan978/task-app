package com.taskapp.admin;

/** 403 {@code INSUFFICIENT_ROLE}: the caller is an admin, but may not do this to this account (Roles table). */
public class InsufficientRoleException extends RuntimeException {

    public InsufficientRoleException() {
        super("You don't have permission to do this.", null, false, false);
    }
}
