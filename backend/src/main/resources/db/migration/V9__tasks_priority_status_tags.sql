-- Task priority, status and tags (spec: specs/tasks-priority-status-tags.md). Additive; `done` is kept (expand-then-contract).
--
-- priority/status: utf8mb4_0900_bin is binary AND NO PAD, so the CHECKs are exact: 'HIGH ', 'high' and 'HIGH\t'
--   are rejected (A5 lesson: ascii_bin is PAD SPACE, so CHECK ... IN ('USER') accepts 'USER ').
-- status is backfilled from done; ck_tasks_status_matches_done then guarantees they never disagree.
-- updated_at has no ON UPDATE clause, so the backfill does not touch it.
ALTER TABLE tasks
    ADD COLUMN priority VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL DEFAULT 'NONE' AFTER description,
    ADD COLUMN status   VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL DEFAULT 'TODO' AFTER priority;

UPDATE tasks SET status = 'DONE' WHERE done = TRUE;

-- ix_tasks_owner_status_created serves ?filter=in_progress (WHERE owner_id = ? AND status = ? ORDER BY created_at DESC, id DESC).
-- ix_tasks_owner_done_created (V2) is kept: All/Active/Done still filter and sort on the mirrored `done` column.
ALTER TABLE tasks
    ADD CONSTRAINT ck_tasks_priority CHECK (priority IN ('NONE', 'LOW', 'MEDIUM', 'HIGH')),
    ADD CONSTRAINT ck_tasks_status CHECK (status IN ('TODO', 'IN_PROGRESS', 'DONE')),
    ADD CONSTRAINT ck_tasks_status_matches_done CHECK ((status = 'DONE') = done),
    ADD INDEX ix_tasks_owner_status_created (owner_id, status, created_at DESC, id DESC);

-- Per-user tags. name uses utf8mb4_0900_ai_ci (like users.display_name, V4): the unique key treats 'Work', 'work'
-- and 'wörk' as the same tag; the stored value keeps the first spelling. 0900 collations are NO PAD, and the
-- app never stores leading/trailing/double spaces (checked below in binary so only U+0020 counts as a space;
-- the app turns every other whitespace/space-separator run into one U+0020 before storing).
-- uk_tags_owner_name is also the FK index for owner_id and serves lookup by (owner_id, name).
CREATE TABLE tags (
    id       BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    owner_id BIGINT       NOT NULL,
    name     VARCHAR(30)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
    CONSTRAINT uk_tags_owner_name UNIQUE (owner_id, name),
    CONSTRAINT fk_tags_owner FOREIGN KEY (owner_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_tags_name_length CHECK (char_length(name) BETWEEN 1 AND 30),
    CONSTRAINT ck_tags_name_spaces CHECK (
        (name COLLATE utf8mb4_0900_bin) NOT LIKE ' %'
        AND (name COLLATE utf8mb4_0900_bin) NOT LIKE '% '
        AND (name COLLATE utf8mb4_0900_bin) NOT LIKE '%  %'),
    CONSTRAINT ck_tags_name_chars CHECK (NOT REGEXP_LIKE(name, '[,[:cntrl:]]', 'c'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- Which tags a task has. PK serves "tags of these tasks" and the task_id FK; ix_task_tags_tag_task serves
-- ?tag= (tasks having tag X) and the tag_id FK. Deleting a task or a tag (or the user, via both) removes its rows.
CREATE TABLE task_tags (
    task_id BIGINT NOT NULL,
    tag_id  BIGINT NOT NULL,
    PRIMARY KEY (task_id, tag_id),
    INDEX ix_task_tags_tag_task (tag_id, task_id),
    CONSTRAINT fk_task_tags_task FOREIGN KEY (task_id) REFERENCES tasks (id) ON DELETE CASCADE,
    CONSTRAINT fk_task_tags_tag FOREIGN KEY (tag_id) REFERENCES tags (id) ON DELETE CASCADE
) ENGINE = InnoDB;
