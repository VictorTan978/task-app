package com.taskapp.security.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Positive;

/**
 * Limits of the email endpoints (specs/email-auth.md), under {@code app.rate-limit} next to
 * {@link RateLimitProperties}, each with an env-var override (see application.yml). All windows are 1 hour.
 *
 * @param forgotPasswordPerIpPerHour        forgot-password requests per client IP (429)
 * @param resetEmailsPerAccountPerHour      reset emails per account (silent: still 202, nothing sent)
 * @param resendVerificationPerUserPerHour  confirmation emails queued by resend per signed-in user (429)
 * @param verifyEmailPerIpPerHour           verify-email requests per client IP (429)
 * @param checkResetTokenPerIpPerHour       check-reset-token requests per client IP (429)
 * @param resetPasswordPerIpPerHour         reset-password requests per client IP (429)
 * @param maxTrackedKeys                    shared with {@link RateLimitProperties#maxTrackedKeys()}
 */
@Validated
@ConfigurationProperties("app.rate-limit")
public record EmailRateLimitProperties(
        @DefaultValue("10") @Positive int forgotPasswordPerIpPerHour,
        @DefaultValue("3") @Positive int resetEmailsPerAccountPerHour,
        @DefaultValue("3") @Positive int resendVerificationPerUserPerHour,
        @DefaultValue("30") @Positive int verifyEmailPerIpPerHour,
        @DefaultValue("30") @Positive int checkResetTokenPerIpPerHour,
        @DefaultValue("20") @Positive int resetPasswordPerIpPerHour,
        @DefaultValue("100000") @Positive int maxTrackedKeys) {
}
