package com.taskapp.security.ratelimit;

import java.time.Clock;
import java.time.Duration;

/**
 * Per-admin limit of the admin change endpoints (specs/roles-admin.md AC20): every change request counts, including
 * the ones then refused (403/404/409). The list ({@code GET /api/admin/users}) is not limited.
 */
public class AdminRateLimiter {

    private final SlidingWindowLimiter actionsByUser;

    public AdminRateLimiter(AdminRateLimitProperties properties, Clock clock) {
        this.actionsByUser = new SlidingWindowLimiter(properties.adminActionsPerUserPerMinute(), Duration.ofMinutes(1),
                properties.maxTrackedKeys(), clock);
    }

    /** Counts one admin action by {@code adminId}; throws (429) if over the limit. {@code email} is for the log. */
    public void acquireAction(long adminId, String email) {
        actionsByUser.tryAcquire(Long.toString(adminId)).ifPresent(blockedFor -> {
            throw new RateLimitedException(blockedFor, RateLimitedException.Limit.ADMIN_ACTION_USER, email);
        });
    }
}
