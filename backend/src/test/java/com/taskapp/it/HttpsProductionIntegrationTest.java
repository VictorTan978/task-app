package com.taskapp.it;

import static com.taskapp.it.ProxyHttpClient.isSecure;
import static com.taskapp.it.ProxyHttpClient.loginBody;
import static com.taskapp.it.ProxyHttpClient.registerBody;
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
 * AC7–AC9 with production settings (REQUIRE_HTTPS=true, the application.yml default) behind a trusted
 * TLS-terminating proxy. The test client connects from loopback, which the default
 * server.tomcat.remoteip.internal-proxies trusts, so it plays the proxy by sending X-Forwarded-* headers.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "REQUIRE_HTTPS=true",
        "app.rate-limit.login-per-ip-per-minute=3",
        "app.rate-limit.register-per-ip-per-hour=10000"})
class HttpsProductionIntegrationTest {

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
            .withUrlParam("connectionTimeZone", "UTC")
            .withUrlParam("forceConnectionTimeZoneToSession", "true");

    @LocalServerPort
    int port;

    private ProxyHttpClient viaHttpsProxy(String clientIp) {
        return new ProxyHttpClient(port).header("X-Forwarded-Proto", "https").header("X-Forwarded-For", clientIp);
    }

    @Test
    void sessionCsrfAndLogoutCookiesAreSecureAndHttpsResponsesCarryHsts() throws Exception {
        ProxyHttpClient b = viaHttpsProxy("198.51.100.1");

        Resp me = b.get("/api/auth/me");
        assertThat(me.status()).isEqualTo(401);
        assertThat(me.hsts()).contains("max-age=");
        assertThat(isSecure(me.setCookie("XSRF-TOKEN"))).as(me.setCookie("XSRF-TOKEN")).isTrue();

        Resp reg = b.post("/api/auth/register", registerBody("Secure Sam", "secure-sam@example.com"));
        assertThat(reg.status()).isEqualTo(201);
        assertThat(reg.hsts()).contains("max-age=");
        assertThat(isSecure(reg.setCookie("JSESSIONID"))).as(reg.setCookie("JSESSIONID")).isTrue();
        assertThat(isSecure(reg.setCookie("XSRF-TOKEN"))).as(reg.setCookie("XSRF-TOKEN")).isTrue();
        assertThat(b.get("/api/auth/me").status()).isEqualTo(200);

        Resp logout = b.post("/api/auth/logout", null);
        assertThat(logout.status()).isEqualTo(204);
        String clearedSession = logout.setCookie("JSESSIONID");
        assertThat(clearedSession).contains("Max-Age=0");
        assertThat(isSecure(clearedSession)).as(clearedSession).isTrue();
        assertThat(isSecure(logout.setCookie("XSRF-TOKEN"))).as(logout.setCookie("XSRF-TOKEN")).isTrue();
        assertThat(b.get("/api/auth/me").status()).isEqualTo(401);
    }

    @Test
    void rateLimiterCountsTheForwardedClientIp() throws Exception {
        for (int i = 0; i < 3; i++) {
            ProxyHttpClient client = viaHttpsProxy("203.0.113.7");
            client.get("/api/auth/me");
            assertThat(client.post("/api/auth/login", loginBody("nobody" + i + "@example.com")).status())
                    .isEqualTo(401);
        }
        ProxyHttpClient sameClient = viaHttpsProxy("203.0.113.7");
        sameClient.get("/api/auth/me");
        assertThat(sameClient.post("/api/auth/login", loginBody("nobody3@example.com")).status()).isEqualTo(429);

        // Same proxy (same TCP peer), different real client: not limited.
        ProxyHttpClient otherClient = viaHttpsProxy("203.0.113.8");
        otherClient.get("/api/auth/me");
        assertThat(otherClient.post("/api/auth/login", loginBody("nobody4@example.com")).status()).isEqualTo(401);
    }

    @Test
    void plainHttpFromTheProxyIsRedirectedToHttps() throws Exception {
        ProxyHttpClient b = new ProxyHttpClient(port)
                .header("X-Forwarded-Proto", "http").header("X-Forwarded-For", "198.51.100.2");

        Resp get = b.get("/api/auth/me?x=1");
        assertThat(get.status()).isEqualTo(302);
        assertThat(get.location()).isEqualTo("https://localhost/api/auth/me?x=1");
        assertThat(get.hsts()).isNull();

        // Redirected before CSRF is checked, so an HTTP form post never reaches the app.
        Resp post = b.post("/api/auth/login", loginBody("nobody@example.com"));
        assertThat(post.status()).isEqualTo(302);
        assertThat(post.location()).isEqualTo("https://localhost/api/auth/login");
    }

    @Test
    void directPlainHttpWithoutProxyHeadersIsRedirectedToTheDefaultHttpsPort() throws Exception {
        Resp r = new ProxyHttpClient(port).get("/api/tasks");

        assertThat(r.status()).isEqualTo(302);
        assertThat(r.location()).isEqualTo("https://localhost/api/tasks");
    }

    @Test
    void healthIsNotRedirectedSoPlainHttpLoadBalancerChecksWork() throws Exception {
        Resp plain = new ProxyHttpClient(port).get("/api/health");
        assertThat(plain.status()).isEqualTo(200);
        assertThat(plain.body()).contains("UP");
        assertThat(plain.hsts()).isNull();

        Resp https = viaHttpsProxy("198.51.100.3").get("/api/health");
        assertThat(https.status()).isEqualTo(200);
        assertThat(https.hsts()).contains("max-age=");
    }
}
