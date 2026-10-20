package com.taskapp.reminder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import com.taskapp.email.EmailContent;
import com.taskapp.email.EmailTemplates;
import com.taskapp.email.MailDelivery;
import com.taskapp.security.ratelimit.ReminderEmailLimiter;
import com.taskapp.task.DeadlockRetry;

/**
 * Fires due reminders (specs/due-times-reminders.md AC10-AC24, contract 0.14.0). {@link #runOnce()} is one scan;
 * {@link ReminderScheduler} calls it on a timer, and tests call it directly with a fixed clock.
 * <p>
 * Per due reminder, two steps:
 * <ol>
 * <li><b>Claim</b>, one short transaction. Locks in the order every other writer uses: the user's row FOR SHARE, the
 * task's row FOR SHARE, then the reminder row {@code FOR UPDATE SKIP LOCKED} (so a second instance that picked the
 * same reminder skips it, and the task or account writers, who lock task then reminder, never wait in a cycle). If it
 * is still due and PENDING: a reminder 1 hour or more late, or whose task is gone, Done, or whose user left the list,
 * becomes SKIPPED (nothing is created); otherwise the notification is inserted (once: {@code notified_at} is set in
 * the same transaction) and, when an email is allowed (account active, email verified, "Reminder emails" on), the
 * reminder stays PENDING with {@code next_attempt_at} pushed 5 minutes ahead (the lease: if this instance crashes
 * the email is tried again later, at-least-once) and the attempt count raised. Otherwise it is SENT.</li>
 * <li><b>Send</b>, outside any lock. The hourly cap per account is counted on the first attempt only (silent: over
 * it the email is skipped). Success: SENT. Failure: tried again a minute later, and FAILED after 3 attempts (logged,
 * never the address or the body). The final update only applies if the row is unchanged since the claim, so an edit
 * that re-armed the reminder meanwhile is not overwritten.</li>
 * </ol>
 * Uses the injected {@link Clock} for every decision.
 */
@Component
public class ReminderProcessor {

    /** At or beyond this lateness a reminder is dropped instead of fired (e.g. the server was down). */
    static final Duration SKIP_AFTER = Duration.ofHours(1);
    /** How long a claimed email attempt is reserved before another scan may pick it up again. */
    static final Duration LEASE = Duration.ofMinutes(5);
    static final Duration RETRY_DELAY = Duration.ofMinutes(1);
    static final int MAX_ATTEMPTS = 3;
    static final Duration RETENTION = Duration.ofDays(90);
    static final int PURGE_BATCH = 1000;

    private static final Logger log = LoggerFactory.getLogger(ReminderProcessor.class);

    private final JdbcTemplate jdbc;
    private final TransactionOperations transaction;
    private final MailDelivery delivery;
    private final ReminderEmailLimiter limiter;
    private final ReminderProperties properties;
    private final Clock clock;

    @Autowired
    public ReminderProcessor(JdbcTemplate jdbc, PlatformTransactionManager transactions, MailDelivery delivery,
            ReminderEmailLimiter limiter, ReminderProperties properties, Clock clock) {
        this(jdbc, new TransactionTemplate(transactions), delivery, limiter, properties, clock);
    }

    ReminderProcessor(JdbcTemplate jdbc, TransactionOperations transaction, MailDelivery delivery,
            ReminderEmailLimiter limiter, ReminderProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.delivery = delivery;
        this.limiter = limiter;
        this.properties = properties;
        this.clock = clock;
    }

    private record Candidate(long id, long userId, long taskId) {
    }

    /** What the claim step hands to the send step. */
    private record Outgoing(long reminderId, long userId, Instant fireAt, int attempt, String to,
            EmailContent content) {
    }

    private record Info(String title, LocalDate dueDate, LocalTime dueTime, boolean done, long listId,
            String listName, boolean personal, boolean member, String email, String displayName,
            boolean reminderEmails, boolean disabled, boolean verified) {
    }

    /** One scan: handles up to {@code batchSize} due reminders. Returns how many it looked at. Never throws. */
    public int runOnce() {
        Instant now = clock.instant();
        List<Candidate> due;
        try {
            due = jdbc.query("SELECT id, user_id, task_id FROM reminders WHERE status = 'PENDING'"
                    + " AND next_attempt_at <= ? ORDER BY next_attempt_at, id LIMIT ?",
                    (rs, i) -> new Candidate(rs.getLong(1), rs.getLong(2), rs.getLong(3)),
                    ReminderTimes.toDb(now), properties.batchSize());
        } catch (RuntimeException e) {
            log.error("Reminder scan failed: {}", e.getClass().getSimpleName());
            return 0;
        }
        for (Candidate candidate : due) {
            try {
                Outgoing outgoing = DeadlockRetry.run(() -> transaction.execute(status -> claim(candidate, now)));
                if (outgoing != null) {
                    send(outgoing);
                }
            } catch (RuntimeException e) {
                log.error("Reminder {} could not be processed: {}", candidate.id(), e.getClass().getSimpleName());
            }
        }
        return due.size();
    }

    private Outgoing claim(Candidate candidate, Instant now) {
        if (!exists("SELECT 1 FROM users WHERE id = ? FOR SHARE", candidate.userId())) {
            return null;
        }
        // The task may be gone (then so is the reminder, by cascade); the lock only keeps writers out of the way.
        jdbc.query("SELECT 1 FROM tasks WHERE id = ? FOR SHARE", rs -> {
        }, candidate.taskId());
        record Locked(Instant fireAt, int attempts, boolean notified) {
        }
        Locked reminder = jdbc.query("SELECT fire_at, attempts, notified_at FROM reminders WHERE id = ?"
                + " AND status = 'PENDING' AND next_attempt_at <= ? FOR UPDATE SKIP LOCKED",
                rs -> rs.next() ? new Locked(ReminderTimes.fromDb(rs.getObject(1, LocalDateTime.class)),
                        rs.getInt(2), rs.getObject(3) != null) : null,
                candidate.id(), ReminderTimes.toDb(now));
        if (reminder == null) {
            return null;
        }
        Info info = info(candidate);
        if (!reminder.notified()) {
            Duration lateness = Duration.between(reminder.fireAt(), now);
            if (lateness.compareTo(SKIP_AFTER) >= 0 || info == null || info.done() || !info.member()
                    || info.dueDate() == null) {
                setStatus(candidate.id(), "SKIPPED");
                return null;
            }
            jdbc.update("INSERT INTO notifications (user_id, task_id, type, title, due_date, due_time, late,"
                    + " created_at) VALUES (?, ?, 'REMINDER', ?, ?, ?, ?, ?)", candidate.userId(),
                    candidate.taskId(), info.title(), info.dueDate(), info.dueTime(),
                    lateness.compareTo(properties.lateAfter()) > 0, ReminderTimes.toDb(now));
            jdbc.update("UPDATE reminders SET notified_at = ? WHERE id = ?", ReminderTimes.toDb(now),
                    candidate.id());
            return emailStep(candidate, reminder.fireAt(), reminder.attempts(), info, lateness, now);
        }
        if (reminder.attempts() >= MAX_ATTEMPTS) {
            // An earlier attempt was claimed but never reported back (crash): do not try again.
            setStatus(candidate.id(), "FAILED");
            log.error("Reminder email failed for reminder {} after {} attempts", candidate.id(), reminder.attempts());
            return null;
        }
        if (info == null || !info.member()) {
            setStatus(candidate.id(), "SENT");
            return null;
        }
        return emailStep(candidate, reminder.fireAt(), reminder.attempts(), info,
                Duration.between(reminder.fireAt(), now), now);
    }

    private Outgoing emailStep(Candidate candidate, Instant fireAt, int attempts, Info info, Duration lateness,
            Instant now) {
        if (info.disabled() || !info.verified() || !info.reminderEmails()) {
            setStatus(candidate.id(), "SENT");
            return null;
        }
        String path = info.personal() ? "/tasks" : "/lists/" + info.listId();
        EmailContent content = EmailTemplates.reminder(info.displayName(), info.title(), info.dueDate(),
                info.dueTime(), info.listName(), delivery.page(path), lateness.compareTo(properties.lateAfter()) > 0);
        jdbc.update("UPDATE reminders SET attempts = attempts + 1, next_attempt_at = ? WHERE id = ?",
                ReminderTimes.toDb(now.plus(LEASE)), candidate.id());
        return new Outgoing(candidate.id(), candidate.userId(), fireAt, attempts + 1, info.email(), content);
    }

    private void send(Outgoing out) {
        if (out.attempt() == 1 && !limiter.tryAcquire(out.userId())) {
            finish(out, "SENT");
            return;
        }
        try {
            delivery.send(out.to(), out.content());
            finish(out, "SENT");
        } catch (RuntimeException e) {
            if (out.attempt() >= MAX_ATTEMPTS) {
                finish(out, "FAILED");
                log.error("Reminder email failed for reminder {} after {} attempts: {}", out.reminderId(),
                        out.attempt(), e.getClass().getSimpleName());
            } else {
                log.warn("Reminder email failed for reminder {} (attempt {}), will retry: {}", out.reminderId(),
                        out.attempt(), e.getClass().getSimpleName());
                jdbc.update("UPDATE reminders SET next_attempt_at = ? WHERE id = ? AND status = 'PENDING'"
                        + " AND attempts = ? AND fire_at = ?", ReminderTimes.toDb(clock.instant().plus(RETRY_DELAY)),
                        out.reminderId(), out.attempt(), ReminderTimes.toDb(out.fireAt()));
            }
        }
    }

    /** Final state of the email step; ignored if the reminder was changed or re-armed since the claim. */
    private void finish(Outgoing out, String status) {
        jdbc.update("UPDATE reminders SET status = ? WHERE id = ? AND status = 'PENDING' AND attempts = ?"
                + " AND fire_at = ?", status, out.reminderId(), out.attempt(), ReminderTimes.toDb(out.fireAt()));
    }

    private void setStatus(long id, String status) {
        jdbc.update("UPDATE reminders SET status = ? WHERE id = ?", status, id);
    }

    private boolean exists(String sql, long id) {
        return Boolean.TRUE.equals(jdbc.query(sql, (ResultSetExtractor<Boolean>) rs -> rs.next(), id));
    }

    private Info info(Candidate c) {
        return jdbc.query("SELECT t.title, t.due_date, t.due_time, t.done, t.list_id, l.name, l.personal,"
                + " (m.user_id IS NOT NULL), u.email, u.display_name, u.reminder_emails, u.disabled_at IS NOT NULL,"
                + " u.email_verified_at IS NOT NULL FROM users u JOIN tasks t ON t.id = ?"
                + " JOIN lists l ON l.id = t.list_id"
                + " LEFT JOIN list_members m ON m.list_id = t.list_id AND m.user_id = u.id WHERE u.id = ?",
                rs -> rs.next() ? new Info(rs.getString(1), rs.getObject(2, LocalDate.class),
                        rs.getObject(3, LocalTime.class), rs.getBoolean(4), rs.getLong(5), rs.getString(6),
                        rs.getBoolean(7), rs.getBoolean(8), rs.getString(9), rs.getString(10), rs.getBoolean(11),
                        rs.getBoolean(12), rs.getBoolean(13)) : null,
                c.taskId(), c.userId());
    }

    /** Deletes notifications older than 90 days, a batch at a time. Returns how many. Never throws. */
    public int purgeOldNotifications() {
        try {
            LocalDateTime cutoff = ReminderTimes.toDb(clock.instant().minus(RETENTION));
            int total = 0;
            int deleted;
            do {
                deleted = jdbc.update("DELETE FROM notifications WHERE created_at < ? LIMIT ?", cutoff, PURGE_BATCH);
                total += deleted;
            } while (deleted == PURGE_BATCH);
            return total;
        } catch (RuntimeException e) {
            log.error("Notification cleanup failed: {}", e.getClass().getSimpleName());
            return 0;
        }
    }
}
