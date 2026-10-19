package com.taskapp.task;

/**
 * The caller is a member of the list but not its owner, and only the owner may do this (0.13.0): 403. A non-member
 * gets {@link ListNotFoundException} instead, never this. Nothing was changed.
 */
public class OwnerOnlyException extends RuntimeException {

    public static final String MESSAGE = "Only the list owner can do this";

    public OwnerOnlyException() {
        super(MESSAGE, null, false, false);
    }
}
