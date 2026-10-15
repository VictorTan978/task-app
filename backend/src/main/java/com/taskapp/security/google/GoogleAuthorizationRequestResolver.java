package com.taskapp.security.google;

import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Spring's resolver ({@code state}, {@code nonce}, and PKCE because the registration requires it), plus the intent
 * and, for a connect, the signed-in user id, as checked by {@link GoogleFlowGuardFilter}. Both are stored with the
 * pending request in the session, never sent to Google.
 */
final class GoogleAuthorizationRequestResolver implements OAuth2AuthorizationRequestResolver {

    static final String INTENT = "taskapp_intent";
    static final String USER_ID = "taskapp_user_id";

    private final DefaultOAuth2AuthorizationRequestResolver delegate;

    GoogleAuthorizationRequestResolver(ClientRegistrationRepository registrations) {
        this.delegate = new DefaultOAuth2AuthorizationRequestResolver(registrations,
                GoogleSignIn.AUTHORIZATION_BASE_URI);
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
        return withIntent(request, delegate.resolve(request));
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String clientRegistrationId) {
        return withIntent(request, delegate.resolve(request, clientRegistrationId));
    }

    /** Null (no flow) unless the guard filter accepted this start. */
    private static OAuth2AuthorizationRequest withIntent(HttpServletRequest request,
            OAuth2AuthorizationRequest resolved) {
        GoogleFlowIntent intent = GoogleFlowGuardFilter.acceptedIntent(request);
        if (resolved == null || intent == null) {
            return null;
        }
        Long userId = GoogleFlowGuardFilter.connectingUserId(request);
        return OAuth2AuthorizationRequest.from(resolved).attributes(attributes -> {
            attributes.put(INTENT, intent.value());
            if (userId != null) {
                attributes.put(USER_ID, userId);
            }
        }).build();
    }
}
