package com.taskapp.security;

import java.util.Optional;

/** Loads the current state of a signed-in user, for {@link SessionUserRefreshFilter}. */
public interface SessionUserLookup {

    /** The user with this id as a session principal, or empty if the account no longer exists. */
    Optional<AuthenticatedUser> findById(long userId);
}
