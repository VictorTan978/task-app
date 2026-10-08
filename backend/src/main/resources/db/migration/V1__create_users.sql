-- Users for session-based authentication (spec: specs/auth.md).
-- email is stored trimmed + lowercase by the application; uniqueness is enforced here.
CREATE TABLE users (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    email         VARCHAR(254) NOT NULL,
    password_hash VARCHAR(60)  NOT NULL,
    display_name  VARCHAR(50)  NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_users_email UNIQUE (email)
);
