package com.taskapp.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.taskapp.security.RememberMeToken;
import com.taskapp.security.RememberMeTokenStore;

/**
 * Regression for bug B1 (specs/sessions.md): concurrent writes to {@code remember_me_tokens} deadlocked in MySQL
 * (gap locks of the "delete expired" DELETE on {@code ix_remember_me_tokens_user}) and the login answered 500.
 * Many parallel remember-me logins (different users, and several devices of one user), parallel "log out other
 * sessions" / password changes, and the stolen-cookie cleanup must never fail. Each scenario is repeated.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.rate-limit.login-per-ip-per-minute=100000",
        "app.rate-limit.register-per-ip-per-hour=100000"})
class RememberMeConcurrencyIntegrationTest {

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
            .withUrlParam("connectionTimeZone", "UTC")
            .withUrlParam("forceConnectionTimeZoneToSession", "true");

    private static final String PASSWORD = "correct-horse";
    private static final int USERS = 12;
    private static final int DEVICES_PER_USER = 3;
    private static final AtomicInteger SEQ = new AtomicInteger();

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    RememberMeTokenStore store;

    private final HttpClient http = HttpClient.newHttpClient();
    private final SecureRandom random = new SecureRandom();

    // ---- scenarios ----

    @RepeatedTest(5)
    void parallelRememberMeLoginsOfManyUsersAndDevicesNeverFail() throws Exception {
        List<String> emails = new ArrayList<>();
        for (int i = 0; i < USERS; i++) {
            emails.add(register());
        }
        // Expired logins make the cleanup in create() actually delete rows for half of the users.
        for (int i = 0; i < USERS; i += 2) {
            insertExpiredToken(userId(emails.get(i)));
        }
        List<Browser> browsers = new ArrayList<>();
        for (String email : emails) {
            for (int d = 0; d < DEVICES_PER_USER; d++) {
                browsers.add(new Browser(email).start());
            }
        }

        List<Integer> statuses = runTogether(browsers.stream()
                .<Callable<Integer>>map(b -> () -> b.login(true).status())
                .toList());

        assertThat(statuses).as("login statuses").containsOnly(200);
        for (String email : emails) {
            assertThat(validTokenCount(email)).as(email).isEqualTo(DEVICES_PER_USER);
        }
    }

    @RepeatedTest(5)
    void parallelLogoutOthersPasswordChangesAndLoginsOfOneUserNeverFail() throws Exception {
        List<String> emails = new ArrayList<>();
        List<Callable<Integer>> work = new ArrayList<>();
        for (int u = 0; u < 4; u++) {
            String email = register();
            emails.add(email);
            insertExpiredToken(userId(email));
            List<Browser> devices = new ArrayList<>();
            for (int d = 0; d < 4; d++) {
                Browser b = new Browser(email).start();
                assertThat(b.login(true).status()).isEqualTo(200);
                devices.add(b);
            }
            work.add(() -> devices.get(0).post("/api/account/logout-other-sessions", null).status());
            work.add(() -> devices.get(1).put("/api/account/password",
                    "{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"" + PASSWORD + "\"}").status());
            work.add(() -> devices.get(2).post("/api/account/logout-other-sessions", null).status());
            work.add(() -> devices.get(3).put("/api/account/password",
                    "{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"" + PASSWORD + "\"}").status());
            for (int d = 0; d < 3; d++) {
                Browser fresh = new Browser(email).start();
                work.add(() -> fresh.login(true).status());
            }
        }

        List<Integer> statuses = runTogether(work);

        // 401 is the contract's answer when another device's "log out others" ended the caller's session first.
        assertThat(statuses).as("statuses").allMatch(s -> s == 200 || s == 204 || s == 401);
        for (int i = 0; i < statuses.size(); i++) {
            if (i % 7 >= 4) {
                assertThat(statuses.get(i)).as("login " + i).isEqualTo(200);
            }
        }
    }

    /** The stolen-cookie response ({@code endAllForUser}) racing new logins of the same and other users. */
    @RepeatedTest(5)
    void parallelEndAllForUserAndCreateNeverFail() throws Exception {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < USERS; i++) {
            long id = userId(register());
            ids.add(id);
            insertExpiredToken(id);
            store.create(token(id), now());
        }
        List<Callable<Integer>> work = new ArrayList<>();
        for (long id : ids) {
            work.add(() -> {
                store.endAllForUser(id, now());
                return 0;
            });
            for (int d = 0; d < 2; d++) {
                work.add(() -> {
                    store.create(token(id), now());
                    return 0;
                });
            }
        }

        assertThat(runTogether(work)).containsOnly(0);
    }

    // ---- helpers ----

    /** Starts all tasks at the same moment; any exception (e.g. a deadlock) fails the test. */
    private static <T> List<T> runTogether(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    go.await();
                    return task.call();
                }));
            }
            go.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) {
                results.add(f.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private String register() throws Exception {
        int n = SEQ.incrementAndGet();
        String email = "conc" + n + "@example.com";
        Browser b = new Browser(email).start();
        Resp r = b.post("/api/auth/register", "{\"displayName\":\"Conc " + n + "\",\"email\":\"" + email
                + "\",\"password\":\"" + PASSWORD + "\"}");
        assertThat(r.status()).as(r.body()).isEqualTo(201);
        return email;
    }

    private long userId(String email) {
        return jdbc.queryForObject("select id from users where email = ?", Long.class, email);
    }

    private int validTokenCount(String email) {
        return jdbc.queryForObject("select count(*) from remember_me_tokens where user_id = ? and expires_at > ?",
                Integer.class, userId(email), java.sql.Timestamp.from(now()));
    }

    private void insertExpiredToken(long userId) {
        jdbc.update("insert into remember_me_tokens (series_hash, user_id, token_hash, expires_at) values (?, ?, ?, ?)",
                randomBytes(), userId, randomBytes(), java.sql.Timestamp.from(now().minusSeconds(60)));
    }

    private RememberMeToken token(long userId) {
        return new RememberMeToken(randomBytes(), userId, randomBytes(), null, null, now().plusSeconds(3600));
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    private byte[] randomBytes() {
        byte[] b = new byte[32];
        random.nextBytes(b);
        return b;
    }

    /** A minimal cookie jar with the XSRF header, like the browser + Angular. */
    class Browser {
        final String email;
        final Map<String, String> cookies = new LinkedHashMap<>();

        Browser(String email) {
            this.email = email;
        }

        Browser start() throws Exception {
            send("GET", "/api/auth/me", null);
            assertThat(cookies).containsKey("XSRF-TOKEN");
            return this;
        }

        Resp login(boolean rememberMe) throws Exception {
            return post("/api/auth/login", "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD
                    + "\",\"rememberMe\":" + rememberMe + "}");
        }

        Resp post(String path, String body) throws Exception {
            return send("POST", path, body);
        }

        Resp put(String path, String body) throws Exception {
            return send("PUT", path, body);
        }

        synchronized Resp send(String method, String path, String body) throws Exception {
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
            if (!"GET".equals(method) && cookies.containsKey("XSRF-TOKEN")) {
                b.header("X-XSRF-TOKEN", cookies.get("XSRF-TOKEN"));
            }
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            for (String sc : r.headers().allValues("Set-Cookie")) {
                String[] nameValue = sc.split(";", 2)[0].split("=", 2);
                if (sc.contains("Max-Age=0")) {
                    cookies.remove(nameValue[0]);
                } else {
                    cookies.put(nameValue[0], nameValue[1]);
                }
            }
            if (r.statusCode() >= 500) {
                throw new AssertionError(method + " " + path + " -> " + r.statusCode() + " " + r.body());
            }
            return new Resp(r.statusCode(), r.body());
        }
    }

    record Resp(int status, String body) {
    }
}
