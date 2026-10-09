package com.taskapp.it;

import static com.taskapp.it.ProxyHttpClient.isSecure;
import static com.taskapp.it.ProxyHttpClient.loginBody;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.taskapp.it.ProxyHttpClient.Resp;

/**
 * X-Forwarded-* headers from a peer that is NOT a trusted proxy are ignored. TRUSTED_PROXIES is set to an
 * address the test client never uses, so the client (loopback) is an untrusted, direct caller.
 * REQUIRE_HTTPS is off here so plain-HTTP requests reach the rate limiter instead of being redirected.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "TRUSTED_PROXIES=10\\\\.99\\\\.99\\\\.99",
        "REQUIRE_HTTPS=false",
        "app.rate-limit.login-per-ip-per-minute=3"})
class UntrustedProxyHeadersIntegrationTest {

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
            .withUrlParam("connectionTimeZone", "UTC")
            .withUrlParam("forceConnectionTimeZoneToSession", "true");

    @LocalServerPort
    int port;

    @Test
    void spoofedForwardedForDoesNotEscapeTheRateLimit() throws Exception {
        for (int i = 0; i < 3; i++) {
            ProxyHttpClient client = new ProxyHttpClient(port).header("X-Forwarded-For", "203.0.113." + i);
            client.get("/api/auth/me");
            assertThat(client.post("/api/auth/login", loginBody("nobody" + i + "@example.com")).status())
                    .isEqualTo(401);
        }
        ProxyHttpClient freshSpoof = new ProxyHttpClient(port).header("X-Forwarded-For", "203.0.113.99");
        freshSpoof.get("/api/auth/me");

        assertThat(freshSpoof.post("/api/auth/login", loginBody("nobody9@example.com")).status()).isEqualTo(429);
    }

    @Test
    void spoofedForwardedProtoDoesNotMakeTheRequestSecure() throws Exception {
        Resp r = new ProxyHttpClient(port).header("X-Forwarded-Proto", "https").get("/api/auth/me");

        assertThat(r.status()).isEqualTo(401);
        assertThat(r.hsts()).isNull();
        assertThat(isSecure(r.setCookie("XSRF-TOKEN"))).isFalse();
    }
}
