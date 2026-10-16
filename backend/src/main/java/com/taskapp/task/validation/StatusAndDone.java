package com.taskapp.task.validation;

import com.taskapp.task.TaskStatus;

/** A request that may carry both {@code status} and its {@code done} mirror. */
public interface StatusAndDone {

    TaskStatus status();

    Boolean done();
}
