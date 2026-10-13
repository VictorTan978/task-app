package com.taskapp.security.audit;

import org.slf4j.event.Level;

/** Security events written by {@link SecurityEventLogger}. Successes are INFO, failures and rejections WARN. */
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
    ACCESS_DENIED(Level.WARN);

    private final Level level;

    SecurityEvent(Level level) {
        this.level = level;
    }

    public Level level() {
        return level;
    }
}
