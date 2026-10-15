package com.taskapp.security.google;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code app.google.*} (application.yml): {@code GOOGLE_CLIENT_ID}, {@code GOOGLE_CLIENT_SECRET},
 * {@code GOOGLE_ISSUER_URI}. Google sign-in is on when both the client id and secret are set; setting only one of
 * them, or a non-https issuer while {@code REQUIRE_HTTPS} is on, fails startup
 * ({@link com.taskapp.config.RequiredSettingsCheck}).
 *
 * @param clientId     OAuth client id from the Google Cloud console; blank = feature off
 * @param clientSecret OAuth client secret; never logged ({@link #toString()} hides it)
 * @param issuerUri    OpenID Connect issuer; its configuration is loaded on first use, not at startup
 */
@ConfigurationProperties("app.google")
public record GoogleProperties(
        String clientId,
        String clientSecret,
        @DefaultValue("https://accounts.google.com") String issuerUri) {

    public GoogleProperties {
        clientId = clientId == null ? "" : clientId.strip();
        clientSecret = clientSecret == null ? "" : clientSecret.strip();
        issuerUri = issuerUri == null ? "" : issuerUri.strip();
    }

    public boolean enabled() {
        return !clientId.isEmpty() && !clientSecret.isEmpty();
    }

    @Override
    public String toString() {
        return "GoogleProperties[clientId=" + clientId + ", clientSecret=****, issuerUri=" + issuerUri + "]";
    }
}
