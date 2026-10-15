package com.taskapp.auth;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.taskapp.security.Role;

import jakarta.persistence.LockModeType;

/**
 * Lookups compare with the column collation (utf8mb4_0900_ai_ci, see V4): case- and accent-insensitive,
 * so {@code existsByEmail("josé@x.com")} is true when {@code jose@x.com} exists.
 */
public interface UserRepository extends JpaRepository<User, Long> {

    /** {@code email} must already be normalized (trimmed, lowercase). */
    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    /** The id of the user with this email (same collation rules), without loading it. Must be normalized. */
    @Query("select u.id from User u where u.email = :email")
    Optional<Long> findIdByEmail(@Param("email") String email);

    /**
     * Whether user {@code id} has this email, compared like login (ignoring letter case and accents).
     * {@code email} must already be normalized.
     */
    boolean existsByIdAndEmail(Long id, String email);

    /**
     * Loads the user and locks its row ({@code SELECT ... FOR UPDATE}) until the transaction ends. Writes of a user's
     * connections take this lock first (see {@link UserConnectionRepository}).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    Optional<User> findByIdForUpdate(@Param("id") long id);

    /**
     * Loads and locks the rows of {@code ids} in id order ({@code SELECT ... ORDER BY id FOR UPDATE}): an admin action
     * locks the acting admin and the target together, always in the same order, so two actions can't deadlock.
     * Missing ids are simply absent from the result.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id in :ids order by u.id")
    List<User> findAllByIdForUpdate(@Param("ids") Collection<Long> ids);

    boolean existsByRole(Role role);

    /**
     * Admin user list: users whose email OR display name contains {@code pattern}, a LIKE pattern escaped with
     * {@value #LIKE_ESCAPE} (null = everyone). The columns' collation (utf8mb4_0900_ai_ci, V4) makes it ignore letter
     * case and accents. Order and page come from {@code pageable}.
     */
    @Query(value = "select u from User u where :pattern is null"
            + " or u.email like :pattern escape '" + LIKE_ESCAPE + "'"
            + " or u.displayName like :pattern escape '" + LIKE_ESCAPE + "'",
            countQuery = "select count(u) from User u where :pattern is null"
                    + " or u.email like :pattern escape '" + LIKE_ESCAPE + "'"
                    + " or u.displayName like :pattern escape '" + LIKE_ESCAPE + "'")
    Page<User> search(@Param("pattern") String pattern, Pageable pageable);

    /** Escape character of {@link #search} patterns (not a backslash: MySQL also treats that as a string escape). */
    char LIKE_ESCAPE = '!';

    /** A {@link #search} pattern matching values that contain {@code text} literally ({@code %}, {@code _} too). */
    static String containsPattern(String text) {
        StringBuilder pattern = new StringBuilder(text.length() + 2).append('%');
        for (char c : text.toCharArray()) {
            if (c == '%' || c == '_' || c == LIKE_ESCAPE) {
                pattern.append(LIKE_ESCAPE);
            }
            pattern.append(c);
        }
        return pattern.append('%').toString();
    }

    /** {@code displayName} must already be trimmed. */
    boolean existsByDisplayName(String displayName);

    /** Whether a user OTHER than {@code id} has this display name (same collation rules). Must be trimmed. */
    boolean existsByDisplayNameAndIdNot(String displayName, Long id);

    /** Sets {@code sessions_valid_after} without loading the user (stolen-cookie detection). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update User u set u.sessionsValidAfter = :cutoff where u.id = :id")
    int endSessionsBefore(@Param("id") long id, @Param("cutoff") Instant cutoff);

    /** Marks the email as confirmed at {@code at}; an earlier confirmation time is kept. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update User u set u.emailVerifiedAt = :at where u.id = :id and u.emailVerifiedAt is null")
    int markEmailVerified(@Param("id") long id, @Param("at") Instant at);

    /** Password reset by email: new BCrypt hash, and the email counts as confirmed (earlier time kept). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update User u set u.passwordHash = :passwordHash, "
            + "u.emailVerifiedAt = coalesce(u.emailVerifiedAt, :at) where u.id = :id")
    int resetPassword(@Param("id") long id, @Param("passwordHash") String passwordHash, @Param("at") Instant at);
}
