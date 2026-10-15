package com.taskapp.security.google;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** specs/google-sign-in.md AC16: the client secret never reaches a log line through the settings object. */
class GoogleSecretNeverLoggedTest {

    @Test
    void propertiesHideTheSecret() {
        GoogleProperties properties = new GoogleProperties(" id ", " very-secret ", "https://accounts.google.com");

        assertThat(properties.toString()).doesNotContain("very-secret").contains("clientSecret=****");
        assertThat(properties.clientSecret()).isEqualTo("very-secret");
        assertThat(properties.enabled()).isTrue();
        assertThat(new GoogleProperties("id", "", null).enabled()).isFalse();
    }

    @Test
    void redirectsUseTheFrontendBaseUrl() {
        GoogleSignIn google = new GoogleSignIn(new GoogleProperties("id", "secret", "https://accounts.google.com"),
                "https://tasks.example.com//");

        assertThat(google.redirectUrl(OAuthResult.LOGGED_IN)).isEqualTo("https://tasks.example.com/tasks");
        assertThat(google.redirectUrl(OAuthResult.GOOGLE_CONNECT_FAILED))
                .isEqualTo("https://tasks.example.com/account?oauth=google_connect_failed");
    }
}
