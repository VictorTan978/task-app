package com.taskapp.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskapp.security.audit.SecurityLogCapture;
import com.taskapp.security.ratelimit.MutableClock;

/**
 * Sessions (specs/sessions.md AC2-AC15) end-to-end over real HTTP against MySQL 8.4 with the Flyway migrations.
 * The server's clock is a {@link MutableClock} (12h / 30d limits, 60s grace). "Closing the browser" drops the
 * browser-session cookies (JSESSIONID, XSRF-TOKEN); an idle-expired session is a JSESSIONID the server no longer
 * knows. Every test uses its own accounts; the clock only moves forward.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.rate-limit.login-per-ip-per-minute=10000",
        "app.rate-limit.register-per-ip-per-hour=10000"})
class SessionsIntegrationTest {

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
            .withUrlParam("connectionTimeZone", "UTC")
            .withUrlParam("forceConnectionTimeZoneToSession", "true");

    @TestConfiguration
    static class ClockConfig {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(Instant.now());
        }
    }

    private static final String PASSWORD = "correct-horse";
    private static final long THIRTY_DAYS_S = Duration.ofDays(30).getSeconds();

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MutableClock clock;

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

    // ---- a cookie jar that behaves like a browser + Angular HttpClient ----

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

        HttpRequest request(String method, String path, String body) {
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
            return b.build();
        }

        Resp send(String method, String path, String body) throws Exception {
            return accept(http.send(request(method, path, body), HttpResponse.BodyHandlers.ofString()));
        }

        Resp accept(HttpResponse<String> r) {
            List<String> setCookies = r.headers().allValues("Set-Cookie");
            for (String sc : setCookies) {
                String[] nameValue = sc.split(";", 2)[0].split("=", 2);
                if (sc.contains("Max-Age=0")) {
                    cookies.remove(nameValue[0]);
                } else {
                    cookies.put(nameValue[0], nameValue[1]);
                }
            }
            return new Resp(r.statusCode(), r.body(), setCookies);
        }

        Browser start() throws Exception {
            get("/api/auth/me");
            assertThat(cookies).containsKey("XSRF-TOKEN");
            return this;
        }

        long register(String name, String email) throws Exception {
            Resp r = post("/api/auth/register", "{\"displayName\":\"" + name + "\",\"email\":\"" + email
                    + "\",\"password\":\"" + PASSWORD + "\"}");
            assertThat(r.status()).as(r.body()).isEqualTo(201);
            return r.json().get("id").asLong();
        }

        Resp login(String email, boolean rememberMe) throws Exception {
            return post("/api/auth/login", "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD
                    + "\",\"rememberMe\":" + rememberMe + "}");
        }

        Resp createTask(String title) throws Exception {
            return post("/api/tasks", "{\"title\":\"" + title + "\"}");
        }

        /** Closing the browser: only the persistent remember-me cookie survives. */
        Browser restart() {
            cookies.remove("JSESSIONID");
            cookies.remove("XSRF-TOKEN");
            return this;
        }

        /** The server forgot this session (30-minute idle timeout); the browser still sends the cookie. */
        void idleExpire() {
            cookies.put("JSESSIONID", "IDLE-EXPIRED-SESSION-ID");
        }

        /** Another browser holding a copy of this browser's cookies. */
        Browser copy() {
            Browser copy = new Browser();
            copy.cookies.putAll(cookies);
            return copy;
        }

        String rememberMe() {
            return cookies.get("remember-me");
        }
    }

    record Resp(int status, String body, List<String> setCookies) {
        JsonNode json() throws IOException {
            return new ObjectMapper().readTree(body);
        }

        String code() throws IOException {
            return json().get("code").asText();
        }

        List<String> cookies(String name) {
            return setCookies.stream().filter(c -> c.startsWith(name + "=")).toList();
        }

        String cookie(String name) {
            List<String> found = cookies(name);
            assertThat(found).as(name + " Set-Cookie").hasSize(1);
            return found.get(0);
        }
    }

    // ---- helpers ----

    private Browser registered(String name, String email) throws Exception {
        Browser b = new Browser().start();
        b.register(name, email);
        return b;
    }

    /** A new browser signed in to an existing account. */
    private Browser device(String email, boolean rememberMe) throws Exception {
        Browser b = new Browser().start();
        Resp r = b.login(email, rememberMe);
        assertThat(r.status()).as(r.body()).isEqualTo(200);
        return b;
    }

    /** Sessions signed in before now must be strictly older than a cutoff set now (the server clock is frozen). */
    private void tick() {
        clock.advance(Duration.ofSeconds(1));
    }

    private long userId(String email) {
        return jdbc.queryForObject("select id from users where email = ?", Long.class, email);
    }

    private int tokenCount(String email) {
        return jdbc.queryForObject("select count(*) from remember_me_tokens where user_id = ?", Integer.class,
                userId(email));
    }

    private static long maxAge(String setCookie) {
        int start = setCookie.indexOf("Max-Age=") + "Max-Age=".length();
        int end = setCookie.indexOf(';', start);
        return Long.parseLong(setCookie.substring(start, end < 0 ? setCookie.length() : end));
    }

    private static String sha256Hex(String base64Url) throws Exception {
        return HexFormat.of().withUpperCase().formatHex(
                MessageDigest.getInstance("SHA-256").digest(Base64.getUrlDecoder().decode(base64Url)));
    }

    // ---- AC2, AC12: remember me ----

    @Test
    void ac2And12LoginWithRememberMeSetsTheCookieAndStoresOnlyHashes() throws Exception {
        registered("Rita", "rita@example.com").post("/api/auth/logout", null);
        Browser b = new Browser().start();

        Resp r = b.login("RITA@example.com", true);

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.json().get("email").asText()).isEqualTo("rita@example.com");
        String cookie = r.cookie("remember-me");
        assertThat(cookie).contains("; Path=/api", "; Max-Age=" + THIRTY_DAYS_S, "; HttpOnly", "; SameSite=Lax")
                .doesNotContain("Domain", "Secure");
        assertThat(r.cookies("JSESSIONID")).hasSize(1);
        assertThat(r.cookies("XSRF-TOKEN")).hasSize(1);

        String value = b.rememberMe();
        String series = value.substring(0, 43);
        String secret = value.substring(44);
        Map<String, Object> row = jdbc.queryForMap("select hex(series_hash) s, hex(token_hash) t, "
                + "previous_token_hash p, rotated_at r, expires_at e from remember_me_tokens where user_id = ?",
                userId("rita@example.com"));
        assertThat(row.get("s")).isEqualTo(sha256Hex(series));
        assertThat(row.get("t")).isEqualTo(sha256Hex(secret));
        assertThat(row.get("p")).isNull();
        assertThat(row.get("r")).isNull();
        String allTokenData = jdbc.queryForList("select concat(hex(series_hash), hex(token_hash)) from "
                + "remember_me_tokens", String.class).toString();
        assertThat(allTokenData).doesNotContain(HexFormat.of().withUpperCase()
                .formatHex(Base64.getUrlDecoder().decode(secret)));
    }

    @Test
    void ac2ReopeningTheBrowserSignsInAutomaticallyWithRotatedCookies() throws Exception {
        registered("Sam", "sam@example.com");
        Browser b = device("sam@example.com", true);
        String before = b.rememberMe();
        String sessionBefore = b.cookies.get("JSESSIONID");
        capture.clear();

        b.restart();
        Resp me = b.get("/api/auth/me");

        assertThat(me.status()).isEqualTo(200);
        assertThat(me.json().get("email").asText()).isEqualTo("sam@example.com");
        assertThat(me.cookies("JSESSIONID")).hasSize(1);
        assertThat(b.cookies.get("JSESSIONID")).isNotEqualTo(sessionBefore);
        // Exactly one XSRF-TOKEN, although the browser came back without one.
        assertThat(me.cookies("XSRF-TOKEN")).hasSize(1);
        String rotated = me.cookie("remember-me");
        assertThat(rotated).contains("; Path=/api", "; HttpOnly", "; SameSite=Lax");
        assertThat(maxAge(rotated)).isBetween(THIRTY_DAYS_S - 5, THIRTY_DAYS_S);
        assertThat(b.rememberMe()).isNotEqualTo(before).startsWith(before.substring(0, 44));
        assertThat(capture.lines()).containsExactly(
                "SECURITY event=REMEMBER_ME_LOGIN email=sam@example.com ip=127.0.0.1 path=/api/auth/me");
        assertThat(String.join("\n", capture.lines())).doesNotContain(before, b.rememberMe());

        // The new session and the rotated XSRF token just work.
        assertThat(b.createTask("After restart").status()).isEqualTo(201);
        assertThat(b.get("/api/tasks").json()).hasSize(1);
    }

    @Test
    void ac2IdleExpiredSessionPlusValidCookieOnPostTasksSucceeds() throws Exception {
        registered("Tia", "tia@example.com");
        Browser b = device("tia@example.com", true);
        String csrfBefore = b.cookies.get("XSRF-TOKEN");
        b.idleExpire();

        Resp create = b.createTask("Created after idle timeout");

        assertThat(create.status()).as(create.body()).isEqualTo(201);
        assertThat(create.json().get("title").asText()).isEqualTo("Created after idle timeout");
        assertThat(jdbc.queryForObject("select count(*) from tasks where owner_id = ?", Integer.class,
                userId("tia@example.com"))).isEqualTo(1);
        assertThat(create.cookies("JSESSIONID")).hasSize(1);
        assertThat(create.cookies("XSRF-TOKEN")).hasSize(1);
        assertThat(b.cookies.get("XSRF-TOKEN")).isNotEqualTo(csrfBefore);
        assertThat(create.cookies("remember-me")).hasSize(1);

        // PUT on the next idle expiry too, with the rotated XSRF token.
        long taskId = create.json().get("id").asLong();
        b.idleExpire();
        Resp put = b.put("/api/tasks/" + taskId,
                "{\"title\":\"Renamed\",\"description\":null,\"dueDate\":null,\"done\":true}");
        assertThat(put.status()).as(put.body()).isEqualTo(200);
        assertThat(put.json().get("done").asBoolean()).isTrue();
    }

    @Test
    void ac3WithoutRememberMeReopeningTheBrowserIsSignedOut() throws Exception {
        registered("Ugo", "ugo@example.com");
        Browser b = device("ugo@example.com", false);
        assertThat(b.rememberMe()).isNull();
        assertThat(tokenCount("ugo@example.com")).isZero();

        Resp me = b.restart().get("/api/auth/me");

        assertThat(me.status()).isEqualTo(401);
        assertThat(me.code()).isEqualTo("UNAUTHENTICATED");
        assertThat(me.cookies("remember-me")).isEmpty();
    }

    // ---- AC4: 30 days from the login, not extended ----

    @Test
    void ac4RememberMeEndsThirtyDaysAfterTheLoginEvenWhenUsedDaily() throws Exception {
        registered("Vic", "vic@example.com");
        Browser b = device("vic@example.com", true);

        for (int day = 1; day <= 29; day++) {
            clock.advance(Duration.ofDays(1));
            Resp me = b.restart().get("/api/auth/me");
            assertThat(me.status()).as("day " + day).isEqualTo(200);
            assertThat(maxAge(me.cookie("remember-me"))).as("day " + day)
                    .isBetween(THIRTY_DAYS_S - Duration.ofDays(day).getSeconds() - 5,
                            THIRTY_DAYS_S - Duration.ofDays(day).getSeconds());
        }
        clock.advance(Duration.ofDays(1));
        capture.clear();

        Resp expired = b.restart().get("/api/auth/me");

        assertThat(expired.status()).isEqualTo(401);
        assertThat(expired.cookie("remember-me")).contains("Max-Age=0");
        assertThat(b.rememberMe()).isNull();
        assertThat(tokenCount("vic@example.com")).isZero();
        assertThat(capture.lines()).contains("SECURITY event=REMEMBER_ME_REJECTED reason=expired"
                + " email=vic@example.com ip=127.0.0.1 path=/api/auth/me");
    }

    @Test
    void rememberMeSessionEndsAtItsLoginsExpiryNotAfter12Hours() throws Exception {
        registered("Wes", "wes@example.com");
        Browser b = device("wes@example.com", true);

        clock.advance(Duration.ofHours(13));
        assertThat(b.get("/api/auth/me").status()).isEqualTo(200);
        assertThat(b.get("/api/tasks").setCookies()).isEmpty();

        clock.advance(Duration.ofDays(30));
        capture.clear();
        Resp me = b.get("/api/auth/me");

        assertThat(me.status()).isEqualTo(401);
        assertThat(capture.lines()).contains("SECURITY event=SESSION_MAX_LENGTH_REACHED kind=remember_me"
                + " email=wes@example.com ip=127.0.0.1 path=/api/auth/me");
    }

    // ---- AC5: logout ----

    @Test
    void ac5LogoutEndsOnlyThisDevicesRememberMeLogin() throws Exception {
        registered("Xia", "xia@example.com");
        Browser a = device("xia@example.com", true);
        Browser b = device("xia@example.com", true);
        Browser stolenFromA = a.copy();
        assertThat(tokenCount("xia@example.com")).isEqualTo(2);

        Resp logout = a.post("/api/auth/logout", null);

        assertThat(logout.status()).isEqualTo(204);
        assertThat(logout.cookie("remember-me")).contains("Max-Age=0", "Path=/api", "HttpOnly", "SameSite=Lax");
        assertThat(logout.cookie("JSESSIONID")).contains("Max-Age=0");
        assertThat(logout.cookies("XSRF-TOKEN")).hasSize(1);
        assertThat(tokenCount("xia@example.com")).isEqualTo(1);
        assertThat(a.restart().get("/api/auth/me").status()).isEqualTo(401);
        stolenFromA.restart();
        assertThat(stolenFromA.get("/api/auth/me").status()).isEqualTo(401);
        // Device B is not affected.
        assertThat(b.restart().get("/api/auth/me").status()).isEqualTo(200);
    }

    @Test
    void logoutEndsTheLoginTheSessionWasSignedInWithEvenWithoutTheCookie() throws Exception {
        registered("Yan", "yan@example.com");
        Browser b = device("yan@example.com", true);
        String cookie = b.rememberMe();
        b.cookies.remove("remember-me");

        assertThat(b.post("/api/auth/logout", null).status()).isEqualTo(204);

        assertThat(tokenCount("yan@example.com")).isZero();
        Browser later = new Browser();
        later.cookies.put("remember-me", cookie);
        assertThat(later.get("/api/auth/me").status()).isEqualTo(401);
    }

    @Test
    void logoutWithAnExpiredSessionNeverSignsInFromTheCookie() throws Exception {
        registered("Zed", "zed@example.com");
        Browser b = device("zed@example.com", true);
        b.idleExpire();
        capture.clear();

        Resp logout = b.post("/api/auth/logout", null);

        assertThat(logout.status()).isEqualTo(204);
        assertThat(logout.cookies("JSESSIONID")).singleElement().asString().contains("Max-Age=0");
        assertThat(tokenCount("zed@example.com")).isZero();
        assertThat(capture.lines()).noneMatch(line -> line.contains("REMEMBER_ME_LOGIN"));
        assertThat(capture.lines()).contains("SECURITY event=LOGOUT email=- ip=127.0.0.1 path=/api/auth/logout");
    }

    // ---- AC6: stolen cookie ----

    @Test
    void ac6ReusingAnAlreadyRotatedCookieEndsEverySessionAndLoginOfTheUser() throws Exception {
        registered("Ada", "ada@example.com");
        Browser victim = device("ada@example.com", true);
        Browser otherDevice = device("ada@example.com", true);
        Browser normalSession = device("ada@example.com", false);
        Browser thief = victim.copy();
        thief.cookies.remove("JSESSIONID");

        // The victim's browser uses the cookie first: it is rotated.
        assertThat(victim.restart().get("/api/auth/me").status()).isEqualTo(200);
        clock.advance(Duration.ofSeconds(61));
        capture.clear();

        Resp reuse = thief.get("/api/tasks");

        assertThat(reuse.status()).isEqualTo(401);
        assertThat(reuse.code()).isEqualTo("UNAUTHENTICATED");
        assertThat(reuse.cookie("remember-me")).contains("Max-Age=0");
        assertThat(capture.lines()).contains("SECURITY event=REMEMBER_ME_REJECTED reason=reused"
                + " email=ada@example.com ip=127.0.0.1 path=/api/tasks");
        assertThat(capture.events().stream().filter(e -> e.getFormattedMessage().contains("reason=reused"))
                .findFirst().orElseThrow().getLevel().toString()).isEqualTo("WARN");
        assertThat(tokenCount("ada@example.com")).isZero();
        assertThat(jdbc.queryForObject("select sessions_valid_after from users where email = 'ada@example.com'",
                java.sql.Timestamp.class)).isNotNull();
        // Every session and remember-me login of the user is over.
        assertThat(victim.get("/api/auth/me").status()).isEqualTo(401);
        assertThat(otherDevice.get("/api/auth/me").status()).isEqualTo(401);
        assertThat(otherDevice.restart().get("/api/auth/me").status()).isEqualTo(401);
        assertThat(normalSession.get("/api/auth/me").status()).isEqualTo(401);
        // Signing in again works normally.
        assertThat(device("ada@example.com", true).get("/api/auth/me").status()).isEqualTo(200);
    }

    @Test
    void parallelRequestsWithTheSameCookieAreAllSignedInAndNotTreatedAsTheft() throws Exception {
        registered("Ben", "ben@example.com");
        Browser b = device("ben@example.com", true);
        b.restart();
        capture.clear();

        List<CompletableFuture<HttpResponse<String>>> inFlight = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            inFlight.add(http.sendAsync(b.request("GET", i % 2 == 0 ? "/api/auth/me" : "/api/tasks", null),
                    HttpResponse.BodyHandlers.ofString()));
        }
        List<HttpResponse<String>> responses = inFlight.stream().map(CompletableFuture::join).toList();

        assertThat(responses).allSatisfy(r -> assertThat(r.statusCode()).isEqualTo(200));
        long rotations = responses.stream().filter(r -> r.headers().allValues("Set-Cookie").stream()
                .anyMatch(c -> c.startsWith("remember-me=") && !c.contains("Max-Age=0"))).count();
        assertThat(rotations).isEqualTo(1);
        assertThat(responses).allSatisfy(r -> assertThat(r.headers().allValues("Set-Cookie"))
                .noneMatch(c -> c.startsWith("remember-me=") && c.contains("Max-Age=0")));
        assertThat(capture.lines()).noneMatch(line -> line.contains("REMEMBER_ME_REJECTED"));
        assertThat(tokenCount("ben@example.com")).isEqualTo(1);

        // A late request with the pre-rotation cookie, still within 60 seconds: accepted, not rotated.
        clock.advance(Duration.ofSeconds(59));
        Resp late = b.copy().restart().get("/api/auth/me");
        assertThat(late.status()).isEqualTo(200);
        assertThat(late.cookies("remember-me")).isEmpty();

        // The browser keeps the last cookies it received; they work.
        responses.forEach(b::accept);
        assertThat(b.get("/api/auth/me").status()).isEqualTo(200);
        assertThat(b.restart().get("/api/auth/me").status()).isEqualTo(200);
    }

    // ---- AC7: maximum session length ----

    @Test
    void ac7NormalSessionEndsTwelveHoursAfterSignInEvenIfActive() throws Exception {
        Browser b = registered("Cal", "cal@example.com");

        for (int hour = 1; hour < 12; hour++) {
            clock.advance(Duration.ofHours(1));
            assertThat(b.get("/api/tasks").status()).as("hour " + hour).isEqualTo(200);
        }
        clock.advance(Duration.ofMinutes(59).plusSeconds(59));
        assertThat(b.get("/api/auth/me").status()).isEqualTo(200);
        clock.advance(Duration.ofSeconds(1));
        capture.clear();

        Resp r = b.get("/api/tasks");

        assertThat(r.status()).isEqualTo(401);
        assertThat(r.code()).isEqualTo("UNAUTHENTICATED");
        assertThat(capture.lines()).startsWith("SECURITY event=SESSION_MAX_LENGTH_REACHED kind=normal"
                + " email=cal@example.com ip=127.0.0.1 path=/api/tasks");
        // A new sign-in starts a new 12 hours.
        assertThat(b.login("cal@example.com", false).status()).isEqualTo(200);
        clock.advance(Duration.ofHours(11));
        assertThat(b.get("/api/auth/me").status()).isEqualTo(200);
    }

    @Test
    void sessionFromAnAutomaticSignInHasNo12HourLimit() throws Exception {
        registered("Dan", "dan@example.com");
        Browser b = device("dan@example.com", true);
        clock.advance(Duration.ofDays(2));
        b.idleExpire();
        assertThat(b.get("/api/auth/me").status()).isEqualTo(200);
        String session = b.cookies.get("JSESSIONID");

        clock.advance(Duration.ofHours(20));

        assertThat(b.get("/api/auth/me").status()).isEqualTo(200);
        assertThat(b.cookies.get("JSESSIONID")).isEqualTo(session);
    }

    // ---- AC10: log out other devices ----

    @Test
    void ac10LogOutOtherDevicesEndsOtherSessionsAndCookiesAndKeepsThisDevice() throws Exception {
        registered("Eve", "eve@example.com");
        Browser a = device("eve@example.com", true);
        Browser b = device("eve@example.com", true);
        Browser c = device("eve@example.com", false);
        String aSession = a.cookies.get("JSESSIONID");
        String aCsrf = a.cookies.get("XSRF-TOKEN");
        String aRememberMe = a.rememberMe();
        Browser oldSessionIdOnly = new Browser();
        oldSessionIdOnly.cookies.put("JSESSIONID", aSession);
        tick();
        capture.clear();

        Resp r = a.post("/api/account/logout-other-sessions", null);

        assertThat(r.status()).isEqualTo(204);
        assertThat(r.body()).isEmpty();
        assertThat(r.setCookies()).hasSize(1);
        assertThat(r.cookie("JSESSIONID")).doesNotContain("Max-Age=0");
        assertThat(a.cookies.get("JSESSIONID")).isNotEqualTo(aSession);
        assertThat(a.cookies.get("XSRF-TOKEN")).isEqualTo(aCsrf);
        assertThat(a.rememberMe()).isEqualTo(aRememberMe);
        assertThat(capture.lines()).containsExactly("SECURITY event=OTHER_SESSIONS_LOGGED_OUT trigger=button"
                + " email=eve@example.com ip=127.0.0.1 path=/api/account/logout-other-sessions");
        assertThat(tokenCount("eve@example.com")).isEqualTo(1);

        // A stays signed in (new id), also as a POST right away, and its old session id is dead.
        assertThat(a.createTask("Still here").status()).isEqualTo(201);
        assertThat(oldSessionIdOnly.get("/api/auth/me").status()).isEqualTo(401);
        // B and C: next call is the "session expired" flow; B's cookie is rejected and expired.
        Resp bNext = b.get("/api/tasks");
        assertThat(bNext.status()).isEqualTo(401);
        assertThat(bNext.cookie("remember-me")).contains("Max-Age=0");
        assertThat(b.restart().get("/api/auth/me").status()).isEqualTo(401);
        assertThat(c.get("/api/tasks").status()).isEqualTo(401);
        // A keeps working with A's cookie after its session is gone.
        assertThat(a.restart().get("/api/auth/me").status()).isEqualTo(200);

        // Idempotent, and A's own new sign-ins are not ended by the old cutoff.
        assertThat(a.post("/api/account/logout-other-sessions", null).status()).isEqualTo(204);
        assertThat(a.get("/api/auth/me").status()).isEqualTo(200);
        assertThat(device("eve@example.com", false).get("/api/auth/me").status()).isEqualTo(200);
    }

    @Test
    void ac10OtherTabsOfThisBrowserStaySignedIn() throws Exception {
        registered("Fin", "fin@example.com");
        Browser tab1 = device("fin@example.com", false);
        Browser otherDevice = device("fin@example.com", false);
        tick();

        assertThat(tab1.post("/api/account/logout-other-sessions", null).status()).isEqualTo(204);
        Browser tab2 = tab1.copy();

        assertThat(tab2.get("/api/tasks").status()).isEqualTo(200);
        assertThat(otherDevice.get("/api/tasks").status()).isEqualTo(401);
    }

    // ---- AC11: password change ----

    @Test
    void ac11PasswordChangeEndsOtherSessionsKeepsThisOneWithANewId() throws Exception {
        registered("Gil", "gil@example.com");
        Browser a = device("gil@example.com", true);
        Browser b = device("gil@example.com", true);
        Browser c = device("gil@example.com", false);
        String aSession = a.cookies.get("JSESSIONID");
        String aCsrf = a.cookies.get("XSRF-TOKEN");
        tick();
        capture.clear();

        Resp r = a.put("/api/account/password",
                "{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"battery-staple-42\"}");

        assertThat(r.status()).isEqualTo(204);
        assertThat(r.setCookies()).hasSize(1);
        assertThat(r.cookie("JSESSIONID")).doesNotContain("Max-Age=0");
        assertThat(a.cookies.get("JSESSIONID")).isNotEqualTo(aSession);
        assertThat(a.cookies.get("XSRF-TOKEN")).isEqualTo(aCsrf);
        assertThat(capture.lines()).containsExactly(
                "SECURITY event=PASSWORD_CHANGED email=gil@example.com ip=127.0.0.1 path=/api/account/password",
                "SECURITY event=OTHER_SESSIONS_LOGGED_OUT trigger=password_change email=gil@example.com"
                        + " ip=127.0.0.1 path=/api/account/password");
        assertThat(a.get("/api/auth/me").status()).isEqualTo(200);
        assertThat(b.get("/api/auth/me").status()).isEqualTo(401);
        assertThat(b.restart().get("/api/auth/me").status()).isEqualTo(401);
        assertThat(c.get("/api/auth/me").status()).isEqualTo(401);
        assertThat(tokenCount("gil@example.com")).isEqualTo(1);
        assertThat(a.restart().get("/api/auth/me").status()).isEqualTo(200);
    }

    @Test
    void ac11FailedPasswordChangeEndsNothing() throws Exception {
        registered("Hux", "hux@example.com");
        Browser a = device("hux@example.com", true);
        Browser b = device("hux@example.com", true);
        String aSession = a.cookies.get("JSESSIONID");
        tick();

        Resp r = a.put("/api/account/password",
                "{\"currentPassword\":\"wrong-horse\",\"newPassword\":\"battery-staple-42\"}");

        assertThat(r.status()).isEqualTo(400);
        assertThat(r.setCookies()).isEmpty();
        assertThat(a.cookies.get("JSESSIONID")).isEqualTo(aSession);
        assertThat(b.get("/api/auth/me").status()).isEqualTo(200);
        assertThat(tokenCount("hux@example.com")).isEqualTo(2);
    }

    // ---- AC13: delete account ----

    @Test
    void ac13DeletingTheAccountRemovesAllItsRememberMeLogins() throws Exception {
        registered("Ida", "ida@example.com");
        Browser a = device("ida@example.com", true);
        Browser b = device("ida@example.com", true);
        long id = userId("ida@example.com");

        Resp r = a.post("/api/account/delete", "{\"password\":\"" + PASSWORD + "\"}");

        assertThat(r.status()).isEqualTo(204);
        assertThat(r.cookie("remember-me")).contains("Max-Age=0");
        assertThat(r.cookie("JSESSIONID")).contains("Max-Age=0");
        assertThat(r.cookies("XSRF-TOKEN")).hasSize(1);
        assertThat(jdbc.queryForObject("select count(*) from remember_me_tokens where user_id = ?", Integer.class,
                id)).isZero();
        Resp other = b.restart().get("/api/auth/me");
        assertThat(other.status()).isEqualTo(401);
        assertThat(other.cookie("remember-me")).contains("Max-Age=0");
    }

    // ---- login / register replace this device's login ----

    @Test
    void registerEndsAndExpiresARememberMeCookieOfAnotherAccount() throws Exception {
        registered("Jan", "jan@example.com");
        Browser b = device("jan@example.com", true);

        Resp r = b.post("/api/auth/register",
                "{\"displayName\":\"Kim\",\"email\":\"kim@example.com\",\"password\":\"" + PASSWORD + "\"}");

        assertThat(r.status()).isEqualTo(201);
        assertThat(r.cookie("remember-me")).contains("Max-Age=0", "Path=/api");
        assertThat(tokenCount("jan@example.com")).isZero();
        assertThat(b.rememberMe()).isNull();
        assertThat(b.get("/api/auth/me").json().get("email").asText()).isEqualTo("kim@example.com");
        assertThat(b.restart().get("/api/auth/me").status()).isEqualTo(401);
    }

    @Test
    void loginReplacesThisDevicesRememberMeLogin() throws Exception {
        registered("Lou", "lou@example.com");
        registered("Mo", "mo@example.com");
        Browser b = device("lou@example.com", true);
        String first = b.rememberMe();

        Resp again = b.login("mo@example.com", true);
        assertThat(again.cookie("remember-me")).doesNotContain("Max-Age=0");
        assertThat(b.rememberMe()).isNotEqualTo(first);
        assertThat(tokenCount("lou@example.com")).isZero();
        assertThat(tokenCount("mo@example.com")).isEqualTo(1);

        Resp normal = b.login("mo@example.com", false);
        assertThat(normal.cookie("remember-me")).contains("Max-Age=0");
        assertThat(tokenCount("mo@example.com")).isZero();
        assertThat(b.restart().get("/api/auth/me").status()).isEqualTo(401);
    }

    @Test
    void failedLoginLeavesTheRememberMeCookieAlone() throws Exception {
        registered("Ned", "ned2@example.com");
        Browser b = device("ned2@example.com", true);

        Resp wrong = b.post("/api/auth/login", "{\"email\":\"ned2@example.com\",\"password\":\"wrong-horse\","
                + "\"rememberMe\":true}");

        assertThat(wrong.status()).isEqualTo(401);
        assertThat(wrong.cookies("remember-me")).isEmpty();
        assertThat(tokenCount("ned2@example.com")).isEqualTo(1);
    }

    // ---- guarantee 5: rejected cookies never cause an error ----

    @Test
    void rejectedCookiesAreExpiredAndTheRequestContinuesAnonymous() throws Exception {
        for (String value : List.of("garbage", "A".repeat(42) + "Q." + "B".repeat(42) + "A", "%zz%00")) {
            Browser b = new Browser();
            b.cookies.put("remember-me", value);

            Resp me = b.get("/api/auth/me");
            assertThat(me.status()).as(value).isEqualTo(401);
            assertThat(me.code()).isEqualTo("UNAUTHENTICATED");
            assertThat(me.cookie("remember-me")).contains("Max-Age=0");

            b.cookies.put("remember-me", value);
            Resp health = b.get("/api/health");
            assertThat(health.status()).isEqualTo(200);
            assertThat(health.cookie("remember-me")).contains("Max-Age=0");

            b.start();
            b.cookies.put("remember-me", value);
            Resp post = b.createTask("x");
            assertThat(post.status()).isEqualTo(401);
        }
    }

    // ---- AC15: 401 / 403 ----

    @Test
    void ac15LogoutOtherSessionsIs401WhenSignedOutAnd403WithoutCsrf() throws Exception {
        Browser anonymous = new Browser().start();
        Resp r = anonymous.post("/api/account/logout-other-sessions", null);
        assertThat(r.status()).isEqualTo(401);
        assertThat(r.code()).isEqualTo("UNAUTHENTICATED");

        Browser b = registered("Ola", "ola2@example.com");
        Browser other = device("ola2@example.com", false);
        b.sendCsrfHeader = false;
        Resp forbidden = b.post("/api/account/logout-other-sessions", null);
        assertThat(forbidden.status()).isEqualTo(403);
        assertThat(forbidden.code()).isEqualTo("CSRF_INVALID");
        assertThat(other.get("/api/auth/me").status()).isEqualTo(200);

        // A body is ignored.
        b.sendCsrfHeader = true;
        tick();
        assertThat(b.post("/api/account/logout-other-sessions", "{\"anything\":true}").status()).isEqualTo(204);
        assertThat(other.get("/api/auth/me").status()).isEqualTo(401);
    }
}
