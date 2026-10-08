package com.taskapp.auth.validation;

import java.nio.charset.StandardCharsets;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class PasswordLengthValidator implements ConstraintValidator<PasswordLength, String> {

    static final int MIN_CHARS = 8;
    static final int MAX_CHARS = 72;
    static final int MAX_UTF8_BYTES = 72;

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null || value.isEmpty()) {
            return true;
        }
        return value.length() >= MIN_CHARS
                && value.length() <= MAX_CHARS
                && value.getBytes(StandardCharsets.UTF_8).length <= MAX_UTF8_BYTES;
    }
}
