package com.taskapp.security;

import java.time.Instant;

/**
 * One stored remember-me login (one device). Holds only SHA-256 hashes, never cookie values.
 *
 * @param seriesHash        SHA-256 of the random per-device series id (primary key)
 * @param userId            owner
 * @param tokenHash         SHA-256 of the current secret
 * @param previousTokenHash SHA-256 of the secret before the last rotation, or null
 * @param rotatedAt         time of the last rotation, or null (set together with {@code previousTokenHash})
 * @param expiresAt         end of this login; never moved
 */
public record RememberMeToken(byte[] seriesHash, long userId, byte[] tokenHash, byte[] previousTokenHash,
        Instant rotatedAt, Instant expiresAt) {
}
