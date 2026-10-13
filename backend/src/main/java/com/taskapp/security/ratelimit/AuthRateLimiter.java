package com.taskapp.security.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;

/**
 * Rate-limit policy for login, register and the current-password checks of the account endpoints. Every limit decision goes through {@link #enforce}, the single
 * place where a request is refused.
 */
public class AuthRateLimiter {

    private final SlidingWindowLimiter loginFailuresByEmail;
    private final SlidingWindowLimiter loginByIp;
    private final SlidingWindowLimiter registerByIp;
    /** Keyed by user id; shared by change password and delete account. */
    private final SlidingWindowLimiter passwordCheckFailuresByUser;

    public AuthRateLimiter(RateLimitProperties properties, Clock clock) {
        int maxKeys = properties.maxTrackedKeys();
        this.loginFailuresByEmail = new SlidingWindowLimiter(properties.loginFailuresPerEmail(),
                properties.loginFailureWindow(), maxKeys, clock);
        this.loginByIp = new SlidingWindowLimiter(properties.loginPerIpPerMinute(), Duration.ofMinutes(1),
                maxKeys, clock);
        this.registerByIp = new SlidingWindowLimiter(properties.registerPerIpPerHour(), Duration.ofHours(1),
                maxKeys, clock);
        this.passwordCheckFailuresByUser = new SlidingWindowLimiter(properties.passwordCheckFailuresPerUser(),
                properties.passwordCheckFailureWindow(), maxKeys, clock);
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

    /**
     * Throws if user {@code userId} has too many recent wrong current passwords. Records nothing.
     * {@code email} is only for the security log.
     */
    public void checkPasswordCheckAllowed(long userId, String email) {
        enforce(passwordCheckFailuresByUser.check(userKey(userId)), RateLimitedException.Limit.PASSWORD_CHECK_USER,
                email);
    }

    public void recordPasswordCheckFailure(long userId) {
        passwordCheckFailuresByUser.record(userKey(userId));
    }

    public void recordPasswordCheckSuccess(long userId) {
        passwordCheckFailuresByUser.reset(userKey(userId));
    }

    private static String userKey(long userId) {
        return Long.toString(userId);
    }

    private static void enforce(Optional<Duration> blockedFor, RateLimitedException.Limit limit, String email) {
        if (blockedFor.isPresent()) {
            throw new RateLimitedException(blockedFor.get(), limit, email);
        }
    }
}
