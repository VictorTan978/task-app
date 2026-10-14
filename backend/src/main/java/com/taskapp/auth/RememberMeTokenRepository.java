package com.taskapp.auth;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.taskapp.security.RememberMeToken;

/**
 * Table {@code remember_me_tokens} (V5). Plain JDBC: the key is a BINARY(32) hash and rotation is a conditional
 * update. Instants are bound as UTC (the connection is pinned to UTC, see application.yml).
 */
@Repository
public class RememberMeTokenRepository {

    private static final String COLUMNS =
            "series_hash, user_id, token_hash, previous_token_hash, rotated_at, expires_at";

    private final JdbcClient jdbc;

    public RememberMeTokenRepository(JdbcTemplate jdbcTemplate) {
        this.jdbc = JdbcClient.create(jdbcTemplate);
    }

    public Optional<RememberMeToken> findBySeriesHash(byte[] seriesHash) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM remember_me_tokens WHERE series_hash = ?")
                .param(seriesHash)
                .query(RememberMeTokenRepository::map)
                .optional();
    }

    public void insert(RememberMeToken token) {
        jdbc.sql("INSERT INTO remember_me_tokens (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?)")
                .params(token.seriesHash(), token.userId(), token.tokenHash(), token.previousTokenHash(),
                        timestamp(token.rotatedAt()), timestamp(token.expiresAt()))
                .update();
    }

    /**
     * Compare-and-set rotation: only if the secret is still {@code currentTokenHash}. MySQL assigns left to right,
     * so {@code previous_token_hash} receives the old {@code token_hash}. {@code expires_at} is never changed.
     */
    public boolean rotate(byte[] seriesHash, byte[] currentTokenHash, byte[] newTokenHash, Instant rotatedAt) {
        return jdbc.sql("""
                UPDATE remember_me_tokens
                SET previous_token_hash = token_hash, token_hash = ?, rotated_at = ?
                WHERE series_hash = ? AND token_hash = ?""")
                .params(newTokenHash, timestamp(rotatedAt), seriesHash, currentTokenHash)
                .update() == 1;
    }

    public int deleteBySeriesHash(byte[] seriesHash) {
        return jdbc.sql("DELETE FROM remember_me_tokens WHERE series_hash = ?").param(seriesHash).update();
    }

    public int deleteExpiredForUser(long userId, Instant now) {
        return jdbc.sql("DELETE FROM remember_me_tokens WHERE user_id = ? AND expires_at <= ?")
                .params(userId, timestamp(now))
                .update();
    }

    public int deleteAllForUser(long userId) {
        return jdbc.sql("DELETE FROM remember_me_tokens WHERE user_id = ?").param(userId).update();
    }

    /** Every login of the user except {@code keepSeriesHash} (null = all of them). */
    public int deleteAllForUserExcept(long userId, byte[] keepSeriesHash) {
        if (keepSeriesHash == null) {
            return deleteAllForUser(userId);
        }
        return jdbc.sql("DELETE FROM remember_me_tokens WHERE user_id = ? AND series_hash <> ?")
                .params(userId, keepSeriesHash)
                .update();
    }

    private static RememberMeToken map(ResultSet rs, int row) throws SQLException {
        return new RememberMeToken(rs.getBytes("series_hash"), rs.getLong("user_id"), rs.getBytes("token_hash"),
                rs.getBytes("previous_token_hash"), instant(rs.getTimestamp("rotated_at")),
                instant(rs.getTimestamp("expires_at")));
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
