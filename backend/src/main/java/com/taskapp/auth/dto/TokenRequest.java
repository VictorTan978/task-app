package com.taskapp.auth.dto;

/**
 * Body of verify-email and check-reset-token. Not bean-validated: a missing, blank or wrong {@code token} is
 * 400 {@code TOKEN_INVALID}, never {@code VALIDATION_FAILED}. {@link #toString()} never shows the token.
 */
public record TokenRequest(String token) {

    @Override
    public String toString() {
        return "TokenRequest[token=****]";
    }
}
