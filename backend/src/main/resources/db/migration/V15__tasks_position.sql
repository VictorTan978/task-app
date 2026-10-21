-- "My order" (spec: specs/undo-trash-dnd-bulk.md, T5c). Expand only: nothing is dropped or renamed.
-- position: hidden, list-wide sort key of the manual order (gapped, steps of 1024; the server renumbers a group
--   when no whole number fits between two neighbours). NULL = never ordered: those tasks come after the positioned
--   ones, newest first. Existing tasks keep NULL.
-- ix_tasks_list_position (list_id, deleted_at, position): the manual list order and the "lowest position
--   of a list" lookup (both only look at live tasks of one list).
ALTER TABLE tasks
    ADD COLUMN position BIGINT NULL,
    ADD INDEX ix_tasks_list_position (list_id, deleted_at, position);

ANALYZE TABLE tasks;
