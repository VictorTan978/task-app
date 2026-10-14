-- Sessions (spec: specs/sessions.md). Timestamps are DATETIME(6) UTC, like V1/V2.
-- NULL = no cutoff. Sessions whose validity time is before this value are ended on their next request.
ALTER TABLE users
    ADD COLUMN sessions_valid_after DATETIME(6) NULL;

-- Remember-me logins, one row per device. Only SHA-256 hashes are stored, never the cookie values.
CREATE TABLE remember_me_tokens (
    series_hash         BINARY(32)  NOT NULL PRIMARY KEY,
    user_id             BIGINT      NOT NULL,
    token_hash          BINARY(32)  NOT NULL,
    previous_token_hash BINARY(32)  NULL,
    rotated_at          DATETIME(6) NULL,
    expires_at          DATETIME(6) NOT NULL,
    created_at          DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    INDEX ix_remember_me_tokens_user (user_id),
    CONSTRAINT fk_remember_me_tokens_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_remember_me_tokens_rotation CHECK ((previous_token_hash IS NULL) = (rotated_at IS NULL))
) ENGINE = InnoDB;
