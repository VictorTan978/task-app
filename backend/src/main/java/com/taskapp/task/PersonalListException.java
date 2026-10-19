package com.taskapp.task;

/** "My tasks" can't be deleted. Nothing was deleted. */
public class PersonalListException extends RuntimeException {

    public static final String MESSAGE = "Your personal list can't be deleted";

    public PersonalListException() {
        super(MESSAGE, null, false, false);
    }
}
