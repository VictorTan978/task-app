package com.taskapp.security.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Positive;

/**
 * Limit of the admin change endpoints (specs/roles-admin.md AC20), under {@code app.rate-limit} next to
 * {@link RateLimitProperties}, with an env-var override (see application.yml).
 *
 * @param adminActionsPerUserPerMinute change requests per acting admin (user id) per minute
 * @param maxTrackedKeys               shared with {@link RateLimitProperties#maxTrackedKeys()}
 */
@Validated
@ConfigurationProperties("app.rate-limit")
public record AdminRateLimitProperties(
        @DefaultValue("60") @Positive int adminActionsPerUserPerMinute,
        @DefaultValue("100000") @Positive int maxTrackedKeys) {
}
