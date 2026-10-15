package com.taskapp.security.audit;

import org.slf4j.event.Level;

/**
 * Security events written by {@link SecurityEventLogger}. Successes are INFO, failures and rejections WARN, except
 * the session events below, which are normal expiries (INFO) unless stated otherwise.
 */
public enum SecurityEvent {
    LOGIN_SUCCESS(Level.INFO),
    /** Field {@code reason=unknown_email|bad_password} (server-side only; the API answers the same for both). */
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
     * google-start-ip|google-callback-ip}.
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
     * (e.g. a device logged out by "log out other sessions"), {@code reason=reused} (stolen-cookie detection) at WARN.
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
     * {@code outcome=sent|unknown_email|account_limit|failed}; written when the background job finishes.
     */
    PASSWORD_RESET_REQUESTED(Level.INFO),
    /** A password was reset with an emailed link; all sessions and remember-me logins of the account ended. */
    PASSWORD_RESET_COMPLETED(Level.INFO),
    /**
     * An emailed link token was refused (the API answers TOKEN_INVALID for all of them). Field
     * {@code reason=missing|malformed|unknown|wrong_purpose|used|expired}. Never contains the token.
     */
    EMAIL_TOKEN_REJECTED(Level.WARN),
    /**
     * An email could not be sent. Fields {@code kind=verify_email|reset_password} and
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
     * account_email_unconfirmed|other_google_connected|google_in_use|already_connected_other|user_gone|error}.
     * Never contains a code, state, nonce or token.
     */
    GOOGLE_LOGIN_FAILURE(Level.WARN),
    /** An account was created by signing in with Google. */
    GOOGLE_ACCOUNT_CREATED(Level.INFO),
    /** A Google account was connected to a user. Field {@code trigger=login|connect}. */
    GOOGLE_LINKED(Level.INFO),
    /** The user disconnected their Google account. */
    GOOGLE_UNLINKED(Level.INFO);

    private final Level level;

    SecurityEvent(Level level) {
        this.level = level;
    }

    public Level level() {
        return level;
    }
}
