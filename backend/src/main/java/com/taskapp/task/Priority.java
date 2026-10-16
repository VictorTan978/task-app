package com.taskapp.task;

import com.fasterxml.jackson.annotation.JsonCreator;

/** Task priority (contract 0.10.0). JSON values are the exact constant names. */
public enum Priority {
    NONE, LOW, MEDIUM, HIGH;

    /**
     * Strict JSON input: only the exact, case-sensitive name. Anything else (other case, a number, a boolean) fails,
     * which the API reports as 400 {@code MALFORMED_REQUEST}. Jackson would otherwise accept enum indexes.
     */
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static Priority fromJson(String value) {
        return valueOf(value);
    }
}
