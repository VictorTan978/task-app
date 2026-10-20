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

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * End-to-end over real HTTP (embedded Tomcat) against MySQL 8.4 with the Flyway migrations applied.
 * Uses a small cookie jar so the tests behave like a browser + Angular HttpClient.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        // Many registrations/logins from 127.0.0.1; the per-email failure limit keeps its default.
        "app.rate-limit.login-per-ip-per-minute=10000",
        "app.rate-limit.register-per-ip-per-hour=10000"})
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
    void apiDocsAreNotPublicByDefault() throws Exception {
        for (String path : List.of("/v3/api-docs", "/swagger-ui.html", "/swagger-ui/index.html")) {
            Resp r = new Browser().get(path);
            assertThat(r.status()).as(path).isEqualTo(401);
            assertThat(r.contentType()).as(path).startsWith("application/problem+json");
        }
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
        assertThat(user.get("emailVerified").asBoolean(true)).isFalse();
        assertThat(user.get("hasPassword").asBoolean(false)).isTrue();
        assertThat(user.get("connections").isArray()).isTrue();
        assertThat(user.get("connections")).isEmpty();
        assertThat(user.get("role").asText()).isEqualTo("USER");
        assertThat(user.size()).isEqualTo(9); // 0.14.0: + timeZone, reminderEmails
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
    void emailMatchingIsAccentInsensitive() throws Exception {
        Resp jose = new Browser().start().post("/api/auth/register",
                registerBody("Jose Accent", "jose@example.com", "jose-password"));
        assertThat(jose.status()).isEqualTo(201);

        // Different display name, so only the email collides.
        Resp joseAccent = new Browser().start().post("/api/auth/register",
                registerBody("Josefina", "josé@example.com", "jose-accent-password"));
        assertThat(joseAccent.status()).isEqualTo(409);
        assertThat(joseAccent.json().get("code").asText()).isEqualTo("EMAIL_TAKEN");
        assertThat(joseAccent.json().get("errors")).hasSize(1);
        assertThat(joseAccent.json().get("errors").get(0).get("field").asText()).isEqualTo("email");
        assertThat(jdbc.queryForObject("select count(*) from users where display_name = 'Josefina'", Integer.class))
                .isZero();

        // Logging in as josé@ with jose's password signs in as jose.
        Browser b = new Browser().start();
        Resp accentLogin = b.post("/api/auth/login", "{\"email\":\"josé@example.com\",\"password\":\"jose-password\"}");
        assertThat(accentLogin.status()).isEqualTo(200);
        assertThat(accentLogin.json().get("id").asLong()).isEqualTo(jose.json().get("id").asLong());
        assertThat(accentLogin.json().get("email").asText()).isEqualTo("jose@example.com");
        assertThat(b.get("/api/auth/me").json().get("id").asLong()).isEqualTo(jose.json().get("id").asLong());

        // The would-be josé password never became an account.
        assertThat(new Browser().start()
                .post("/api/auth/login", "{\"email\":\"josé@example.com\",\"password\":\"jose-accent-password\"}")
                .status()).isEqualTo(401);
    }

    @Test
    void emailMatchingIsCaseInsensitiveAlsoCombinedWithAccents() throws Exception {
        Resp jose = new Browser().start().post("/api/auth/register",
                registerBody("Jose Case", "jose.case@example.com", "jose-password"));
        assertThat(jose.status()).isEqualTo(201);

        Resp dup = new Browser().start().post("/api/auth/register",
                registerBody("Joseph Case", "JOSE.CASE@EXAMPLE.COM", "other-password"));
        assertThat(dup.status()).isEqualTo(409);
        assertThat(dup.json().get("code").asText()).isEqualTo("EMAIL_TAKEN");
        assertThat(dup.json().get("errors")).hasSize(1);

        Resp login = new Browser().start()
                .post("/api/auth/login", "{\"email\":\"JOSE.CASE@EXAMPLE.COM\",\"password\":\"jose-password\"}");
        assertThat(login.status()).isEqualTo(200);
        assertThat(login.json().get("id").asLong()).isEqualTo(jose.json().get("id").asLong());
        assertThat(login.json().get("email").asText()).isEqualTo("jose.case@example.com");

        Resp caseAndAccentLogin = new Browser().start()
                .post("/api/auth/login", "{\"email\":\"JOSÉ.CASE@EXAMPLE.COM\",\"password\":\"jose-password\"}");
        assertThat(caseAndAccentLogin.status()).isEqualTo(200);
        assertThat(caseAndAccentLogin.json().get("id").asLong()).isEqualTo(jose.json().get("id").asLong());
    }

    @Test
    void sharpSEqualsDoubleSUnderAiCi() throws Exception {
        // utf8mb4_0900_ai_ci expands 'ß' to 'ss' (verified on MySQL 8.4), so these are the same email.
        Resp strasse = new Browser().start().post("/api/auth/register",
                registerBody("Strasse", "strasse@example.com", "strasse-password"));
        Resp strasseSharp = new Browser().start().post("/api/auth/register",
                registerBody("Tramway", "straße@example.com", "strasse-sharp-password"));

        assertThat(strasse.status()).isEqualTo(201);
        assertThat(strasseSharp.status()).isEqualTo(409);
        assertThat(strasseSharp.json().get("code").asText()).isEqualTo("EMAIL_TAKEN");
        Resp login = new Browser().start()
                .post("/api/auth/login", "{\"email\":\"straße@example.com\",\"password\":\"strasse-password\"}");
        assertThat(login.status()).isEqualTo(200);
        assertThat(login.json().get("id").asLong()).isEqualTo(strasse.json().get("id").asLong());
    }

    @Test
    void emailAndDisplayNameUseAiCiCollationWithUniqueKeys() {
        for (String column : List.of("email", "display_name")) {
            assertThat(jdbc.queryForObject("""
                    select collation_name from information_schema.columns
                    where table_schema = database() and table_name = 'users' and column_name = ?
                    """, String.class, column)).as(column).isEqualTo("utf8mb4_0900_ai_ci");
        }
        assertThat(jdbc.queryForObject("""
                select count(*) from information_schema.statistics
                where table_schema = database() and table_name = 'users'
                  and index_name = 'uk_users_email' and column_name = 'email' and non_unique = 0
                """, Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                select count(*) from information_schema.statistics
                where table_schema = database() and table_name = 'users'
                  and index_name = 'uk_users_display_name' and column_name = 'display_name' and non_unique = 0
                """, Integer.class)).isEqualTo(1);
    }

    @Test
    void displayNameMatchingIgnoresCaseAccentsAndSurroundingSpaces() throws Exception {
        Resp owner = new Browser().start().post("/api/auth/register",
                registerBody("José", "ac14-owner@example.com", "correct-horse"));
        assertThat(owner.status()).isEqualTo(201);
        assertThat(owner.json().get("displayName").asText()).isEqualTo("José");

        List<String> variants = List.of("José", "jose", "JOSE", " José ");
        for (int i = 0; i < variants.size(); i++) {
            Resp dup = new Browser().start().post("/api/auth/register",
                    registerBody(variants.get(i), "ac14-variant" + i + "@example.com", "correct-horse"));

            assertThat(dup.status()).as(variants.get(i)).isEqualTo(409);
            assertThat(dup.contentType()).startsWith("application/problem+json");
            JsonNode body = dup.json();
            assertThat(body.get("code").asText()).isEqualTo("DISPLAY_NAME_TAKEN");
            assertThat(body.get("detail").asText()).isEqualTo("This display name is already taken");
            assertThat(body.get("errors")).hasSize(1);
            assertThat(body.get("errors").get(0).get("field").asText()).isEqualTo("displayName");
            assertThat(body.get("errors").get(0).get("code").asText()).isEqualTo("DISPLAY_NAME_TAKEN");
            assertThat(body.get("errors").get(0).get("message").asText()).isEqualTo("This display name is already taken");
        }
        assertThat(jdbc.queryForObject("select count(*) from users where email like 'ac14-%'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void emailAndDisplayNameBothTakenReturnsEmailTakenWithTwoFieldErrors() throws Exception {
        assertThat(new Browser().start().post("/api/auth/register",
                registerBody("Hannah", "hannah@example.com", "correct-horse")).status()).isEqualTo(201);

        Resp dup = new Browser().start().post("/api/auth/register",
                registerBody(" HANNAH ", "Hannah@Example.com", "other-password"));

        assertThat(dup.status()).isEqualTo(409);
        JsonNode body = dup.json();
        assertThat(body.get("code").asText()).isEqualTo("EMAIL_TAKEN");
        assertThat(body.get("detail").asText()).isEqualTo("An account with this email already exists");
        assertThat(body.get("errors")).hasSize(2);
        assertThat(body.get("errors").findValuesAsString("field")).containsExactly("email", "displayName");
        assertThat(body.get("errors").findValuesAsString("code")).containsExactly("EMAIL_TAKEN", "DISPLAY_NAME_TAKEN");
        assertThat(body.get("errors").findValuesAsString("message")).containsExactly(
                "An account with this email already exists", "This display name is already taken");
        assertThat(jdbc.queryForObject("select count(*) from users where email = 'hannah@example.com'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void differentDisplayNameSucceeds() throws Exception {
        assertThat(new Browser().start().post("/api/auth/register",
                registerBody("Ivan", "ivan@example.com", "correct-horse")).status()).isEqualTo(201);

        Resp other = new Browser().start().post("/api/auth/register",
                registerBody("Ivana", "ivana@example.com", "correct-horse"));

        assertThat(other.status()).isEqualTo(201);
        assertThat(other.json().get("displayName").asText()).isEqualTo("Ivana");
    }

    @Test
    void validationRunsBeforeDisplayNameUniqueness() throws Exception {
        assertThat(new Browser().start().post("/api/auth/register",
                registerBody("Kim", "kim@example.com", "correct-horse")).status()).isEqualTo(201);

        Resp r = new Browser().start().post("/api/auth/register", registerBody("kim", "kim2@example.com", "short"));

        assertThat(r.status()).isEqualTo(400);
        assertThat(r.json().get("code").asText()).isEqualTo("VALIDATION_FAILED");
        assertThat(r.json().get("errors").findValuesAsString("field")).containsExactly("password");
    }

    @Test
    void validationReportsAllInvalidFields() throws Exception {
        Resp r = new Browser().start().post("/api/auth/register", registerBody("", "nope", "1234567"));

        assertThat(r.status()).isEqualTo(400);
        JsonNode body = r.json();
        assertThat(body.get("code").asText()).isEqualTo("VALIDATION_FAILED");
        assertThat(body.get("errors")).hasSize(3);
        assertThat(body.get("errors").findValuesAsString("field")).containsExactly("displayName", "email", "password");
        assertThat(body.get("errors").findValuesAsString("code")).containsExactly("REQUIRED", "INVALID_EMAIL", "PASSWORD_LENGTH");
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

    private static String loginBody(String email, String password) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
    }

    @Test
    void fiveFailedLoginsBlockThatEmailEvenWithCorrectPassword() throws Exception {
        new Browser().start().post("/api/auth/register", registerBody("Rita", "rita@example.com", "correct-horse"));
        new Browser().start().post("/api/auth/register", registerBody("Sam", "sam@example.com", "correct-horse"));
        Browser b = new Browser().start();
        for (int i = 0; i < 5; i++) {
            assertThat(b.post("/api/auth/login", loginBody("rita@example.com", "wrong-horse")).status()).isEqualTo(401);
        }

        Resp blocked = b.post("/api/auth/login", loginBody(" RITA@example.com", "correct-horse"));

        assertThat(blocked.status()).isEqualTo(429);
        assertThat(blocked.contentType()).startsWith("application/problem+json");
        assertThat(blocked.json().get("code").asText()).isEqualTo("RATE_LIMITED");
        assertThat(blocked.json().get("detail").asText()).isEqualTo("Too many attempts. Please try again later.");
        assertThat(blocked.json().has("errors")).isFalse();
        assertThat(blocked.setCookie("JSESSIONID")).isNull();
        HttpResponse<String> raw = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/login"))
                .header("Content-Type", "application/json")
                .header("Cookie", "XSRF-TOKEN=" + b.cookies.get("XSRF-TOKEN"))
                .header("X-XSRF-TOKEN", b.cookies.get("XSRF-TOKEN"))
                .POST(HttpRequest.BodyPublishers.ofString(loginBody("rita@example.com", "correct-horse"))).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(raw.statusCode()).isEqualTo(429);
        assertThat(Long.parseLong(raw.headers().firstValue("Retry-After").orElseThrow())).isBetween(1L, 900L);

        // Other emails are not affected.
        assertThat(b.post("/api/auth/login", loginBody("sam@example.com", "correct-horse")).status()).isEqualTo(200);
    }

    @Test
    void unknownEmailsAreLimitedLikeKnownOnes() throws Exception {
        Browser b = new Browser().start();
        for (int i = 0; i < 5; i++) {
            assertThat(b.post("/api/auth/login", loginBody("ghost@example.com", "wrong-horse")).status()).isEqualTo(401);
        }
        assertThat(b.post("/api/auth/login", loginBody("ghost@example.com", "wrong-horse")).status()).isEqualTo(429);
    }

    @Test
    void successfulLoginResetsFailureCount() throws Exception {
        new Browser().start().post("/api/auth/register", registerBody("Tom", "tom@example.com", "correct-horse"));
        Browser b = new Browser().start();
        for (int i = 0; i < 4; i++) {
            assertThat(b.post("/api/auth/login", loginBody("tom@example.com", "wrong-horse")).status()).isEqualTo(401);
        }
        assertThat(b.post("/api/auth/login", loginBody("tom@example.com", "correct-horse")).status()).isEqualTo(200);

        for (int i = 0; i < 4; i++) {
            assertThat(b.post("/api/auth/login", loginBody("tom@example.com", "wrong-horse")).status()).isEqualTo(401);
        }
        assertThat(b.post("/api/auth/login", loginBody("tom@example.com", "correct-horse")).status()).isEqualTo(200);
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
            // "hasPassword" (0.8.0) is the only field allowed to mention it.
            assertThat(r.body().replace("\"hasPassword\":true", "")).doesNotContain(password).doesNotContain(hash)
                    .doesNotContainIgnoringCase("password").doesNotContain("$2a$");
        }
    }
}
