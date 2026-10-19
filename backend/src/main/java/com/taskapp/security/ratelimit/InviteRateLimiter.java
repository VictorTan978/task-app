package com.taskapp.security.ratelimit;

import java.time.Clock;
import java.time.Duration;

/**
 * Per-inviter limit of {@code POST /api/lists/{id}/invitations} (429, counted by the invitation service after the
 * request passed its 400 / 404 / 403 / PERSONAL_LIST checks) and the silent per-recipient limit of invitation emails
 * (counted by the background email job).
 */
public class InviteRateLimiter {

    private static final Duration HOUR = Duration.ofHours(1);

    private final SlidingWindowLimiter invitesByUser;
    private final SlidingWindowLimiter inviteEmailsByAccount;

    public InviteRateLimiter(InviteRateLimitProperties properties, Clock clock) {
        this.invitesByUser = new SlidingWindowLimiter(properties.invitesPerUserPerHour(), HOUR,
                properties.maxTrackedKeys(), clock);
        this.inviteEmailsByAccount = new SlidingWindowLimiter(properties.inviteEmailsPerAccountPerHour(), HOUR,
                properties.maxTrackedKeys(), clock);
    }

    /** Counts one invitation by {@code userId}; throws (429) if over the limit. {@code email} is for the log. */
    public void acquireInvite(long userId, String email) {
        invitesByUser.tryAcquire(Long.toString(userId)).ifPresent(blockedFor -> {
            throw new RateLimitedException(blockedFor, RateLimitedException.Limit.INVITE_USER, email);
        });
    }

    /** Silent per-recipient limit: true (and counted) if one more invitation email may be sent. Never throws. */
    public boolean tryAcquireInviteEmail(long recipientUserId) {
        return inviteEmailsByAccount.tryAcquire(Long.toString(recipientUserId)).isEmpty();
    }
}
