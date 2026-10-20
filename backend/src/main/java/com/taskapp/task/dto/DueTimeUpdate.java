package com.taskapp.task.dto;

import java.time.LocalTime;

/**
 * The {@code dueTime} of a task replacement, three states (contract 0.14.0): not sent = {@code null} (unchanged),
 * JSON {@code null} = {@link #CLEAR}, a value = that time. See {@link DueTimeDeserializer}.
 */
public record DueTimeUpdate(LocalTime value) {

    public static final DueTimeUpdate CLEAR = new DueTimeUpdate(null);
}
