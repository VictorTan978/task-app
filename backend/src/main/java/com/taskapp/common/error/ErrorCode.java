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
    /** 403 at login (correct password) or 401 on a session: the account is disabled (contract 0.9.0). */
    public static final String ACCOUNT_DISABLED = "ACCOUNT_DISABLED";
    /** 409 on /api/admin/**: the target is the caller's own account. */
    public static final String CANNOT_MODIFY_SELF = "CANNOT_MODIFY_SELF";
    /** 403 on /api/admin/**: the caller is an admin, but not allowed to do this to this account. */
    public static final String INSUFFICIENT_ROLE = "INSUFFICIENT_ROLE";
    /** 404 on /api/admin/**: no account has this id. */
    public static final String USER_NOT_FOUND = "USER_NOT_FOUND";
    /** 404: a list (path, {@code ?list=} or body {@code listId}) is not one of the user's lists (0.12.0). */
    public static final String LIST_NOT_FOUND = "LIST_NOT_FOUND";
    /** 409 on {@code POST /api/lists}: the user already owns 50 lists (0.12.0). */
    public static final String LIST_LIMIT = "LIST_LIMIT";
    /** 409 on {@code DELETE /api/lists/{id}}: "My tasks" can't be deleted (0.12.0). */
    public static final String PERSONAL_LIST = "PERSONAL_LIST";
    /** 403: the caller is a member of the list but not its owner, and only the owner may do this (0.13.0). */
    public static final String OWNER_ONLY = "OWNER_ONLY";
    /** 409: the owner can't leave their list or be removed from it (0.13.0). */
    public static final String OWNER_CANNOT_LEAVE = "OWNER_CANNOT_LEAVE";
    /** 404 on {@code DELETE /api/lists/{id}/members/{userId}}: not a member of the list (0.13.0). */
    public static final String MEMBER_NOT_FOUND = "MEMBER_NOT_FOUND";
    /** 409 on invite: the address is a current member's (0.13.0). */
    public static final String ALREADY_MEMBER = "ALREADY_MEMBER";
    /** 409 on invite: a pending invitation for the address exists (0.13.0). */
    public static final String ALREADY_INVITED = "ALREADY_INVITED";
    /** 409 on invite and accept: the list has 20 members (0.13.0). */
    public static final String MEMBER_LIMIT = "MEMBER_LIMIT";
    /** 409 on invite: the list has 20 pending invitations (0.13.0). */
    public static final String INVITATION_LIMIT = "INVITATION_LIMIT";
    /** 404 on accept, decline and cancel: "This invitation is no longer valid" (0.13.0). */
    public static final String INVITATION_NOT_FOUND = "INVITATION_NOT_FOUND";

    /** Field-level codes used inside {@code errors[]}. */
    public static final String REQUIRED = "REQUIRED";
    public static final String INVALID_EMAIL = "INVALID_EMAIL";
    public static final String TOO_LONG = "TOO_LONG";
    public static final String PASSWORD_LENGTH = "PASSWORD_LENGTH";
    /** {@code page} / {@code size} of the admin user list out of range. */
    public static final String OUT_OF_RANGE = "OUT_OF_RANGE";
    /** {@code role} of the admin role change: missing or not USER/ADMIN. */
    public static final String INVALID_ROLE = "INVALID_ROLE";
    /** {@code tags}: a tag contains a comma or a control character (0.10.0). */
    public static final String INVALID_CHARACTERS = "INVALID_CHARACTERS";
    /** {@code tags}: more than 10 different tags on one task (0.10.0). */
    public static final String TOO_MANY = "TOO_MANY";
    /** {@code tags}: the list (on a move, the target list) would have more than 200 tags (0.10.0; per list 0.12.0). */
    public static final String TAG_LIMIT = "TAG_LIMIT";
    /** {@code status}: status and done both sent and disagreeing (0.10.0). */
    public static final String STATUS_CONFLICT = "STATUS_CONFLICT";
    /** {@code cursor} of the task list: unreadable or issued for another filter/sort/tag/search (0.11.0). */
    public static final String INVALID_CURSOR = "INVALID_CURSOR";

    private ErrorCode() {
    }
}
