package com.taskapp.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
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

import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.taskapp.auth.EmailJobs;
import com.taskapp.auth.EmailTokenPurpose;
import com.taskapp.auth.EmailTokenService;
import com.taskapp.security.audit.SecurityLogCapture;

/**
 * specs/email-auth.md AC16 and the bug B1 lock rules for email tokens: one token used by parallel requests works
 * exactly once, and token writes racing remember-me logins, automatic remember-me sign-ins, resends and a password
 * reset of the same user never deadlock or fail. Each scenario is repeated. No mail server is needed: emails fail
 * fast (closed SMTP port), tokens are issued directly where a test needs to know them.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.rate-limit.login-per-ip-per-minute=100000",
        "app.rate-limit.register-per-ip-per-hour=100000",
        "app.rate-limit.verify-email-per-ip-per-hour=100000",
        "app.rate-limit.reset-password-per-ip-per-hour=100000",
        "app.rate-limit.resend-verification-per-user-per-hour=100000"})
class EmailTokenConcurrencyIntegrationTest {

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
            .withUrlParam("connectionTimeZone", "UTC")
            .withUrlParam("forceConnectionTimeZoneToSession", "true");

    private static final String PASSWORD = "correct-horse";
    private static final String NEW_PASSWORD = "battery-staple-42";
    private static final AtomicInteger SEQ = new AtomicInteger();

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    EmailTokenService tokens;

    @Autowired
    EmailJobs emailJobs;

    private final HttpClient http = HttpClient.newHttpClient();
    private final SecurityLogCapture capture = new SecurityLogCapture();

    @BeforeEach
    void start() {
        capture.start();
    }

    @AfterEach
    void stop() {
        capture.stop();
    }

    @RepeatedTest(5)
    void parallelResetsWithOneTokenSucceedExactlyOnce() throws Exception {
        String email = register();
        String token = tokens.issue(userId(email), EmailTokenPurpose.RESET_PASSWORD).orElseThrow().token();
        List<Browser> browsers = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            browsers.add(new Browser().start());
        }

        List<Integer> statuses = runTogether(browsers.stream().<Callable<Integer>>map(b -> () -> b.post(
                "/api/auth/reset-password", "{\"token\":\"" + token + "\",\"newPassword\":\"" + NEW_PASSWORD
                        + "\"}")).toList());

        assertThat(statuses).filteredOn(s -> s == 204).hasSize(1);
        assertThat(statuses).filteredOn(s -> s == 400).hasSize(7);
        assertThat(capture.allLines()).filteredOn(l -> l.startsWith("SECURITY event=PASSWORD_RESET_COMPLETED email="
                + email)).hasSize(1);
        assertThat(new Browser().start().post("/api/auth/login",
                "{\"email\":\"" + email + "\",\"password\":\"" + NEW_PASSWORD + "\"}")).isEqualTo(200);
    }

    @RepeatedTest(3)
    void parallelVerificationsWithOneTokenSucceedExactlyOnce() throws Exception {
        String email = register();
        String token = tokens.issue(userId(email), EmailTokenPurpose.VERIFY_EMAIL).orElseThrow().token();
        List<Browser> browsers = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            browsers.add(new Browser().start());
        }

        List<Integer> statuses = runTogether(browsers.stream().<Callable<Integer>>map(
                b -> () -> b.post("/api/auth/verify-email", "{\"token\":\"" + token + "\"}")).toList());

        assertThat(statuses).filteredOn(s -> s == 204).hasSize(1);
        assertThat(statuses).filteredOn(s -> s == 400).hasSize(7);
    }

    @RepeatedTest(5)
    void parallelResendsRememberMeLoginsAndResetOfOneUserNeverDeadlock() throws Exception {
        List<Callable<Integer>> work = new ArrayList<>();
        for (int u = 0; u < 4; u++) {
            String email = register();
            long id = userId(email);
            List<Browser> devices = new ArrayList<>();
            for (int d = 0; d < 3; d++) {
                Browser b = new Browser().start();
                assertThat(b.login(email, true)).isEqualTo(200);
                devices.add(b);
            }
            String resetToken = tokens.issue(id, EmailTokenPurpose.RESET_PASSWORD).orElseThrow().token();
            // Device 2 "closes the browser": its next request signs in from the remember-me cookie.
            devices.get(2).cookies.remove("JSESSIONID");

            work.add(() -> devices.get(0).post("/api/account/resend-verification", null));
            work.add(() -> devices.get(1).post("/api/account/resend-verification", null));
            work.add(() -> devices.get(2).get("/api/tasks"));
            work.add(() -> new Browser().start().post("/api/auth/reset-password",
                    "{\"token\":\"" + resetToken + "\",\"newPassword\":\"" + NEW_PASSWORD + "\"}"));
            for (int d = 0; d < 2; d++) {
                Browser fresh = new Browser().start();
                work.add(() -> fresh.login(email, true));
            }
            work.add(() -> {
                tokens.issue(id, EmailTokenPurpose.VERIFY_EMAIL);
                return 0;
            });
            work.add(() -> {
                tokens.issue(id, EmailTokenPurpose.VERIFY_EMAIL);
                return 0;
            });
        }

        List<Integer> statuses = runTogether(work);

        // 401: a reset ended the caller's session or remember-me login first; 409: the reset confirmed the email
        // first; 400: never expected here (the reset token is not replaced); 200/204 otherwise.
        assertThat(statuses).as("statuses").allMatch(s -> s == 0 || s == 200 || s == 204 || s == 401 || s == 409);
        for (int i = 0; i < statuses.size(); i++) {
            if (i % 8 == 3) {
                assertThat(statuses.get(i)).as("reset " + i).isEqualTo(204);
            }
        }
        Awaitility.await().atMost(Duration.ofSeconds(30)).until(emailJobs::isIdle);
        assertThat(capture.allLines()).noneMatch(l -> l.contains("reason=error"));
    }

    // ---- helpers ----

    /** Starts all tasks at the same moment; any exception (e.g. a deadlock, or a 5xx) fails the test. */
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
            return Collections.unmodifiableList(results);
        } finally {
            pool.shutdownNow();
        }
    }

    private String register() throws Exception {
        int n = SEQ.incrementAndGet();
        String email = "etc" + n + "@example.com";
        assertThat(new Browser().start().post("/api/auth/register", "{\"displayName\":\"Etc " + n
                + "\",\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}")).isEqualTo(201);
        return email;
    }

    private long userId(String email) {
        return jdbc.queryForObject("select id from users where email = ?", Long.class, email);
    }

    /** A minimal cookie jar with the XSRF header, like the browser + Angular. */
    class Browser {
        final Map<String, String> cookies = new LinkedHashMap<>();

        Browser start() throws Exception {
            get("/api/auth/me");
            assertThat(cookies).containsKey("XSRF-TOKEN");
            return this;
        }

        int login(String email, boolean rememberMe) throws Exception {
            return post("/api/auth/login", "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD
                    + "\",\"rememberMe\":" + rememberMe + "}");
        }

        int get(String path) throws Exception {
            return send("GET", path, null);
        }

        int post(String path, String body) throws Exception {
            return send("POST", path, body);
        }

        synchronized int send(String method, String path, String body) throws Exception {
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
            return r.statusCode();
        }
    }
}
