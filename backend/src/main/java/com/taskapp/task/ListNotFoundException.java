package com.taskapp.task;

/** The list is not one of the signed-in user's lists (unknown, another user's, deleted): all look the same. */
public class ListNotFoundException extends RuntimeException {

    public static final String MESSAGE = "List not found";

    public ListNotFoundException() {
        super(MESSAGE, null, false, false);
    }
}
