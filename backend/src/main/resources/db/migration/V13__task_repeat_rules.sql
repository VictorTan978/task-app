-- Recurring tasks (spec: specs/due-times-reminders.md, T4b). Expand only: three nullable columns, existing rows keep
-- today's meaning (no rule). No index (nothing queries by rule), no backfill.
--
-- repeat_frequency: DAILY, WEEKLY, MONTHLY or YEARLY; NULL = the task does not repeat. utf8mb4_0900_bin is binary AND
--   NO PAD, so the CHECK is exact (V9 lesson).
-- repeat_weekdays: WEEKLY only. Bitmask, MON = 1, TUE = 2, WED = 4, THU = 8, FRI = 16, SAT = 32, SUN = 64 (1..127).
-- repeat_anchor: the due date the rule was last set from. Monthly / yearly occurrences are counted from its day /
--   month and day (clamped to the month's end), so Jan 31 -> Feb 28 -> Mar 31 does not drift.
-- The rule lives on the task that is still to do: completing a repeating task moves it to the copy (all three NULL on
-- the completed one). A rule needs a due date, so clearing the due date must clear the rule in the same statement.
ALTER TABLE tasks
    ADD COLUMN repeat_frequency VARCHAR(8) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL AFTER due_time,
    ADD COLUMN repeat_weekdays  TINYINT UNSIGNED NULL AFTER repeat_frequency,
    ADD COLUMN repeat_anchor    DATE NULL AFTER repeat_weekdays,
    ADD CONSTRAINT ck_tasks_repeat_frequency CHECK (
        repeat_frequency IS NULL OR repeat_frequency IN ('DAILY', 'WEEKLY', 'MONTHLY', 'YEARLY')),
    ADD CONSTRAINT ck_tasks_repeat_shape CHECK (
        (repeat_frequency IS NULL AND repeat_weekdays IS NULL AND repeat_anchor IS NULL)
        OR (repeat_frequency IS NOT NULL AND repeat_anchor IS NOT NULL AND due_date IS NOT NULL
            AND ((repeat_frequency = 'WEEKLY' AND repeat_weekdays IS NOT NULL AND repeat_weekdays BETWEEN 1 AND 127)
                 OR (repeat_frequency <> 'WEEKLY' AND repeat_weekdays IS NULL))));

ANALYZE TABLE tasks;
