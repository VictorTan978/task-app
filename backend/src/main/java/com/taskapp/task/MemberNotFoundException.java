package com.taskapp.task;

/** The user to remove is not a member of the list (never was, left, or already removed; 0.13.0). */
public class MemberNotFoundException extends RuntimeException {

    public static final String MESSAGE = "Member not found";

    public MemberNotFoundException() {
        super(MESSAGE, null, false, false);
    }
}
