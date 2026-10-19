package com.taskapp.task;

/** The user already owns {@value ListService#MAX_LISTS} lists ("My tasks" included). Nothing was created. */
public class ListLimitException extends RuntimeException {

    public static final String MESSAGE = "You can have at most 50 lists";

    public ListLimitException() {
        super(MESSAGE, null, false, false);
    }
}
