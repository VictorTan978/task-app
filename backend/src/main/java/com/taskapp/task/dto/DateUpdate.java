package com.taskapp.task.dto;

import java.time.LocalDate;

/**
 * The {@code dueDate} of {@code PATCH /api/tasks/{id}}, three states (contract 0.16.0): not sent = Java {@code null}
 * on the request (unchanged), JSON {@code null} = {@link #CLEAR}, a date = that date. See {@link DateUpdateDeserializer}.
 */
public record DateUpdate(LocalDate value) {

    public static final DateUpdate CLEAR = new DateUpdate(null);
}
