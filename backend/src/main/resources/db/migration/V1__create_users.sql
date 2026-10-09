-- Users for session-based authentication (spec: specs/auth.md).
-- email is stored trimmed + lowercase by the application; uniqueness is enforced here.
-- Timestamps are DATETIME(6) holding UTC (the JDBC connection is pinned to UTC); not TIMESTAMP, which ends in 2038.
CREATE TABLE users (
    id            BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    email         VARCHAR(254) NOT NULL,
    password_hash VARCHAR(60)  NOT NULL,
    display_name  VARCHAR(50)  NOT NULL,
    created_at    DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT uk_users_email UNIQUE (email)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
