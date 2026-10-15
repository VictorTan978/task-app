package com.taskapp.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/**
 * The fake OpenID Connect provider (same image as the docker-compose "oidc" service) standing in for Google in
 * integration tests: ghcr.io/navikt/mock-oauth2-server with its interactive login page. Approving = submitting that
 * page's form ({@code username} = the {@code sub}, {@code claims} = extra ID token claims as JSON) to the
 * authorization URL the app redirected to; the provider answers 302 to the app's callback with {@code code} and
 * {@code state}. It checks PKCE and echoes the nonce, like Google.
 */
final class FakeGoogle {

    static final String IMAGE = "ghcr.io/navikt/mock-oauth2-server:6.0.3";
    static final String CLIENT_ID = "task-app-test";
    static final String CLIENT_SECRET = "task-app-test-secret-never-logged";
    private static final int PORT = 8080;

    private final GenericContainer<?> container;
    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    FakeGoogle(GenericContainer<?> container) {
        this.container = container;
    }

    @SuppressWarnings("resource")
    static GenericContainer<?> container() {
        return new GenericContainer<>(IMAGE)
                .withExposedPorts(PORT)
                .withEnv("JSON_CONFIG", "{\"interactiveLogin\": true}")
                .waitingFor(Wait.forHttp("/default/.well-known/openid-configuration").forPort(PORT));
    }

    static String issuer(GenericContainer<?> container) {
        return "http://" + container.getHost() + ":" + container.getMappedPort(PORT) + "/default";
    }

    /** Turns Google sign-in on, pointed at this container. */
    static void register(GenericContainer<?> container, DynamicPropertyRegistry registry) {
        registry.add("app.google.client-id", () -> CLIENT_ID);
        registry.add("app.google.client-secret", () -> CLIENT_SECRET);
        registry.add("app.google.issuer-uri", () -> issuer(container));
    }

    String issuer() {
        return issuer(container);
    }

    /** ID token claims as JSON. */
    static String claims(String email, boolean emailVerified, String name) {
        StringBuilder json = new StringBuilder("{");
        if (email != null) {
            json.append("\"email\":\"").append(email).append("\",");
        }
        json.append("\"email_verified\":").append(emailVerified);
        if (name != null) {
            json.append(",\"name\":\"").append(name).append('"');
        }
        return json.append('}').toString();
    }

    /**
     * The user approves on the provider's page: returns the provider's redirect to the app's callback (absolute,
     * {@code <APP_BASE_URL>/api/auth/oauth2/callback/google?code=...&state=...}).
     */
    String approve(String authorizationUrl, String subject, String claimsJson) {
        assertThat(authorizationUrl).startsWith(issuer() + "/authorize?");
        String form = "username=" + encode(subject) + "&claims=" + encode(claimsJson);
        try {
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(authorizationUrl))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(r.statusCode()).as(r.body()).isEqualTo(302);
            return r.headers().firstValue("Location").orElseThrow();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** One query parameter of a URL (URL-decoded), or null. */
    static String param(String url, String name) {
        String query = URI.create(url).getRawQuery();
        if (query == null) {
            return null;
        }
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv[0].equals(name)) {
                return kv.length < 2 ? "" : java.net.URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
            }
        }
        return null;
    }
}
