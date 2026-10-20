package com.taskapp.security.ratelimit;

import java.time.Clock;
import java.time.Duration;

/** Silent per-recipient cap of reminder emails (counted by the reminder scheduler, before it sends). */
public class ReminderEmailLimiter {

    private final SlidingWindowLimiter emailsByAccount;

    public ReminderEmailLimiter(ReminderRateLimitProperties properties, Clock clock) {
        this.emailsByAccount = new SlidingWindowLimiter(properties.reminderEmailsPerAccountPerHour(),
                Duration.ofHours(1), properties.maxTrackedKeys(), clock);
    }

    /** True (and counted) if one more reminder email may be sent to this account. Never throws. */
    public boolean tryAcquire(long userId) {
        return emailsByAccount.tryAcquire(Long.toString(userId)).isEmpty();
    }
}
