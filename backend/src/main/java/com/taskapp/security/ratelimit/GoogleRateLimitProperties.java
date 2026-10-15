package com.taskapp.security.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Positive;

/**
 * Limits of the Google sign-in endpoints (specs/google-sign-in.md AC19), under {@code app.rate-limit} next to
 * {@link RateLimitProperties}, each with an env-var override (see application.yml). Both windows are 1 minute.
 *
 * @param googleStartPerIpPerMinute    starts ({@code POST /api/auth/oauth2/authorization/google}) per client IP
 * @param googleCallbackPerIpPerMinute callbacks ({@code GET /api/auth/oauth2/callback/google}) per client IP
 * @param maxTrackedKeys               shared with {@link RateLimitProperties#maxTrackedKeys()}
 */
@Validated
@ConfigurationProperties("app.rate-limit")
public record GoogleRateLimitProperties(
        @DefaultValue("20") @Positive int googleStartPerIpPerMinute,
        @DefaultValue("20") @Positive int googleCallbackPerIpPerMinute,
        @DefaultValue("100000") @Positive int maxTrackedKeys) {
}
