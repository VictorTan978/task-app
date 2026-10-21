package com.taskapp.task.dto;

/**
 * A nullable text field of {@code PATCH /api/tasks/{id}} (the description), three states (contract 0.16.0): not sent =
 * Java {@code null} on the request (unchanged), JSON {@code null} = {@link #CLEAR}, a string = that text, stripped
 * (blank clears, like {@code PUT}). See {@link TextUpdateDeserializer}.
 */
public record TextUpdate(String value) {

    public static final TextUpdate CLEAR = new TextUpdate(null);

    public TextUpdate {
        value = TaskText.description(value);
    }
}
