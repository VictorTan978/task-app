package com.taskapp.security;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.stereotype.Component;

import com.taskapp.auth.dto.UserResponse;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

/**
 * Session mechanics for the JSON login/register/logout endpoints: what Spring Security's form login
 * would otherwise do (session fixation protection, SecurityContext persistence, CSRF token rotation).
 */
@Component
public class SessionAuthenticator {

    private final SecurityContextRepository securityContextRepository;
    private final CsrfTokenRepository csrfTokenRepository;
    private final String sessionCookieName;
    private final boolean requireHttps;
    private final SecurityContextHolderStrategy contextHolder = SecurityContextHolder.getContextHolderStrategy();

    public SessionAuthenticator(SecurityContextRepository securityContextRepository,
            CsrfTokenRepository csrfTokenRepository,
            @Value("${server.servlet.session.cookie.name:JSESSIONID}") String sessionCookieName,
            @Value("${app.security.require-https:true}") boolean requireHttps) {
        this.securityContextRepository = securityContextRepository;
        this.csrfTokenRepository = csrfTokenRepository;
        this.sessionCookieName = sessionCookieName;
        this.requireHttps = requireHttps;
    }

    /** Replaces any existing session with a fresh one holding {@code user}, and rotates the CSRF token. */
    public void signIn(UserResponse user, HttpServletRequest request, HttpServletResponse response) {
        invalidateSession(request);
        request.getSession(true);

        AuthenticatedUser principal = new AuthenticatedUser(user.id(), user.email(), user.displayName());
        SecurityContext context = contextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of()));
        contextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);

        rotateCsrfToken(request, response);
    }

    /**
     * Replaces the signed-in user held by the current session with {@code user} (e.g. after a display name
     * change). Keeps the session id and the CSRF token; never creates a session or sets a cookie.
     */
    public void refreshPrincipal(UserResponse user, HttpServletRequest request, HttpServletResponse response) {
        AuthenticatedUser principal = new AuthenticatedUser(user.id(), user.email(), user.displayName());
        SecurityContext context = contextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of()));
        contextHolder.setContext(context);
        if (request.getSession(false) != null) {
            securityContextRepository.saveContext(context, request, response);
        }
    }

    /** Idempotent: works without a session too. */
    public void signOut(HttpServletRequest request, HttpServletResponse response) {
        invalidateSession(request);
        contextHolder.clearContext();
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(sessionCookieName, "")
                .path("/")
                .maxAge(0)
                .httpOnly(true)
                .sameSite("Lax")
                // Same rule as the session cookie itself (server.servlet.session.cookie.secure + Tomcat).
                .secure(requireHttps || request.isSecure())
                .build()
                .toString());
        rotateCsrfToken(request, response);
    }

    private static void invalidateSession(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
    }

    private void rotateCsrfToken(HttpServletRequest request, HttpServletResponse response) {
        CsrfToken token = csrfTokenRepository.generateToken(request);
        csrfTokenRepository.saveToken(token, request, response);
    }
}
