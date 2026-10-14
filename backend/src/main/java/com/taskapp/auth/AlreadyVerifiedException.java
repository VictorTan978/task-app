package com.taskapp.auth;

/** Resend verification for an account whose email is already confirmed: 409 {@code ALREADY_VERIFIED}. */
public class AlreadyVerifiedException extends RuntimeException {

    public AlreadyVerifiedException() {
        super("Your email address is already confirmed.", null, false, false);
    }
}
