-- List sharing (spec: specs/sharing-team-lists.md, T3b). Expand only: nothing is dropped or renamed.
--
-- list_members: who can see and change a list. Owners are members of their own lists (backfilled below; the
--   app inserts the owner's row together with every new list). Who OWNS a list stays lists.owner_id (owners
--   never change), so the role is derived (owner_id = user_id) and can never disagree with it.
--   PK (list_id, user_id) serves "members of a list", the membership check and the list_id FK;
--   ix_list_members_user serves "my lists" and the user_id FK.
CREATE TABLE list_members (
    list_id   BIGINT      NOT NULL,
    user_id   BIGINT      NOT NULL,
    joined_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (list_id, user_id),
    INDEX ix_list_members_user (user_id, list_id),
    CONSTRAINT fk_list_members_list FOREIGN KEY (list_id) REFERENCES lists (id) ON DELETE CASCADE,
    CONSTRAINT fk_list_members_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE = InnoDB;

INSERT INTO list_members (list_id, user_id, joined_at)
SELECT id, owner_id, created_at FROM lists ORDER BY id;

-- list_invitations: pending invitations by email address. A row is stored the same way whether or not an
--   account has that address (AC18); the invitee is matched by email when they read or accept (verified
--   accounts only, in the app). Accept, decline and cancel delete the row; an expired row stays until the owner
--   invites that address again (the row is refreshed) or the list is deleted.
--   email: stored trimmed + lowercase by the app, same collation as users.email (equal ignoring case and accents,
--   like login), so a match by email uses ix_list_invitations_email_expires.
--   uk_list_invitations_list_email: one invitation per address per list; also serves the list_id FK and the
--   owner's "pending invitations of this list".
CREATE TABLE list_invitations (
    id         BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    list_id    BIGINT       NOT NULL,
    email      VARCHAR(254) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
    invited_at DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    expires_at DATETIME(6)  NOT NULL,
    CONSTRAINT uk_list_invitations_list_email UNIQUE (list_id, email),
    INDEX ix_list_invitations_email_expires (email, expires_at),
    CONSTRAINT fk_list_invitations_list FOREIGN KEY (list_id) REFERENCES lists (id) ON DELETE CASCADE,
    CONSTRAINT ck_list_invitations_email_length CHECK (char_length(email) BETWEEN 3 AND 254),
    CONSTRAINT ck_list_invitations_expiry CHECK (expires_at > invited_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- tasks.owner_id now means "added by": deleting an account keeps the tasks it added to other people's lists,
--   with owner_id NULL ("Added by a deleted user"). Its own lists still go, with their tasks (fk_tasks_list).
--   The FK is dropped first and re-added with the same name; ix_tasks_owner_done_created stays its index.
--   Re-adding a FK with foreign_key_checks on makes MySQL copy the table (ALGORITHM=COPY): writes to tasks wait
--   while it runs (startup, before traffic; 26k rows took 0.18 s in testing). Kept on purpose (approved).
ALTER TABLE tasks DROP FOREIGN KEY fk_tasks_owner;
ALTER TABLE tasks
    MODIFY COLUMN owner_id BIGINT NULL,
    ADD CONSTRAINT fk_tasks_owner FOREIGN KEY (owner_id) REFERENCES users (id) ON DELETE SET NULL;

ANALYZE TABLE list_members, list_invitations;
