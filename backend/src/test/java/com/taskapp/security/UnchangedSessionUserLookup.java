package com.taskapp.security;

import java.util.Optional;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Test-only {@link SessionUserLookup} for MockMvc slices without a database: the signed-in user always still
 * exists, unchanged. spring-security-test's {@code authentication(...)} stores the context in the (mock) HTTP
 * session, so {@link SessionUserRefreshFilter} runs in those slices; add this class to their {@code @Import}.
 */
public class UnchangedSessionUserLookup implements SessionUserLookup {

    @Override
    public Optional<AuthenticatedUser> findById(long userId) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AuthenticatedUser user
                && user.id() == userId) {
            return Optional.of(user);
        }
        return Optional.empty();
    }
}
