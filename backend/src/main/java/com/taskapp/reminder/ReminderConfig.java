package com.taskapp.reminder;

import java.time.Clock;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.taskapp.security.ratelimit.ReminderEmailLimiter;
import com.taskapp.security.ratelimit.ReminderRateLimitProperties;

/**
 * Turns on Spring's built-in scheduling (no new dependency) and binds the reminder settings. What runs is decided by
 * {@link ReminderScheduler}, which only exists while {@code app.reminders.enabled} is true.
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties({ReminderProperties.class, ReminderRateLimitProperties.class})
public class ReminderConfig {

    /** Silent cap of reminder emails per recipient account (per app instance), on the injected clock. */
    @Bean
    ReminderEmailLimiter reminderEmailLimiter(ReminderRateLimitProperties properties, Clock clock) {
        return new ReminderEmailLimiter(properties, clock);
    }
}
