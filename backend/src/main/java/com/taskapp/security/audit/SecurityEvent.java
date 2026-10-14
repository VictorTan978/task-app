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
    /** Wrong current password on an account endpoint. Field {@code action=change_password|delete_account}. */
    PASSWORD_CHECK_FAILURE(Level.WARN),
    /** Field {@code limit=login-ip|login-email|register-ip|password-check-user}. */
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
    OTHER_SESSIONS_LOGGED_OUT(Level.INFO);

    private final Level level;

    SecurityEvent(Level level) {
        this.level = level;
    }

    public Level level() {
        return level;
    }
}
