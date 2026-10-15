-- Sign in with Google (spec: specs/google-sign-in.md). Timestamps are DATETIME(6) UTC, like V1/V5/V6.

-- Accounts created with Google have no password until they set one with "Forgot password?" (NULL = no password).
-- Same type, character set and collation as before; only NOT NULL is dropped. Existing rows are untouched.
ALTER TABLE users
    MODIFY COLUMN password_hash VARCHAR(60) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL;

-- External sign-in accounts connected to a user, deleted with the user.
-- provider_subject is the provider's stable account id (OpenID Connect "sub"), compared exactly (ascii_bin).
-- provider_email is the provider account's email as of its latest sign-in or connect, for display only.
CREATE TABLE user_connections (
    id               BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id          BIGINT       NOT NULL,
    provider         VARCHAR(20)  CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    provider_subject VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    provider_email   VARCHAR(254) NOT NULL,
    created_at       DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    -- A provider account belongs to at most one user.
    CONSTRAINT uk_user_connections_provider_subject UNIQUE (provider, provider_subject),
    -- A user has at most one connection per provider. Also serves the FK lookup used by ON DELETE CASCADE.
    CONSTRAINT uk_user_connections_user_provider UNIQUE (user_id, provider),
    CONSTRAINT fk_user_connections_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_user_connections_provider CHECK (provider IN ('google'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
