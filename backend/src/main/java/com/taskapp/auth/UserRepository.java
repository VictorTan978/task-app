package com.taskapp.auth;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {

    /** {@code email} must already be normalized (trimmed, lowercase). */
    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);
}
