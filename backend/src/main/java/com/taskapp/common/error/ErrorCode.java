package com.taskapp.common.error;

/** Stable machine-readable error codes, exposed as the {@code code} property of every ProblemDetail. */
public final class ErrorCode {

    public static final String VALIDATION_FAILED = "VALIDATION_FAILED";
    public static final String MALFORMED_REQUEST = "MALFORMED_REQUEST";
    public static final String EMAIL_TAKEN = "EMAIL_TAKEN";
    public static final String DISPLAY_NAME_TAKEN = "DISPLAY_NAME_TAKEN";
    /** 400 on /api/account/*: the password given to confirm the action is wrong. Also a field-level code. */
    public static final String WRONG_PASSWORD = "WRONG_PASSWORD";
    public static final String INVALID_CREDENTIALS = "INVALID_CREDENTIALS";
    public static final String UNAUTHENTICATED = "UNAUTHENTICATED";
    public static final String CSRF_INVALID = "CSRF_INVALID";
    public static final String FORBIDDEN = "FORBIDDEN";
    public static final String NOT_FOUND = "NOT_FOUND";
    public static final String RATE_LIMITED = "RATE_LIMITED";
    public static final String INTERNAL_ERROR = "INTERNAL_ERROR";
    /** 400: an emailed link token is invalid for any reason (the reason is only logged). */
    public static final String TOKEN_INVALID = "TOKEN_INVALID";
    /** 409 on resend-verification: the email is already confirmed. */
    public static final String ALREADY_VERIFIED = "ALREADY_VERIFIED";
    /** 409: the account has no password (change password, disconnect Google). */
    public static final String PASSWORD_REQUIRED = "PASSWORD_REQUIRED";
    /** 400 on delete account for accounts without a password: the typed email is wrong. Also a field-level code. */
    public static final String EMAIL_MISMATCH = "EMAIL_MISMATCH";

    /** Field-level codes used inside {@code errors[]}. */
    public static final String REQUIRED = "REQUIRED";
    public static final String INVALID_EMAIL = "INVALID_EMAIL";
    public static final String TOO_LONG = "TOO_LONG";
    public static final String PASSWORD_LENGTH = "PASSWORD_LENGTH";

    private ErrorCode() {
    }
}
