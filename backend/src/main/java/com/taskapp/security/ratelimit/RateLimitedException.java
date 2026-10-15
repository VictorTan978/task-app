package com.taskapp.security.ratelimit;

import java.time.Duration;

/** A rate limit was hit. Rendered as 429 {@code RATE_LIMITED} with a {@code Retry-After} header. */
public class RateLimitedException extends RuntimeException {

    /** Which limit was hit; {@link #logValue()} is written to the security log. */
    public enum Limit {
        LOGIN_IP("login-ip"),
        LOGIN_EMAIL("login-email"),
        REGISTER_IP("register-ip"),
        PASSWORD_CHECK_USER("password-check-user"),
        FORGOT_PASSWORD_IP("forgot-password-ip"),
        /** Silent: never turned into a 429, only logged by the background job. */
        RESET_EMAIL_ACCOUNT("reset-email-account"),
        RESEND_VERIFICATION_USER("resend-verification-user"),
        VERIFY_EMAIL_IP("verify-email-ip"),
        CHECK_RESET_TOKEN_IP("check-reset-token-ip"),
        RESET_PASSWORD_IP("reset-password-ip"),
        /** Google flow start; answered with the failure redirect, never a 429. */
        GOOGLE_START_IP("google-start-ip"),
        /** Google flow callback; answered with the failure redirect, never a 429. */
        GOOGLE_CALLBACK_IP("google-callback-ip"),
        /** Admin change requests per acting admin (specs/roles-admin.md AC20). */
        ADMIN_ACTION_USER("admin-action-user");

        private final String logValue;

        Limit(String logValue) {
            this.logValue = logValue;
        }

        public String logValue() {
            return logValue;
        }
    }

    private final Duration retryAfter;
    private final transient Limit limit;
    private final transient String email;

    /**
     * {@code email} is the limited email for {@link Limit#LOGIN_EMAIL}, the signed-in user's email for
     * {@link Limit#PASSWORD_CHECK_USER}, {@link Limit#RESEND_VERIFICATION_USER} and {@link Limit#ADMIN_ACTION_USER},
     * otherwise null (not known yet).
     */
    public RateLimitedException(Duration retryAfter, Limit limit, String email) {
        super("Rate limit exceeded", null, false, false);
        this.retryAfter = retryAfter;
        this.limit = limit;
        this.email = email;
    }

    public Duration retryAfter() {
        return retryAfter;
    }

    public Limit limit() {
        return limit;
    }

    public String email() {
        return email;
    }

    /** {@link #retryAfter()} rounded up to whole seconds, at least 1 (the {@code Retry-After} value). */
    public long retryAfterSeconds() {
        long seconds = retryAfter.getSeconds() + (retryAfter.getNano() > 0 ? 1 : 0);
        return Math.max(1, seconds);
    }
}
