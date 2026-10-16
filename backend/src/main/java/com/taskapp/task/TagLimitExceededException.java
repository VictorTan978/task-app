package com.taskapp.task;

/** The change would leave the user with more than 200 tags. Thrown inside the transaction, so nothing is saved. */
public class TagLimitExceededException extends RuntimeException {

    public static final String MESSAGE = "You can have at most 200 tags";

    public TagLimitExceededException() {
        super(MESSAGE, null, false, false);
    }
}
