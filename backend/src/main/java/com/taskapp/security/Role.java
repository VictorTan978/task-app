package com.taskapp.security;

import java.util.List;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * The role of an account (column {@code users.role}, V8; specs/roles-admin.md). Exactly one per account. The
 * signed-in user's role lives in the authorities of their {@code Authentication} ({@link #authorities()}), loaded
 * from the database on every request by {@link SessionUserRefreshFilter}.
 */
public enum Role {
    USER,
    ADMIN,
    SUPER_ADMIN;

    /** {@code ROLE_<name>}, as used by {@code hasRole}/{@code hasAnyRole} in the security config. */
    public List<GrantedAuthority> authorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + name()));
    }

    /** May use {@code /api/admin/**}. */
    public boolean isAdmin() {
        return this == ADMIN || this == SUPER_ADMIN;
    }
}
