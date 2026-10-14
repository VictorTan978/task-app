package com.taskapp.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskapp.auth.EmailJobs;
import com.taskapp.security.audit.SecurityLogCapture;
import com.taskapp.security.ratelimit.MutableClock;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;

/**
 * specs/email-auth.md AC1-AC18 end to end: real HTTP, MySQL 8.4 with the Flyway migrations, and a real SMTP server
 * (Mailpit) whose messages are read back through its API. The server clock is a {@link MutableClock} (link
 * expiry). Every browser has its own client IP (X-Forwarded-For from the trusted loopback proxy), so the per-IP
 * limits of one test never affect another. After every test, no log line and no response contains a link token.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.rate-limit.login-per-ip-per-minute=10000",
        "app.rate-limit.register-per-ip-per-hour=10000"})
class EmailAuthIntegrationTest {

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
            .withUrlParam("connectionTimeZone", "UTC")
            .withUrlParam("forceConnectionTimeZoneToSession", "true");

    @Container
    static GenericContainer<?> mailpitContainer = Mailpit.container();

    @DynamicPropertySource
    static void mailServer(DynamicPropertyRegistry registry) {
        Mailpit.register(mailpitContainer, registry);
    }

    @TestConfiguration
    static class ClockConfig {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(Instant.now());
        }
    }

    private static final String PASSWORD = "correct-horse";
    private static final String NEW_PASSWORD = "battery-staple-42";
    private static final String BASE_URL = "https://app.taskapp.test";
    private static final AtomicInteger SEQ = new AtomicInteger();

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MutableClock clock;

    @Autowired
    EmailJobs emailJobs;

    private final HttpClient http = HttpClient.newHttpClient();
    private final SecurityLogCapture capture = new SecurityLogCapture();
    private final ListAppender<ILoggingEvent> allLogs = new ListAppender<>();
    private final Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    private Mailpit mailpit;
    /** Every token read from an email in this test, and every response the server sent. */
    private final List<String> tokens = Collections.synchronizedList(new ArrayList<>());
    private final List<String> responses = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    void start() {
        mailpit = new Mailpit(mailpitContainer);
        capture.start();
        allLogs.start();
        root.addAppender(allLogs);
    }

    /** AC16: no token, link or {@code token=} in any log line (any logger) or any API response. */
    @AfterEach
    void noTokenLeaked() {
        Awaitility.await().atMost(Duration.ofSeconds(20)).until(emailJobs::isIdle);
        root.detachAppender(allLogs);
        allLogs.stop();
        capture.stop();
        List<String> logLines;
        synchronized (allLogs.list) {
            logLines = allLogs.list.stream().map(EmailAuthIntegrationTest::fullText).toList();
        }
        String logs = String.join("\n", logLines);
        String sent = String.join("\n", responses);
        assertThat(logs).doesNotContain("token=", BASE_URL + "/verify-email", BASE_URL + "/reset-password");
        for (String token : tokens) {
            assertThat(logs).doesNotContain(token);
            assertThat(sent).doesNotContain(token);
        }
    }

    private static String fullText(ILoggingEvent event) {
        StringBuilder text = new StringBuilder(event.getFormattedMessage());
        for (IThrowableProxy t = event.getThrowableProxy(); t != null; t = t.getCause()) {
            text.append('\n').append(t.getClassName()).append(": ").append(t.getMessage());
        }
        return text.toString();
    }

    // ---- browser + Angular HttpClient ----

    class Browser {
        final Map<String, String> cookies = new LinkedHashMap<>();
        final String ip = ip(SEQ.incrementAndGet());
        boolean sendCsrfHeader = true;

        Browser start() throws Exception {
            get("/api/auth/me");
            assertThat(cookies).containsKey("XSRF-TOKEN");
            return this;
        }

        Resp get(String path) throws Exception {
            return send("GET", path, null);
        }

        Resp post(String path, String body) throws Exception {
            return send("POST", path, body);
        }

        Resp send(String method, String path, String body) throws Exception {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .header("X-Forwarded-For", ip)
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
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            List<String> setCookies = r.headers().allValues("Set-Cookie");
            for (String sc : setCookies) {
                String[] nameValue = sc.split(";", 2)[0].split("=", 2);
                if (sc.contains("Max-Age=0")) {
                    cookies.remove(nameValue[0]);
                } else {
                    cookies.put(nameValue[0], nameValue[1]);
                }
            }
            responses.add(r.headers().map() + "\n" + r.body());
            assertThat(r.statusCode()).as(method + " " + path + " " + r.body()).isLessThan(500);
            return new Resp(r.statusCode(), r.body(), setCookies);
        }

        long register(String name, String email) throws Exception {
            Resp r = post("/api/auth/register", "{\"displayName\":\"" + name + "\",\"email\":\"" + email
                    + "\",\"password\":\"" + PASSWORD + "\"}");
            assertThat(r.status()).as(r.body()).isEqualTo(201);
            return r.json().get("id").asLong();
        }

        Resp login(String email, String password, boolean rememberMe) throws Exception {
            return post("/api/auth/login", "{\"email\":\"" + email + "\",\"password\":\"" + password
                    + "\",\"rememberMe\":" + rememberMe + "}");
        }

        Resp verify(String token) throws Exception {
            return post("/api/auth/verify-email", "{\"token\":\"" + token + "\"}");
        }

        Resp forgot(String email) throws Exception {
            return post("/api/auth/forgot-password", "{\"email\":\"" + email + "\"}");
        }

        Resp check(String token) throws Exception {
            return post("/api/auth/check-reset-token", "{\"token\":\"" + token + "\"}");
        }

        Resp reset(String token, String newPassword) throws Exception {
            return post("/api/auth/reset-password", "{\"token\":\"" + token + "\",\"newPassword\":\""
                    + newPassword + "\"}");
        }

        Resp resend() throws Exception {
            return post("/api/account/resend-verification", null);
        }

        boolean emailVerified() throws Exception {
            Resp me = get("/api/auth/me");
            assertThat(me.status()).as(me.body()).isEqualTo(200);
            return me.json().get("emailVerified").asBoolean();
        }
    }

    record Resp(int status, String body, List<String> setCookies) {
        JsonNode json() throws IOException {
            return new ObjectMapper().readTree(body);
        }

        String code() throws IOException {
            return json().get("code").asText();
        }

        String setCookie(String name) {
            return setCookies.stream().filter(c -> c.startsWith(name + "=")).findFirst().orElse(null);
        }
    }

    // ---- helpers ----

    /** A distinct client IP for every browser of the run. */
    private static String ip(int n) {
        return "198.18." + (n / 250) + "." + (n % 250 + 1);
    }

    private static String uniqueEmail(String name) {
        return name + SEQ.incrementAndGet() + "@example.com";
    }

    private String verifyToken(String email, int count) {
        String token = mailpit.awaitLatest(email, count).token("/verify-email");
        tokens.add(token);
        return token;
    }

    private String resetToken(String email, int count) {
        String token = mailpit.awaitLatest(email, count).token("/reset-password");
        tokens.add(token);
        return token;
    }

    private void awaitAudit(String line) {
        Awaitility.await().atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> assertThat(capture.allLines()).contains(line));
    }

    private void assertTokenInvalid(Resp r) throws IOException {
        assertThat(r.status()).as(r.body()).isEqualTo(400);
        assertThat(r.code()).isEqualTo("TOKEN_INVALID");
        assertThat(r.json().get("detail").asText()).isEqualTo("This link is invalid or has expired.");
        assertThat(r.json().has("errors")).isFalse();
    }

    // ---- AC1, AC3: confirmation email and link ----

    @Test
    void ac1Ac3RegisterSendsAConfirmationLinkThatConfirmsOnceInAnyBrowser() throws Exception {
        String email = uniqueEmail("ada");
        Browser b = new Browser().start();
        Resp reg = b.post("/api/auth/register", "{\"displayName\":\"Ada <b>\",\"email\":\"" + email
                + "\",\"password\":\"" + PASSWORD + "\"}");
        assertThat(reg.status()).isEqualTo(201);
        assertThat(reg.json().get("emailVerified").asBoolean(true)).isFalse();
        assertThat(b.emailVerified()).isFalse();

        Mailpit.Message mail = mailpit.awaitLatest(email, 1);
        assertThat(mail.subject()).isEqualTo("Confirm your email for Task App");
        assertThat(mail.from()).isEqualTo("Task App <no-reply@taskapp.test>");
        assertThat(mail.to()).containsExactly(email);
        String token = mail.token("/verify-email");
        tokens.add(token);
        assertThat(token).matches("[A-Za-z0-9_-]{43}");
        assertThat(mail.text()).contains(BASE_URL + "/verify-email?token=" + token, "24 hours", "Hi Ada <b>,");
        assertThat(mail.html()).contains("href=\"" + BASE_URL + "/verify-email?token=" + token + "\"",
                ">Confirm email</a>", "Hi Ada &lt;b&gt;,");
        assertThat(jdbc.queryForObject("select count(*) from email_tokens where purpose = 'VERIFY_EMAIL'"
                + " and user_id = (select id from users where email = ?)", Integer.class, email)).isOne();
        awaitAudit("SECURITY event=EMAIL_VERIFICATION_SENT trigger=register email=" + email + " ip=" + b.ip
                + " path=/api/auth/register");

        // Opened in another browser, not signed in.
        Browser other = new Browser().start();
        Resp verified = other.verify(token);
        assertThat(verified.status()).as(verified.body()).isEqualTo(204);
        assertThat(verified.body()).isEmpty();
        assertThat(other.get("/api/auth/me").status()).isEqualTo(401);
        assertThat(b.emailVerified()).isTrue();
        assertThat(capture.allLines()).contains("SECURITY event=EMAIL_VERIFIED email=" + email + " ip=" + other.ip
                + " path=/api/auth/verify-email");

        assertTokenInvalid(other.verify(token));
        assertThat(capture.allLines()).contains("SECURITY event=EMAIL_TOKEN_REJECTED reason=used email=" + email
                + " ip=" + other.ip + " path=/api/auth/verify-email");
        assertThat(b.emailVerified()).isTrue();
    }

    @Test
    void ac3VerifyingWhileSignedInAsAnotherAccountChangesNothingButTheTokensAccount() throws Exception {
        String ada = uniqueEmail("ada");
        String bea = uniqueEmail("bea");
        new Browser().start().register("Ada " + SEQ.get(), ada);
        String token = verifyToken(ada, 1);
        Browser beaBrowser = new Browser().start();
        beaBrowser.register("Bea " + SEQ.get(), bea);
        String session = beaBrowser.cookies.get("JSESSIONID");

        Resp r = beaBrowser.verify(token);

        assertThat(r.status()).isEqualTo(204);
        assertThat(r.setCookie("JSESSIONID")).isNull();
        assertThat(beaBrowser.cookies.get("JSESSIONID")).isEqualTo(session);
        assertThat(beaBrowser.emailVerified()).isFalse();
        Browser adaAgain = new Browser().start();
        assertThat(adaAgain.login(ada, PASSWORD, false).json().get("emailVerified").asBoolean()).isTrue();
    }

    // ---- AC4, AC5: resend ----

    @Test
    void ac4Ac5ResendReplacesTheOldLinkIsLimitedAndAnsweres409OnceConfirmed() throws Exception {
        String email = uniqueEmail("rex");
        Browser b = new Browser().start();
        b.register("Rex " + SEQ.get(), email);
        String first = verifyToken(email, 1);

        Resp resend = b.resend();
        assertThat(resend.status()).as(resend.body()).isEqualTo(204);
        assertThat(resend.setCookies()).isEmpty();
        String second = verifyToken(email, 2);
        assertThat(second).isNotEqualTo(first);
        awaitAudit("SECURITY event=EMAIL_VERIFICATION_SENT trigger=resend email=" + email + " ip=" + b.ip
                + " path=/api/account/resend-verification");

        // AC4: replaced by a newer link.
        assertTokenInvalid(b.verify(first));
        assertThat(b.emailVerified()).isFalse();

        // AC5: 3 per hour per account; the register email does not count.
        assertThat(b.resend().status()).isEqualTo(204);
        assertThat(b.resend().status()).isEqualTo(204);
        Resp limited = b.resend();
        assertThat(limited.status()).isEqualTo(429);
        assertThat(limited.code()).isEqualTo("RATE_LIMITED");
        assertThat(limited.setCookies()).isEmpty();
        String newest = verifyToken(email, 4);
        assertTokenInvalid(b.verify(second));

        assertThat(b.verify(newest).status()).isEqualTo(204);
        // Confirmed: 409 wins over the per-user 429.
        Resp already = b.resend();
        assertThat(already.status()).isEqualTo(409);
        assertThat(already.code()).isEqualTo("ALREADY_VERIFIED");
        assertThat(already.json().get("detail").asText()).isEqualTo("Your email address is already confirmed.");
        mailpit.awaitCount(email, 4);
    }

    @Test
    void ac18ResendNeedsASignedInUser() throws Exception {
        Browser b = new Browser().start();
        Resp r = b.resend();
        assertThat(r.status()).isEqualTo(401);
        assertThat(r.code()).isEqualTo("UNAUTHENTICATED");
    }

    // ---- AC6 ----

    @Test
    void ac6AccountsWithoutAConfirmationTimeAreUnconfirmedAndCanConfirm() throws Exception {
        String email = uniqueEmail("old");
        Browser b = new Browser().start();
        b.register("Old " + SEQ.get(), email);
        verifyToken(email, 1);
        // As migrated by V6: the column is NULL for every existing account.
        jdbc.update("update users set email_verified_at = null where email = ?", email);
        assertThat(b.emailVerified()).isFalse();

        assertThat(b.resend().status()).isEqualTo(204);
        assertThat(b.verify(verifyToken(email, 2)).status()).isEqualTo(204);
        assertThat(b.emailVerified()).isTrue();
    }

    // ---- AC8-AC10: forgot, check, reset ----

    @Test
    void ac8Ac9Ac10ForgotCheckResetEndsEverySessionAndConfirmsTheEmail() throws Exception {
        String email = uniqueEmail("jose");
        Browser owner = new Browser().start();
        owner.register("José " + SEQ.get(), email);
        verifyToken(email, 1);
        // Device A: remember me; device B: normal session.
        Browser deviceA = new Browser().start();
        assertThat(deviceA.login(email, PASSWORD, true).status()).isEqualTo(200);
        String rememberMeA = deviceA.cookies.get("remember-me");
        assertThat(rememberMeA).isNotNull();
        Browser deviceB = new Browser().start();
        assertThat(deviceB.login(email, PASSWORD, false).status()).isEqualTo(200);
        // Locked out by wrong passwords (login per-email limit).
        Browser attacker = new Browser().start();
        for (int i = 0; i < 5; i++) {
            assertThat(attacker.login(email, "wrong-password", false).status()).isEqualTo(401);
        }
        assertThat(attacker.login(email, PASSWORD, false).status()).isEqualTo(429);

        // AC8: matched ignoring case and accents; sent to the stored address.
        String typed = "  " + email.replace("jose", "JOSÉ") + " ";
        Browser forgetful = new Browser().start();
        Resp forgot = forgetful.forgot(typed);
        assertThat(forgot.status()).as(forgot.body()).isEqualTo(202);
        assertThat(forgot.body()).isEmpty();
        assertThat(forgot.setCookies()).isEmpty();
        Mailpit.Message mail = mailpit.awaitLatest(email, 2);
        assertThat(mail.subject()).isEqualTo("Reset your Task App password");
        assertThat(mail.to()).containsExactly(email);
        assertThat(mail.text()).contains(BASE_URL + "/reset-password?token=", "1 hour ");
        assertThat(mail.html()).contains(">Reset password</a>");
        String token = mail.token("/reset-password");
        tokens.add(token);
        String typedNormalized = typed.strip().toLowerCase(java.util.Locale.ROOT);
        awaitAudit("SECURITY event=PASSWORD_RESET_REQUESTED outcome=sent email=" + typedNormalized + " ip="
                + forgetful.ip + " path=/api/auth/forgot-password");

        // Checking does not use the token up.
        Browser linkBrowser = new Browser().start();
        assertThat(linkBrowser.check(token).status()).isEqualTo(204);
        assertThat(linkBrowser.check(token).status()).isEqualTo(204);

        // A 4xx never uses the token up and keeps the session.
        Resp tooShort = linkBrowser.reset(token, "short");
        assertThat(tooShort.status()).isEqualTo(400);
        assertThat(tooShort.code()).isEqualTo("VALIDATION_FAILED");
        assertThat(tooShort.json().get("errors").get(0).get("field").asText()).isEqualTo("newPassword");
        assertThat(tooShort.json().get("errors").get(0).get("code").asText()).isEqualTo("PASSWORD_LENGTH");
        assertThat(linkBrowser.reset(token, "").json().get("errors").get(0).get("code").asText())
                .isEqualTo("REQUIRED");
        assertThat(linkBrowser.check(token).status()).isEqualTo(204);

        // Reset from device B's browser: this browser is signed out too. (The test clock stands still: sessions
        // end when they started BEFORE the reset.)
        clock.advance(Duration.ofSeconds(1));
        String csrfBefore = deviceB.cookies.get("XSRF-TOKEN");
        Resp reset = deviceB.reset(token, NEW_PASSWORD);
        assertThat(reset.status()).as(reset.body()).isEqualTo(204);
        assertThat(reset.setCookie("JSESSIONID")).contains("Max-Age=0");
        assertThat(reset.setCookie("remember-me")).contains("Max-Age=0");
        assertThat(reset.setCookie("XSRF-TOKEN")).isNotNull().doesNotContain("Max-Age=0");
        assertThat(deviceB.cookies.get("XSRF-TOKEN")).isNotEqualTo(csrfBefore);
        assertThat(deviceB.get("/api/auth/me").status()).isEqualTo(401);
        assertThat(capture.allLines()).contains("SECURITY event=PASSWORD_RESET_COMPLETED email=" + email + " ip="
                + deviceB.ip + " path=/api/auth/reset-password");

        // AC10: every other session and remember-me login ended.
        assertThat(owner.get("/api/auth/me").status()).isEqualTo(401);
        Resp a = deviceA.get("/api/tasks");
        assertThat(a.status()).isEqualTo(401);
        assertThat(deviceA.cookies).doesNotContainKey("remember-me");
        assertThat(jdbc.queryForObject("select count(*) from remember_me_tokens where user_id ="
                + " (select id from users where email = ?)", Integer.class, email)).isZero();

        // AC9: only the new password works; the lock-out was lifted; the email counts as confirmed.
        Browser fresh = new Browser().start();
        assertThat(fresh.login(email, PASSWORD, false).status()).isEqualTo(401);
        Resp login = attacker.login(email, NEW_PASSWORD, false);
        assertThat(login.status()).as(login.body()).isEqualTo(200);
        assertThat(login.json().get("emailVerified").asBoolean()).isTrue();

        // AC11: used.
        assertTokenInvalid(linkBrowser.check(token));
        assertTokenInvalid(linkBrowser.reset(token, "another-password-1"));
    }

    @Test
    void ac10ResetAlsoConfirmsAnUnconfirmedEmail() throws Exception {
        String email = uniqueEmail("una");
        new Browser().start().register("Una " + SEQ.get(), email);
        mailpit.awaitCount(email, 1);
        Browser b = new Browser().start();
        assertThat(b.forgot(email).status()).isEqualTo(202);

        assertThat(b.reset(resetToken(email, 2), NEW_PASSWORD).status()).isEqualTo(204);

        assertThat(b.login(email, NEW_PASSWORD, false).json().get("emailVerified").asBoolean()).isTrue();
    }

    @Test
    void ac8UnknownEmailGetsTheSame202AndNothingIsSent() throws Exception {
        String email = uniqueEmail("nobody");
        Browser b = new Browser().start();

        Resp r = b.forgot(email);

        assertThat(r.status()).isEqualTo(202);
        assertThat(r.body()).isEmpty();
        assertThat(r.setCookies()).isEmpty();
        awaitAudit("SECURITY event=PASSWORD_RESET_REQUESTED outcome=unknown_email email=" + email + " ip=" + b.ip
                + " path=/api/auth/forgot-password");
        assertThat(mailpit.to(email)).isEmpty();
    }

    @Test
    void ac12SilentPerAccountLimitSharedByCaseAndAccentVariants() throws Exception {
        String email = uniqueEmail("lea");
        new Browser().start().register("Lea " + SEQ.get(), email);
        mailpit.awaitCount(email, 1);
        List<String> variants = List.of(email, email.toUpperCase(java.util.Locale.ROOT),
                email.replace("lea", "léa"), " " + email);

        for (String variant : variants) {
            // A new IP each time: only the per-account limit applies.
            Resp r = new Browser().start().forgot(variant);
            assertThat(r.status()).isEqualTo(202);
            assertThat(r.body()).isEmpty();
        }

        Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(capture.allLines())
                .filteredOn(l -> l.startsWith("SECURITY event=PASSWORD_RESET_REQUESTED")).hasSize(4));
        List<Mailpit.Message> mails = mailpit.awaitCount(email, 4);
        Awaitility.await().pollDelay(Duration.ofMillis(300)).until(() -> true);
        assertThat(mailpit.to(email)).hasSize(4);
        assertThat(capture.allLines()).filteredOn(l -> l.contains("outcome=account_limit")).hasSize(1);
        assertThat(capture.allLines()).filteredOn(l -> l.startsWith(
                "SECURITY event=RATE_LIMITED limit=reset-email-account email=" + email)).hasSize(1);

        // AC11: only the newest reset link works.
        List<String> resetTokens = mails.subList(1, 4).stream().map(m -> m.token("/reset-password")).toList();
        tokens.addAll(resetTokens);
        Browser b = new Browser().start();
        assertTokenInvalid(b.check(resetTokens.get(0)));
        assertTokenInvalid(b.check(resetTokens.get(1)));
        assertThat(b.check(resetTokens.get(2)).status()).isEqualTo(204);
    }

    @Test
    void ac12PerIpLimitIs429WithRetryAfterAndCountsInvalidRequests() throws Exception {
        Browser b = new Browser().start();
        assertThat(b.forgot("not-an-email").status()).isEqualTo(400);
        for (int i = 0; i < 9; i++) {
            assertThat(b.forgot(uniqueEmail("ip")).status()).isEqualTo(202);
        }

        Resp limited = b.forgot(uniqueEmail("ip"));

        assertThat(limited.status()).isEqualTo(429);
        assertThat(limited.code()).isEqualTo("RATE_LIMITED");
        assertThat(Long.parseLong(limited.json().get("status").asText())).isEqualTo(429);
        assertThat(new Browser().start().forgot(uniqueEmail("ip")).status()).isEqualTo(202);
    }

    // ---- AC11: wrong purpose, expiry ----

    @Test
    void ac11ATokenOnlyWorksForItsOwnPurpose() throws Exception {
        String email = uniqueEmail("pia");
        Browser b = new Browser().start();
        b.register("Pia " + SEQ.get(), email);
        String verify = verifyToken(email, 1);
        assertThat(b.forgot(email).status()).isEqualTo(202);
        String reset = resetToken(email, 2);

        assertTokenInvalid(b.check(verify));
        assertTokenInvalid(b.reset(verify, NEW_PASSWORD));
        assertTokenInvalid(b.verify(reset));
        awaitAudit("SECURITY event=EMAIL_TOKEN_REJECTED reason=wrong_purpose email=" + email + " ip=" + b.ip
                + " path=/api/auth/reset-password");

        // Nothing was used up by the wrong calls.
        assertThat(b.check(reset).status()).isEqualTo(204);
        assertThat(b.verify(verify).status()).isEqualTo(204);
        Browser fresh = new Browser().start();
        assertThat(fresh.login(email, PASSWORD, false).status()).isEqualTo(200);
    }

    @Test
    void ac11MalformedAndMissingTokensGiveTheSameResponse() throws Exception {
        Browser b = new Browser().start();
        assertTokenInvalid(b.verify("short"));
        assertTokenInvalid(b.post("/api/auth/verify-email", "{}"));
        assertTokenInvalid(b.post("/api/auth/check-reset-token", "{\"token\":null}"));
        assertTokenInvalid(b.reset("A".repeat(43), NEW_PASSWORD));
        assertTokenInvalid(b.post("/api/auth/reset-password", "{\"newPassword\":\"x\"}"));
        Resp malformed = b.post("/api/auth/verify-email", "{nope");
        assertThat(malformed.status()).isEqualTo(400);
        assertThat(malformed.code()).isEqualTo("MALFORMED_REQUEST");
    }

    @Test
    void ac4Ac11ExpiredLinksAreInvalid() throws Exception {
        String email = uniqueEmail("exp");
        Browser b = new Browser().start();
        b.register("Exp " + SEQ.get(), email);
        String verify = verifyToken(email, 1);
        assertThat(b.forgot(email).status()).isEqualTo(202);
        String reset = resetToken(email, 2);

        clock.advance(Duration.ofHours(1));
        assertTokenInvalid(b.check(reset));
        assertTokenInvalid(b.reset(reset, NEW_PASSWORD));
        assertThat(capture.allLines()).anyMatch(l -> l.startsWith(
                "SECURITY event=EMAIL_TOKEN_REJECTED reason=expired email=" + email));

        clock.advance(Duration.ofHours(23).minusSeconds(1));
        assertThat(b.verify(verify).status()).isEqualTo(204);

        // A second account's confirmation link, 24 hours later.
        String other = uniqueEmail("exp");
        Browser c = new Browser().start();
        c.register("Exp " + SEQ.get(), other);
        String otherVerify = verifyToken(other, 1);
        clock.advance(Duration.ofHours(24));
        assertTokenInvalid(c.verify(otherVerify));
    }

    // ---- AC18: public endpoints never 401, CSRF required ----

    @Test
    void ac18PublicEndpointsIgnoreAStaleSessionAndABadRememberMeCookie() throws Exception {
        String email = uniqueEmail("sid");
        new Browser().start().register("Sid " + SEQ.get(), email);
        String verify = verifyToken(email, 1);
        Browser b = new Browser().start();
        assertThat(b.forgot(email).status()).isEqualTo(202);
        String reset = resetToken(email, 2);

        // An idle-expired session and a rejected remember-me cookie.
        b.cookies.put("JSESSIONID", "STALE-SESSION-ID");
        b.cookies.put("remember-me", "not-a-valid-cookie");
        Resp check = b.check(reset);
        assertThat(check.status()).as(check.body()).isEqualTo(204);
        assertThat(check.setCookie("remember-me")).contains("Max-Age=0");

        b.cookies.put("remember-me", "A".repeat(43) + "." + "B".repeat(43));
        assertThat(b.verify(verify).status()).isEqualTo(204);
        b.cookies.put("remember-me", "A".repeat(43) + "." + "B".repeat(43));
        assertThat(b.forgot(uniqueEmail("x")).status()).isEqualTo(202);
        b.cookies.put("remember-me", "A".repeat(43) + "." + "B".repeat(43));
        assertTokenInvalid(b.verify(verify));
        b.cookies.put("remember-me", "A".repeat(43) + "." + "B".repeat(43));
        assertThat(b.reset(reset, NEW_PASSWORD).status()).isEqualTo(204);
        // Only the startup GET /api/auth/me was a 401.
        assertThat(capture.allLines()).noneMatch(l -> l.startsWith("SECURITY event=UNAUTHENTICATED")
                && l.contains("ip=" + b.ip + " ") && !l.endsWith("path=/api/auth/me"));
    }

    @Test
    void ac18PublicEndpointsWorkForASessionEndedByAResetAndARememberMeCookieOfIt() throws Exception {
        String email = uniqueEmail("end");
        new Browser().start().register("End " + SEQ.get(), email);
        mailpit.awaitCount(email, 1);
        Browser device = new Browser().start();
        assertThat(device.login(email, PASSWORD, true).status()).isEqualTo(200);
        Browser b = new Browser().start();
        assertThat(b.forgot(email).status()).isEqualTo(202);
        clock.advance(Duration.ofSeconds(1));
        assertThat(b.reset(resetToken(email, 2), NEW_PASSWORD).status()).isEqualTo(204);

        // The device's session and remember-me login were ended by the reset.
        assertThat(device.forgot(email).status()).isEqualTo(202);
        assertThat(device.cookies).doesNotContainKey("remember-me");
        assertTokenInvalid(device.check("A".repeat(43)));
    }

    @Test
    void ac18PublicEndpointsNeedTheCsrfHeader() throws Exception {
        Browser b = new Browser().start();
        b.sendCsrfHeader = false;
        for (String path : List.of("/api/auth/verify-email", "/api/auth/forgot-password",
                "/api/auth/check-reset-token", "/api/auth/reset-password")) {
            Resp r = b.post(path, "{\"email\":\"a@example.com\",\"token\":\"x\"}");
            assertThat(r.status()).as(path).isEqualTo(403);
            assertThat(r.code()).isEqualTo("CSRF_INVALID");
        }
    }
}
