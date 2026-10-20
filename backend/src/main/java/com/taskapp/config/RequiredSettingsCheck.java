package com.taskapp.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Secure by default: application.yml has no database or mail defaults, so outside the {@code dev} profile the
 * app must be given its database and mail settings ({@code MAIL_HOST}, {@code MAIL_FROM}, {@code APP_BASE_URL}).
 * With {@code REQUIRE_HTTPS=true}, {@code APP_BASE_URL} must also be {@code https://}. Google sign-in settings must be
 * complete when used ({@link #checkGoogleSettings}). Without this check a missing variable surfaces later as an
 * unrelated error (e.g. "'url' must start with \"jdbc\"") instead of naming the variable.
 *
 * <p>Runs after the config files (including profile-specific ones) are loaded. A setting counts as
 * missing when its placeholder cannot be resolved or resolves to a blank value.
 * Registered in {@code META-INF/spring.factories}; reported by {@link MissingRequiredSettingsFailureAnalyzer}.
 */
public class RequiredSettingsCheck implements EnvironmentPostProcessor, Ordered {

    /** Spring property -> environment variable that application.yml reads it from. */
    static final Map<String, String> REQUIRED = new LinkedHashMap<>();
    static {
        REQUIRED.put("spring.datasource.url", "DB_URL");
        REQUIRED.put("spring.datasource.username", "DB_USERNAME");
        REQUIRED.put("spring.datasource.password", "DB_PASSWORD");
        // Flyway's own (privileged) user; the datasource user above is data-only.
        REQUIRED.put("spring.flyway.user", "DB_MIGRATION_USERNAME");
        REQUIRED.put("spring.flyway.password", "DB_MIGRATION_PASSWORD");
        // Email (specs/email-auth.md): without them password reset would silently never work.
        REQUIRED.put("spring.mail.host", "MAIL_HOST");
        REQUIRED.put("app.email.from", "MAIL_FROM");
        REQUIRED.put("app.email.base-url", "APP_BASE_URL");
    }

    static final String REQUIRE_HTTPS = "app.security.require-https";
    static final String BASE_URL = "app.email.base-url";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        List<String> missing = new ArrayList<>();
        REQUIRED.forEach((property, envVar) -> {
            if (!isSet(environment, property)) {
                missing.add(envVar);
            }
        });
        if (!missing.isEmpty()) {
            throw new MissingRequiredSettingsException(missing);
        }
        checkBaseUrlUsesHttps(environment);
        checkGoogleSettings(environment);
    }

    static final String GOOGLE_CLIENT_ID = "app.google.client-id";
    static final String GOOGLE_CLIENT_SECRET = "app.google.client-secret";
    static final String GOOGLE_ISSUER_URI = "app.google.issuer-uri";

    /**
     * Sign in with Google is optional (specs/google-sign-in.md): off with neither {@code GOOGLE_CLIENT_ID} nor
     * {@code GOOGLE_CLIENT_SECRET}. Only one of them is a mistake that would otherwise show up only at the first
     * sign-in. With {@code REQUIRE_HTTPS=true}, the issuer (where the ID token's keys come from) must be https.
     */
    private static void checkGoogleSettings(ConfigurableEnvironment environment) {
        boolean clientId = isSet(environment, GOOGLE_CLIENT_ID);
        boolean clientSecret = isSet(environment, GOOGLE_CLIENT_SECRET);
        if (clientId != clientSecret) {
            throw new InvalidSettingException(clientId ? "GOOGLE_CLIENT_SECRET" : "GOOGLE_CLIENT_ID",
                    "must be set when " + (clientId ? "GOOGLE_CLIENT_ID" : "GOOGLE_CLIENT_SECRET")
                            + " is set (set both to turn on sign in with Google, or neither)");
        }
        if (!clientId) {
            return;
        }
        String issuer = isSet(environment, GOOGLE_ISSUER_URI)
                ? environment.getProperty(GOOGLE_ISSUER_URI).strip() : "";
        if (startsWith(issuer, "https://")) {
            return;
        }
        if (requireHttps(environment)) {
            throw new InvalidSettingException("GOOGLE_ISSUER_URI",
                    "must start with https:// when REQUIRE_HTTPS is true (the sign-in keys would otherwise be fetched"
                            + " over plain http)");
        }
        if (!startsWith(issuer, "http://")) {
            throw new InvalidSettingException("GOOGLE_ISSUER_URI", "must start with https:// or http://");
        }
    }

    private static boolean startsWith(String value, String prefix) {
        return value.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    private static boolean requireHttps(ConfigurableEnvironment environment) {
        String requireHttps;
        try {
            requireHttps = environment.getProperty(REQUIRE_HTTPS, "true");
        } catch (IllegalArgumentException unresolvablePlaceholder) {
            requireHttps = "true";
        }
        return !"false".equalsIgnoreCase(requireHttps.strip());
    }

    /**
     * With {@code REQUIRE_HTTPS=true} (the production default) the links in emails must be https: a reset link sent
     * over plain http could be read on the network.
     */
    private static void checkBaseUrlUsesHttps(ConfigurableEnvironment environment) {
        if (requireHttps(environment)) {
            String baseUrl = environment.getProperty(BASE_URL, "").strip();
            if (!baseUrl.regionMatches(true, 0, "https://", 0, "https://".length())) {
                throw new InvalidSettingException("APP_BASE_URL",
                        "must start with https:// when REQUIRE_HTTPS is true (email links would otherwise use"
                                + " plain http)");
            }
        }
    }

    private static boolean isSet(ConfigurableEnvironment environment, String property) {
        try {
            String value = environment.getProperty(property);
            return value != null && !value.isBlank();
        } catch (IllegalArgumentException unresolvablePlaceholder) {
            return false;
        }
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
