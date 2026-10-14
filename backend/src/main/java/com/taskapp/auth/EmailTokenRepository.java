package com.taskapp.auth;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Table {@code email_tokens} (V6), plain JDBC like {@link RememberMeTokenRepository}: the key is a BINARY(32) hash.
 * Instants are bound as UTC (the connection is pinned to UTC, see application.yml).
 * <p>
 * Lock order (bug B1 rules, same as the remember-me writes): callers run READ COMMITTED, lock the owner's
 * {@code users} row first ({@link #lockUser}), then touch token rows by primary key only. The multi-row delete reads
 * the keys with a plain, non-locking SELECT and deletes by primary key, and only when there is something to delete,
 * so no gap locks are taken on {@code ix_email_tokens_user}.
 */
@Repository
public class EmailTokenRepository {

    private static final String COLUMNS = "token_hash, user_id, purpose, expires_at, used_at";

    private final JdbcClient jdbc;

    public EmailTokenRepository(JdbcTemplate jdbcTemplate) {
        this.jdbc = JdbcClient.create(jdbcTemplate);
    }

    /** The user's current email, display name and confirmation state, read while their row is locked. */
    public record LockedUser(long id, String email, String displayName, boolean emailVerified) {
    }

    /** {@code SELECT ... FOR UPDATE} on the user's row; empty if the user does not exist. */
    public Optional<LockedUser> lockUser(long userId) {
        return jdbc.sql("SELECT id, email, display_name, email_verified_at FROM users WHERE id = ? FOR UPDATE")
                .param(userId)
                .query((rs, row) -> new LockedUser(rs.getLong("id"), rs.getString("email"),
                        rs.getString("display_name"), rs.getTimestamp("email_verified_at") != null))
                .optional();
    }

    public Optional<EmailToken> find(byte[] tokenHash) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM email_tokens WHERE token_hash = ?")
                .param(tokenHash)
                .query(EmailTokenRepository::map)
                .optional();
    }

    public void insert(byte[] tokenHash, long userId, EmailTokenPurpose purpose, Instant expiresAt,
            Instant createdAt) {
        jdbc.sql("INSERT INTO email_tokens (token_hash, user_id, purpose, expires_at, created_at)"
                        + " VALUES (?, ?, ?, ?, ?)")
                .params(tokenHash, userId, purpose.name(), Timestamp.from(expiresAt), Timestamp.from(createdAt))
                .update();
    }

    /**
     * Before issuing a new token: deletes the user's unused tokens of {@code purpose} (they are replaced) and all
     * their expired tokens. Non-locking SELECT, then a primary-key DELETE only if there are rows.
     */
    public int deleteReplacedAndExpired(long userId, EmailTokenPurpose purpose, Instant now) {
        List<byte[]> hashes = jdbc.sql("""
                SELECT token_hash FROM email_tokens
                WHERE user_id = ? AND ((purpose = ? AND used_at IS NULL) OR expires_at <= ?)
                ORDER BY token_hash""")
                .params(userId, purpose.name(), Timestamp.from(now))
                .query(byte[].class)
                .list();
        if (hashes.isEmpty()) {
            return 0;
        }
        String placeholders = String.join(", ", Collections.nCopies(hashes.size(), "?"));
        return jdbc.sql("DELETE FROM email_tokens WHERE token_hash IN (" + placeholders + ")")
                .params(new ArrayList<Object>(hashes))
                .update();
    }

    /** Marks the token used, only if it still is unused. False if a parallel request used it first. */
    public boolean markUsed(byte[] tokenHash, Instant now) {
        return jdbc.sql("UPDATE email_tokens SET used_at = ? WHERE token_hash = ? AND used_at IS NULL")
                .params(Timestamp.from(now), tokenHash)
                .update() == 1;
    }

    private static EmailToken map(ResultSet rs, int row) throws SQLException {
        Timestamp usedAt = rs.getTimestamp("used_at");
        return new EmailToken(rs.getBytes("token_hash"), rs.getLong("user_id"),
                EmailTokenPurpose.valueOf(rs.getString("purpose")), rs.getTimestamp("expires_at").toInstant(),
                usedAt == null ? null : usedAt.toInstant());
    }
}
