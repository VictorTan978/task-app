-- users.email must match case-insensitively only (spec: specs/auth.md AC2, AC4).
-- The table default utf8mb4_0900_ai_ci also ignores accents, so 'jose@...' and 'josé@...' collided
-- (409 on register, cross-account login). utf8mb4_0900_as_ci is accent-sensitive, case-insensitive.
-- The unique key uk_users_email is kept and rebuilt by MySQL under the new collation.
ALTER TABLE users
    MODIFY COLUMN email VARCHAR(254) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_ci NOT NULL;
