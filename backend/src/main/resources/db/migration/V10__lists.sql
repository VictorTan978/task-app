-- Lists (spec: specs/sharing-team-lists.md, T3a). Expand only: tasks.owner_id and tags.owner_id stay.
-- At most one personal list per user via a functional unique index (NULL for non-personal lists never collides);
-- "at least one" comes from the sign-up transactions and this backfill.
CREATE TABLE lists (
    id         BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    owner_id   BIGINT       NOT NULL,
    name       VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
    personal   BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    INDEX ix_lists_owner_name (owner_id, name, id),
    UNIQUE INDEX uk_lists_one_personal_per_owner ((IF(personal, owner_id, NULL))),
    CONSTRAINT fk_lists_owner FOREIGN KEY (owner_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_lists_name_length CHECK (char_length(name) BETWEEN 1 AND 100)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

INSERT INTO lists (owner_id, name, personal, created_at)
SELECT id, 'My tasks', TRUE, created_at FROM users ORDER BY id;

ALTER TABLE tasks ADD COLUMN list_id BIGINT NULL AFTER owner_id;
UPDATE tasks t JOIN lists l ON l.owner_id = t.owner_id AND l.personal = TRUE SET t.list_id = l.id;
ALTER TABLE tasks
    MODIFY COLUMN list_id BIGINT NOT NULL,
    ADD INDEX ix_tasks_list_done_created (list_id, done, created_at DESC, id DESC),
    ADD INDEX ix_tasks_list_status_created (list_id, status, created_at DESC, id DESC),
    ADD CONSTRAINT fk_tasks_list FOREIGN KEY (list_id) REFERENCES lists (id) ON DELETE CASCADE;

ALTER TABLE tags ADD COLUMN list_id BIGINT NULL AFTER owner_id;
UPDATE tags g JOIN lists l ON l.owner_id = g.owner_id AND l.personal = TRUE SET g.list_id = l.id;
ALTER TABLE tags
    MODIFY COLUMN list_id BIGINT NOT NULL,
    ADD CONSTRAINT uk_tags_list_name UNIQUE (list_id, name),
    ADD INDEX ix_tags_owner (owner_id),
    ADD CONSTRAINT fk_tags_list FOREIGN KEY (list_id) REFERENCES lists (id) ON DELETE CASCADE;
ALTER TABLE tags DROP INDEX uk_tags_owner_name;

-- Without fresh statistics the optimizer picked the done index for ?filter=in_progress in testing.
ANALYZE TABLE lists, tasks, tags;
