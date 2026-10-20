package com.taskapp.reminder;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * {@code app.reminders.*} (application.yml, specs/due-times-reminders.md).
 *
 * @param enabled      kill switch of the background jobs (the reminder scan and the notification cleanup). Off = no
 *                     reminder fires; everything else works. Default on.
 * @param pollInterval pause between two scans for due reminders (default 60s)
 * @param lateAfter    a reminder that fires more than this after its fire time is flagged late (default 5m)
 * @param batchSize    reminders handled per scan (default 100); a longer backlog is worked off over the next scans
 */
@Validated
@ConfigurationProperties("app.reminders")
public record ReminderProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("60s") @NotNull Duration pollInterval,
        @DefaultValue("5m") @NotNull Duration lateAfter,
        @DefaultValue("100") @Positive int batchSize) {

    public ReminderProperties {
        if (pollInterval != null && pollInterval.isNegative() || pollInterval != null && pollInterval.isZero()) {
            throw new IllegalArgumentException("app.reminders.poll-interval must be positive");
        }
    }
}
