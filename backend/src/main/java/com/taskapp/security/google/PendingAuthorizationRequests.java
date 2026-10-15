package com.taskapp.security.google;

import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * The pending Google request ({@code state}, {@code nonce}, PKCE verifier, intent) lives in the HTTP session of the
 * browser that started the flow (one per browser: a new start replaces it). This is the login-CSRF protection: a
 * callback is only accepted with the {@code state} stored in the session of the browser that delivers it.
 * <p>
 * The request removed by the callback is also kept as a request attribute, so the success and failure handlers
 * know the intent ({@link #removed}).
 */
final class PendingAuthorizationRequests implements AuthorizationRequestRepository<OAuth2AuthorizationRequest> {

    private static final String REMOVED = PendingAuthorizationRequests.class.getName() + ".REMOVED";

    private final HttpSessionOAuth2AuthorizationRequestRepository sessions =
            new HttpSessionOAuth2AuthorizationRequestRepository();

    /** The pending request whose {@code state} equals the request's {@code state} parameter, or null. */
    @Override
    public OAuth2AuthorizationRequest loadAuthorizationRequest(HttpServletRequest request) {
        return sessions.loadAuthorizationRequest(request);
    }

    @Override
    public void saveAuthorizationRequest(OAuth2AuthorizationRequest authorizationRequest,
            HttpServletRequest request, HttpServletResponse response) {
        sessions.saveAuthorizationRequest(authorizationRequest, request, response);
    }

    @Override
    public OAuth2AuthorizationRequest removeAuthorizationRequest(HttpServletRequest request,
            HttpServletResponse response) {
        OAuth2AuthorizationRequest removed = sessions.removeAuthorizationRequest(request, response);
        if (removed != null) {
            request.setAttribute(REMOVED, removed);
        }
        return removed;
    }

    /** The pending request this callback removed (matched by {@code state}), or null if none matched. */
    static OAuth2AuthorizationRequest removed(HttpServletRequest request) {
        return request.getAttribute(REMOVED) instanceof OAuth2AuthorizationRequest removed ? removed : null;
    }
}
