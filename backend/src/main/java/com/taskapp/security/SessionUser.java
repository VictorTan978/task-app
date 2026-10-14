package com.taskapp.security;

import java.time.Instant;

/**
 * Current state of a signed-in user, as loaded on every request by {@link SessionUserRefreshFilter}.
 *
 * @param user                the principal with current email and display name
 * @param sessionsValidAfter  sessions whose {@link SessionAttributes#VALID_FROM} is before this are ended; null = no
 *                            cutoff
 */
public record SessionUser(AuthenticatedUser user, Instant sessionsValidAfter) {

    /** Whether a session valid from {@code validFrom} has been ended by a later cutoff. */
    public boolean endsSessionValidFrom(Instant validFrom) {
        return sessionsValidAfter != null && validFrom.isBefore(sessionsValidAfter);
    }
}
