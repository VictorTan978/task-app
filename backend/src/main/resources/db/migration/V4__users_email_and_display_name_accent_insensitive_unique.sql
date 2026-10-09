-- Reverses V3 and makes display names unique (spec: specs/auth.md AC2, AC4, AC14; Decisions log 2026-10-09).
--
-- email: back to utf8mb4_0900_ai_ci (case- AND accent-insensitive), so 'jose@...' and 'josé@...' are the
--   same account: 409 EMAIL_TAKEN on register, and login with either spelling finds the same user.
--   The unique key uk_users_email is kept; MySQL rebuilds it under the new collation.
--
-- display_name: the collation is set explicitly (instead of relying on the table default) because the
--   unique key below depends on it: 'José' = 'jose' = 'JOSE' under utf8mb4_0900_ai_ci. Values are stored
--   trimmed by the application with the user's original casing; leading/trailing spaces are never stored.
--   uk_users_display_name backs the 409 DISPLAY_NAME_TAKEN check and catches concurrent registrations.
--
-- Fails if existing rows already hold duplicate display names (or emails) under ai_ci; the local DB was
-- emptied before applying this (approved).
ALTER TABLE users
    MODIFY COLUMN email VARCHAR(254) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
    MODIFY COLUMN display_name VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
    ADD CONSTRAINT uk_users_display_name UNIQUE (display_name);
