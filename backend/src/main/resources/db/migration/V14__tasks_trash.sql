-- Trash (spec: specs/undo-trash-dnd-bulk.md, T5a). Expand only: nothing is dropped or renamed.
-- deleted_at: when the task was trashed (UTC, DATETIME(6)); NULL = not trashed. Purged 30 days later by the app.
-- deleted_by: who trashed it; SET NULL when that account is deleted ("Deleted by a deleted user").
--   No CHECK may use deleted_by: MySQL refuses CHECKs on columns with FK referential actions (error 3823).
-- ix_tasks_list_deleted (list_id, deleted_at, id): per-list trash queries; the trash listing merges my lists.
-- ix_tasks_deleted (deleted_at, id): the hourly purge (WHERE deleted_at < cutoff ORDER BY deleted_at LIMIT n).
-- ix_tasks_deleted_by: the fk_tasks_deleted_by index (InnoDB would create it implicitly).
-- The live-list indexes (V2, V9, V10) are kept: the live-list queries add "deleted_at IS NULL" as a cheap row filter.
ALTER TABLE tasks
    ADD COLUMN deleted_at DATETIME(6) NULL,
    ADD COLUMN deleted_by BIGINT NULL,
    ADD INDEX ix_tasks_list_deleted (list_id, deleted_at, id),
    ADD INDEX ix_tasks_deleted (deleted_at, id),
    ADD INDEX ix_tasks_deleted_by (deleted_by),
    ADD CONSTRAINT fk_tasks_deleted_by FOREIGN KEY (deleted_by) REFERENCES users (id) ON DELETE SET NULL;

ANALYZE TABLE tasks;
