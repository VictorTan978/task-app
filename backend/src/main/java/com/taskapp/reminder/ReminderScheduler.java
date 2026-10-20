package com.taskapp.reminder;

import java.time.Duration;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Component;

/**
 * The timer: scans for due reminders every {@code app.reminders.poll-interval} and deletes old notifications every
 * hour. Exists only while {@code app.reminders.enabled} is true (the default); tests turn it off and call
 * {@link ReminderProcessor} directly.
 */
@Component
@ConditionalOnProperty(prefix = "app.reminders", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ReminderScheduler implements SchedulingConfigurer {

    private static final Duration CLEANUP_EVERY = Duration.ofHours(1);
    private static final Duration CLEANUP_FIRST_AFTER = Duration.ofMinutes(1);

    private final ReminderProcessor processor;
    private final ReminderProperties properties;

    public ReminderScheduler(ReminderProcessor processor, ReminderProperties properties) {
        this.processor = processor;
        this.properties = properties;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addFixedDelayTask(new FixedDelayTask(processor::runOnce, properties.pollInterval(),
                properties.pollInterval()));
        registrar.addFixedDelayTask(new FixedDelayTask(processor::purgeOldNotifications, CLEANUP_EVERY,
                CLEANUP_FIRST_AFTER));
    }
}
