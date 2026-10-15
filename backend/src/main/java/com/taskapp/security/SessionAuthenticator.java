package com.taskapp.security;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.function.Function;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.stereotype.Component;

import com.taskapp.auth.dto.UserResponse;
import com.taskapp.security.HashedRememberMeServices.RememberMeLogin;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

/**
 * Session mechanics for the JSON login/register/logout endpoints and for remember-me sign-ins: what Spring
 * Security's form login would otherwise do (session fixation protection, SecurityContext persistence, CSRF token
 * rotation), plus the session lifetime attributes ({@link SessionAttributes}) checked by
 * {@link SessionUserRefreshFilter}.
 */
@Component
public class SessionAuthenticator {

    private final SecurityContextRepository securityContextRepository;
    private final CsrfTokenRepository csrfTokenRepository;
    private final HashedRememberMeServices rememberMeServices;
    private final SessionProperties sessionProperties;
    private final Clock clock;
    private final String sessionCookieName;
    private final boolean requireHttps;
    private final SecurityContextHolderStrategy contextHolder = SecurityContextHolder.getContextHolderStrategy();

    public SessionAuthenticator(SecurityContextRepository securityContextRepository,
            CsrfTokenRepository csrfTokenRepository, HashedRememberMeServices rememberMeServices,
            SessionProperties sessionProperties, Clock clock,
            @Value("${server.servlet.session.cookie.name:JSESSIONID}") String sessionCookieName,
            @Value("${app.security.require-https:true}") boolean requireHttps) {
        this.securityContextRepository = securityContextRepository;
        this.csrfTokenRepository = csrfTokenRepository;
        this.rememberMeServices = rememberMeServices;
        this.sessionProperties = sessionProperties;
        this.clock = clock;
        this.sessionCookieName = sessionCookieName;
        this.requireHttps = requireHttps;
    }

    /**
     * Password login or register: replaces any existing session with a fresh one holding {@code user}, and rotates
     * the CSRF token. A remember-me login this device already had (cookie or session) is ended. With
     * {@code rememberMe} a new one is created (cookie set, session lasts until it expires); without it the session
     * is a normal one ({@code app.session.max-length}) and a remember-me cookie in the request is expired.
     */
    public void signIn(UserResponse user, boolean rememberMe, HttpServletRequest request,
            HttpServletResponse response) {
        rememberMeServices.endThisDevicesLogin(request, rememberMeSeries(request));
        RememberMeLogin login = null;
        if (rememberMe) {
            login = rememberMeServices.createLogin(user.id(), request, response);
        } else if (rememberMeServices.hasCookie(request)) {
            rememberMeServices.expireCookie(request, response);
        }

        Instant now = now();
        AuthenticatedUser principal = new AuthenticatedUser(user.id(), user.email(), user.displayName());
        startSession(UsernamePasswordAuthenticationToken.authenticated(principal, null, user.role().authorities()),
                now, login == null ? now.plus(sessionProperties.maxLength()) : login.expiresAt(),
                login == null ? null : login.seriesId(), request, response);
        rotateCsrfToken(request, response);
    }

    /**
     * Session setup for an automatic sign-in from the remember-me cookie (the remember-me filter's session
     * strategy): a new session like a password login, lasting until the remember-me login expires. Rotates the
     * CSRF token, unless this response already issues a brand-new one (the request had no XSRF-TOKEN cookie), so
     * the response carries exactly one XSRF-TOKEN cookie.
     */
    void signInFromRememberMe(Authentication authentication, HttpServletRequest request,
            HttpServletResponse response) {
        RememberMeLogin login = (RememberMeLogin) authentication.getDetails();
        startSession(authentication, now(), login.expiresAt(), login.seriesId(), request, response);
        rotateCsrfTokenUnlessNewInThisResponse(request, response);
    }

    /**
     * Replaces the signed-in user held by the current session with {@code user} (e.g. after a display name
     * change). Keeps the session id and the CSRF token; never creates a session or sets a cookie.
     */
    public void refreshPrincipal(UserResponse user, HttpServletRequest request, HttpServletResponse response) {
        AuthenticatedUser principal = new AuthenticatedUser(user.id(), user.email(), user.displayName());
        SecurityContext context = contextHolder.createEmptyContext();
        context.setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(principal, null, user.role().authorities()));
        contextHolder.setContext(context);
        if (request.getSession(false) != null) {
            securityContextRepository.saveContext(context, request, response);
        }
    }

    /**
     * Runs {@code action} (which ends all OTHER sessions of the user, see {@link SessionRenewal}) and keeps this
     * session alive through it: this session's validity time is moved to the new cutoff BEFORE the action, so
     * parallel requests of this session (other tabs) are never ended by it; on failure it is moved back. On success
     * the session id changes (the container sets the new JSESSIONID); the maximum length, the remember-me login and
     * the CSRF token stay as they are.
     */
    public <T> T keepOnlyThisSession(HttpServletRequest request, Function<SessionRenewal, T> action) {
        Instant now = now();
        HttpSession session = request.getSession(false);
        if (session == null) {
            return action.apply(new SessionRenewal(now, now, null));
        }
        Instant previous = session.getAttribute(SessionAttributes.VALID_FROM) instanceof Instant validFrom
                ? validFrom : now;
        String series = (String) session.getAttribute(SessionAttributes.REMEMBER_ME_SERIES);
        session.setAttribute(SessionAttributes.VALID_FROM, now);
        T result;
        try {
            result = action.apply(new SessionRenewal(previous, now, series));
        } catch (RuntimeException e) {
            try {
                if (now.equals(session.getAttribute(SessionAttributes.VALID_FROM))) {
                    session.setAttribute(SessionAttributes.VALID_FROM, previous);
                }
            } catch (IllegalStateException invalidated) {
                // Nothing to restore.
            }
            throw e;
        }
        try {
            request.changeSessionId();
        } catch (IllegalStateException invalidated) {
            // A parallel request ended this session meanwhile; the next request answers 401.
        }
        return result;
    }

    /**
     * Logout and account delete. Ends this device's remember-me login, invalidates the session, expires the
     * JSESSIONID and remember-me cookies and rotates the CSRF token. Idempotent: works without a session too.
     */
    public void signOut(HttpServletRequest request, HttpServletResponse response) {
        rememberMeServices.endThisDevicesLogin(request, rememberMeSeries(request));
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
        rememberMeServices.expireCookie(request, response);
        rotateCsrfToken(request, response);
    }

    private void startSession(Authentication authentication, Instant now, Instant expiresAt, String rememberMeSeries,
            HttpServletRequest request, HttpServletResponse response) {
        invalidateSession(request);
        HttpSession session = request.getSession(true);
        session.setAttribute(SessionAttributes.EXPIRES_AT, expiresAt);
        session.setAttribute(SessionAttributes.VALID_FROM, now);
        if (rememberMeSeries != null) {
            session.setAttribute(SessionAttributes.REMEMBER_ME_SERIES, rememberMeSeries);
        }

        SecurityContext context = contextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        contextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);
    }

    /** The remember-me login the current session was signed in with, or null. */
    private static String rememberMeSeries(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return null;
        }
        try {
            return (String) session.getAttribute(SessionAttributes.REMEMBER_ME_SERIES);
        } catch (IllegalStateException invalidated) {
            return null;
        }
    }

    private Instant now() {
        // Same precision as the DATETIME(6) columns it is compared with.
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static void invalidateSession(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            try {
                session.invalidate();
            } catch (IllegalStateException alreadyInvalidated) {
                // A concurrent request got here first.
            }
        }
    }

    private void rotateCsrfToken(HttpServletRequest request, HttpServletResponse response) {
        CsrfToken token = csrfTokenRepository.generateToken(request);
        csrfTokenRepository.saveToken(token, request, response);
    }

    /**
     * {@code CsrfCookieFilter} has already resolved this request's token. If it differs from the request's cookie
     * (none was sent), it was generated for this response and is already set on it: nobody else can know it.
     */
    private void rotateCsrfTokenUnlessNewInThisResponse(HttpServletRequest request, HttpServletResponse response) {
        CsrfToken current = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        CsrfToken fromCookie = csrfTokenRepository.loadToken(request);
        if (current != null && (fromCookie == null || !fromCookie.getToken().equals(current.getToken()))) {
            return;
        }
        rotateCsrfToken(request, response);
    }
}
