package com.taskapp.security.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Positive;

/**
 * Limit of reminder emails (specs/due-times-reminders.md AC14, contract 0.14.0), under {@code app.rate-limit} next to
 * {@link RateLimitProperties}, with an env-var override (see application.yml). The window is 1 hour.
 *
 * @param reminderEmailsPerAccountPerHour reminder emails per recipient account, per app instance; silent: the
 *                                        in-app notification is still created, only the email is skipped
 * @param maxTrackedKeys                  shared with {@link RateLimitProperties#maxTrackedKeys()}
 */
@Validated
@ConfigurationProperties("app.rate-limit")
public record ReminderRateLimitProperties(
        @DefaultValue("20") @Positive int reminderEmailsPerAccountPerHour,
        @DefaultValue("100000") @Positive int maxTrackedKeys) {
}
