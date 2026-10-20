package com.taskapp.reminder;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.taskapp.task.Task;

/**
 * The task side of reminders (contract 0.14.0): one reminder per user per task, with the fire time computed in the
 * owner's zone ({@link FireTimes}). Every method that writes runs inside the caller's transaction. Lock order:
 * the task row first (the caller has just updated it), then this task's reminder rows in ascending id; the zone
 * change locks the user's reminder rows in ascending id (callers wrap it in a deadlock retry). The scheduler locks
 * user, task, reminder in this order too (see {@link ReminderProcessor}).
 * <p>
 * A reminder whose fire time is in the future is (re)armed: PENDING, attempts 0, not notified. One whose new fire
 * time is not in the future never fires: a PENDING one that has not notified yet becomes SKIPPED; one that already
 * fired keeps its state. Uses the injected {@link Clock}.
 */
@Service
public class ReminderService {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public ReminderService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** My reminder on a task, and whether its fire time has passed. */
    public record Mine(ReminderOffset offset, boolean passed) {
    }

    /** My reminders on these tasks (one query); tasks without one are absent. */
    public Map<Long, Mine> mine(long userId, Collection<Long> taskIds) {
        Map<Long, Mine> result = new HashMap<>();
        if (taskIds.isEmpty()) {
            return result;
        }
        Instant now = clock.instant();
        String placeholders = String.join(",", taskIds.stream().map(id -> "?").toList());
        List<Object> args = new ArrayList<>();
        args.add(userId);
        args.addAll(taskIds);
        jdbc.query("SELECT task_id, reminder_offset, fire_at FROM reminders WHERE user_id = ? AND task_id IN ("
                + placeholders + ")", rs -> {
                    Instant fireAt = ReminderTimes.fromDb(rs.getObject("fire_at", LocalDateTime.class));
                    result.put(rs.getLong("task_id"), new Mine(ReminderOffset.valueOf(rs.getString("reminder_offset")),
                            !fireAt.isAfter(now)));
                }, args.toArray());
        return result;
    }

    private record Row(long id, long userId, ReminderOffset offset, Instant fireAt, String status,
            boolean notified) {
    }

    /**
     * Brings the task's reminders in line with its (already saved) due date and time. {@code requested} is the
     * caller's {@code reminder} request value: null = keep mine, {@code NONE} = remove mine, an offset = set mine
     * (the caller has checked that it fits). Clearing the due date removes everyone's reminders; an offset that no
     * longer fits (timed vs date-only) is removed; every remaining one is recomputed in its owner's zone.
     */
    public void afterTaskSaved(Task task, long callerId, String requested) {
        if (task.getDueDate() == null) {
            jdbc.update("DELETE FROM reminders WHERE task_id = ?", task.getId());
            return;
        }
        Instant now = clock.instant();
        boolean hasTime = task.getDueTime() != null;
        List<Row> rows = jdbc.query("SELECT id, user_id, reminder_offset, fire_at, status, notified_at"
                + " FROM reminders WHERE task_id = ? ORDER BY id FOR UPDATE", (rs, i) -> new Row(rs.getLong("id"),
                        rs.getLong("user_id"), ReminderOffset.valueOf(rs.getString("reminder_offset")),
                        ReminderTimes.fromDb(rs.getObject("fire_at", LocalDateTime.class)), rs.getString("status"),
                        rs.getObject("notified_at") != null), task.getId());
        List<Long> userIds = new ArrayList<>(rows.stream().map(Row::userId).toList());
        if (!userIds.contains(callerId)) {
            userIds.add(callerId);
        }
        Map<Long, String> zones = zones(userIds);
        ReminderOffset requestedOffset = ReminderOffset.parse(requested).orElse(null);
        boolean callerHasRow = false;
        for (Row row : rows) {
            ReminderOffset offset = row.offset();
            if (row.userId() == callerId) {
                callerHasRow = true;
                if (ReminderOffset.NONE.equals(requested)) {
                    deleteRow(row.id());
                    continue;
                }
                if (requestedOffset != null) {
                    offset = requestedOffset;
                }
            }
            if (!offset.fits(hasTime)) {
                deleteRow(row.id());
                continue;
            }
            Instant fireAt = FireTimes.fireAt(task.getDueDate(), task.getDueTime(), offset,
                    FireTimes.zoneOrUtc(zones.get(row.userId())));
            rearm(row, offset, fireAt, now);
        }
        if (!callerHasRow && requestedOffset != null && requestedOffset.fits(hasTime)) {
            Instant fireAt = FireTimes.fireAt(task.getDueDate(), task.getDueTime(), requestedOffset,
                    FireTimes.zoneOrUtc(zones.get(callerId)));
            boolean future = fireAt.isAfter(now);
            jdbc.update("INSERT INTO reminders (task_id, user_id, reminder_offset, fire_at, status, attempts,"
                    + " next_attempt_at) VALUES (?, ?, ?, ?, ?, 0, ?)", task.getId(), callerId,
                    requestedOffset.name(), ReminderTimes.toDb(fireAt), future ? "PENDING" : "SKIPPED",
                    ReminderTimes.toDb(fireAt));
        }
    }

    /** The user's zone changed: recompute the fire time of all their reminders. Call inside a transaction. */
    public void zoneChanged(long userId) {
        Instant now = clock.instant();
        // Lock first, read after: the read then sees what the transactions we waited for committed.
        jdbc.queryForList("SELECT id FROM reminders WHERE user_id = ? ORDER BY id FOR UPDATE", Long.class, userId);
        String zone = jdbc.query("SELECT time_zone FROM users WHERE id = ?",
                rs -> rs.next() ? rs.getString(1) : null, userId);
        record Joined(Row row, LocalDate dueDate, LocalTime dueTime) {
        }
        List<Joined> joined = jdbc.query("SELECT r.id, r.user_id, r.reminder_offset, r.fire_at, r.status,"
                + " r.notified_at, t.due_date, t.due_time FROM reminders r JOIN tasks t ON t.id = r.task_id"
                + " WHERE r.user_id = ? ORDER BY r.id", (rs, i) -> new Joined(new Row(rs.getLong("id"),
                        rs.getLong("user_id"), ReminderOffset.valueOf(rs.getString("reminder_offset")),
                        ReminderTimes.fromDb(rs.getObject("fire_at", LocalDateTime.class)), rs.getString("status"),
                        rs.getObject("notified_at") != null), rs.getObject("due_date", LocalDate.class),
                        rs.getObject("due_time", LocalTime.class)), userId);
        for (Joined j : joined) {
            if (j.dueDate() == null || !j.row().offset().fits(j.dueTime() != null)) {
                deleteRow(j.row().id());
                continue;
            }
            rearm(j.row(), j.row().offset(), FireTimes.fireAt(j.dueDate(), j.dueTime(), j.row().offset(),
                    FireTimes.zoneOrUtc(zone)), now);
        }
    }

    private void rearm(Row row, ReminderOffset offset, Instant fireAt, Instant now) {
        if (fireAt.equals(row.fireAt()) && offset == row.offset()) {
            return;
        }
        if (fireAt.isAfter(now)) {
            jdbc.update("UPDATE reminders SET reminder_offset = ?, fire_at = ?, status = 'PENDING', attempts = 0,"
                    + " next_attempt_at = ?, notified_at = NULL WHERE id = ?", offset.name(),
                    ReminderTimes.toDb(fireAt), ReminderTimes.toDb(fireAt), row.id());
            return;
        }
        // Never fires: a PENDING one that has not notified yet is dropped; one that already fired keeps its state.
        String status = "PENDING".equals(row.status()) && !row.notified() ? "SKIPPED" : row.status();
        jdbc.update("UPDATE reminders SET reminder_offset = ?, fire_at = ?, status = ? WHERE id = ?",
                offset.name(), ReminderTimes.toDb(fireAt), status, row.id());
    }

    private void deleteRow(long id) {
        jdbc.update("DELETE FROM reminders WHERE id = ?", id);
    }

    private Map<Long, String> zones(Collection<Long> userIds) {
        Map<Long, String> zones = new HashMap<>();
        String placeholders = String.join(",", userIds.stream().map(id -> "?").toList());
        jdbc.query("SELECT id, time_zone FROM users WHERE id IN (" + placeholders + ")",
                rs -> {
                    zones.put(rs.getLong(1), rs.getString(2));
                }, userIds.toArray());
        return zones;
    }
}
