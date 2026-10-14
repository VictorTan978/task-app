package com.taskapp.auth;

import org.springframework.validation.BindingResult;

/**
 * Request fields failed validation that had to run inside the service (after another check, e.g. the reset token).
 * Rendered exactly like a {@code @Valid} failure: 400 {@code VALIDATION_FAILED} with {@code errors}.
 */
public class InvalidFieldsException extends RuntimeException {

    private final transient BindingResult bindingResult;

    public InvalidFieldsException(BindingResult bindingResult) {
        super("Validation failed", null, false, false);
        this.bindingResult = bindingResult;
    }

    public BindingResult bindingResult() {
        return bindingResult;
    }
}
