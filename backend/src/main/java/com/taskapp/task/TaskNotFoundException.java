package com.taskapp.task;

/** The task does not exist or belongs to another user; both cases look identical to the caller. */
public class TaskNotFoundException extends RuntimeException {

    public TaskNotFoundException() {
        super("Task not found");
    }
}
