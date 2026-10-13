package com.taskapp.security;

import java.io.IOException;
import java.util.Optional;
import java.util.function.Supplier;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

/**
 * Re-checks the session's signed-in user on every request, after CSRF and before authorization:
 * <ul>
 * <li>User gone (account deleted, e.g. in another tab or device): the session is invalidated and the request
 * continues as anonymous, so protected endpoints answer 401 via the entry point and a stale session can never
 * create data for a deleted account.</li>
 * <li>Email or display name changed (e.g. in another session): the session principal is refreshed in place
 * (same session id, no cookies).</li>
 * </ul>
 * Only principals held in an HTTP session are checked; one primary-key lookup per such request. The lookup is
 * resolved lazily, so security slices without the user repository (MockMvc tests without a session) still work.
 */
public class SessionUserRefreshFilter extends OncePerRequestFilter {

    private final Supplier<SessionUserLookup> lookup;
    private final SecurityContextRepository securityContextRepository;
    private final SecurityContextHolderStrategy contextHolder = SecurityContextHolder.getContextHolderStrategy();

    public SessionUserRefreshFilter(Supplier<SessionUserLookup> lookup,
            SecurityContextRepository securityContextRepository) {
        this.lookup = lookup;
        this.securityContextRepository = securityContextRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        HttpSession session = request.getSession(false);
        Authentication authentication = contextHolder.getContext().getAuthentication();
        if (session != null && authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof AuthenticatedUser current) {
            Optional<AuthenticatedUser> latest = lookup.get().findById(current.id());
            if (latest.isEmpty()) {
                invalidate(session);
                contextHolder.clearContext();
            } else if (!latest.get().equals(current)) {
                SecurityContext context = contextHolder.createEmptyContext();
                context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                        latest.get(), null, authentication.getAuthorities()));
                contextHolder.setContext(context);
                securityContextRepository.saveContext(context, request, response);
            }
        }
        chain.doFilter(request, response);
    }

    private static void invalidate(HttpSession session) {
        try {
            session.invalidate();
        } catch (IllegalStateException alreadyInvalidated) {
            // A concurrent request of the same session got here first.
        }
    }
}
