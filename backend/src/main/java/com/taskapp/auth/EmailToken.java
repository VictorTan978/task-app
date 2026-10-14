package com.taskapp.auth;

import java.time.Instant;

/**
 * One row of {@code email_tokens}. Holds only the SHA-256 hash of the token, never the token.
 *
 * @param usedAt when it was used, or null (unused)
 */
public record EmailToken(byte[] tokenHash, long userId, EmailTokenPurpose purpose, Instant expiresAt,
        Instant usedAt) {

    @Override
    public String toString() {
        return "EmailToken[userId=" + userId + ", purpose=" + purpose + ", expiresAt=" + expiresAt + ", usedAt="
                + usedAt + "]";
    }
}
