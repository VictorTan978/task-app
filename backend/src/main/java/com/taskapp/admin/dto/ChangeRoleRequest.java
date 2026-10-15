package com.taskapp.admin.dto;

import java.util.Optional;

import com.taskapp.security.Role;

/**
 * Body of {@code PUT /api/admin/users/{id}/role}. {@code role} is checked by the service: only {@code USER} or
 * {@code ADMIN} (exactly); anything else, including {@code SUPER_ADMIN} and a missing role, is 400 {@code role} /
 * {@code INVALID_ROLE}.
 */
public record ChangeRoleRequest(String role) {

    /** The role to set, if it is one the API may assign. */
    public Optional<Role> assignableRole() {
        if (Role.USER.name().equals(role)) {
            return Optional.of(Role.USER);
        }
        if (Role.ADMIN.name().equals(role)) {
            return Optional.of(Role.ADMIN);
        }
        return Optional.empty();
    }
}
