package com.taskapp.it;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import com.taskapp.security.audit.SecurityLogCapture;

/**
 * specs/google-sign-in.md AC19: per-IP limits of the Google start and callback, answered with the flow's failure
 * redirect (never a 429) and logged as RATE_LIMITED. Each test acts as its own client IP (X-Forwarded-For from a
 * trusted loopback peer), so the per-IP counters don't interfere.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.rate-limit.login-per-ip-per-minute=10000",
        "app.rate-limit.register-per-ip-per-hour=10000",
        "app.rate-limit.google-start-per-ip-per-minute=3",
        "app.rate-limit.google-callback-per-ip-per-minute=2"})
class GoogleSignInRateLimitIntegrationTest {

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

    private static final String CALLBACK = "/api/auth/oauth2/callback/google";
    private static final String FAILED = GoogleBrowser.APP_BASE_URL + "/login?oauth=google_failed";
    private static final String CONNECT_FAILED = GoogleBrowser.APP_BASE_URL + "/account?oauth=google_connect_failed";

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    private final FakeGoogle google = new FakeGoogle(oidc);
    private final SecurityLogCapture capture = new SecurityLogCapture();

    @BeforeEach
    void start() {
        capture.start();
    }

    @AfterEach
    void stop() {
        capture.stop();
    }

    private GoogleBrowser from(String ip) throws Exception {
        GoogleBrowser b = new GoogleBrowser(port);
        b.headers.put("X-Forwarded-For", ip);
        return b.start();
    }

    @Test
    void startOverTheLimitRedirectsToTheIntentsFailureRouteWithoutVisitingGoogle() throws Exception {
        GoogleBrowser b = from("203.0.113.11");
        b.register("Limit One", "limit1@example.com");
        // Requests failing the CSRF check are not counted.
        for (int i = 0; i < 5; i++) {
            assertThat(b.startGoogle("login", "wrong").status()).isEqualTo(403);
        }
        for (int i = 0; i < 3; i++) {
            assertThat(b.startGoogle("login").location()).startsWith(google.issuer());
        }

        Resp login = b.startGoogle("login");
        Resp connect = b.startGoogle("connect");

        assertThat(login.status()).isEqualTo(302);
        assertThat(login.location()).isEqualTo(FAILED);
        assertThat(connect.location()).isEqualTo(CONNECT_FAILED);
        assertThat(capture.lines()).filteredOn(l -> l.startsWith("SECURITY event=RATE_LIMITED"))
                .containsExactly(
                        "SECURITY event=RATE_LIMITED limit=google-start-ip email=limit1@example.com"
                                + " ip=203.0.113.11 path=/api/auth/oauth2/authorization/google",
                        "SECURITY event=RATE_LIMITED limit=google-start-ip email=limit1@example.com"
                                + " ip=203.0.113.11 path=/api/auth/oauth2/authorization/google");
        // Other clients are not affected.
        assertThat(from("203.0.113.12").startGoogle("login").location()).startsWith(google.issuer());
    }

    @Test
    void loginCallbackOverTheLimitFailsAndCreatesNothing() throws Exception {
        GoogleBrowser b = from("203.0.113.21");
        String callback = b.approvedCallback(google, "login", "rl-1", FakeGoogle.claims("rl1@gmail.com", true, "R"));
        for (int i = 0; i < 2; i++) {
            assertThat(from("203.0.113.21").get(CALLBACK).location()).isEqualTo(FAILED);
        }

        Resp r = b.get(callback);

        assertThat(r.location()).isEqualTo(FAILED);
        assertThat(r.setCookie("JSESSIONID")).isNull();
        assertThat(b.me().status()).isEqualTo(401);
        assertThat(jdbc.queryForObject("select count(*) from users where email = 'rl1@gmail.com'", Integer.class))
                .isZero();
        assertThat(capture.lines()).filteredOn(l -> l.startsWith("SECURITY event=RATE_LIMITED"))
                .containsExactly("SECURITY event=RATE_LIMITED"
                + " limit=google-callback-ip email=- ip=203.0.113.21 path=" + CALLBACK);
    }

    @Test
    void connectCallbackOverTheLimitGoesBackToAccountAndKeepsTheSession() throws Exception {
        GoogleBrowser b = from("203.0.113.31");
        b.register("Limit Three", "limit3@example.com");
        String session = b.cookies.get("JSESSIONID");
        String callback = b.approvedCallback(google, "connect", "rl-3",
                FakeGoogle.claims("rl3@gmail.com", true, "R"));
        for (int i = 0; i < 2; i++) {
            from("203.0.113.31").get(CALLBACK);
        }

        Resp r = b.get(callback);

        assertThat(r.location()).isEqualTo(CONNECT_FAILED);
        assertThat(b.cookies.get("JSESSIONID")).isEqualTo(session);
        assertThat(b.me().json().get("connections")).isEmpty();
    }
}
