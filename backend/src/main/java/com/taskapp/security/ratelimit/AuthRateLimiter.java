package com.taskapp.security.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;

/**
 * Rate-limit policy for login and register. Every limit decision goes through {@link #enforce}, the single
 * place where a request is refused.
 */
public class AuthRateLimiter {

    private final SlidingWindowLimiter loginFailuresByEmail;
    private final SlidingWindowLimiter loginByIp;
    private final SlidingWindowLimiter registerByIp;

    public AuthRateLimiter(RateLimitProperties properties, Clock clock) {
        int maxKeys = properties.maxTrackedKeys();
        this.loginFailuresByEmail = new SlidingWindowLimiter(properties.loginFailuresPerEmail(),
                properties.loginFailureWindow(), maxKeys, clock);
        this.loginByIp = new SlidingWindowLimiter(properties.loginPerIpPerMinute(), Duration.ofMinutes(1),
                maxKeys, clock);
        this.registerByIp = new SlidingWindowLimiter(properties.registerPerIpPerHour(), Duration.ofHours(1),
                maxKeys, clock);
    }

    /** Counts one login request from {@code clientIp}; throws if over the per-IP limit. */
    public void acquireLogin(String clientIp) {
        enforce(loginByIp.tryAcquire(clientIp), RateLimitedException.Limit.LOGIN_IP, null);
    }

    /** Counts one register request from {@code clientIp}; throws if over the per-IP limit. */
    public void acquireRegister(String clientIp) {
        enforce(registerByIp.tryAcquire(clientIp), RateLimitedException.Limit.REGISTER_IP, null);
    }

    /** Throws if {@code email} (already normalized) has too many recent failed logins. Records nothing. */
    public void checkLoginAllowed(String email) {
        enforce(loginFailuresByEmail.check(email), RateLimitedException.Limit.LOGIN_EMAIL, email);
    }

    public void recordLoginFailure(String email) {
        loginFailuresByEmail.record(email);
    }

    public void recordLoginSuccess(String email) {
        loginFailuresByEmail.reset(email);
    }

    private static void enforce(Optional<Duration> blockedFor, RateLimitedException.Limit limit, String email) {
        if (blockedFor.isPresent()) {
            throw new RateLimitedException(blockedFor.get(), limit, email);
        }
    }
}
