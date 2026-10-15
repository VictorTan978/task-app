package com.taskapp.security.ratelimit;

import java.time.Clock;
import java.time.Duration;

/**
 * Per-client-IP limits of the Google flow. These endpoints are browser navigations, so a refusal is not a 429: the
 * caller answers with the flow's failure redirect (contract 0.8.0, "Rate limiting") and logs {@code RATE_LIMITED}.
 */
public class GoogleRateLimiter {

    private final SlidingWindowLimiter startByIp;
    private final SlidingWindowLimiter callbackByIp;

    public GoogleRateLimiter(GoogleRateLimitProperties properties, Clock clock) {
        Duration minute = Duration.ofMinutes(1);
        this.startByIp = new SlidingWindowLimiter(properties.googleStartPerIpPerMinute(), minute,
                properties.maxTrackedKeys(), clock);
        this.callbackByIp = new SlidingWindowLimiter(properties.googleCallbackPerIpPerMinute(), minute,
                properties.maxTrackedKeys(), clock);
    }

    /** Counts one start from {@code clientIp}; true if it is allowed. */
    public boolean tryAcquireStart(String clientIp) {
        return startByIp.tryAcquire(clientIp).isEmpty();
    }

    /** Counts one callback from {@code clientIp}; true if it is allowed. */
    public boolean tryAcquireCallback(String clientIp) {
        return callbackByIp.tryAcquire(clientIp).isEmpty();
    }
}
