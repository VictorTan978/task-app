package com.taskapp.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.taskapp.auth.EmailJobs;
import com.taskapp.security.audit.SecurityLogCapture;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * specs/email-auth.md AC15: the mail server is down (the test settings point SMTP at a closed port). Register and
 * forgot-password answer exactly as usual, the failure is only logged ({@code EMAIL_SEND_FAILED}), and the user can
 * ask again with resend.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EmailDeliveryFailureIntegrationTest {

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
            .withUrlParam("connectionTimeZone", "UTC")
            .withUrlParam("forceConnectionTimeZoneToSession", "true");

    @LocalServerPort
    int port;

    @org.springframework.beans.factory.annotation.Autowired
    EmailJobs emailJobs;

    private final HttpClient http = HttpClient.newHttpClient();
    private final Map<String, String> cookies = new LinkedHashMap<>();
    private final SecurityLogCapture capture = new SecurityLogCapture();
    private final ListAppender<ILoggingEvent> appLog = new ListAppender<>();
    private final Logger jobsLogger = (Logger) LoggerFactory.getLogger(EmailJobs.class);

    @BeforeEach
    void start() {
        capture.start();
        appLog.start();
        jobsLogger.addAppender(appLog);
    }

    @AfterEach
    void stop() {
        jobsLogger.detachAppender(appLog);
        capture.stop();
    }

    private int send(String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        if (body != null) {
            b.header("Content-Type", "application/json");
        }
        if (!cookies.isEmpty()) {
            b.header("Cookie", cookies.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue())
                    .collect(Collectors.joining("; ")));
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
        return r.statusCode();
    }

    private void awaitAudit(String line) {
        Awaitility.await().atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> assertThat(capture.allLines()).contains(line));
    }

    @Test
    void registerForgotAndResendAnswerNormallyAndTheFailureIsOnlyLogged() throws Exception {
        send("GET", "/api/auth/me", null);
        String tail = " email=down@example.com ip=127.0.0.1 path=";

        assertThat(send("POST", "/api/auth/register",
                "{\"displayName\":\"Down\",\"email\":\"down@example.com\",\"password\":\"correct-horse\"}"))
                .isEqualTo(201);
        awaitAudit("SECURITY event=EMAIL_SEND_FAILED kind=verify_email reason=smtp" + tail + "/api/auth/register");

        assertThat(send("POST", "/api/auth/forgot-password", "{\"email\":\"down@example.com\"}")).isEqualTo(202);
        awaitAudit("SECURITY event=EMAIL_SEND_FAILED kind=reset_password reason=smtp" + tail
                + "/api/auth/forgot-password");
        awaitAudit("SECURITY event=PASSWORD_RESET_REQUESTED outcome=failed" + tail + "/api/auth/forgot-password");

        // The user can try again later.
        assertThat(send("POST", "/api/account/resend-verification", null)).isEqualTo(204);
        Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(capture.allLines())
                .filteredOn(l -> l.startsWith("SECURITY event=EMAIL_SEND_FAILED kind=verify_email")).hasSize(2));
        Awaitility.await().atMost(Duration.ofSeconds(20)).until(emailJobs::isIdle);

        assertThat(capture.allLines()).noneMatch(l -> l.contains("reason=error"))
                .noneMatch(l -> l.startsWith("SECURITY event=EMAIL_VERIFICATION_SENT"));
        List<ILoggingEvent> app = List.copyOf(appLog.list);
        assertThat(app).isNotEmpty().allMatch(e -> e.getLevel() == Level.WARN);
        assertThat(app).allMatch(e -> e.getFormattedMessage().contains("could not be sent")
                && !e.getFormattedMessage().contains("token"));
    }
}
