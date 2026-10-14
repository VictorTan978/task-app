-- Email verification and password reset (spec: specs/email-auth.md). Timestamps are DATETIME(6) UTC, like V1/V5.
-- NULL = email not confirmed yet (also every account created before this migration).
ALTER TABLE users
    ADD COLUMN email_verified_at DATETIME(6) NULL;

-- Single-use links sent by email. Only the SHA-256 hash of the token is stored, never the token itself.
CREATE TABLE email_tokens (
    token_hash BINARY(32)  NOT NULL PRIMARY KEY,
    user_id    BIGINT      NOT NULL,
    purpose    VARCHAR(20) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    used_at    DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    INDEX ix_email_tokens_user (user_id),
    CONSTRAINT fk_email_tokens_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_email_tokens_purpose CHECK (purpose IN ('VERIFY_EMAIL', 'RESET_PASSWORD'))
) ENGINE = InnoDB;
