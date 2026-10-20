package com.taskapp.notification;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.MapBindingResult;

import com.taskapp.auth.InvalidFieldsException;
import com.taskapp.reminder.ReminderTimes;

/**
 * The signed-in user's own notifications (contract 0.14.0). Every query filters by the user id from the session, so
 * another user's notification is indistinguishable from a missing one. {@code taskId} / {@code listId} come from a
 * join on the task and the caller's membership of its list: null when the task is gone or access was lost.
 */
@Service
public class NotificationService {

    static final int DEFAULT_LIMIT = 20;
    static final int MAX_LIMIT = 50;
    static final String LIMIT_MESSAGE = "Limit must be between 1 and 50";
    static final String CURSOR_MESSAGE = "The list changed, reload from the first page";

    private static final String SELECT = """
            SELECT n.id, n.type, n.read_at IS NOT NULL AS is_read, n.created_at, n.late, n.title, n.due_date,
                   n.due_time, IF(m.user_id IS NULL, NULL, t.id) AS task_id,
                   IF(m.user_id IS NULL, NULL, t.list_id) AS list_id
            FROM notifications n
            LEFT JOIN tasks t ON t.id = n.task_id
            LEFT JOIN list_members m ON m.list_id = t.list_id AND m.user_id = n.user_id
            """;

    private static final RowMapper<NotificationResponse> MAPPER = (rs, i) -> new NotificationResponse(
            rs.getLong("id"), rs.getString("type"), rs.getBoolean("is_read"),
            ReminderTimes.fromDb(rs.getObject("created_at", LocalDateTime.class)), rs.getBoolean("late"),
            rs.getString("title"), rs.getObject("due_date", LocalDate.class),
            rs.getObject("due_time", LocalTime.class), (Long) rs.getObject("task_id"),
            (Long) rs.getObject("list_id"));

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public NotificationService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * One page, newest first. {@code limitRaw} null = 20; otherwise an integer 1-50, else 400 {@code limit} /
     * {@code OUT_OF_RANGE}. A {@code cursor} that can't be read is 400 {@code cursor} / {@code INVALID_CURSOR}
     * (both are reported together).
     */
    @Transactional(readOnly = true)
    public NotificationPage list(long userId, String limitRaw, String cursorRaw) {
        MapBindingResult errors = new MapBindingResult(new HashMap<>(), "notificationQuery");
        int limit = DEFAULT_LIMIT;
        if (limitRaw != null) {
            try {
                limit = Integer.parseInt(limitRaw.strip());
            } catch (NumberFormatException e) {
                limit = 0;
            }
            if (limit < 1 || limit > MAX_LIMIT) {
                errors.rejectValue("limit", "Min", LIMIT_MESSAGE);
            }
        }
        NotificationCursor after = null;
        if (cursorRaw != null && !cursorRaw.isEmpty()) {
            after = NotificationCursor.decode(cursorRaw).orElse(null);
            if (after == null) {
                errors.rejectValue("cursor", "InvalidCursor", CURSOR_MESSAGE);
            }
        }
        if (errors.hasErrors()) {
            throw new InvalidFieldsException(errors);
        }
        List<NotificationResponse> rows;
        if (after == null) {
            rows = jdbc.query(SELECT + " WHERE n.user_id = ? ORDER BY n.created_at DESC, n.id DESC LIMIT ?", MAPPER,
                    userId, limit + 1);
        } else {
            var at = ReminderTimes.toDb(after.createdAt());
            rows = jdbc.query(SELECT + " WHERE n.user_id = ? AND (n.created_at < ? OR (n.created_at = ? AND n.id < ?))"
                    + " ORDER BY n.created_at DESC, n.id DESC LIMIT ?", MAPPER, userId, at, at, after.id(),
                    limit + 1);
        }
        boolean more = rows.size() > limit;
        List<NotificationResponse> items = more ? rows.subList(0, limit) : rows;
        String next = null;
        if (more) {
            NotificationResponse last = items.get(items.size() - 1);
            next = new NotificationCursor(last.createdAt(), last.id()).encode();
        }
        return new NotificationPage(items, next);
    }

    @Transactional(readOnly = true)
    public NotificationCount count(long userId) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE user_id = ? AND read_at IS NULL",
                Long.class, userId);
        return new NotificationCount(count == null ? 0 : count);
    }

    /** Idempotent: reading a read notification changes nothing. 404 when it is not the caller's. */
    @Transactional
    public NotificationResponse markRead(long userId, long id) {
        Optional<NotificationResponse> found = find(userId, id);
        if (found.isEmpty()) {
            throw new NotificationNotFoundException();
        }
        if (found.get().read()) {
            return found.get();
        }
        jdbc.update("UPDATE notifications SET read_at = ? WHERE id = ? AND user_id = ? AND read_at IS NULL",
                ReminderTimes.toDb(Instant.now(clock)), id, userId);
        return find(userId, id).orElseThrow(NotificationNotFoundException::new);
    }

    @Transactional
    public void markAllRead(long userId) {
        jdbc.update("UPDATE notifications SET read_at = ? WHERE user_id = ? AND read_at IS NULL",
                ReminderTimes.toDb(Instant.now(clock)), userId);
    }

    private Optional<NotificationResponse> find(long userId, long id) {
        return jdbc.query(SELECT + " WHERE n.id = ? AND n.user_id = ?", MAPPER, id, userId).stream().findFirst();
    }
}
