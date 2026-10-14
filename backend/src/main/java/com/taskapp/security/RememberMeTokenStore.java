package com.taskapp.security;

import java.time.Instant;
import java.util.Optional;

/** Persistence for remember-me logins, used by {@link HashedRememberMeServices}. Implemented in the auth module. */
public interface RememberMeTokenStore {

    Optional<RememberMeToken> find(byte[] seriesHash);

    /** Stores a new login, after deleting the same user's expired logins (as of {@code now}). */
    void create(RememberMeToken token, Instant now);

    /**
     * Atomically replaces the secret if it is still {@code currentTokenHash}: the current one becomes the previous
     * one with {@code rotatedAt = now}; the expiry is not touched. False if a parallel request rotated it first (or
     * the login is gone).
     */
    boolean rotate(byte[] seriesHash, byte[] currentTokenHash, byte[] newTokenHash, Instant now);

    void delete(byte[] seriesHash);

    /**
     * Stolen-cookie response: deletes ALL of the user's remember-me logins and ends all their sessions
     * ({@code sessions_valid_after = now}), in one transaction.
     */
    void endAllForUser(long userId, Instant now);
}
