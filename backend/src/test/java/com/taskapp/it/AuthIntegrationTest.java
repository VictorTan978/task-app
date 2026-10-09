package com.taskapp.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * End-to-end over real HTTP (embedded Tomcat) against MySQL 8.4 with the Flyway migrations applied.
 * Uses a small cookie jar so the tests behave like a browser + Angular HttpClient.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthIntegrationTest {

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
            .withUrlParam("connectionTimeZone", "UTC")
            .withUrlParam("forceConnectionTimeZoneToSession", "true");

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();

    /** Minimal browser: stores cookies, echoes XSRF-TOKEN in X-XSRF-TOKEN like Angular. */
    class Browser {
        final Map<String, String> cookies = new LinkedHashMap<>();
        boolean sendCsrfHeader = true;

        Resp get(String path) throws Exception {
            return send(builder(path).GET());
        }

        Resp post(String path, String body) throws Exception {
            HttpRequest.Builder b = builder(path).header("Content-Type", "application/json")
                    .POST(body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
            if (sendCsrfHeader && cookies.containsKey("XSRF-TOKEN")) {
                b.header("X-XSRF-TOKEN", cookies.get("XSRF-TOKEN"));
            }
            return send(b);
        }

        private HttpRequest.Builder builder(String path) {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
            if (!cookies.isEmpty()) {
                b.header("Cookie", cookies.entrySet().stream()
                        .map(e -> e.getKey() + "=" + e.getValue()).collect(Collectors.joining("; ")));
            }
            return b;
        }

        private Resp send(HttpRequest.Builder b) throws IOException, InterruptedException {
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            List<String> setCookies = r.headers().allValues("Set-Cookie");
            for (String sc : setCookies) {
                String[] nameValue = sc.split(";", 2)[0].split("=", 2);
                boolean expired = sc.contains("Max-Age=0");
                if (expired) {
                    cookies.remove(nameValue[0]);
                } else {
                    cookies.put(nameValue[0], nameValue[1]);
                }
            }
            return new Resp(r.statusCode(), r.headers().firstValue("Content-Type").orElse(""), r.body(), setCookies);
        }

        /** Primes the XSRF-TOKEN cookie the way the app does on startup. */
        Browser start() throws Exception {
            get("/api/auth/me");
            assertThat(cookies).containsKey("XSRF-TOKEN");
            return this;
        }
    }

    record Resp(int status, String contentType, String body, List<String> setCookies) {
        JsonNode json() throws IOException {
            return new ObjectMapper().readTree(body);
        }

        String setCookie(String name) {
            return setCookies.stream().filter(c -> c.startsWith(name + "=")).reduce((a, b) -> b).orElse(null);
        }
    }

    private static String registerBody(String name, String email, String password) {
        return "{\"displayName\":\"" + name + "\",\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
    }

    @Test
    void healthIsPublic() throws Exception {
        Resp r = new Browser().get("/api/health");
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.json().get("status").asText()).isEqualTo("UP");
    }

    @Test
    void meWithoutSessionIs401AndIssuesReadableCsrfCookieWithoutCreatingSession() throws Exception {
        Resp r = new Browser().get("/api/auth/me");

        assertThat(r.status()).isEqualTo(401);
        assertThat(r.contentType()).startsWith("application/problem+json");
        assertThat(r.json().get("code").asText()).isEqualTo("UNAUTHENTICATED");
        String xsrf = r.setCookie("XSRF-TOKEN");
        assertThat(xsrf).contains("Path=/").contains("SameSite=Lax").doesNotContain("HttpOnly");
        assertThat(r.setCookie("JSESSIONID")).isNull();
    }

    @Test
    void registerSignsInThenMeReturnsUser() throws Exception {
        Browser b = new Browser().start();
        String csrfBefore = b.cookies.get("XSRF-TOKEN");

        Resp reg = b.post("/api/auth/register", registerBody("  Alice ", "  Alice@Example.COM ", "correct-horse"));

        assertThat(reg.status()).isEqualTo(201);
        JsonNode user = reg.json();
        assertThat(user.get("id").isIntegralNumber()).isTrue();
        assertThat(user.get("email").asText()).isEqualTo("alice@example.com");
        assertThat(user.get("displayName").asText()).isEqualTo("Alice");
        assertThat(user.size()).isEqualTo(3);
        assertThat(reg.setCookie("JSESSIONID")).contains("HttpOnly").contains("SameSite=Lax").contains("Path=/");
        assertThat(b.cookies.get("XSRF-TOKEN")).isNotEqualTo(csrfBefore);

        Resp me = b.get("/api/auth/me");
        assertThat(me.status()).isEqualTo(200);
        assertThat(me.json()).isEqualTo(user);

        String hash = jdbc.queryForObject("select password_hash from users where email = 'alice@example.com'", String.class);
        assertThat(hash).startsWith("$2").hasSize(60);
    }

    @Test
    void duplicateEmailIsCaseInsensitive409() throws Exception {
        assertThat(new Browser().start().post("/api/auth/register",
                registerBody("Bob", "bob@example.com", "correct-horse")).status()).isEqualTo(201);

        Resp dup = new Browser().start().post("/api/auth/register",
                registerBody("Bobby", "Bob@Example.com", "other-password"));

        assertThat(dup.status()).isEqualTo(409);
        assertThat(dup.contentType()).startsWith("application/problem+json");
        assertThat(dup.json().get("code").asText()).isEqualTo("EMAIL_TAKEN");
        assertThat(dup.json().get("errors").get(0).get("field").asText()).isEqualTo("email");
        assertThat(jdbc.queryForObject("select count(*) from users where email = 'bob@example.com'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void validationReportsAllInvalidFields() throws Exception {
        Resp r = new Browser().start().post("/api/auth/register", registerBody("", "nope", "1234567"));

        assertThat(r.status()).isEqualTo(400);
        JsonNode body = r.json();
        assertThat(body.get("code").asText()).isEqualTo("VALIDATION_FAILED");
        assertThat(body.get("errors")).hasSize(3);
        assertThat(body.get("errors").findValuesAsText("field")).containsExactly("displayName", "email", "password");
        assertThat(body.get("errors").findValuesAsText("code")).containsExactly("REQUIRED", "INVALID_EMAIL", "PASSWORD_LENGTH");
        assertThat(jdbc.queryForObject("select count(*) from users where email = 'nope'", Integer.class)).isZero();
    }

    @Test
    void loginAnyCaseAndIdenticalFailureBodies() throws Exception {
        new Browser().start().post("/api/auth/register", registerBody("Carol", "carol@example.com", "correct-horse"));

        Browser b = new Browser().start();
        Resp ok = b.post("/api/auth/login", "{\"email\":\" CAROL@example.com\",\"password\":\"correct-horse\"}");
        assertThat(ok.status()).isEqualTo(200);
        assertThat(ok.json().get("email").asText()).isEqualTo("carol@example.com");
        assertThat(ok.setCookie("JSESSIONID")).isNotNull();
        assertThat(b.get("/api/auth/me").status()).isEqualTo(200);

        Resp wrongPassword = new Browser().start()
                .post("/api/auth/login", "{\"email\":\"carol@example.com\",\"password\":\"wrong-horse\"}");
        Resp unknownEmail = new Browser().start()
                .post("/api/auth/login", "{\"email\":\"carol@example.com\",\"password\":\"wrong-horse\"}"
                        .replace("carol@", "nobody@"));
        assertThat(wrongPassword.status()).isEqualTo(401);
        assertThat(unknownEmail.status()).isEqualTo(401);
        assertThat(wrongPassword.json().get("code").asText()).isEqualTo("INVALID_CREDENTIALS");
        assertThat(wrongPassword.body()).isEqualTo(unknownEmail.body());
    }

    @Test
    void loginRotatesSessionId() throws Exception {
        new Browser().start().post("/api/auth/register", registerBody("Dan", "dan@example.com", "correct-horse"));
        Browser b = new Browser().start();
        b.post("/api/auth/login", "{\"email\":\"dan@example.com\",\"password\":\"correct-horse\"}");
        String firstSession = b.cookies.get("JSESSIONID");

        b.post("/api/auth/login", "{\"email\":\"dan@example.com\",\"password\":\"correct-horse\"}");

        assertThat(b.cookies.get("JSESSIONID")).isNotNull().isNotEqualTo(firstSession);
    }

    @Test
    void logoutEndsSessionAndRotatesCsrf() throws Exception {
        Browser b = new Browser().start();
        b.post("/api/auth/register", registerBody("Erin", "erin@example.com", "correct-horse"));
        String oldSession = b.cookies.get("JSESSIONID");
        String csrfBefore = b.cookies.get("XSRF-TOKEN");

        Resp out = b.post("/api/auth/logout", null);

        assertThat(out.status()).isEqualTo(204);
        assertThat(out.setCookie("JSESSIONID")).contains("Max-Age=0");
        assertThat(b.cookies.get("XSRF-TOKEN")).isNotNull().isNotEqualTo(csrfBefore);
        assertThat(b.get("/api/auth/me").status()).isEqualTo(401);

        // The old session cookie must not work anymore either.
        Browser attacker = new Browser();
        attacker.cookies.put("JSESSIONID", oldSession);
        assertThat(attacker.get("/api/auth/me").status()).isEqualTo(401);

        // Idempotent when not signed in.
        assertThat(b.post("/api/auth/logout", null).status()).isEqualTo(204);
    }

    @Test
    void protectedPathWithoutSessionIs401Problem() throws Exception {
        Resp r = new Browser().get("/api/tasks");
        assertThat(r.status()).isEqualTo(401);
        assertThat(r.contentType()).startsWith("application/problem+json");
        assertThat(r.json().get("code").asText()).isEqualTo("UNAUTHENTICATED");
        assertThat(r.setCookie("XSRF-TOKEN")).isNotNull();
    }

    @Test
    void stateChangingRequestsWithoutCsrfAre403() throws Exception {
        Browser b = new Browser().start();
        b.sendCsrfHeader = false;

        Resp r = b.post("/api/auth/register", registerBody("Frank", "frank@example.com", "correct-horse"));

        assertThat(r.status()).isEqualTo(403);
        assertThat(r.contentType()).startsWith("application/problem+json");
        assertThat(r.json().get("code").asText()).isEqualTo("CSRF_INVALID");
        assertThat(jdbc.queryForObject("select count(*) from users where email = 'frank@example.com'", Integer.class))
                .isZero();

        Browser wrongToken = new Browser().start();
        wrongToken.sendCsrfHeader = false;
        Resp r2 = wrongToken.send(wrongToken.builder("/api/auth/logout")
                .header("X-XSRF-TOKEN", "not-the-token").POST(HttpRequest.BodyPublishers.noBody()));
        assertThat(r2.status()).isEqualTo(403);
        assertThat(r2.json().get("code").asText()).isEqualTo("CSRF_INVALID");
    }

    @Test
    void noResponseContainsPasswordOrHash() throws Exception {
        Browser b = new Browser().start();
        String password = "super-secret-pw";
        Resp reg = b.post("/api/auth/register", registerBody("Gina", "gina@example.com", password));
        Resp login = b.post("/api/auth/login", "{\"email\":\"gina@example.com\",\"password\":\"" + password + "\"}");
        Resp me = b.get("/api/auth/me");
        String hash = jdbc.queryForObject("select password_hash from users where email = 'gina@example.com'", String.class);

        for (Resp r : List.of(reg, login, me)) {
            assertThat(r.body()).doesNotContain(password).doesNotContain(hash)
                    .doesNotContainIgnoringCase("password").doesNotContain("$2a$");
        }
    }
}
