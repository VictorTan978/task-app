package com.taskapp.it;

import static com.taskapp.it.ProxyHttpClient.isSecure;
import static com.taskapp.it.ProxyHttpClient.registerBody;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.taskapp.it.ProxyHttpClient.Resp;

/**
 * AC10: with the dev profile, login, logout and CSRF work over plain http://localhost as before: no
 * redirect, no Secure cookies, no HSTS. REQUIRE_HTTPS=true (the production value) proves that
 * application-dev.yml switches HTTPS-only behaviour off by itself.
 */
@Testcontainers
@ActiveProfiles("dev")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "REQUIRE_HTTPS=true",
        "app.rate-limit.login-per-ip-per-minute=10000",
        "app.rate-limit.register-per-ip-per-hour=10000"})
class DevProfileHttpIntegrationTest {

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
            .withUrlParam("connectionTimeZone", "UTC")
            .withUrlParam("forceConnectionTimeZoneToSession", "true");

    @LocalServerPort
    int port;

    @Test
    void registerLoginLogoutAndCsrfWorkOverPlainHttp() throws Exception {
        ProxyHttpClient b = new ProxyHttpClient(port);

        Resp me = b.get("/api/auth/me");
        assertThat(me.status()).isEqualTo(401);
        assertThat(me.hsts()).isNull();
        assertThat(isSecure(me.setCookie("XSRF-TOKEN"))).isFalse();

        Resp reg = b.post("/api/auth/register", registerBody("Dev Dora", "dev-dora@example.com"));
        assertThat(reg.status()).isEqualTo(201);
        assertThat(isSecure(reg.setCookie("JSESSIONID"))).isFalse();
        assertThat(b.get("/api/auth/me").status()).isEqualTo(200);

        Resp logout = b.post("/api/auth/logout", null);
        assertThat(logout.status()).isEqualTo(204);
        assertThat(logout.setCookie("JSESSIONID")).contains("Max-Age=0");
        assertThat(isSecure(logout.setCookie("JSESSIONID"))).isFalse();
        assertThat(isSecure(logout.setCookie("XSRF-TOKEN"))).isFalse();
        assertThat(b.get("/api/auth/me").status()).isEqualTo(401);

        Resp login = b.post("/api/auth/login",
                "{\"email\":\"dev-dora@example.com\",\"password\":\"correct-horse\"}");
        assertThat(login.status()).isEqualTo(200);
        assertThat(isSecure(login.setCookie("JSESSIONID"))).isFalse();
        assertThat(b.get("/api/auth/me").status()).isEqualTo(200);
    }

    @Test
    void postWithoutCsrfHeaderIsStillRejected() throws Exception {
        ProxyHttpClient b = new ProxyHttpClient(port);
        b.get("/api/auth/me");
        b.cookies.remove("XSRF-TOKEN");

        assertThat(b.post("/api/auth/login", "{\"email\":\"a@example.com\",\"password\":\"x\"}").status())
                .isEqualTo(403);
    }
}
