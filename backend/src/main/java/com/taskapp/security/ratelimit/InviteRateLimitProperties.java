package com.taskapp.security.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Positive;

/**
 * Limits of list invitations (specs/sharing-team-lists.md AC19, contract 0.13.0), under {@code app.rate-limit} next
 * to {@link RateLimitProperties}, with env-var overrides (see application.yml). Both windows are 1 hour.
 *
 * @param invitesPerUserPerHour         invitations per inviter (user id); over it: 429
 * @param inviteEmailsPerAccountPerHour invitation emails per recipient account; silent: the invitation is still
 *                                      created, only the email is skipped
 * @param maxTrackedKeys                shared with {@link RateLimitProperties#maxTrackedKeys()}
 */
@Validated
@ConfigurationProperties("app.rate-limit")
public record InviteRateLimitProperties(
        @DefaultValue("30") @Positive int invitesPerUserPerHour,
        @DefaultValue("5") @Positive int inviteEmailsPerAccountPerHour,
        @DefaultValue("100000") @Positive int maxTrackedKeys) {
}
