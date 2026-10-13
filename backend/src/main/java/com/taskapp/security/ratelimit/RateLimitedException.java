package com.taskapp.security.ratelimit;

import java.time.Duration;

/** A rate limit was hit. Rendered as 429 {@code RATE_LIMITED} with a {@code Retry-After} header. */
public class RateLimitedException extends RuntimeException {

    /** Which limit was hit; {@link #logValue()} is written to the security log. */
    public enum Limit {
        LOGIN_IP("login-ip"),
        LOGIN_EMAIL("login-email"),
        REGISTER_IP("register-ip"),
        PASSWORD_CHECK_USER("password-check-user");

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
     * {@link Limit#PASSWORD_CHECK_USER}, otherwise null (not known yet).
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
