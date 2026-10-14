package com.taskapp.auth;

import java.time.Instant;
import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.taskapp.security.RememberMeToken;
import com.taskapp.security.RememberMeTokenStore;

/** {@link RememberMeTokenStore} on {@code remember_me_tokens} and {@code users.sessions_valid_after}. */
@Component
class DatabaseRememberMeTokenStore implements RememberMeTokenStore {

    private final RememberMeTokenRepository tokens;
    private final UserRepository users;

    DatabaseRememberMeTokenStore(RememberMeTokenRepository tokens, UserRepository users) {
        this.tokens = tokens;
        this.users = users;
    }

    @Override
    public Optional<RememberMeToken> find(byte[] seriesHash) {
        return tokens.findBySeriesHash(seriesHash);
    }

    @Override
    @Transactional
    public void create(RememberMeToken token, Instant now) {
        tokens.deleteExpiredForUser(token.userId(), now);
        tokens.insert(token);
    }

    @Override
    public boolean rotate(byte[] seriesHash, byte[] currentTokenHash, byte[] newTokenHash, Instant now) {
        return tokens.rotate(seriesHash, currentTokenHash, newTokenHash, now);
    }

    @Override
    public void delete(byte[] seriesHash) {
        tokens.deleteBySeriesHash(seriesHash);
    }

    @Override
    @Transactional
    public void endAllForUser(long userId, Instant now) {
        tokens.deleteAllForUser(userId);
        users.endSessionsBefore(userId, now);
    }
}
