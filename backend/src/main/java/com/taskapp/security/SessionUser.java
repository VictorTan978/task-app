package com.taskapp.security;

import java.time.Instant;
import java.util.List;

import org.springframework.security.core.GrantedAuthority;

/**
 * Current state of a signed-in user, as loaded on every request by {@link SessionUserRefreshFilter}.
 *
 * @param user                the principal with current email and display name
 * @param sessionsValidAfter  sessions whose {@link SessionAttributes#VALID_FROM} is before this are ended; null = no
 *                            cutoff
 * @param role                the current role; the session's authorities are rebuilt from it
 * @param disabled            the account is disabled by an admin: every session and remember-me login is refused
 */
public record SessionUser(AuthenticatedUser user, Instant sessionsValidAfter, Role role, boolean disabled) {

    /** An active {@link Role#USER}. */
    public SessionUser(AuthenticatedUser user, Instant sessionsValidAfter) {
        this(user, sessionsValidAfter, Role.USER, false);
    }

    /** Whether a session valid from {@code validFrom} has been ended by a later cutoff. */
    public boolean endsSessionValidFrom(Instant validFrom) {
        return sessionsValidAfter != null && validFrom.isBefore(sessionsValidAfter);
    }

    /** The authorities of a session of this user: always from the current role, never copied from an old session. */
    public List<GrantedAuthority> authorities() {
        return role.authorities();
    }
}
