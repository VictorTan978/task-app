package com.taskapp.security;

import java.util.Optional;

/** Loads the current state of a signed-in user, for {@link SessionUserRefreshFilter} and remember-me sign-ins. */
public interface SessionUserLookup {

    /** The user with this id, or empty if the account no longer exists. One primary-key lookup. */
    Optional<SessionUser> findById(long userId);
}
