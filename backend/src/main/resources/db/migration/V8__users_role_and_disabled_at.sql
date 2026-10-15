-- Roles and admin users (spec: specs/roles-admin.md). Additive only. Timestamps DATETIME(6) UTC, like V1/V5/V6/V7.
-- role: every account has exactly one role; existing and new rows get 'USER' (AC1). Compared exactly (ascii_bin).
-- disabled_at: NULL = active; set when an admin disables the account, cleared when enabled. Data is kept (AC19).
ALTER TABLE users
    ADD COLUMN role        VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'USER',
    ADD COLUMN disabled_at DATETIME(6) NULL,
    ADD CONSTRAINT ck_users_role CHECK (role IN ('USER', 'ADMIN', 'SUPER_ADMIN'));
