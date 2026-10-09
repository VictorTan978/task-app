-- Personal tasks (spec: specs/tasks.md). Owned by a user; deleted with the user.
-- Timestamps are DATETIME(6) holding UTC (the JDBC connection is pinned to UTC); not TIMESTAMP, which ends in 2038.
CREATE TABLE tasks (
    id          BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    owner_id    BIGINT        NOT NULL,
    title       VARCHAR(200)  NOT NULL,
    description VARCHAR(2000),
    done        BOOLEAN       NOT NULL DEFAULT FALSE,
    due_date    DATE,
    created_at  DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at  DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    -- Serves the list query (WHERE owner_id = ? [AND done = ?] ORDER BY done, created_at DESC, id DESC)
    -- and the FK lookup used by ON DELETE CASCADE. Declared inline so InnoDB uses it for the FK
    -- instead of creating a separate implicit index on owner_id.
    INDEX ix_tasks_owner_done_created (owner_id, done, created_at DESC, id DESC),
    CONSTRAINT fk_tasks_owner FOREIGN KEY (owner_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_tasks_title_not_empty CHECK (char_length(title) > 0),
    CONSTRAINT ck_tasks_description_not_empty CHECK (description IS NULL OR char_length(description) > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
