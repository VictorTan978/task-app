package com.taskapp.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.taskapp.it.GoogleBrowser.Resp;

/**
 * Races decided by the unique keys (specs/google-sign-in.md, contract 0.8.0 "Account matching"): the loser's
 * attempt is retried and then sees the winner's rows. Never two accounts or connections, never a server error.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.rate-limit.login-per-ip-per-minute=10000",
        "app.rate-limit.register-per-ip-per-hour=10000",
        "app.rate-limit.google-start-per-ip-per-minute=10000",
        "app.rate-limit.google-callback-per-ip-per-minute=10000"})
class GoogleSignInConcurrencyIntegrationTest {

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
            .withUrlParam("connectionTimeZone", "UTC")
            .withUrlParam("forceConnectionTimeZoneToSession", "true");

    @Container
    static GenericContainer<?> oidc = FakeGoogle.container();

    @DynamicPropertySource
    static void google(DynamicPropertyRegistry registry) {
        FakeGoogle.register(oidc, registry);
    }

    private static final String TASKS = GoogleBrowser.APP_BASE_URL + "/tasks";
    private static final String UNVERIFIED = GoogleBrowser.APP_BASE_URL + "/login?oauth=google_unverified";

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    private final FakeGoogle google = new FakeGoogle(oidc);

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    /** Runs the tasks at the same moment; returns their results in order. */
    private static <T> List<T> together(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            CyclicBarrier barrier = new CyclicBarrier(tasks.size());
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return task.call();
                }));
            }
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void parallelCallbacksForTheSameNewGoogleAccountCreateOneAccount() throws Exception {
        for (int round = 0; round < 3; round++) {
            String sub = "race-sub-" + round;
            String email = "race" + round + "@gmail.com";
            List<GoogleBrowser> browsers = new ArrayList<>();
            List<Callable<Resp>> callbacks = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                GoogleBrowser b = new GoogleBrowser(port).start();
                String callback = b.approvedCallback(google, "login", sub,
                        FakeGoogle.claims(email, true, "Racer " + round));
                browsers.add(b);
                callbacks.add(() -> b.get(callback));
            }

            List<Resp> results = together(callbacks);

            assertThat(results).allSatisfy(r -> assertThat(r.location()).isEqualTo(TASKS));
            assertThat(count("select count(*) from users where email = ?", email)).isEqualTo(1);
            assertThat(count("select count(*) from user_connections where provider_subject = ?", sub)).isEqualTo(1);
            long id = jdbc.queryForObject("select id from users where email = ?", Long.class, email);
            for (GoogleBrowser b : browsers) {
                assertThat(b.me().json().get("id").asLong()).isEqualTo(id);
            }
        }
    }

    @Test
    void googleSignUpRacingARegisterForTheSameEmailEndsWithOneConsistentAccount() throws Exception {
        for (int round = 0; round < 8; round++) {
            String email = "both" + round + "@example.com";
            GoogleBrowser viaGoogle = new GoogleBrowser(port).start();
            String callback = viaGoogle.approvedCallback(google, "login", "both-sub-" + round,
                    FakeGoogle.claims(email, true, "Google Racer " + round));
            GoogleBrowser viaForm = new GoogleBrowser(port).start();
            String body = "{\"displayName\":\"Form Racer " + round + "\",\"email\":\"" + email
                    + "\",\"password\":\"correct-horse\"}";

            List<Resp> results = together(List.of(() -> viaGoogle.get(callback),
                    () -> viaForm.post("/api/auth/register", body)));
            Resp google = results.get(0);
            Resp register = results.get(1);

            assertThat(count("select count(*) from users where email = ?", email)).isEqualTo(1);
            if (register.status() == 201) {
                // The register won: an unconfirmed password account, never linked automatically.
                assertThat(google.location()).isEqualTo(UNVERIFIED);
                assertThat(count("select count(*) from user_connections c join users u on u.id = c.user_id"
                        + " where u.email = ?", email)).isZero();
            } else {
                assertThat(register.status()).as(register.body()).isEqualTo(409);
                assertThat(register.json().get("code").asText()).isEqualTo("EMAIL_TAKEN");
                assertThat(google.location()).isEqualTo(TASKS);
                assertThat(viaGoogle.me().json().get("hasPassword").asBoolean()).isFalse();
            }
        }
    }
}
