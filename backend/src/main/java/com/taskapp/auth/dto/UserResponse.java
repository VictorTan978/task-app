package com.taskapp.auth.dto;

import java.util.List;

import com.taskapp.auth.User;
import com.taskapp.security.Role;

/**
 * Public view of a user. Never carries the password or its hash, nor any provider token or account id.
 *
 * @param hasPassword false for accounts created with Google that have not set a password yet
 * @param connections connected external sign-in accounts (empty when none), at most one per provider
 * @param role        USER, ADMIN or SUPER_ADMIN (contract 0.9.0)
 * @param timeZone    IANA region id, null until set (contract 0.14.0)
 * @param reminderEmails whether reminders also send an email (contract 0.14.0)
 */
public record UserResponse(long id, String email, String displayName, boolean emailVerified, boolean hasPassword,
        List<ConnectionResponse> connections, Role role, String timeZone, boolean reminderEmails) {

    /** A user with no time zone and reminder emails on (the state of every account before 0.14.0). */
    public UserResponse(long id, String email, String displayName, boolean emailVerified, boolean hasPassword,
            List<ConnectionResponse> connections, Role role) {
        this(id, email, displayName, emailVerified, hasPassword, connections, role, null, true);
    }

    public UserResponse {
        connections = connections == null ? List.of() : List.copyOf(connections);
        role = role == null ? Role.USER : role;
    }

    /** Reads {@link User#getConnections()}: call inside a transaction, or on a user created in this request. */
    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getEmail(), user.getDisplayName(), user.isEmailVerified(),
                user.hasPassword(), user.getConnections().stream().map(ConnectionResponse::from).toList(),
                user.getRole(), user.getTimeZone(), user.isReminderEmails());
    }
}
