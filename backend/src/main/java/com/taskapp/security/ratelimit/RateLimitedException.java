package com.taskapp.security.ratelimit;

import java.time.Duration;

/** A rate limit was hit. Rendered as 429 {@code RATE_LIMITED} with a {@code Retry-After} header. */
public class RateLimitedException extends RuntimeException {

    private final Duration retryAfter;

    public RateLimitedException(Duration retryAfter) {
        super("Rate limit exceeded", null, false, false);
        this.retryAfter = retryAfter;
    }

    public Duration retryAfter() {
        return retryAfter;
    }

    /** {@link #retryAfter()} rounded up to whole seconds, at least 1 (the {@code Retry-After} value). */
    public long retryAfterSeconds() {
        long seconds = retryAfter.getSeconds() + (retryAfter.getNano() > 0 ? 1 : 0);
        return Math.max(1, seconds);
    }
}
