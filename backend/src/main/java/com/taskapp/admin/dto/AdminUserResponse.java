package com.taskapp.admin.dto;

import java.time.Instant;

import com.taskapp.auth.User;
import com.taskapp.security.Role;

/**
 * An account as shown on the admin page (contract 0.9.0 {@code AdminUser}). Only these fields: never tasks, task
 * counts, passwords, hashes or provider ids (specs/roles-admin.md AC10).
 */
public record AdminUserResponse(long id, String email, String displayName, Instant createdAt, boolean emailVerified,
        boolean googleConnected, Role role, boolean disabled) {

    public static AdminUserResponse from(User user, boolean googleConnected) {
        return new AdminUserResponse(user.getId(), user.getEmail(), user.getDisplayName(), user.getCreatedAt(),
                user.isEmailVerified(), googleConnected, user.getRole(), user.isDisabled());
    }
}
