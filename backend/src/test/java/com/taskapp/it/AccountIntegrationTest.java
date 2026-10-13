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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import com.taskapp.security.audit.SecurityLogCapture;

/**
 * Account settings (specs/account-settings.md AC3-AC16) end-to-end over real HTTP against MySQL 8.4 with the
 * Flyway migrations, using a cookie jar that behaves like a browser + Angular HttpClient.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.rate-limit.login-per-ip-per-minute=10000",
        "app.rate-limit.register-per-ip-per-hour=10000"})
class AccountIntegrationTest {

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
            .withUrlParam("connectionTimeZone", "UTC")
            .withUrlParam("forceConnectionTimeZoneToSession", "true");

    private static final String PASSWORD = "correct-horse";

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    private final HttpClient http = HttpClient.newHttpClient();
    private final SecurityLogCapture capture = new SecurityLogCapture();

    @BeforeEach
    void startCapture() {
        capture.start();
    }

    @AfterEach
    void stopCapture() {
        capture.stop();
    }

    class Browser {
        final Map<String, String> cookies = new LinkedHashMap<>();
        boolean sendCsrfHeader = true;

        Resp get(String path) throws Exception {
            return send("GET", path, null);
        }

        Resp post(String path, String body) throws Exception {
            return send("POST", path, body);
        }

        Resp put(String path, String body) throws Exception {
            return send("PUT", path, body);
        }

        Resp send(String method, String path, String body) throws Exception {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofString(body));
            if (body != null) {
                b.header("Content-Type", "application/json");
            }
            if (!cookies.isEmpty()) {
                b.header("Cookie", cookies.entrySet().stream()
                        .map(e -> e.getKey() + "=" + e.getValue()).collect(Collectors.joining("; ")));
            }
            if (!"GET".equals(method) && sendCsrfHeader && cookies.containsKey("XSRF-TOKEN")) {
                b.header("X-XSRF-TOKEN", cookies.get("XSRF-TOKEN"));
            }
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            List<String> setCookies = r.headers().allValues("Set-Cookie");
            for (String sc : setCookies) {
                String[] nameValue = sc.split(";", 2)[0].split("=", 2);
                if (sc.contains("Max-Age=0")) {
                    cookies.remove(nameValue[0]);
                } else {
                    cookies.put(nameValue[0], nameValue[1]);
                }
            }
            return new Resp(r.statusCode(), r.headers().firstValue("Content-Type").orElse(""),
                    r.headers().firstValue("Retry-After").orElse(null), r.body(), setCookies);
        }

        Browser start() throws Exception {
            get("/api/auth/me");
            assertThat(cookies).containsKey("XSRF-TOKEN");
            return this;
        }

        /** Registers (and signs in) a new account; returns its id. */
        long register(String name, String email) throws Exception {
            Resp r = post("/api/auth/register", "{\"displayName\":\"" + name + "\",\"email\":\"" + email
                    + "\",\"password\":\"" + PASSWORD + "\"}");
            assertThat(r.status()).as(r.body()).isEqualTo(201);
            return r.json().get("id").asLong();
        }

        Resp login(String email, String password) throws Exception {
            return post("/api/auth/login", "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}");
        }

        Resp changeDisplayName(String displayName) throws Exception {
            return put("/api/account/display-name", "{\"displayName\":\"" + displayName + "\"}");
        }

        Resp changePassword(String current, String next) throws Exception {
            return put("/api/account/password",
                    "{\"currentPassword\":\"" + current + "\",\"newPassword\":\"" + next + "\"}");
        }

        Resp deleteAccount(String password) throws Exception {
            return post("/api/account/delete", "{\"password\":\"" + password + "\"}");
        }

        Resp createTask(String title) throws Exception {
            return post("/api/tasks", "{\"title\":\"" + title + "\"}");
        }
    }

    record Resp(int status, String contentType, String retryAfter, String body, List<String> setCookies) {
        JsonNode json() throws IOException {
            return new ObjectMapper().readTree(body);
        }

        String code() throws IOException {
            return json().get("code").asText();
        }

        JsonNode error() throws IOException {
            return json().get("errors").get(0);
        }
    }

    private Browser signedIn(String name, String email) throws Exception {
        Browser b = new Browser().start();
        b.register(name, email);
        return b;
    }

    private Browser secondSession(String email) throws Exception {
        Browser b = new Browser().start();
        assertThat(b.login(email, PASSWORD).status()).isEqualTo(200);
        return b;
    }

    private String displayNameInDb(String email) {
        return jdbc.queryForObject("select display_name from users where email = ?", String.class, email);
    }

    private String hashInDb(String email) {
        return jdbc.queryForObject("select password_hash from users where email = ?", String.class, email);
    }

    // --- AC3-AC6: display name ---

    @Test
    void ac3DisplayNameChangeIsSavedAndSeenByThisAndOtherSessions() throws Exception {
        Browser b = signedIn("Anna", "anna@example.com");
        Browser other = secondSession("anna@example.com");
        String sessionBefore = b.cookies.get("JSESSIONID");
        String csrfBefore = b.cookies.get("XSRF-TOKEN");

        Resp r = b.changeDisplayName("  Anna Banana ");

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.contentType()).startsWith("application/json");
        assertThat(r.json().get("displayName").asText()).isEqualTo("Anna Banana");
        assertThat(r.json().get("email").asText()).isEqualTo("anna@example.com");
        assertThat(r.json().size()).isEqualTo(3);
        assertThat(r.setCookies()).isEmpty();
        assertThat(b.cookies.get("JSESSIONID")).isEqualTo(sessionBefore);
        assertThat(b.cookies.get("XSRF-TOKEN")).isEqualTo(csrfBefore);
        assertThat(displayNameInDb("anna@example.com")).isEqualTo("Anna Banana");

        // Same session ("after a refresh") and a second session both see the new name.
        assertThat(b.get("/api/auth/me").json().get("displayName").asText()).isEqualTo("Anna Banana");
        assertThat(other.get("/api/auth/me").json().get("displayName").asText()).isEqualTo("Anna Banana");

        // The refreshed principal is what later requests (and the audit log) see.
        capture.clear();
        assertThat(other.changeDisplayName("Anna B").status()).isEqualTo(200);
        assertThat(capture.lines()).containsExactly("SECURITY event=DISPLAY_NAME_CHANGED email=anna@example.com"
                + " ip=127.0.0.1 path=/api/account/display-name");
        assertThat(b.get("/api/auth/me").json().get("displayName").asText()).isEqualTo("Anna B");
    }

    @Test
    void ac4NameOfAnotherUserIsTakenIgnoringCaseAccentsAndSpaces() throws Exception {
        signedIn("José", "jose-owner@example.com");
        Browser b = signedIn("Pedro", "pedro@example.com");

        for (String variant : List.of("jose", "JOSE", " José ", "José")) {
            Resp r = b.changeDisplayName(variant);

            assertThat(r.status()).as(variant).isEqualTo(409);
            assertThat(r.contentType()).startsWith("application/problem+json");
            assertThat(r.code()).isEqualTo("DISPLAY_NAME_TAKEN");
            assertThat(r.json().get("detail").asText()).isEqualTo("This display name is already taken");
            assertThat(r.json().get("instance").asText()).isEqualTo("/api/account/display-name");
            assertThat(r.json().get("errors")).hasSize(1);
            assertThat(r.error().get("field").asText()).isEqualTo("displayName");
            assertThat(r.error().get("code").asText()).isEqualTo("DISPLAY_NAME_TAKEN");
            assertThat(r.error().get("message").asText()).isEqualTo("This display name is already taken");
        }
        assertThat(displayNameInDb("pedro@example.com")).isEqualTo("Pedro");
        assertThat(b.get("/api/auth/me").json().get("displayName").asText()).isEqualTo("Pedro");
    }

    @Test
    void ac5OwnNameWithOtherCaseOrAccentsIsAllowed() throws Exception {
        Browser b = signedIn("Bob", "bob-ac5@example.com");

        Resp lower = b.changeDisplayName("bob");
        assertThat(lower.status()).isEqualTo(200);
        assertThat(lower.json().get("displayName").asText()).isEqualTo("bob");
        assertThat(displayNameInDb("bob-ac5@example.com")).isEqualTo("bob");

        assertThat(b.changeDisplayName("Böb").status()).isEqualTo(200);
        assertThat(displayNameInDb("bob-ac5@example.com")).isEqualTo("Böb");

        // Saving the exact current name is fine too, and is not logged as a change.
        capture.clear();
        assertThat(b.changeDisplayName("Böb").status()).isEqualTo(200);
        assertThat(capture.lines()).isEmpty();
    }

    @Test
    void ac6InvalidDisplayNamesChangeNothing() throws Exception {
        Browser b = signedIn("Cleo", "cleo@example.com");

        for (String invalid : List.of("", "    ", "x".repeat(51))) {
            Resp r = b.changeDisplayName(invalid);
            assertThat(r.status()).isEqualTo(400);
            assertThat(r.code()).isEqualTo("VALIDATION_FAILED");
            assertThat(r.error().get("field").asText()).isEqualTo("displayName");
            assertThat(r.error().get("code").asText()).isIn("REQUIRED", "TOO_LONG");
        }
        assertThat(displayNameInDb("cleo@example.com")).isEqualTo("Cleo");
        assertThat(b.changeDisplayName("  " + "y".repeat(50) + "  ").status()).isEqualTo(200);
    }

    // --- AC7-AC9: password ---

    @Test
    void ac7PasswordChangeKeepsSessionAndOnlyTheNewPasswordWorks() throws Exception {
        Browser b = signedIn("Dora", "dora@example.com");
        String sessionBefore = b.cookies.get("JSESSIONID");
        String csrfBefore = b.cookies.get("XSRF-TOKEN");

        Resp r = b.changePassword(PASSWORD, "battery-staple-42");

        assertThat(r.status()).isEqualTo(204);
        assertThat(r.body()).isEmpty();
        assertThat(r.setCookies()).isEmpty();
        assertThat(b.cookies.get("JSESSIONID")).isEqualTo(sessionBefore);
        assertThat(b.cookies.get("XSRF-TOKEN")).isEqualTo(csrfBefore);
        assertThat(b.get("/api/auth/me").status()).isEqualTo(200);
        assertThat(hashInDb("dora@example.com")).startsWith("$2").hasSize(60);

        assertThat(b.post("/api/auth/logout", null).status()).isEqualTo(204);
        Resp old = b.login("dora@example.com", PASSWORD);
        assertThat(old.status()).isEqualTo(401);
        assertThat(old.json().get("detail").asText()).isEqualTo("Invalid email or password");
        assertThat(b.login("dora@example.com", "battery-staple-42").status()).isEqualTo(200);
    }

    @Test
    void ac8WrongCurrentPasswordChangesNothing() throws Exception {
        Browser b = signedIn("Eli", "eli@example.com");
        String hashBefore = hashInDb("eli@example.com");

        Resp r = b.changePassword("wrong-horse", "battery-staple-42");

        assertThat(r.status()).isEqualTo(400);
        assertThat(r.contentType()).startsWith("application/problem+json");
        assertThat(r.code()).isEqualTo("WRONG_PASSWORD");
        assertThat(r.json().get("detail").asText()).isEqualTo("Current password is incorrect");
        assertThat(r.json().get("errors")).hasSize(1);
        assertThat(r.error().get("field").asText()).isEqualTo("currentPassword");
        assertThat(r.error().get("code").asText()).isEqualTo("WRONG_PASSWORD");
        assertThat(r.error().get("message").asText()).isEqualTo("Current password is incorrect");
        assertThat(hashInDb("eli@example.com")).isEqualTo(hashBefore);
        // Still signed in: a wrong password is never a 401.
        assertThat(b.get("/api/auth/me").status()).isEqualTo(200);
    }

    @Test
    void ac9InvalidNewPasswordChangesNothingAndIsNotCountedTowardsTheLimit() throws Exception {
        Browser b = signedIn("Fay", "fay@example.com");
        String hashBefore = hashInDb("fay@example.com");

        for (int i = 0; i < 3; i++) {
            for (String invalid : List.of("1234567", "x".repeat(73))) {
                Resp r = b.changePassword("wrong-horse", invalid);
                assertThat(r.status()).isEqualTo(400);
                assertThat(r.code()).isEqualTo("VALIDATION_FAILED");
                assertThat(r.error().get("field").asText()).isEqualTo("newPassword");
                assertThat(r.error().get("code").asText()).isEqualTo("PASSWORD_LENGTH");
            }
        }
        assertThat(hashInDb("fay@example.com")).isEqualTo(hashBefore);
        // Six validation failures with a wrong current password were not counted: the limit is 5.
        assertThat(b.changePassword(PASSWORD, "x".repeat(72)).status()).isEqualTo(204);
    }

    // --- AC11-AC13: delete ---

    @Test
    void ac11DeleteRemovesAccountAndTasksSignsOutAndFreesEmailAndName() throws Exception {
        Browser b = signedIn("Gus", "gus@example.com");
        long id = jdbc.queryForObject("select id from users where email = 'gus@example.com'", Long.class);
        assertThat(b.createTask("Gus task 1").status()).isEqualTo(201);
        assertThat(b.createTask("Gus task 2").status()).isEqualTo(201);
        String csrfBefore = b.cookies.get("XSRF-TOKEN");

        Resp r = b.deleteAccount(PASSWORD);

        assertThat(r.status()).isEqualTo(204);
        assertThat(r.body()).isEmpty();
        assertThat(r.setCookies().stream().filter(c -> c.startsWith("JSESSIONID=")).findFirst().orElseThrow())
                .contains("Max-Age=0");
        assertThat(b.cookies).doesNotContainKey("JSESSIONID");
        assertThat(b.cookies.get("XSRF-TOKEN")).isNotNull().isNotEqualTo(csrfBefore);
        assertThat(jdbc.queryForObject("select count(*) from users where id = ?", Integer.class, id)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from tasks where owner_id = ?", Integer.class, id)).isZero();
        assertThat(b.get("/api/auth/me").status()).isEqualTo(401);

        // The fresh XSRF-TOKEN from the 204 works right away, without calling /me first.
        Resp login = secondSessionWithoutLogin(b).login("gus@example.com", PASSWORD);
        assertThat(login.status()).isEqualTo(401);
        assertThat(login.json().get("detail").asText()).isEqualTo("Invalid email or password");
        assertThat(b.register("Gus", "gus@example.com")).isNotEqualTo(id);
    }

    @Test
    void ac12WrongPasswordDeletesNothingAndKeepsSession() throws Exception {
        Browser b = signedIn("Hal", "hal@example.com");
        b.createTask("Hal task");

        Resp r = b.deleteAccount("wrong-horse");

        assertThat(r.status()).isEqualTo(400);
        assertThat(r.code()).isEqualTo("WRONG_PASSWORD");
        assertThat(r.json().get("detail").asText()).isEqualTo("Password is incorrect");
        assertThat(r.json().get("errors")).hasSize(1);
        assertThat(r.error().get("field").asText()).isEqualTo("password");
        assertThat(r.error().get("code").asText()).isEqualTo("WRONG_PASSWORD");
        assertThat(r.error().get("message").asText()).isEqualTo("Password is incorrect");
        assertThat(r.setCookies()).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from users where email = 'hal@example.com'", Integer.class))
                .isEqualTo(1);
        assertThat(b.get("/api/tasks").json()).hasSize(1);
    }

    @Test
    void ac13OtherUsersAreUnaffectedByADeletion() throws Exception {
        Browser a = signedIn("Ivy", "ivy@example.com");
        Browser other = signedIn("Jon", "jon@example.com");
        a.createTask("Ivy task");
        other.createTask("Jon task 1");
        other.createTask("Jon task 2");

        assertThat(a.deleteAccount(PASSWORD).status()).isEqualTo(204);

        assertThat(other.get("/api/auth/me").json().get("email").asText()).isEqualTo("jon@example.com");
        JsonNode tasks = other.get("/api/tasks").json();
        assertThat(tasks).hasSize(2);
        assertThat(tasks.findValuesAsText("title")).containsExactlyInAnyOrder("Jon task 1", "Jon task 2");
        assertThat(other.createTask("Jon task 3").status()).isEqualTo(201);
        assertThat(new Browser().start().login("jon@example.com", PASSWORD).status()).isEqualTo(200);
    }

    @Test
    void secondSessionOfADeletedUserGets401NotA500AndCreatesNothing() throws Exception {
        Browser b = signedIn("Kai", "kai@example.com");
        long id = jdbc.queryForObject("select id from users where email = 'kai@example.com'", Long.class);
        Browser other = secondSession("kai@example.com");

        assertThat(b.deleteAccount(PASSWORD).status()).isEqualTo(204);

        Resp list = other.get("/api/tasks");
        assertThat(list.status()).isEqualTo(401);
        assertThat(list.contentType()).startsWith("application/problem+json");
        assertThat(list.code()).isEqualTo("UNAUTHENTICATED");

        Browser another = secondSessionWithoutLogin(other);
        Resp create = another.createTask("Orphan");
        assertThat(create.status()).isEqualTo(401);
        assertThat(create.code()).isEqualTo("UNAUTHENTICATED");
        assertThat(jdbc.queryForObject("select count(*) from tasks where owner_id = ?", Integer.class, id)).isZero();

        assertThat(other.get("/api/auth/me").status()).isEqualTo(401);
        assertThat(other.changeDisplayName("Ghost").status()).isEqualTo(401);
        assertThat(other.post("/api/auth/logout", null).status()).isEqualTo(204);
    }

    /** A fresh browser holding the same (now dead) session cookie as {@code source}, like another tab. */
    private Browser secondSessionWithoutLogin(Browser source) {
        Browser copy = new Browser();
        copy.cookies.putAll(source.cookies);
        return copy;
    }

    @Test
    void deletedUsersOtherSessionsDieOnTheirFirstRequestEvenAPost() throws Exception {
        Browser b = signedIn("Lia", "lia@example.com");
        long id = jdbc.queryForObject("select id from users where email = 'lia@example.com'", Long.class);
        Browser other = secondSession("lia@example.com");

        assertThat(b.deleteAccount(PASSWORD).status()).isEqualTo(204);

        // First call of the other session is a state-changing one.
        Resp create = other.createTask("Orphan");
        assertThat(create.status()).isEqualTo(401);
        assertThat(create.code()).isEqualTo("UNAUTHENTICATED");
        assertThat(jdbc.queryForObject("select count(*) from tasks where owner_id = ?", Integer.class, id)).isZero();
    }

    // --- AC14: audit events ---

    @Test
    void ac14EveryAccountActionWritesASecurityEventAndNoPassword() throws Exception {
        String secret = "Sup3r-Secret-Pw";
        String wrong = "Wr0ng-Guess-Pw";
        String next = "N3w-Secret-Pw-42";
        Browser b = new Browser().start();
        b.post("/api/auth/register",
                "{\"displayName\":\"Max\",\"email\":\"max@example.com\",\"password\":\"" + secret + "\"}");
        capture.clear();

        assertThat(b.changeDisplayName("Maxi").status()).isEqualTo(200);
        assertThat(b.changePassword(wrong, next).status()).isEqualTo(400);
        assertThat(b.changePassword(secret, next).status()).isEqualTo(204);
        assertThat(b.deleteAccount(wrong).status()).isEqualTo(400);
        Resp deleted = b.deleteAccount(next);
        assertThat(deleted.status()).isEqualTo(204);

        String tail = " email=max@example.com ip=127.0.0.1 path=/api/account/";
        assertThat(capture.lines()).containsExactly(
                "SECURITY event=DISPLAY_NAME_CHANGED" + tail + "display-name",
                "SECURITY event=PASSWORD_CHECK_FAILURE action=change_password" + tail + "password",
                "SECURITY event=PASSWORD_CHANGED" + tail + "password",
                "SECURITY event=PASSWORD_CHECK_FAILURE action=delete_account" + tail + "delete",
                "SECURITY event=ACCOUNT_DELETED" + tail + "delete");
        assertThat(capture.events().stream().map(e -> e.getLevel().toString()).toList())
                .containsExactly("INFO", "WARN", "INFO", "WARN", "INFO");
        assertThat(String.join("\n", capture.lines())).doesNotContain(secret, wrong, next);
    }

    // --- AC15: shared per-user password-check limit ---

    @Test
    void ac15FiveWrongPasswordsAcrossBothEndpointsGive429OnBoth() throws Exception {
        Browser b = signedIn("Ned", "ned@example.com");
        Browser bystander = signedIn("Ola", "ola@example.com");

        for (int i = 0; i < 3; i++) {
            assertThat(b.changePassword("wrong-horse", "battery-staple-42").status()).isEqualTo(400);
        }
        for (int i = 0; i < 2; i++) {
            assertThat(b.deleteAccount("wrong-horse").status()).isEqualTo(400);
        }
        capture.clear();

        Resp change = b.changePassword(PASSWORD, "battery-staple-42");
        Resp delete = b.deleteAccount(PASSWORD);

        for (Resp r : List.of(change, delete)) {
            assertThat(r.status()).isEqualTo(429);
            assertThat(r.contentType()).startsWith("application/problem+json");
            assertThat(r.code()).isEqualTo("RATE_LIMITED");
            assertThat(r.json().get("detail").asText()).isEqualTo("Too many attempts. Please try again later.");
            assertThat(r.json().has("errors")).isFalse();
            assertThat(Long.parseLong(r.retryAfter())).isBetween(1L, 900L);
        }
        assertThat(delete.json().get("instance").asText()).isEqualTo("/api/account/delete");
        assertThat(capture.lines()).containsExactly(
                "SECURITY event=RATE_LIMITED limit=password-check-user email=ned@example.com ip=127.0.0.1"
                        + " path=/api/account/password",
                "SECURITY event=RATE_LIMITED limit=password-check-user email=ned@example.com ip=127.0.0.1"
                        + " path=/api/account/delete");
        // Nothing changed, still signed in; validation still answers first.
        assertThat(jdbc.queryForObject("select count(*) from users where email = 'ned@example.com'", Integer.class))
                .isEqualTo(1);
        assertThat(b.get("/api/auth/me").status()).isEqualTo(200);
        assertThat(b.changePassword(PASSWORD, "short").code()).isEqualTo("VALIDATION_FAILED");
        // Other users are not affected; login is a separate limit.
        assertThat(bystander.changePassword(PASSWORD, PASSWORD).status()).isEqualTo(204);
        assertThat(new Browser().start().login("ned@example.com", PASSWORD).status()).isEqualTo(200);
    }

    @Test
    void ac15CorrectPasswordResetsTheCounter() throws Exception {
        Browser b = signedIn("Pia", "pia@example.com");
        for (int round = 0; round < 2; round++) {
            for (int i = 0; i < 4; i++) {
                assertThat(b.deleteAccount("wrong-horse").status()).isEqualTo(400);
            }
            assertThat(b.changePassword(PASSWORD, PASSWORD).status()).isEqualTo(204);
        }
    }

    // --- AC16: 401 and 403 ---

    @Test
    void ac16EndpointsAre401WithoutSessionAnd403WithoutCsrf() throws Exception {
        Browser anonymous = new Browser().start();
        for (Resp r : List.of(anonymous.changeDisplayName("X"), anonymous.changePassword(PASSWORD, PASSWORD),
                anonymous.deleteAccount(PASSWORD))) {
            assertThat(r.status()).isEqualTo(401);
            assertThat(r.contentType()).startsWith("application/problem+json");
            assertThat(r.code()).isEqualTo("UNAUTHENTICATED");
        }

        Browser b = signedIn("Quin", "quin@example.com");
        b.sendCsrfHeader = false;
        for (Resp r : List.of(b.changeDisplayName("Quinn"), b.changePassword(PASSWORD, "battery-staple-42"),
                b.deleteAccount(PASSWORD))) {
            assertThat(r.status()).isEqualTo(403);
            assertThat(r.code()).isEqualTo("CSRF_INVALID");
        }
        assertThat(displayNameInDb("quin@example.com")).isEqualTo("Quin");
        b.sendCsrfHeader = true;
        assertThat(b.get("/api/auth/me").status()).isEqualTo(200);
        assertThat(b.login("quin@example.com", PASSWORD).status()).isEqualTo(200);
    }

    @Test
    void noAccountResponseContainsAPasswordOrHash() throws Exception {
        Browser b = signedIn("Ray", "ray@example.com");
        String hash = hashInDb("ray@example.com");

        for (Resp r : List.of(b.changeDisplayName("Rae"), b.changePassword("Wrong-Pw-123", "N3w-Pw-456"),
                b.deleteAccount("Wrong-Pw-123"))) {
            assertThat(r.body()).doesNotContain(PASSWORD, "Wrong-Pw-123", "N3w-Pw-456", hash, "$2a$");
        }
    }
}
