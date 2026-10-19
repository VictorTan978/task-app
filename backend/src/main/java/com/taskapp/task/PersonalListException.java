package com.taskapp.task;

/** "My tasks" can't be deleted (0.12.0) or shared (0.13.0). Nothing was changed. */
public class PersonalListException extends RuntimeException {

    public static final String MESSAGE = "Your personal list can't be deleted";
    public static final String SHARE_MESSAGE = "Your personal list can't be shared";

    public PersonalListException() {
        this(MESSAGE);
    }

    private PersonalListException(String message) {
        super(message, null, false, false);
    }

    /** Inviting someone to "My tasks". */
    public static PersonalListException share() {
        return new PersonalListException(SHARE_MESSAGE);
    }
}
