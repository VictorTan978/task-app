package com.taskapp.security;

import java.util.Optional;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Test-only {@link SessionUserLookup} for MockMvc slices without a database: the signed-in user always still
 * exists, unchanged, with no session cutoff. spring-security-test's {@code authentication(...)} stores the context
 * in the (mock) HTTP session, so {@link SessionUserRefreshFilter} runs in those slices; add this class to their
 * {@code @Import} and sign in with {@link TestSessions#signedInSession}.
 */
public class UnchangedSessionUserLookup implements SessionUserLookup {

    @Override
    public Optional<SessionUser> findById(long userId) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AuthenticatedUser user
                && user.id() == userId) {
            // Same role as the session holds (USER when it holds none), so the filter changes nothing.
            Role role = authentication.getAuthorities().stream()
                    .map(a -> a.getAuthority())
                    .filter(a -> a.startsWith("ROLE_"))
                    .map(a -> Role.valueOf(a.substring("ROLE_".length())))
                    .findFirst().orElse(Role.USER);
            return Optional.of(new SessionUser(user, null, role, false));
        }
        return Optional.empty();
    }
}
