package com.taskapp.security.google;

/** What a Google flow was started for (form field {@code intent}). */
enum GoogleFlowIntent {
    /** Sign in or sign up, from {@code /login} and {@code /register}. */
    LOGIN("login", OAuthResult.GOOGLE_FAILED),
    /** Connect Google to the signed-in account, from {@code /account}. */
    CONNECT("connect", OAuthResult.GOOGLE_CONNECT_FAILED);

    private final String value;
    private final OAuthResult failure;

    GoogleFlowIntent(String value, OAuthResult failure) {
        this.value = value;
        this.failure = failure;
    }

    /** The form / log value. */
    String value() {
        return value;
    }

    /** Where a failed flow of this intent ends (when the session is still valid). */
    OAuthResult failure() {
        return failure;
    }

    /** {@code null} or absent = LOGIN; an unknown value = null. */
    static GoogleFlowIntent fromForm(String value) {
        if (value == null || value.equals(LOGIN.value)) {
            return LOGIN;
        }
        return value.equals(CONNECT.value) ? CONNECT : null;
    }

    /** From a stored authorization request attribute; anything unexpected = LOGIN. */
    static GoogleFlowIntent fromAttribute(Object value) {
        return CONNECT.value.equals(value) ? CONNECT : LOGIN;
    }
}
