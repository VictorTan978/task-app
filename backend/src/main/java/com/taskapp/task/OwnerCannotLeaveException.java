package com.taskapp.task;

/** The owner can't leave their list or be removed from it (no ownership transfer; 0.13.0). Nothing was changed. */
public class OwnerCannotLeaveException extends RuntimeException {

    public static final String MESSAGE = "The owner can't leave the list";

    public OwnerCannotLeaveException() {
        super(MESSAGE, null, false, false);
    }
}
