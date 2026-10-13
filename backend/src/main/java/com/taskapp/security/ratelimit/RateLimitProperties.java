package com.taskapp.security.ratelimit;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Limits for {@code POST /api/auth/login} and {@code /register}, and for the current-password checks of
 * {@code PUT /api/account/password} and {@code POST /api/account/delete}. Set in {@code application.yml} under
 * {@code app.rate-limit}, each with an env-var override (see there).
 *
 * @param loginFailuresPerEmail failed logins allowed per email within {@code loginFailureWindow}
 * @param loginFailureWindow    sliding window for failed logins per email
 * @param loginPerIpPerMinute   login requests allowed per client IP per minute
 * @param registerPerIpPerHour  register requests allowed per client IP per hour
 * @param passwordCheckFailuresPerUser wrong current passwords allowed per signed-in user within
 *                              {@code passwordCheckFailureWindow} (one counter for both account endpoints)
 * @param passwordCheckFailureWindow sliding window for wrong current passwords per user
 * @param maxTrackedKeys        per counter, the most emails/IPs kept in memory before the least recently
 *                              seen ones are dropped
 */
@Validated
@ConfigurationProperties("app.rate-limit")
public record RateLimitProperties(
        @DefaultValue("5") @Positive int loginFailuresPerEmail,
        @DefaultValue("15m") @NotNull Duration loginFailureWindow,
        @DefaultValue("20") @Positive int loginPerIpPerMinute,
        @DefaultValue("5") @Positive int registerPerIpPerHour,
        @DefaultValue("100000") @Positive int maxTrackedKeys,
        @DefaultValue("5") @Positive int passwordCheckFailuresPerUser,
        @DefaultValue("15m") @NotNull Duration passwordCheckFailureWindow) {
}
