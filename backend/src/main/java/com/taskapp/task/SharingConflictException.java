package com.taskapp.task;

import com.taskapp.common.error.ErrorCode;

/** A 409 of the sharing endpoints (0.13.0): an invitation or an accept was refused and nothing was changed. */
public class SharingConflictException extends RuntimeException {

    private final transient String code;

    private SharingConflictException(String code, String message) {
        super(message, null, false, false);
        this.code = code;
    }

    public static SharingConflictException alreadyMember() {
        return new SharingConflictException(ErrorCode.ALREADY_MEMBER, "This person is already a member of the list");
    }

    public static SharingConflictException alreadyInvited() {
        return new SharingConflictException(ErrorCode.ALREADY_INVITED,
                "This email address already has a pending invitation");
    }

    public static SharingConflictException memberLimit() {
        return new SharingConflictException(ErrorCode.MEMBER_LIMIT, "A list can have at most 20 members");
    }

    public static SharingConflictException invitationLimit() {
        return new SharingConflictException(ErrorCode.INVITATION_LIMIT,
                "A list can have at most 20 pending invitations");
    }

    /** The {@code code} of the problem body. */
    public String code() {
        return code;
    }
}
