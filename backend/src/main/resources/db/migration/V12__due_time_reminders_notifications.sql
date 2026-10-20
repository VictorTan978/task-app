-- Due times, reminders and in-app notifications (spec: specs/due-times-reminders.md, T4a). Expand only: nothing is
-- dropped or renamed, and existing rows keep today's meaning (no zone yet, reminder emails on, no due time).
--
-- status / reminder_offset / type: utf8mb4_0900_bin is binary AND NO PAD, so the CHECKs are exact (V9 lesson).
-- Timestamps are DATETIME(6) holding UTC (the JDBC connection is pinned to UTC), like every other table.

-- users.time_zone: IANA region id (e.g. Europe/Bucharest); NULL until the browser or the user sets it (the app then
--   computes in UTC). The app validates the id against the JDK's region ids; the CHECK only bounds the length.
-- users.reminder_emails: "Reminder emails" toggle in Account, default on.
ALTER TABLE users
    ADD COLUMN time_zone       VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    ADD COLUMN reminder_emails BOOLEAN NOT NULL DEFAULT TRUE,
    ADD CONSTRAINT ck_users_time_zone_length CHECK (time_zone IS NULL OR char_length(time_zone) BETWEEN 1 AND 64);

-- tasks.due_time: wall-clock time of the due date ("09:30" in each member's own zone); only with a due date.
ALTER TABLE tasks
    ADD COLUMN due_time TIME NULL AFTER due_date,
    ADD CONSTRAINT ck_tasks_due_time_needs_date CHECK (due_time IS NULL OR due_date IS NOT NULL);

-- reminders: at most one per user per task (uk_reminders_task_user, also the task_id FK index). Deleted with the
--   task or the user.
--   fire_at: the instant it fires (due date + time - offset, in the user's zone; UTC while they have none).
--   status: PENDING (still to process: a first firing, or an email to retry), SENT (done), SKIPPED (never fires or
--   was dropped: past when saved, task done / deleted / no longer a member, 1 h or more late), FAILED (email failed
--   3 times; the notification exists).
--   next_attempt_at: when the scheduler may pick it up. It starts as fire_at; a claim pushes it forward (the lease),
--   so a crashed claim is picked up again later. attempts: email attempts made. notified_at: when the notification
--   was created (it is created once, in the claim transaction).
--   ix_reminders_claim serves the scheduler's "due and PENDING" scan; ix_reminders_user serves "my reminders of
--   these tasks" and the user_id FK.
CREATE TABLE reminders (
    id              BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    task_id         BIGINT       NOT NULL,
    user_id         BIGINT       NOT NULL,
    reminder_offset VARCHAR(16)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    fire_at         DATETIME(6)  NOT NULL,
    status          VARCHAR(16)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL DEFAULT 'PENDING',
    attempts        SMALLINT     NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6)  NOT NULL,
    notified_at     DATETIME(6)  NULL,
    created_at      DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT uk_reminders_task_user UNIQUE (task_id, user_id),
    INDEX ix_reminders_claim (status, next_attempt_at),
    INDEX ix_reminders_user (user_id, task_id),
    CONSTRAINT fk_reminders_task FOREIGN KEY (task_id) REFERENCES tasks (id) ON DELETE CASCADE,
    CONSTRAINT fk_reminders_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_reminders_offset CHECK (reminder_offset IN ('AT_DUE', 'TEN_MINUTES', 'ONE_HOUR', 'ONE_DAY', 'ON_THE_DAY')),
    CONSTRAINT ck_reminders_status CHECK (status IN ('PENDING', 'SENT', 'SKIPPED', 'FAILED')),
    CONSTRAINT ck_reminders_attempts CHECK (attempts >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- notifications: one per reminder that fired. title / due_date / due_time are a snapshot taken when it fired.
--   task_id: SET NULL when the task is deleted ("This task is no longer available"); deleted with the user.
--   read_at: NULL = unread. Kept 90 days (hourly cleanup in the app).
--   ix_notifications_user_created serves the list (newest first); ix_notifications_user_unread the unread count;
--   ix_notifications_created the retention delete; ix_notifications_task the task_id FK.
CREATE TABLE notifications (
    id         BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id    BIGINT       NOT NULL,
    task_id    BIGINT       NULL,
    type       VARCHAR(16)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL DEFAULT 'REMINDER',
    title      VARCHAR(200) NOT NULL,
    due_date   DATE         NOT NULL,
    due_time   TIME         NULL,
    late       BOOLEAN      NOT NULL DEFAULT FALSE,
    read_at    DATETIME(6)  NULL,
    created_at DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    INDEX ix_notifications_user_created (user_id, created_at DESC, id DESC),
    INDEX ix_notifications_user_unread (user_id, read_at),
    INDEX ix_notifications_created (created_at),
    INDEX ix_notifications_task (task_id),
    CONSTRAINT fk_notifications_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_notifications_task FOREIGN KEY (task_id) REFERENCES tasks (id) ON DELETE SET NULL,
    CONSTRAINT ck_notifications_type CHECK (type IN ('REMINDER')),
    CONSTRAINT ck_notifications_title_not_empty CHECK (char_length(title) > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

ANALYZE TABLE users, tasks, reminders, notifications;
