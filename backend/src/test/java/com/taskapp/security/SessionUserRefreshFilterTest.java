package com.taskapp.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

import com.taskapp.security.audit.SecurityLogCapture;
import com.taskapp.security.ratelimit.MutableClock;

import jakarta.servlet.FilterChain;

class SessionUserRefreshFilterTest {

    private static final AuthenticatedUser BOB = new AuthenticatedUser(1L, "bob@example.com", "Bob");

    private static final Instant SIGNED_IN = Instant.parse("2026-10-13T08:00:00Z");

    private final SessionUserLookup lookup = mock(SessionUserLookup.class);
    private final MutableClock clock = new MutableClock(SIGNED_IN);
    private final SessionUserRefreshFilter filter =
            new SessionUserRefreshFilter(() -> lookup, new HttpSessionSecurityContextRepository(), clock);
    private final SecurityLogCapture capture = new SecurityLogCapture().start();

    private final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/tasks");
    private final MockHttpServletResponse response = new MockHttpServletResponse();
    private final MockHttpSession session = new MockHttpSession();
    /** The authentication the rest of the chain (authorization, controllers) sees. */
    private final AtomicReference<Authentication> seenByChain = new AtomicReference<>();
    private final FilterChain chain = (req, res) ->
            seenByChain.set(SecurityContextHolder.getContext().getAuthentication());

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        capture.stop();
    }

    /** A normal 12h session signed in at SIGNED_IN, like SessionAuthenticator creates. */
    private void signedInWithSession(AuthenticatedUser principal) {
        signedInWithSessionWithoutLifetime(principal);
        session.setAttribute(SessionAttributes.EXPIRES_AT, SIGNED_IN.plus(Duration.ofHours(12)));
        session.setAttribute(SessionAttributes.VALID_FROM, SIGNED_IN);
    }

    private void signedInWithSessionWithoutLifetime(AuthenticatedUser principal) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of()));
        SecurityContextHolder.setContext(context);
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        request.setSession(session);
    }

    private static SessionUser current(AuthenticatedUser user) {
        return new SessionUser(user, null);
    }

    // --- maximum length (specs/sessions.md AC7) ---

    @Test
    void sessionIsEndedAtItsMaximumLengthEvenIfBusyWithoutADatabaseQuery() throws Exception {
        signedInWithSession(BOB);
        clock.advance(Duration.ofHours(12).minusNanos(1000));
        when(lookup.findById(1L)).thenReturn(Optional.of(current(BOB)));
        filter.doFilter(request, response, chain);
        assertThat(seenByChain.get().getPrincipal()).isEqualTo(BOB);

        clock.advance(Duration.ofNanos(1000));
        filter.doFilter(request, response, chain);

        assertThat(session.isInvalid()).isTrue();
        assertThat(seenByChain.get()).isNull();
        assertThat(capture.lines()).containsExactly("SECURITY event=SESSION_MAX_LENGTH_REACHED kind=normal"
                + " email=bob@example.com ip=127.0.0.1 path=/api/tasks");
        assertThat(capture.events().get(0).getLevel().toString()).isEqualTo("INFO");
    }

    @Test
    void rememberMeSessionReportsItsKind() throws Exception {
        signedInWithSession(BOB);
        session.setAttribute(SessionAttributes.REMEMBER_ME_SERIES, "A".repeat(42) + "Q");
        session.setAttribute(SessionAttributes.EXPIRES_AT, SIGNED_IN.plus(Duration.ofDays(30)));
        clock.advance(Duration.ofDays(30));

        filter.doFilter(request, response, chain);

        assertThat(session.isInvalid()).isTrue();
        assertThat(capture.lines()).singleElement().asString().contains("kind=remember_me");
    }

    @Test
    void sessionWithoutLifetimeAttributesIsTreatedAsEnded() throws Exception {
        signedInWithSessionWithoutLifetime(BOB);

        filter.doFilter(request, response, chain);

        assertThat(session.isInvalid()).isTrue();
        assertThat(seenByChain.get()).isNull();
        verifyNoInteractions(lookup);
    }

    // --- sessions_valid_after (AC10, AC11) ---

    @Test
    void sessionValidFromBeforeTheUsersCutoffIsEnded() throws Exception {
        signedInWithSession(BOB);
        when(lookup.findById(1L)).thenReturn(Optional.of(new SessionUser(BOB, SIGNED_IN.plusNanos(1000))));

        filter.doFilter(request, response, chain);

        assertThat(session.isInvalid()).isTrue();
        assertThat(seenByChain.get()).isNull();
        assertThat(response.getHeaders("Set-Cookie")).isEmpty();
    }

    @Test
    void sessionValidFromTheCutoffOrLaterSurvives() throws Exception {
        signedInWithSession(BOB);
        when(lookup.findById(1L)).thenReturn(Optional.of(new SessionUser(BOB, SIGNED_IN)));

        filter.doFilter(request, response, chain);

        assertThat(session.isInvalid()).isFalse();
        assertThat(seenByChain.get().getPrincipal()).isEqualTo(BOB);
    }

    @Test
    void deletedUserInvalidatesSessionAndContinuesAsAnonymous() throws Exception {
        signedInWithSession(BOB);
        when(lookup.findById(1L)).thenReturn(Optional.empty());

        filter.doFilter(request, response, chain);

        assertThat(session.isInvalid()).isTrue();
        assertThat(seenByChain.get()).isNull();
        assertThat(response.getHeaders("Set-Cookie")).isEmpty();
    }

    @Test
    void changedDisplayNameRefreshesPrincipalInTheSameSession() throws Exception {
        signedInWithSession(BOB);
        AuthenticatedUser renamed = new AuthenticatedUser(1L, "bob@example.com", "Bobby");
        when(lookup.findById(1L)).thenReturn(Optional.of(current(renamed)));

        filter.doFilter(request, response, chain);

        assertThat(seenByChain.get().getPrincipal()).isEqualTo(renamed);
        assertThat(seenByChain.get().isAuthenticated()).isTrue();
        assertThat(session.isInvalid()).isFalse();
        assertThat(request.getSession(false)).isSameAs(session);
        SecurityContext stored = (SecurityContext) session.getAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        assertThat(stored.getAuthentication().getPrincipal()).isEqualTo(renamed);
        assertThat(response.getHeaders("Set-Cookie")).isEmpty();
    }

    @Test
    void unchangedUserKeepsTheSameAuthentication() throws Exception {
        signedInWithSession(BOB);
        Authentication before = SecurityContextHolder.getContext().getAuthentication();
        when(lookup.findById(1L)).thenReturn(Optional.of(current(BOB)));

        filter.doFilter(request, response, chain);

        assertThat(seenByChain.get()).isSameAs(before);
        assertThat(session.isInvalid()).isFalse();
    }

    @Test
    void requestsWithoutSessionOrWithoutSignedInUserAreNotLookedUp() throws Exception {
        // Anonymous with a session.
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new AnonymousAuthenticationToken("key", "anonymousUser",
                AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));
        SecurityContextHolder.setContext(context);
        request.setSession(session);
        filter.doFilter(request, response, chain);

        // Signed-in principal but no HTTP session.
        SecurityContextHolder.clearContext();
        MockHttpServletRequest noSession = new MockHttpServletRequest("GET", "/api/tasks");
        SecurityContext signedIn = SecurityContextHolder.createEmptyContext();
        signedIn.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(BOB, null, List.of()));
        SecurityContextHolder.setContext(signedIn);
        filter.doFilter(noSession, new MockHttpServletResponse(), chain);

        verifyNoInteractions(lookup);
        assertThat(seenByChain.get().getPrincipal()).isEqualTo(BOB);
    }
}
