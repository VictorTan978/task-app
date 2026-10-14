package com.taskapp.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * AC11/AC12 over real HTTP: the SECURITY lines of a register -> failed logins -> login -> logout flow, and no
 * password, password hash, session ID or CSRF token value anywhere in the application's console output.
 */
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.rate-limit.login-per-ip-per-minute=10000",
        "app.rate-limit.register-per-ip-per-hour=10000"})
class SecurityEventLoggingIntegrationTest {

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
            .withUrlParam("connectionTimeZone", "UTC")
            .withUrlParam("forceConnectionTimeZoneToSession", "true");

    private static final String PASSWORD = "Sup3r-Secret-Passw0rd";
    private static final String WRONG_PASSWORD = "Wr0ng-Guess-Passw0rd";

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    private final HttpClient http = HttpClient.newHttpClient();
    private final Map<String, String> cookies = new LinkedHashMap<>();
    /** Every cookie value the server ever issued (session IDs and CSRF tokens). */
    private final List<String> issuedSecrets = new ArrayList<>();

    private int send(String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        if (!cookies.isEmpty()) {
            b.header("Cookie", cookies.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue())
                    .collect(Collectors.joining("; ")));
        }
        if (cookies.containsKey("XSRF-TOKEN")) {
            b.header("X-XSRF-TOKEN", cookies.get("XSRF-TOKEN"));
        }
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        for (String sc : r.headers().allValues("Set-Cookie")) {
            String[] nameValue = sc.split(";", 2)[0].split("=", 2);
            if (sc.contains("Max-Age=0")) {
                cookies.remove(nameValue[0]);
            } else {
                cookies.put(nameValue[0], nameValue[1]);
                issuedSecrets.add(nameValue[1]);
            }
        }
        return r.statusCode();
    }

    private static String login(String email, String password) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
    }

    /** Lines of request threads only: the background email jobs (threads "email-N") log at unpredictable times. */
    private static List<String> securityLines(CapturedOutput output) {
        return output.getOut().lines().filter(l -> l.contains("SECURITY event="))
                .filter(l -> !EMAIL_THREAD.matcher(l).find())
                .map(l -> l.substring(l.indexOf("SECURITY event="))).toList();
    }

    private static final java.util.regex.Pattern EMAIL_THREAD = java.util.regex.Pattern.compile("\\[\\s*email-\\d+\\]");

    @Test
    void fullFlowLogsOneLinePerEventAndNoSecrets(CapturedOutput output) throws Exception {
        assertThat(send("GET", "/api/auth/me", null)).isEqualTo(401);
        assertThat(send("POST", "/api/auth/register",
                "{\"displayName\":\"Audit\",\"email\":\"Audit@Example.com\",\"password\":\"" + PASSWORD + "\"}"))
                .isEqualTo(201);
        assertThat(send("POST", "/api/auth/logout", null)).isEqualTo(204);
        assertThat(send("POST", "/api/auth/login", login("audit@example.com", WRONG_PASSWORD))).isEqualTo(401);
        assertThat(send("POST", "/api/auth/login", login("ghost@example.com", WRONG_PASSWORD))).isEqualTo(401);
        assertThat(send("POST", "/api/auth/login", login("audit@example.com", PASSWORD))).isEqualTo(200);
        assertThat(send("GET", "/api/auth/me", null)).isEqualTo(200);
        String csrf = cookies.remove("XSRF-TOKEN");
        assertThat(send("POST", "/api/auth/logout", null)).isEqualTo(403);
        cookies.put("XSRF-TOKEN", csrf);
        assertThat(send("POST", "/api/auth/logout", null)).isEqualTo(204);

        String ip = "127.0.0.1";
        assertThat(securityLines(output)).containsExactly(
                "SECURITY event=UNAUTHENTICATED email=- ip=" + ip + " path=/api/auth/me",
                "SECURITY event=REGISTER_SUCCESS email=audit@example.com ip=" + ip + " path=/api/auth/register",
                "SECURITY event=LOGOUT email=audit@example.com ip=" + ip + " path=/api/auth/logout",
                "SECURITY event=LOGIN_FAILURE reason=bad_password email=audit@example.com ip=" + ip
                        + " path=/api/auth/login",
                "SECURITY event=LOGIN_FAILURE reason=unknown_email email=ghost@example.com ip=" + ip
                        + " path=/api/auth/login",
                "SECURITY event=LOGIN_SUCCESS email=audit@example.com ip=" + ip + " path=/api/auth/login",
                "SECURITY event=CSRF_REJECTED email=audit@example.com ip=" + ip + " path=/api/auth/logout",
                "SECURITY event=LOGOUT email=audit@example.com ip=" + ip + " path=/api/auth/logout");

        // specs/email-auth.md AC15/AC17: the confirmation email queued by register fails (no mail server in this
        // test) and is logged by the background job, without failing the register.
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(output.getOut()).contains("SECURITY event=EMAIL_SEND_FAILED kind=verify_email reason=smtp"
                        + " email=audit@example.com ip=" + ip + " path=/api/auth/register"));

        String hash = jdbc.queryForObject("select password_hash from users where email = 'audit@example.com'",
                String.class);
        String all = output.getAll();
        assertThat(issuedSecrets).isNotEmpty();
        assertThat(all).doesNotContain(PASSWORD, WRONG_PASSWORD, hash, "JSESSIONID", "XSRF");
        for (String secret : issuedSecrets) {
            assertThat(all).doesNotContain(secret);
        }
    }
}
