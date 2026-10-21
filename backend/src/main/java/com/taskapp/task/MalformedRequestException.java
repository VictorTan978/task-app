package com.taskapp.task;

/** The body is well-formed JSON but a value has the wrong type for the action (answered like an unreadable body). */
public class MalformedRequestException extends RuntimeException {

    public MalformedRequestException(String message) {
        super(message, null, false, false);
    }
}
