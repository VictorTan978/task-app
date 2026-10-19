package com.taskapp.task;

/**
 * "This invitation is no longer valid" (0.13.0): unknown, not for the caller's verified email, expired, cancelled,
 * declined, already accepted, or its list was deleted. All look the same. Nothing was changed.
 */
public class InvitationNotFoundException extends RuntimeException {

    public static final String MESSAGE = "This invitation is no longer valid";

    public InvitationNotFoundException() {
        super(MESSAGE, null, false, false);
    }
}
