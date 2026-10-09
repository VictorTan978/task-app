package com.taskapp.common.error;

/** Stable machine-readable error codes, exposed as the {@code code} property of every ProblemDetail. */
public final class ErrorCode {

    public static final String VALIDATION_FAILED = "VALIDATION_FAILED";
    public static final String MALFORMED_REQUEST = "MALFORMED_REQUEST";
    public static final String EMAIL_TAKEN = "EMAIL_TAKEN";
    public static final String DISPLAY_NAME_TAKEN = "DISPLAY_NAME_TAKEN";
    public static final String INVALID_CREDENTIALS = "INVALID_CREDENTIALS";
    public static final String UNAUTHENTICATED = "UNAUTHENTICATED";
    public static final String CSRF_INVALID = "CSRF_INVALID";
    public static final String FORBIDDEN = "FORBIDDEN";
    public static final String NOT_FOUND = "NOT_FOUND";
    public static final String RATE_LIMITED = "RATE_LIMITED";
    public static final String INTERNAL_ERROR = "INTERNAL_ERROR";

    /** Field-level codes used inside {@code errors[]}. */
    public static final String REQUIRED = "REQUIRED";
    public static final String INVALID_EMAIL = "INVALID_EMAIL";
    public static final String TOO_LONG = "TOO_LONG";
    public static final String PASSWORD_LENGTH = "PASSWORD_LENGTH";

    private ErrorCode() {
    }
}
