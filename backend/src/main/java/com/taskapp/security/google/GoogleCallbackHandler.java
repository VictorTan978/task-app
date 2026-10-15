package com.taskapp.security.google;

import java.io.IOException;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;

import com.taskapp.auth.GoogleAccountService;
import com.taskapp.auth.GoogleConnect;
import com.taskapp.auth.GoogleIdentity;
import com.taskapp.auth.GoogleLogin;
import com.taskapp.security.AuthenticatedUser;
import com.taskapp.security.SessionAuthenticator;
import com.taskapp.security.audit.AuditOrigin;
import com.taskapp.security.audit.SecurityEvent;
import com.taskapp.security.audit.SecurityEventLogger;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

/**
 * Ends every Google callback with a 302 to a frontend route (contract 0.8.0, redirect table in "Sign in with
 * Google"), never JSON or an error page.
 * <ul>
 * <li>Success (Google's ID token passed every check): the account is matched by {@link GoogleAccountService}. A
 * login signs in exactly like a password login without "remember me" ({@link SessionAuthenticator#signIn}: new
 * session, rotated CSRF token, 12-hour maximum, any remember-me cookie ended). A connect keeps the session as it
 * is.</li>
 * <li>Anything else ends the flow: nothing is signed in, and an anonymous session created only to hold the pending
 * request is invalidated.</li>
 * </ul>
 * The OAuth2 authentication itself is never stored (the login filter's context repository discards it), and no
 * code, state, nonce or token is ever logged: only fixed reason values.
 */
final class GoogleCallbackHandler implements AuthenticationSuccessHandler, AuthenticationFailureHandler {

    private static final Logger log = LoggerFactory.getLogger(GoogleCallbackHandler.class);

    private final GoogleSignIn google;
    private final Supplier<GoogleAccountService> accounts;
    private final Supplier<SessionAuthenticator> sessions;

    GoogleCallbackHandler(GoogleSignIn google, Supplier<GoogleAccountService> accounts,
            Supplier<SessionAuthenticator> sessions) {
        this.google = google;
        this.accounts = accounts;
        this.sessions = sessions;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
            Authentication authentication) throws IOException {
        OAuth2AuthorizationRequest pending = PendingAuthorizationRequests.removed(request);
        GoogleFlowIntent intent = intentOf(pending);
        // Holds the OAuth2 token until here; a login sets the real user below, a connect keeps the stored session.
        SecurityContextHolder.clearContext();
        try {
            GoogleIdentity identity = identity(authentication);
            if (intent == GoogleFlowIntent.CONNECT) {
                connect(request, response, pending, identity);
            } else {
                login(request, response, pending, identity);
            }
        } catch (RuntimeException e) {
            serverError(request, response, pending, e);
        }
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException exception) throws IOException {
        OAuth2AuthorizationRequest pending = PendingAuthorizationRequests.removed(request);
        fail(request, response, pending, reasonFor(exception), null, failureRoute(request, pending));
    }

    private void login(HttpServletRequest request, HttpServletResponse response, OAuth2AuthorizationRequest pending,
            GoogleIdentity identity) throws IOException {
        GoogleLogin result = accounts.get().signIn(identity);
        if (!result.outcome().signsIn()) {
            OAuthResult route = switch (result.outcome()) {
                case UNVERIFIED -> OAuthResult.GOOGLE_UNVERIFIED;
                case DISABLED -> OAuthResult.ACCOUNT_DISABLED;
                default -> OAuthResult.GOOGLE_FAILED;
            };
            fail(request, response, pending, result.reason(), identity.email(), route);
            return;
        }
        String email = result.user().email();
        if (result.outcome() == GoogleLogin.Outcome.CREATED) {
            audit(SecurityEvent.GOOGLE_ACCOUNT_CREATED, request, email);
        } else if (result.outcome() == GoogleLogin.Outcome.LINKED) {
            audit(SecurityEvent.GOOGLE_LINKED, request, email, "trigger", "login");
        }
        sessions.get().signIn(result.user(), false, request, response);
        audit(SecurityEvent.GOOGLE_LOGIN_SUCCESS, request, email);
        response.sendRedirect(google.redirectUrl(OAuthResult.LOGGED_IN));
    }

    private void connect(HttpServletRequest request, HttpServletResponse response, OAuth2AuthorizationRequest pending,
            GoogleIdentity identity) throws IOException {
        AuthenticatedUser user = connectingUser(request, pending);
        if (user == null) {
            fail(request, response, pending, "no_session", null, OAuthResult.GOOGLE_FAILED);
            return;
        }
        GoogleConnect result = accounts.get().connect(user.id(), identity);
        switch (result.outcome()) {
            case CONNECTED -> {
                audit(SecurityEvent.GOOGLE_LINKED, request, user.email(), "trigger", "connect");
                response.sendRedirect(google.redirectUrl(OAuthResult.GOOGLE_CONNECTED));
            }
            case ALREADY_CONNECTED -> response.sendRedirect(google.redirectUrl(OAuthResult.GOOGLE_CONNECTED));
            case IN_USE -> fail(request, response, pending, "google_in_use", null, OAuthResult.GOOGLE_IN_USE);
            case OTHER_GOOGLE_CONNECTED -> fail(request, response, pending, "already_connected_other", null,
                    OAuthResult.GOOGLE_CONNECT_FAILED);
            case INVALID -> fail(request, response, pending, "invalid_identity", null,
                    OAuthResult.GOOGLE_CONNECT_FAILED);
            case USER_GONE -> fail(request, response, pending, "user_gone", null, OAuthResult.GOOGLE_FAILED);
            case DISABLED -> fail(request, response, pending, "account_disabled", null, OAuthResult.ACCOUNT_DISABLED);
        }
    }

    /** Any unexpected error: the failure route, like every other failure. Logs only exception types. */
    void serverError(HttpServletRequest request, HttpServletResponse response, OAuth2AuthorizationRequest pending,
            Exception e) throws IOException {
        Throwable root = NestedExceptionUtils.getMostSpecificCause(e);
        log.error("Google callback failed: {} ({})", e.getClass().getName(), root.getClass().getName());
        SecurityContextHolder.clearContext();
        fail(request, response, pending, "error", null, failureRoute(request, pending));
    }

    /** Over the callback limit (already logged as RATE_LIMITED): the failure route, nothing else logged. */
    void rateLimited(HttpServletRequest request, HttpServletResponse response, OAuth2AuthorizationRequest pending)
            throws IOException {
        finish(request, response, failureRoute(request, pending));
    }

    /** Logs GOOGLE_LOGIN_FAILURE, ends a flow-only session and redirects to {@code route}. */
    private void fail(HttpServletRequest request, HttpServletResponse response, OAuth2AuthorizationRequest pending,
            String reason, String googleEmail, OAuthResult route) throws IOException {
        AuthenticatedUser signedIn = GoogleFlowGuardFilter.signedInUser(request);
        audit(SecurityEvent.GOOGLE_LOGIN_FAILURE, request, signedIn != null ? signedIn.email() : googleEmail,
                "intent", pending == null ? null : intentOf(pending).value(), "reason", reason);
        finish(request, response, route);
    }

    private void finish(HttpServletRequest request, HttpServletResponse response, OAuthResult route)
            throws IOException {
        endFlowOnlySession(request);
        response.sendRedirect(google.redirectUrl(route));
    }

    /**
     * Failure route of the pending request: a connect whose session is still signed in as the same user goes back to
     * {@code /account}; everything else (login, unknown request, connect whose session ended) to {@code /login}.
     */
    private static OAuthResult failureRoute(HttpServletRequest request, OAuth2AuthorizationRequest pending) {
        if (intentOf(pending) == GoogleFlowIntent.CONNECT && connectingUser(request, pending) != null) {
            return OAuthResult.GOOGLE_CONNECT_FAILED;
        }
        return OAuthResult.GOOGLE_FAILED;
    }

    /** The signed-in user of this callback, if it is the one who started the connect; otherwise null. */
    private static AuthenticatedUser connectingUser(HttpServletRequest request, OAuth2AuthorizationRequest pending) {
        AuthenticatedUser signedIn = GoogleFlowGuardFilter.signedInUser(request);
        Object startedBy = pending == null ? null : pending.getAttribute(GoogleAuthorizationRequestResolver.USER_ID);
        return signedIn != null && startedBy instanceof Long id && id == signedIn.id() ? signedIn : null;
    }

    /** A session that isn't signed in only held the pending request: it is invalidated (contract 0.8.0). */
    static void endFlowOnlySession(HttpServletRequest request) {
        if (GoogleFlowGuardFilter.signedInUser(request) != null) {
            return;
        }
        HttpSession session = request.getSession(false);
        if (session != null) {
            try {
                session.invalidate();
            } catch (IllegalStateException alreadyInvalidated) {
                // A parallel request ended it.
            }
        }
    }

    private static GoogleFlowIntent intentOf(OAuth2AuthorizationRequest pending) {
        return pending == null ? GoogleFlowIntent.LOGIN
                : GoogleFlowIntent.fromAttribute(pending.getAttribute(GoogleAuthorizationRequestResolver.INTENT));
    }

    /** Only from the ID token Spring Security verified (signature, issuer, audience, expiry, nonce). */
    private static GoogleIdentity identity(Authentication authentication) {
        if (!(authentication.getPrincipal() instanceof OidcUser user) || user.getIdToken() == null) {
            throw new IllegalStateException("Not an OpenID Connect sign-in");
        }
        OidcIdToken token = user.getIdToken();
        return new GoogleIdentity(token.getSubject(), token.getEmail(), Boolean.TRUE.equals(token.getEmailVerified()),
                token.getFullName());
    }

    /** A fixed value per failure kind; never the provider's own description. */
    static String reasonFor(AuthenticationException exception) {
        if (!(exception instanceof OAuth2AuthenticationException oauth) || oauth.getError() == null) {
            return "error";
        }
        return switch (oauth.getError().getErrorCode()) {
            case "access_denied" -> "cancelled";
            case "authorization_request_not_found" -> "request_not_found";
            case "invalid_state_parameter" -> "invalid_state";
            case "invalid_request" -> "invalid_request";
            case "invalid_id_token", "invalid_nonce" -> "invalid_token";
            case "invalid_token_response" -> "token_error";
            case "client_registration_not_found" -> "provider_unavailable";
            default -> "provider_error";
        };
    }

    /** {@code fields} are name/value pairs; a null value is written as {@code -}. */
    static void audit(SecurityEvent event, HttpServletRequest request, String email, String... fields) {
        SecurityEventLogger.log(event, AuditOrigin.of(request), email, fields);
    }
}
