package com.taskapp.security.google;

import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.ClientRegistrations;

/**
 * "Sign in with Google" as configured for this run (specs/google-sign-in.md): on or off, the paths of the flow, the
 * Google client registration (loaded lazily) and the frontend URLs the flow ends on.
 */
public class GoogleSignIn {

    public static final String REGISTRATION_ID = "google";
    /** Base of {@link #START_PATH}, as Spring's authorization request resolver expects it. */
    public static final String AUTHORIZATION_BASE_URI = "/api/auth/oauth2/authorization";
    public static final String START_PATH = AUTHORIZATION_BASE_URI + "/" + REGISTRATION_ID;
    public static final String CALLBACK_PATH = "/api/auth/oauth2/callback/" + REGISTRATION_ID;
    /** Every path under this prefix other than the two above answers 404. */
    public static final String FLOW_PREFIX = "/api/auth/oauth2/";

    private final boolean enabled;
    private final String frontendBaseUrl;
    private final ClientRegistrationRepository registrations;

    /**
     * @param frontendBaseUrl {@code APP_BASE_URL}: the frontend and API origin; the callback registered at Google is
     *                        {@code <APP_BASE_URL>/api/auth/oauth2/callback/google}
     */
    public GoogleSignIn(GoogleProperties properties, String frontendBaseUrl) {
        this.enabled = properties.enabled();
        this.frontendBaseUrl = stripTrailingSlashes(frontendBaseUrl);
        this.registrations = enabled
                ? new LazyClientRegistrationRepository(REGISTRATION_ID, () -> load(properties, this.frontendBaseUrl))
                : null;
    }

    public boolean enabled() {
        return enabled;
    }

    /** The lazily loaded Google registration; only when {@link #enabled()}. */
    ClientRegistrationRepository registrations() {
        if (!enabled) {
            throw new IllegalStateException("Google sign-in is not configured");
        }
        return registrations;
    }

    /** Absolute frontend URL for {@code result}: {@code <APP_BASE_URL><route>}. */
    String redirectUrl(OAuthResult result) {
        return frontendBaseUrl + result.route();
    }

    /**
     * OpenID discovery from the issuer (network call), then the app's settings: scopes {@code openid email profile},
     * PKCE required, fixed redirect URI.
     */
    private static ClientRegistration load(GoogleProperties properties, String frontendBaseUrl) {
        return ClientRegistrations.fromIssuerLocation(properties.issuerUri())
                .registrationId(REGISTRATION_ID)
                .clientName("Google")
                .clientId(properties.clientId())
                .clientSecret(properties.clientSecret())
                .scope("openid", "email", "profile")
                .redirectUri(frontendBaseUrl + CALLBACK_PATH)
                .clientSettings(ClientRegistration.ClientSettings.builder().requireProofKey(true).build())
                .build();
    }

    private static String stripTrailingSlashes(String url) {
        String value = url == null ? "" : url.strip();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }
}
