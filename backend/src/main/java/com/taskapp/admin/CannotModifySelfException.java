package com.taskapp.admin;

/** 409 {@code CANNOT_MODIFY_SELF}: an admin action on the caller's own account. */
public class CannotModifySelfException extends RuntimeException {

    public CannotModifySelfException() {
        super("You can't do this to your own account.", null, false, false);
    }
}
