package com.taskapp.auth;

import java.util.Optional;

import org.springframework.stereotype.Component;

import com.taskapp.security.AuthenticatedUser;
import com.taskapp.security.SessionUser;
import com.taskapp.security.SessionUserLookup;

/** One primary-key lookup per signed-in request (see {@link com.taskapp.security.SessionUserRefreshFilter}). */
@Component
class UserSessionLookup implements SessionUserLookup {

    private final UserRepository userRepository;

    UserSessionLookup(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public Optional<SessionUser> findById(long userId) {
        return userRepository.findById(userId).map(user -> new SessionUser(
                new AuthenticatedUser(user.getId(), user.getEmail(), user.getDisplayName()),
                user.getSessionsValidAfter()));
    }
}
