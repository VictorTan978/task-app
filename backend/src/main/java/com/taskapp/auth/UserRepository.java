package com.taskapp.auth;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Lookups compare with the column collation (utf8mb4_0900_ai_ci, see V4): case- and accent-insensitive,
 * so {@code existsByEmail("josé@x.com")} is true when {@code jose@x.com} exists.
 */
public interface UserRepository extends JpaRepository<User, Long> {

    /** {@code email} must already be normalized (trimmed, lowercase). */
    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

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
