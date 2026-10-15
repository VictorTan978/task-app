package com.taskapp.security;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.filter.OncePerRequestFilter;

import com.taskapp.security.audit.SecurityEvent;
import com.taskapp.security.audit.SecurityEventLogger;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

/**
 * Re-checks the session's signed-in user on every request, after CSRF and before remember-me and authorization.
 * The session is invalidated and the request continues as anonymous (protected endpoints answer 401 via the entry
 * point, unless a valid remember-me cookie signs the user in again) when:
 * <ul>
 * <li>the session lacks its lifetime attributes ({@link SessionAttributes}): not created by this version;</li>
 * <li>it reached its maximum length ({@link SessionAttributes#EXPIRES_AT}; logged as
 * {@code SESSION_MAX_LENGTH_REACHED}), checked without a database query;</li>
 * <li>the user is gone (account deleted, e.g. in another tab or device), so a stale session can never create data
 * for a deleted account;</li>
 * <li>the account is disabled (specs/roles-admin.md): also logged as {@code ACCOUNT_DISABLED_SESSION_ENDED}, and
 * the 401 says {@code ACCOUNT_DISABLED} (request attribute {@link ProblemAuthenticationEntryPoint#ACCOUNT_DISABLED});</li>
 * <li>the user's {@code sessions_valid_after} is later than the session's {@link SessionAttributes#VALID_FROM}
 * (log out other sessions, password change or stolen-cookie detection on another device).</li>
 * </ul>
 * Email, display name or role changed (e.g. in another session, or by an admin): the session's authentication is
 * rebuilt in place (same session id, no cookies), with the authorities of the current role.
 * <p>
 * Only principals held in an HTTP session are checked; one primary-key lookup per such request. The lookup is
 * resolved lazily, so security slices without the user repository (MockMvc tests without a session) still work.
 */
public class SessionUserRefreshFilter extends OncePerRequestFilter {

    private final Supplier<SessionUserLookup> lookup;
    private final SecurityContextRepository securityContextRepository;
    private final Clock clock;
    private final SecurityContextHolderStrategy contextHolder = SecurityContextHolder.getContextHolderStrategy();

    public SessionUserRefreshFilter(Supplier<SessionUserLookup> lookup,
            SecurityContextRepository securityContextRepository, Clock clock) {
        this.lookup = lookup;
        this.securityContextRepository = securityContextRepository;
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        HttpSession session = request.getSession(false);
        Authentication authentication = contextHolder.getContext().getAuthentication();
        if (session != null && authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof AuthenticatedUser current) {
            check(session, authentication, current, request, response);
        }
        chain.doFilter(request, response);
    }

    private void check(HttpSession session, Authentication authentication, AuthenticatedUser current,
            HttpServletRequest request, HttpServletResponse response) {
        Instant expiresAt;
        Instant validFrom;
        boolean rememberMe;
        try {
            expiresAt = session.getAttribute(SessionAttributes.EXPIRES_AT) instanceof Instant e ? e : null;
            validFrom = session.getAttribute(SessionAttributes.VALID_FROM) instanceof Instant v ? v : null;
            rememberMe = session.getAttribute(SessionAttributes.REMEMBER_ME_SERIES) != null;
        } catch (IllegalStateException invalidated) {
            // A concurrent request of the same session ended it.
            contextHolder.clearContext();
            return;
        }
        if (expiresAt == null || validFrom == null) {
            end(session);
            return;
        }
        if (!clock.instant().isBefore(expiresAt)) {
            end(session);
            SecurityEventLogger.log(SecurityEvent.SESSION_MAX_LENGTH_REACHED, "kind",
                    rememberMe ? "remember_me" : "normal", request, current.email());
            return;
        }
        Optional<SessionUser> found = lookup.get().findById(current.id());
        if (found.isEmpty()) {
            end(session);
            return;
        }
        SessionUser latest = found.get();
        if (latest.disabled()) {
            end(session);
            request.setAttribute(ProblemAuthenticationEntryPoint.ACCOUNT_DISABLED, Boolean.TRUE);
            SecurityEventLogger.log(SecurityEvent.ACCOUNT_DISABLED_SESSION_ENDED, request, latest.user().email());
            return;
        }
        if (latest.endsSessionValidFrom(validFrom)) {
            end(session);
            return;
        }
        List<GrantedAuthority> authorities = latest.authorities();
        if (!latest.user().equals(current) || !sameAuthorities(authentication.getAuthorities(), authorities)) {
            // Always the authorities of the current role: never copied from the old authentication.
            SecurityContext context = contextHolder.createEmptyContext();
            context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                    latest.user(), null, authorities));
            contextHolder.setContext(context);
            securityContextRepository.saveContext(context, request, response);
        }
    }

    private static boolean sameAuthorities(Collection<? extends GrantedAuthority> held,
            List<GrantedAuthority> current) {
        return names(held).equals(names(current));
    }

    private static Set<String> names(Collection<? extends GrantedAuthority> authorities) {
        return authorities.stream().map(GrantedAuthority::getAuthority).collect(Collectors.toSet());
    }

    private void end(HttpSession session) {
        try {
            session.invalidate();
        } catch (IllegalStateException alreadyInvalidated) {
            // A concurrent request of the same session got here first.
        }
        contextHolder.clearContext();
    }
}
