package com.taskapp.auth;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

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
}
