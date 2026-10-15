package com.taskapp.admin;

/** 404 {@code USER_NOT_FOUND}: no account has the target id (e.g. deleted meanwhile). */
public class UserNotFoundException extends RuntimeException {

    public UserNotFoundException() {
        super("This user no longer exists.", null, false, false);
    }
}
