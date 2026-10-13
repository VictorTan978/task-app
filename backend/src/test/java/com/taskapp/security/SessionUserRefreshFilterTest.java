package com.taskapp.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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

import jakarta.servlet.FilterChain;

class SessionUserRefreshFilterTest {

    private static final AuthenticatedUser BOB = new AuthenticatedUser(1L, "bob@example.com", "Bob");

    private final SessionUserLookup lookup = mock(SessionUserLookup.class);
    private final SessionUserRefreshFilter filter =
            new SessionUserRefreshFilter(() -> lookup, new HttpSessionSecurityContextRepository());

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
    }

    private void signedInWithSession(AuthenticatedUser principal) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of()));
        SecurityContextHolder.setContext(context);
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        request.setSession(session);
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
        when(lookup.findById(1L)).thenReturn(Optional.of(renamed));

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
        when(lookup.findById(1L)).thenReturn(Optional.of(BOB));

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
