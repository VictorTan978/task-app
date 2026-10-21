package com.taskapp.task;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.taskapp.reminder.FireTimes;
import com.taskapp.reminder.ReminderService;

/**
 * Creates the next task of a completed repeating task (contract 0.15.0). Runs inside the completing write's
 * transaction, which holds the completed task's row lock (see {@link TaskService}): the decision to copy was taken on
 * the committed status, so a concurrent second completion finds the task already done and creates nothing.
 */
@Service
public class RecurrenceService {

    private final TaskRepository tasks;
    private final TagService tags;
    private final ReminderService reminders;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public RecurrenceService(TaskRepository tasks, TagService tags, ReminderService reminders, JdbcTemplate jdbc,
            Clock clock) {
        this.tasks = tasks;
        this.tags = tags;
        this.reminders = reminders;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** "Today" for the completer: their account zone, UTC when they have none. */
    LocalDate today(long userId) {
        String zone = jdbc.query("SELECT time_zone FROM users WHERE id = ?", rs -> rs.next() ? rs.getString(1) : null,
                userId);
        ZoneId id = FireTimes.zoneOrUtc(zone);
        return LocalDate.ofInstant(clock.instant(), id);
    }

    /**
     * Saves the next task of {@code completed} (already saved, with its post-update values, in its final list) and
     * copies its tags and its members' reminders. {@code rule} and {@code anchor} are the ones the completed task had
     * before they moved to the copy. {@code addedBy} is the completer.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Task createNext(long addedBy, Task completed, RepeatRule rule, LocalDate anchor, Instant now) {
        LocalDate due = rule.next(anchor, completed.getDueDate(), today(addedBy));
        Task copy = completed.nextOccurrence(addedBy, due, rule, anchor, now);
        // "My order" (0.18.0): the copy goes to the top of the list, like any new task.
        copy.positionedAt(Task.above(tasks.minLivePosition(completed.getListId())));
        Task next = tasks.saveAndFlush(copy);
        tags.copyTags(completed.getId(), next.getId());
        reminders.copyReminders(completed, next);
        return next;
    }
}
