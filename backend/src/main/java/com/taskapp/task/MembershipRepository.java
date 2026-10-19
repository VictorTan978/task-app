package com.taskapp.task;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * Tables {@code list_members} and {@code list_invitations} (V11, specs/sharing-team-lists.md T3b), plain JDBC (the
 * member key is composite and nothing here needs an entity). Instants are bound as UTC (the connection is pinned to
 * UTC, see application.yml).
 * <p>
 * Lock rules (as {@link TagRepository}): the only locking read is {@link #shareMember}, an exact primary-key lookup.
 * Every write touches one row by its primary key (or inserts one), and only after a plain read found it, with the
 * list's row lock held ({@link ListRepository#lockList}), so no gap or range lock is ever taken on these tables.
 * Callers take that lock before their first plain read, so the reads see every committed change.
 */
@Repository
public class MembershipRepository {

    private final JdbcClient jdbc;

    public MembershipRepository(JdbcTemplate jdbcTemplate) {
        this.jdbc = JdbcClient.create(jdbcTemplate);
    }

    // ---- members ----

    /** One row of the members panel. {@code owner}: the user is the list's owner. */
    public record MemberRow(long userId, String displayName, String email, boolean owner, Instant joinedAt) {
    }

    /**
     * S lock on the membership row (exact primary key). Empty when the user is not a member; that answer is a 404 and
     * the transaction ends at once.
     */
    public Optional<Long> shareMember(long listId, long userId) {
        return jdbc.sql("SELECT user_id FROM list_members WHERE list_id = ? AND user_id = ? FOR SHARE")
                .params(listId, userId)
                .query(Long.class)
                .optional();
    }

    /** Plain read. */
    public boolean isMember(long listId, long userId) {
        return jdbc.sql("SELECT COUNT(*) FROM list_members WHERE list_id = ? AND user_id = ?")
                .params(listId, userId)
                .query(Long.class)
                .single() > 0;
    }

    public void insertMember(long listId, long userId, Instant joinedAt) {
        jdbc.sql("INSERT INTO list_members (list_id, user_id, joined_at) VALUES (?, ?, ?)")
                .params(listId, userId, Timestamp.from(joinedAt))
                .update();
    }

    /** By primary key; the caller has read that the row exists. */
    public int deleteMember(long listId, long userId) {
        return jdbc.sql("DELETE FROM list_members WHERE list_id = ? AND user_id = ?")
                .params(listId, userId)
                .update();
    }

    /** Plain read (primary-key prefix). */
    public int countMembers(long listId) {
        return jdbc.sql("SELECT COUNT(*) FROM list_members WHERE list_id = ?")
                .param(listId)
                .query(Integer.class)
                .single();
    }

    /** Member count per list id, one query. Lists without members (none in practice) are absent. */
    public Map<Long, Integer> countMembers(Collection<Long> listIds) {
        Map<Long, Integer> counts = new HashMap<>();
        if (listIds.isEmpty()) {
            return counts;
        }
        jdbc.sql("SELECT list_id, COUNT(*) AS n FROM list_members WHERE list_id IN (" + placeholders(listIds.size())
                        + ") GROUP BY list_id")
                .params(new ArrayList<Object>(listIds))
                .query((rs, row) -> counts.put(rs.getLong("list_id"), rs.getInt("n")))
                .list();
        return counts;
    }

    /** The members panel: the owner first, then by display name (column collation), then by id. */
    public List<MemberRow> members(long listId) {
        return jdbc.sql("""
                SELECT u.id, u.display_name, u.email, u.id = l.owner_id AS is_owner, m.joined_at
                FROM list_members m
                JOIN lists l ON l.id = m.list_id
                JOIN users u ON u.id = m.user_id
                WHERE m.list_id = ?
                ORDER BY is_owner DESC, u.display_name, u.id""")
                .param(listId)
                .query((rs, row) -> new MemberRow(rs.getLong("id"), rs.getString("display_name"),
                        rs.getString("email"), rs.getBoolean("is_owner"), rs.getTimestamp("joined_at").toInstant()))
                .list();
    }

    /**
     * Whether one of the list's members has this email (compared like login). Reads the list's members first and
     * looks up each one by id (forced join order), so the work does not depend on whether some other account has the
     * address (AC18). {@code email} must be normalized.
     */
    public boolean isMemberEmail(long listId, String email) {
        return jdbc.sql("""
                SELECT COUNT(*) FROM list_members m STRAIGHT_JOIN users u ON u.id = m.user_id
                WHERE m.list_id = ? AND u.email = ?""")
                .params(listId, email)
                .query(Long.class)
                .single() > 0;
    }

    // ---- invitations ----

    /** A stored invitation. */
    public record InvitationRow(long id, long listId, String email, Instant invitedAt, Instant expiresAt) {

        public boolean pendingAt(Instant now) {
            return expiresAt.isAfter(now);
        }
    }

    /** An invitation to a user's verified email, with its list's owner (for the lock order). */
    public record MyInvitationTarget(long id, long listId, long ownerId) {
    }

    /** One entry of {@code GET /api/invitations}. */
    public record MyInvitationRow(long id, long listId, String listName, String ownerDisplayName, Instant invitedAt,
            Instant expiresAt) {
    }

    private static final String INVITATION_COLUMNS = "id, list_id, email, invited_at, expires_at";

    /** The list's invitation for this address (pending or expired). Plain read. {@code email} normalized. */
    public Optional<InvitationRow> findInvitation(long listId, String email) {
        return jdbc.sql("SELECT " + INVITATION_COLUMNS + " FROM list_invitations WHERE list_id = ? AND email = ?")
                .params(listId, email)
                .query(MembershipRepository::invitation)
                .optional();
    }

    /** Plain read by primary key. */
    public Optional<InvitationRow> findInvitationById(long id) {
        return jdbc.sql("SELECT " + INVITATION_COLUMNS + " FROM list_invitations WHERE id = ?")
                .param(id)
                .query(MembershipRepository::invitation)
                .optional();
    }

    /** The list's pending invitations, newest first. Plain read. */
    public List<InvitationRow> pending(long listId, Instant now) {
        return jdbc.sql("SELECT " + INVITATION_COLUMNS + " FROM list_invitations WHERE list_id = ? AND expires_at > ?"
                        + " ORDER BY invited_at DESC, id DESC")
                .params(listId, Timestamp.from(now))
                .query(MembershipRepository::invitation)
                .list();
    }

    /** Plain read. */
    public int countPending(long listId, Instant now) {
        return jdbc.sql("SELECT COUNT(*) FROM list_invitations WHERE list_id = ? AND expires_at > ?")
                .params(listId, Timestamp.from(now))
                .query(Integer.class)
                .single();
    }

    /** Returns the new id. */
    public long insertInvitation(long listId, String email, Instant invitedAt, Instant expiresAt) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO list_invitations (list_id, email, invited_at, expires_at) VALUES (?, ?, ?, ?)")
                .params(listId, email, Timestamp.from(invitedAt), Timestamp.from(expiresAt))
                .update(keys);
        return keys.getKey().longValue();
    }

    /** Re-invite of an expired invitation: same row, new dates. By primary key. */
    public int refreshInvitation(long id, Instant invitedAt, Instant expiresAt) {
        return jdbc.sql("UPDATE list_invitations SET invited_at = ?, expires_at = ? WHERE id = ?")
                .params(Timestamp.from(invitedAt), Timestamp.from(expiresAt), id)
                .update();
    }

    /** By primary key; the caller has read that the row exists. */
    public int deleteInvitation(long id) {
        return jdbc.sql("DELETE FROM list_invitations WHERE id = ?").param(id).update();
    }

    /**
     * The pending invitation {@code id} if it is to {@code userId}'s email and that email is verified, with its list's
     * owner. Plain read.
     */
    public Optional<MyInvitationTarget> findMine(long id, long userId, Instant now) {
        return jdbc.sql("""
                SELECT i.id, i.list_id, l.owner_id
                FROM users me
                JOIN list_invitations i ON i.email = me.email
                JOIN lists l ON l.id = i.list_id
                WHERE me.id = ? AND me.email_verified_at IS NOT NULL AND i.id = ? AND i.expires_at > ?""")
                .params(userId, id, Timestamp.from(now))
                .query((rs, row) -> new MyInvitationTarget(rs.getLong("id"), rs.getLong("list_id"),
                        rs.getLong("owner_id")))
                .optional();
    }

    /** The pending invitations to {@code userId}'s email, only if it is verified; newest first. Plain read. */
    public List<MyInvitationRow> mine(long userId, Instant now) {
        return jdbc.sql("""
                SELECT i.id, i.list_id, l.name, o.display_name, i.invited_at, i.expires_at
                FROM users me
                JOIN list_invitations i ON i.email = me.email AND i.expires_at > ?
                JOIN lists l ON l.id = i.list_id
                JOIN users o ON o.id = l.owner_id
                WHERE me.id = ? AND me.email_verified_at IS NOT NULL
                ORDER BY i.invited_at DESC, i.id DESC""")
                .params(Timestamp.from(now), userId)
                .query((rs, row) -> new MyInvitationRow(rs.getLong("id"), rs.getLong("list_id"), rs.getString("name"),
                        rs.getString("display_name"), rs.getTimestamp("invited_at").toInstant(),
                        rs.getTimestamp("expires_at").toInstant()))
                .list();
    }

    /** How many entries {@link #mine} would return. */
    public long countMine(long userId, Instant now) {
        return jdbc.sql("""
                SELECT COUNT(*)
                FROM users me
                JOIN list_invitations i ON i.email = me.email AND i.expires_at > ?
                WHERE me.id = ? AND me.email_verified_at IS NOT NULL""")
                .params(Timestamp.from(now), userId)
                .query(Long.class)
                .single();
    }

    /** A user's display name (the inviter's, for the email). Plain read. */
    public Optional<String> displayName(long userId) {
        return jdbc.sql("SELECT display_name FROM users WHERE id = ?").param(userId).query(String.class).optional();
    }

    // ---- account deletion ----

    /**
     * Every list an account deletion touches: the lists the user is a member of (their own included), the lists
     * holding tasks they added, and the lists with an invitation to their email. Ascending. Plain read.
     */
    public List<Long> listsTouchedBy(long userId, String email) {
        return jdbc.sql("""
                SELECT list_id FROM list_members WHERE user_id = ?
                UNION SELECT list_id FROM tasks WHERE owner_id = ?
                UNION SELECT list_id FROM list_invitations WHERE email = ?
                ORDER BY list_id""")
                .params(userId, userId, email)
                .query(Long.class)
                .list();
    }

    /** Ids of every invitation (pending or expired) to this email. Plain read. */
    public List<Long> invitationIdsFor(String email) {
        return jdbc.sql("SELECT id FROM list_invitations WHERE email = ? ORDER BY id")
                .param(email)
                .query(Long.class)
                .list();
    }

    private static InvitationRow invitation(ResultSet rs, int row) throws SQLException {
        return new InvitationRow(rs.getLong("id"), rs.getLong("list_id"), rs.getString("email"),
                rs.getTimestamp("invited_at").toInstant(), rs.getTimestamp("expires_at").toInstant());
    }

    private static String placeholders(int n) {
        return String.join(", ", Collections.nCopies(n, "?"));
    }
}
