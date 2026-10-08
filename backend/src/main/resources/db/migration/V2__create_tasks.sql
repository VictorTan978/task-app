-- Personal tasks (spec: specs/tasks.md). Owned by a user; deleted with the user.
CREATE TABLE tasks (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    owner_id    BIGINT        NOT NULL,
    title       VARCHAR(200)  NOT NULL,
    description VARCHAR(2000),
    done        BOOLEAN       NOT NULL DEFAULT FALSE,
    due_date    DATE,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT fk_tasks_owner FOREIGN KEY (owner_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_tasks_title_not_empty CHECK (char_length(title) > 0),
    CONSTRAINT ck_tasks_description_not_empty CHECK (description IS NULL OR char_length(description) > 0)
);

-- Serves the list query (WHERE owner_id = ? [AND done = ?] ORDER BY done, created_at DESC, id DESC)
-- and the FK lookup used by ON DELETE CASCADE.
CREATE INDEX ix_tasks_owner_done_created ON tasks (owner_id, done, created_at DESC, id DESC);
