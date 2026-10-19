package com.taskapp.security.audit;

import org.slf4j.event.Level;

/**
 * Security events written by {@link SecurityEventLogger}. Successes are INFO, failures and rejections WARN, except
 * the session events below, which are normal expiries (INFO) unless stated otherwise.
 */
public enum SecurityEvent {
    LOGIN_SUCCESS(Level.INFO),
    /**
     * Field {@code reason=unknown_email|bad_password|no_password} (server-side only; the API answers the same for
     * these), or {@code reason=account_disabled} (correct password, account disabled: 403 ACCOUNT_DISABLED).
     */
    LOGIN_FAILURE(Level.WARN),
    REGISTER_SUCCESS(Level.INFO),
    LOGOUT(Level.INFO),
    PASSWORD_CHANGED(Level.INFO),
    /** Only when the stored display name actually changed (a case-only change counts). */
    DISPLAY_NAME_CHANGED(Level.INFO),
    ACCOUNT_DELETED(Level.INFO),
    /**
     * Wrong confirmation on an account endpoint. Field {@code action=change_password|delete_account} (wrong password)
     * or {@code action=delete_account_email} (wrong email, accounts without a password).
     */
    PASSWORD_CHECK_FAILURE(Level.WARN),
    /**
     * Field {@code limit=login-ip|login-email|register-ip|password-check-user|forgot-password-ip|
     * reset-email-account|resend-verification-user|verify-email-ip|check-reset-token-ip|reset-password-ip|
     * google-start-ip|google-callback-ip|admin-action-user|invite-user|invite-email-account}.
     */
    RATE_LIMITED(Level.WARN),
    /** 401 from the security chain or from a session whose user no longer exists. */
    UNAUTHENTICATED(Level.WARN),
    /** 403 because the CSRF token was missing or wrong. */
    CSRF_REJECTED(Level.WARN),
    /** 403 for any other reason. */
    ACCESS_DENIED(Level.WARN),
    /** A remember-me cookie signed the user in (new session). */
    REMEMBER_ME_LOGIN(Level.INFO),
    /**
     * A remember-me cookie was rejected and expired. Field {@code reason=malformed|unknown|expired|user_gone} at INFO
     * (e.g. a device logged out by "log out other sessions"), {@code reason=reused} (stolen-cookie detection) and
     * {@code reason=account_disabled} (the login is deleted) at WARN.
     */
    REMEMBER_ME_REJECTED(Level.INFO),
    /** A session reached its maximum length and was ended. Field {@code kind=normal|remember_me}. */
    SESSION_MAX_LENGTH_REACHED(Level.INFO),
    /** All other sessions and remember-me logins of the user were ended. Field {@code trigger=button|password_change}. */
    OTHER_SESSIONS_LOGGED_OUT(Level.INFO),
    /** A confirmation email was handed to the mail server. Field {@code trigger=register|resend}. */
    EMAIL_VERIFICATION_SENT(Level.INFO),
    /** An email address was confirmed with a link. */
    EMAIL_VERIFIED(Level.INFO),
    /**
     * Forgot password, one line per accepted request, with the email as typed (normalized). Field
     * {@code outcome=sent|unknown_email|account_disabled|account_limit|failed}; written when the background job
     * finishes.
     */
    PASSWORD_RESET_REQUESTED(Level.INFO),
    /**
     * A list invitation was created (0.13.0), one line per invitation with the invited address, written when the
     * background email job finishes. Field {@code outcome=sent|no_account|account_disabled|account_limit|failed}
     * (server-side only: the API answers the same for all).
     */
    LIST_INVITATION_EMAIL(Level.INFO),
    /** A password was reset with an emailed link; all sessions and remember-me logins of the account ended. */
    PASSWORD_RESET_COMPLETED(Level.INFO),
    /**
     * An emailed link token was refused (the API answers TOKEN_INVALID for all of them). Field
     * {@code reason=missing|malformed|unknown|wrong_purpose|used|expired|account_disabled}. Never contains the token.
     */
    EMAIL_TOKEN_REJECTED(Level.WARN),
    /**
     * An email could not be sent. Fields {@code kind=verify_email|reset_password|list_invitation} and
     * {@code reason=smtp|queue_full|error}.
     */
    EMAIL_SEND_FAILED(Level.WARN),
    /**
     * Signed in with Google (existing, linked or new account); written after {@code GOOGLE_ACCOUNT_CREATED} or
     * {@code GOOGLE_LINKED} when those happened.
     */
    GOOGLE_LOGIN_SUCCESS(Level.INFO),
    /**
     * A Google sign-in or connect ended without success. Fields {@code intent=login|connect|-} and
     * {@code reason=cancelled|provider_error|request_not_found|invalid_state|invalid_request|invalid_token|
     * token_error|provider_unavailable|invalid_intent|no_session|invalid_identity|google_email_unverified|
     * account_email_unconfirmed|other_google_connected|google_in_use|already_connected_other|user_gone|
     * account_disabled|error}.
     * Never contains a code, state, nonce or token.
     */
    GOOGLE_LOGIN_FAILURE(Level.WARN),
    /** An account was created by signing in with Google. */
    GOOGLE_ACCOUNT_CREATED(Level.INFO),
    /** A Google account was connected to a user. Field {@code trigger=login|connect}. */
    GOOGLE_LINKED(Level.INFO),
    /** The user disconnected their Google account. */
    GOOGLE_UNLINKED(Level.INFO),
    /**
     * A session of a disabled account was ended on its next request (the request answers 401 ACCOUNT_DISABLED on
     * protected endpoints). Email = that account.
     */
    ACCOUNT_DISABLED_SESSION_ENDED(Level.WARN),
    /**
     * {@code ADMIN_BOOTSTRAP_EMAIL} made this account the first super admin at startup. Field {@code target_id}.
     * No request (ip and path are {@code -}).
     */
    ADMIN_BOOTSTRAPPED(Level.INFO),
    /*
     * Admin actions (specs/roles-admin.md AC17): fields actor_id (the admin), target_id (the account) and action
     * (disable|enable|revoke_sessions|change_role|delete); email = the admin's. Written after the change committed,
     * only when something changed (an action already in effect is not logged).
     */
    ADMIN_USER_DISABLED(Level.INFO),
    ADMIN_USER_ENABLED(Level.INFO),
    ADMIN_USER_SESSIONS_REVOKED(Level.INFO),
    /** Also fields {@code from} and {@code to} (roles). */
    ADMIN_ROLE_CHANGED(Level.INFO),
    ADMIN_USER_DELETED(Level.INFO),
    /**
     * An admin endpoint refused the caller. Fields actor_id, target_id ({@code -} for the list), action
     * ({@code list} or one of the actions above) and {@code reason=not_admin|self|insufficient_role|
     * target_super_admin|target_not_found}. {@code not_admin} replaces ACCESS_DENIED on {@code /api/admin/**}.
     */
    ADMIN_ACCESS_DENIED(Level.WARN);

    private final Level level;

    SecurityEvent(Level level) {
        this.level = level;
    }

    public Level level() {
        return level;
    }
}
