package com.taskapp.task;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Deletes tasks that have been in the trash for 30 days, hourly (contract 0.16.0, AC5), using the scheduling that
 * {@code ReminderConfig} turns on. Exists while {@code app.trash.purge-enabled} is true (the default); tests turn it
 * off and call {@link TrashService#purgeExpired()} with a fixed clock.
 */
@Component
@ConditionalOnProperty(prefix = "app.trash", name = "purge-enabled", havingValue = "true", matchIfMissing = true)
public class TrashPurgeScheduler {

    private static final Logger log = LoggerFactory.getLogger(TrashPurgeScheduler.class);

    private final TrashService trash;

    public TrashPurgeScheduler(TrashService trash) {
        this.trash = trash;
    }

    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT2M")
    void purge() {
        try {
            int deleted = trash.purgeExpired();
            if (deleted > 0) {
                log.info("Trash purge deleted {} tasks", deleted);
            }
        } catch (RuntimeException e) {
            log.error("Trash purge failed: {}", e.getClass().getSimpleName());
        }
    }
}
