package com.taskapp.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

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

import tools.jackson.databind.JsonNode;
import com.taskapp.auth.EmailTokenPurpose;
import com.taskapp.auth.EmailTokenService;
import com.taskapp.it.GoogleBrowser.Resp;
import com.taskapp.security.audit.SecurityLogCapture;
import com.taskapp.security.ratelimit.MutableClock;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;

/**
 * Sign in with Google (specs/google-sign-in.md AC2-AC10, AC12-AC18) end-to-end over real HTTP: MySQL 8.4 with the
 * Flyway migrations, and the fake OpenID Connect provider (mock-oauth2-server) as Google. The browser submits the
 * start form, "approves" on the provider's login page, and follows the redirect to the callback.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.rate-limit.login-per-ip-per-minute=10000",
        "app.rate-limit.register-per-ip-per-hour=10000",
        "app.rate-limit.reset-password-per-ip-per-hour=10000",
        "app.rate-limit.google-start-per-ip-per-minute=10000",
        "app.rate-limit.google-callback-per-ip-per-minute=10000"})
class GoogleSignInIntegrationTest {

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

    @TestConfiguration
    static class ClockConfig {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(Instant.now());
        }
    }

    private static final String TASKS = GoogleBrowser.APP_BASE_URL + "/tasks";
    private static final String FAILED = GoogleBrowser.APP_BASE_URL + "/login?oauth=google_failed";
    private static final String UNVERIFIED = GoogleBrowser.APP_BASE_URL + "/login?oauth=google_unverified";
    private static final String CONNECTED = GoogleBrowser.APP_BASE_URL + "/account?oauth=google_connected";
    private static final String IN_USE = GoogleBrowser.APP_BASE_URL + "/account?oauth=google_in_use";
    private static final String CONNECT_FAILED = GoogleBrowser.APP_BASE_URL + "/account?oauth=google_connect_failed";

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MutableClock clock;

    @Autowired
    EmailTokenService emailTokens;

    private final FakeGoogle google = new FakeGoogle(oidc);
    private final SecurityLogCapture capture = new SecurityLogCapture();
    private final ListAppender<ILoggingEvent> allLogs = new ListAppender<>();
    private final Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);

    @BeforeEach
    void start() {
        capture.start();
        allLogs.start();
        root.addAppender(allLogs);
    }

    @AfterEach
    void stop() {
        capture.stop();
        root.detachAppender(allLogs);
        allLogs.stop();
    }

    private GoogleBrowser browser() throws Exception {
        return new GoogleBrowser(port).start();
    }

    private static String claims(String email, boolean verified, String name) {
        return FakeGoogle.claims(email, verified, name);
    }

    private int users(String email) {
        return jdbc.queryForObject("select count(*) from users where email = ?", Integer.class, email);
    }

    private long userId(String email) {
        return jdbc.queryForObject("select id from users where email = ?", Long.class, email);
    }

    private void confirm(String email) {
        jdbc.update("update users set email_verified_at = now(6) where email = ?", email);
    }

    private List<String> auditLines(String event) {
        return capture.lines().stream().filter(l -> l.startsWith("SECURITY event=" + event + " ")).toList();
    }

    // --- AC2: new account

    @Test
    void ac2FirstGoogleSignInCreatesAConfirmedAccountWithoutPasswordAndSignsIn() throws Exception {
        GoogleBrowser b = browser();
        String csrfBefore = b.cookies.get("XSRF-TOKEN");

        Resp start = b.startGoogle("login");

        assertThat(start.status()).isEqualTo(302);
        String authorize = start.location();
        assertThat(authorize).startsWith(google.issuer() + "/authorize?");
        assertThat(FakeGoogle.param(authorize, "response_type")).isEqualTo("code");
        assertThat(FakeGoogle.param(authorize, "client_id")).isEqualTo(FakeGoogle.CLIENT_ID);
        assertThat(FakeGoogle.param(authorize, "scope")).isEqualTo("openid email profile");
        assertThat(FakeGoogle.param(authorize, "redirect_uri"))
                .isEqualTo(GoogleBrowser.APP_BASE_URL + "/api/auth/oauth2/callback/google");
        assertThat(FakeGoogle.param(authorize, "state")).isNotBlank();
        assertThat(FakeGoogle.param(authorize, "nonce")).isNotBlank();
        assertThat(FakeGoogle.param(authorize, "code_challenge")).isNotBlank();
        assertThat(FakeGoogle.param(authorize, "code_challenge_method")).isEqualTo("S256");
        assertThat(authorize).doesNotContain(FakeGoogle.CLIENT_SECRET);
        // A short-lived anonymous session holds the pending request.
        String flowSession = b.cookies.get("JSESSIONID");
        assertThat(flowSession).isNotNull();
        assertThat(b.me().status()).isEqualTo(401);

        Resp callback = b.get(google.approve(authorize, "g-ana", claims("Ana.Lopez@Gmail.com", true, "Ana López")));

        assertThat(callback.status()).isEqualTo(302);
        assertThat(callback.location()).isEqualTo(TASKS);
        assertThat(callback.setCookie("JSESSIONID")).contains("HttpOnly").contains("SameSite=Lax");
        assertThat(b.cookies.get("JSESSIONID")).isNotEqualTo(flowSession);
        assertThat(b.cookies.get("XSRF-TOKEN")).isNotEqualTo(csrfBefore);
        assertThat(callback.setCookie("remember-me")).isNull();

        JsonNode me = b.me().json();
        assertThat(me.get("email").asText()).isEqualTo("ana.lopez@gmail.com");
        assertThat(me.get("displayName").asText()).isEqualTo("Ana López");
        assertThat(me.get("emailVerified").asBoolean()).isTrue();
        assertThat(me.get("hasPassword").asBoolean()).isFalse();
        assertThat(me.get("connections")).hasSize(1);
        assertThat(me.get("connections").get(0).get("provider").asText()).isEqualTo("google");
        assertThat(me.get("connections").get(0).get("email").asText()).isEqualTo("ana.lopez@gmail.com");
        assertThat(me.size()).isEqualTo(7);
        assertThat(me.get("role").asText()).isEqualTo("USER");
        assertThat(me.toString()).doesNotContain("g-ana");
        assertThat(jdbc.queryForObject("select password_hash from users where email = 'ana.lopez@gmail.com'",
                String.class)).isNull();
        // specs/sharing-team-lists.md AC1: exactly one list, "My tasks".
        JsonNode lists = b.get("/api/lists").json();
        assertThat(lists).hasSize(1);
        assertThat(lists.get(0).get("name").asText()).isEqualTo("My tasks");
        assertThat(lists.get(0).get("personal").asBoolean()).isTrue();

        assertThat(auditLines("GOOGLE_ACCOUNT_CREATED")).containsExactly(
                "SECURITY event=GOOGLE_ACCOUNT_CREATED email=ana.lopez@gmail.com ip=127.0.0.1"
                        + " path=/api/auth/oauth2/callback/google");
        assertThat(auditLines("GOOGLE_LOGIN_SUCCESS")).hasSize(1);
    }

    // --- specs/roles-admin.md AC18: a disabled account can't sign in with Google

    private static final String ACCOUNT_DISABLED = GoogleBrowser.APP_BASE_URL + "/login?oauth=account_disabled";

    @Test
    void disabledAccountGoesToTheDisabledNoticeAndNothingIsSignedInLinkedOrCreated() throws Exception {
        assertThat(browser().google(google, "login", "g-dis", claims("dis@gmail.com", true, "Dis")).location())
                .isEqualTo(TASKS);
        long id = userId("dis@gmail.com");
        GoogleBrowser owner = browser();
        long linkable = owner.register("Linda", "linda@example.com").json().get("id").asLong();
        confirm("linda@example.com");
        jdbc.update("update users set disabled_at = now(6) where id in (?, ?)", id, linkable);
        int usersBefore = jdbc.queryForObject("select count(*) from users", Integer.class);

        GoogleBrowser b = browser();
        Resp byId = b.google(google, "login", "g-dis", claims("dis@gmail.com", true, "Dis"));
        Resp byEmail = b.google(google, "login", "g-linda", claims("linda@example.com", true, "Linda"));

        assertThat(byId.location()).isEqualTo(ACCOUNT_DISABLED);
        assertThat(byEmail.location()).isEqualTo(ACCOUNT_DISABLED);
        assertThat(b.me().status()).isEqualTo(401);
        assertThat(jdbc.queryForObject("select count(*) from users", Integer.class)).isEqualTo(usersBefore);
        assertThat(jdbc.queryForObject("select count(*) from user_connections where user_id = ?", Integer.class,
                linkable)).isZero();
        assertThat(auditLines("GOOGLE_LOGIN_FAILURE")).containsExactly(
                "SECURITY event=GOOGLE_LOGIN_FAILURE intent=login reason=account_disabled email=dis@gmail.com"
                        + " ip=127.0.0.1 path=/api/auth/oauth2/callback/google",
                "SECURITY event=GOOGLE_LOGIN_FAILURE intent=login reason=account_disabled email=linda@example.com"
                        + " ip=127.0.0.1 path=/api/auth/oauth2/callback/google");
        assertThat(auditLines("GOOGLE_LOGIN_SUCCESS")).hasSize(1);
    }

    // --- AC3 / AC17

    @Test
    void ac3Ac17SameGoogleAccountReachesTheSameAccountEvenAfterAnEmailChangeAtGoogle() throws Exception {
        assertThat(browser().google(google, "login", "g-bea", claims("bea@gmail.com", true, "Bea")).location())
                .isEqualTo(TASKS);
        long id = userId("bea@gmail.com");

        GoogleBrowser later = browser();
        Resp again = later.google(google, "login", "g-bea", claims("bea.renamed@gmail.com", true, "Bea R"));

        assertThat(again.location()).isEqualTo(TASKS);
        JsonNode me = later.me().json();
        assertThat(me.get("id").asLong()).isEqualTo(id);
        assertThat(me.get("email").asText()).isEqualTo("bea@gmail.com");
        assertThat(me.get("displayName").asText()).isEqualTo("Bea");
        assertThat(me.get("connections").get(0).get("email").asText()).isEqualTo("bea.renamed@gmail.com");
        assertThat(auditLines("GOOGLE_ACCOUNT_CREATED")).hasSize(1);
        assertThat(auditLines("GOOGLE_LOGIN_SUCCESS")).hasSize(2);
    }

    // --- AC4

    @Test
    void ac4DisplayNamesAreShortenedNumberedOrTakenFromTheEmail() throws Exception {
        browser().register("Carla", "carla@example.com");
        GoogleBrowser taken = browser();
        taken.google(google, "login", "g-carla", claims("carla.g@gmail.com", true, "CARLA"));
        assertThat(taken.me().json().get("displayName").asText()).isEqualTo("CARLA 2");

        GoogleBrowser longName = browser();
        longName.google(google, "login", "g-long", claims("long@gmail.com", true, "L".repeat(70)));
        assertThat(longName.me().json().get("displayName").asText()).isEqualTo("L".repeat(50));

        GoogleBrowser noName = browser();
        noName.google(google, "login", "g-noname", claims("dora.d@gmail.com", true, null));
        assertThat(noName.me().json().get("displayName").asText()).isEqualTo("dora.d");

        // The name can be changed later like any other.
        Resp renamed = noName.put("/api/account/display-name", "{\"displayName\":\"Dora\"}");
        assertThat(renamed.status()).isEqualTo(200);
        assertThat(renamed.json().get("displayName").asText()).isEqualTo("Dora");
    }

    // --- AC5

    @Test
    void ac5VerifiedGoogleEmailLinksAConfirmedAccountIgnoringCaseAndAccents() throws Exception {
        GoogleBrowser owner = browser();
        long id = owner.register("José", "jose@example.com").json().get("id").asLong();
        confirm("jose@example.com");

        GoogleBrowser b = browser();
        Resp r = b.google(google, "login", "g-jose", claims("JOSÉ@Example.com", true, "José G"));

        assertThat(r.location()).isEqualTo(TASKS);
        JsonNode me = b.me().json();
        assertThat(me.get("id").asLong()).isEqualTo(id);
        assertThat(me.get("hasPassword").asBoolean()).isTrue();
        assertThat(me.get("displayName").asText()).isEqualTo("José");
        assertThat(auditLines("GOOGLE_LINKED")).containsExactly("SECURITY event=GOOGLE_LINKED trigger=login"
                + " email=jose@example.com ip=127.0.0.1 path=/api/auth/oauth2/callback/google");
        // The password keeps working.
        assertThat(browser().login("jose@example.com", GoogleBrowser.PASSWORD, false).status()).isEqualTo(200);
    }

    @Test
    void ac5EveryOtherExistingAccountCaseIsRefusedAndChangesNothing() throws Exception {
        browser().register("Unconfirmed", "unconfirmed@example.com");
        browser().register("Confirmed", "confirmed@example.com");
        confirm("confirmed@example.com");
        browser().register("HasOther", "hasother@example.com");
        confirm("hasother@example.com");
        GoogleBrowser other = browser();
        other.login("hasother@example.com", GoogleBrowser.PASSWORD, false);
        assertThat(other.google(google, "connect", "g-other", claims("x@gmail.com", true, "X")).location())
                .isEqualTo(CONNECTED);
        int connectionsBefore = jdbc.queryForObject("select count(*) from user_connections", Integer.class);

        record Case(String sub, String email, boolean verified, String reason) {
        }
        for (Case c : List.of(
                new Case("g-u1", "unconfirmed@example.com", true, "account_email_unconfirmed"),
                new Case("g-u2", "confirmed@example.com", false, "google_email_unverified"),
                new Case("g-u3", "hasother@example.com", true, "other_google_connected"),
                new Case("g-u4", "nobody@gmail.com", false, "google_email_unverified"))) {
            GoogleBrowser b = browser();
            Resp r = b.google(google, "login", c.sub(), claims(c.email(), c.verified(), "N"));

            assertThat(r.location()).as(c.toString()).isEqualTo(UNVERIFIED);
            assertThat(r.setCookie("JSESSIONID")).as("no session for " + c).isNull();
            assertThat(b.me().status()).isEqualTo(401);
            assertThat(auditLines("GOOGLE_LOGIN_FAILURE")).last().asString()
                    .contains("intent=login reason=" + c.reason());
        }
        assertThat(users("nobody@gmail.com")).isZero();
        assertThat(jdbc.queryForObject("select count(*) from user_connections", Integer.class))
                .isEqualTo(connectionsBefore);
    }

    // --- AC6

    @Test
    void ac6CancelTamperedStateAndLostSessionAllFailWithoutChanges() throws Exception {
        // Cancel: Google sends error=access_denied with the state.
        GoogleBrowser cancel = browser();
        String state = FakeGoogle.param(cancel.startGoogle("login").location(), "state");
        Resp cancelled = cancel.get("/api/auth/oauth2/callback/google?error=access_denied&state=" + state);
        assertThat(cancelled.location()).isEqualTo(FAILED);
        assertThat(auditLines("GOOGLE_LOGIN_FAILURE")).last().asString()
                .startsWith("SECURITY event=GOOGLE_LOGIN_FAILURE intent=login reason=cancelled email=-");
        // The pending request is gone with the flow-only session: replaying the same state fails too.
        assertThat(cancel.get("/api/auth/oauth2/callback/google?error=access_denied&state=" + state).location())
                .isEqualTo(FAILED);
        assertThat(auditLines("GOOGLE_LOGIN_FAILURE")).last().asString().contains("reason=request_not_found");

        // Tampered state.
        GoogleBrowser tamper = browser();
        String callback = tamper.approvedCallback(google, "login", "g-tamper", claims("tamper@gmail.com", true, "T"));
        Resp tampered = tamper.get(callback.replaceAll("state=[^&]+", "state=forged"));
        assertThat(tampered.location()).isEqualTo(FAILED);
        assertThat(users("tamper@gmail.com")).isZero();

        // Lost session: the right callback, but the browser lost its session cookie.
        GoogleBrowser lost = browser();
        String lostCallback = lost.approvedCallback(google, "login", "g-lost", claims("lost@gmail.com", true, "L"));
        lost.cookies.remove("JSESSIONID");
        assertThat(lost.get(lostCallback).location()).isEqualTo(FAILED);
        assertThat(lost.me().status()).isEqualTo(401);
        assertThat(users("lost@gmail.com")).isZero();

        // Missing everything.
        assertThat(browser().get("/api/auth/oauth2/callback/google").location()).isEqualTo(FAILED);
    }

    // --- AC7

    @Test
    void ac7GoogleLoginIsANormalSessionThatEndsAfter12HoursAndEndsRememberMe() throws Exception {
        GoogleBrowser b = browser();
        b.google(google, "login", "g-max", claims("max@gmail.com", true, "Max"));
        b.post("/api/auth/logout", null);
        b.register("Remy", "remy@example.com");
        assertThat(b.login("remy@example.com", GoogleBrowser.PASSWORD, true).status()).isEqualTo(200);
        assertThat(b.cookies).containsKey("remember-me");
        String rememberMe = b.cookies.get("remember-me");

        Resp r = b.google(google, "login", "g-max", claims("max@gmail.com", true, "Max"));

        assertThat(r.location()).isEqualTo(TASKS);
        assertThat(r.setCookie("remember-me")).contains("Max-Age=0").contains("Path=/api");
        assertThat(b.cookies).doesNotContainKey("remember-me");
        assertThat(b.me().json().get("email").asText()).isEqualTo("max@gmail.com");
        // The remember-me login of the other account was ended, not just its cookie.
        GoogleBrowser stolen = new GoogleBrowser(port);
        stolen.cookies.put("remember-me", rememberMe);
        assertThat(stolen.me().status()).isEqualTo(401);

        clock.advance(Duration.ofHours(11).plusMinutes(59));
        assertThat(b.me().status()).isEqualTo(200);
        clock.advance(Duration.ofMinutes(2));
        assertThat(b.me().status()).isEqualTo(401);
        assertThat(capture.lines()).anySatisfy(l -> assertThat(l)
                .startsWith("SECURITY event=SESSION_MAX_LENGTH_REACHED kind=normal email=max@gmail.com"));
    }

    // --- AC8, AC9

    @Test
    void ac8Ac9PasswordLoginIsRefusedUntilAPasswordIsSetByEmail() throws Exception {
        GoogleBrowser b = browser();
        b.google(google, "login", "g-nina", claims("nina@gmail.com", true, "Nina"));
        long id = b.me().json().get("id").asLong();

        Resp refused = browser().login("nina@gmail.com", "any-password-123", false);
        Resp unknown = browser().login("nobody-here@gmail.com", "any-password-123", false);
        assertThat(refused.status()).isEqualTo(401);
        assertThat(refused.json().get("code").asText()).isEqualTo("INVALID_CREDENTIALS");
        assertThat(refused.body().replaceAll("\"instance\":\"[^\"]*\"", ""))
                .isEqualTo(unknown.body().replaceAll("\"instance\":\"[^\"]*\"", ""));
        assertThat(b.put("/api/account/password",
                "{\"currentPassword\":\"x\",\"newPassword\":\"battery-staple-42\"}").json().get("code").asText())
                .isEqualTo("PASSWORD_REQUIRED");

        // Forgot password works for this account (the email link's token, issued directly here).
        String token = emailTokens.issue(id, EmailTokenPurpose.RESET_PASSWORD).orElseThrow().token();
        GoogleBrowser reset = browser();
        assertThat(reset.post("/api/auth/reset-password",
                "{\"token\":\"" + token + "\",\"newPassword\":\"battery-staple-42\"}").status()).isEqualTo(204);

        GoogleBrowser withPassword = browser();
        assertThat(withPassword.login("nina@gmail.com", "battery-staple-42", false).json().get("hasPassword")
                .asBoolean()).isTrue();
        assertThat(browser().google(google, "login", "g-nina", claims("nina@gmail.com", true, "Nina")).location())
                .isEqualTo(TASKS);
    }

    // --- AC10

    @Test
    void ac10AccountWithoutPasswordIsDeletedByTypingItsEmail() throws Exception {
        GoogleBrowser b = browser();
        b.google(google, "login", "g-olga", claims("olga@gmail.com", true, "Olga"));
        long id = b.me().json().get("id").asLong();

        Resp onlyPassword = b.post("/api/account/delete", "{\"password\":\"whatever\"}");
        assertThat(onlyPassword.status()).isEqualTo(400);
        assertThat(onlyPassword.json().get("code").asText()).isEqualTo("VALIDATION_FAILED");
        assertThat(onlyPassword.json().get("errors").get(0).get("field").asText()).isEqualTo("email");
        assertThat(onlyPassword.json().get("errors").get(0).get("message").asText()).isEqualTo("Email is required");

        Resp wrong = b.post("/api/account/delete", "{\"email\":\"someone@gmail.com\"}");
        assertThat(wrong.status()).isEqualTo(400);
        assertThat(wrong.json().get("code").asText()).isEqualTo("EMAIL_MISMATCH");
        assertThat(wrong.json().get("errors").get(0).get("message").asText()).isEqualTo("Email doesn't match");
        assertThat(b.me().status()).isEqualTo(200);

        Resp deleted = b.post("/api/account/delete", "{\"email\":\"  ÓLGA@Gmail.com \"}");
        assertThat(deleted.status()).isEqualTo(204);
        assertThat(b.me().status()).isEqualTo(401);
        assertThat(jdbc.queryForObject("select count(*) from user_connections where user_id = ?", Integer.class, id))
                .isZero();
        // The Google account is free again: signing in creates a new account.
        assertThat(browser().google(google, "login", "g-olga", claims("olga@gmail.com", true, "Olga")).location())
                .isEqualTo(TASKS);
        assertThat(userId("olga@gmail.com")).isNotEqualTo(id);
    }

    // --- AC12, AC13, AC14, AC15

    @Test
    void ac12ConnectAddsGoogleToThisAccountAndKeepsTheSession() throws Exception {
        GoogleBrowser b = browser();
        b.register("Pia", "pia@example.com");
        String session = b.cookies.get("JSESSIONID");
        String csrf = b.cookies.get("XSRF-TOKEN");
        assertThat(b.me().status()).isEqualTo(200);

        Resp start = b.startGoogle("connect");
        assertThat(start.setCookie("JSESSIONID")).isNull();
        Resp r = b.get(google.approve(start.location(), "g-pia", claims("pia.personal@gmail.com", true, "Pia")));

        assertThat(r.location()).isEqualTo(CONNECTED);
        assertThat(r.setCookies()).isEmpty();
        assertThat(b.cookies.get("JSESSIONID")).isEqualTo(session);
        assertThat(b.cookies.get("XSRF-TOKEN")).isEqualTo(csrf);
        JsonNode me = b.me().json();
        assertThat(me.get("email").asText()).isEqualTo("pia@example.com");
        assertThat(me.get("emailVerified").asBoolean()).isFalse();
        assertThat(me.get("connections").get(0).get("email").asText()).isEqualTo("pia.personal@gmail.com");
        assertThat(auditLines("GOOGLE_LINKED")).containsExactly("SECURITY event=GOOGLE_LINKED trigger=connect"
                + " email=pia@example.com ip=127.0.0.1 path=/api/auth/oauth2/callback/google");

        // Connecting the same Google account again is still "connected".
        assertThat(b.google(google, "connect", "g-pia", claims("pia.personal@gmail.com", true, "Pia")).location())
                .isEqualTo(CONNECTED);
        // A different Google account can't be added next to it.
        assertThat(b.google(google, "connect", "g-pia-2", claims("pia2@gmail.com", true, "Pia")).location())
                .isEqualTo(CONNECT_FAILED);
        // Signing in with Google now reaches this account.
        GoogleBrowser viaGoogle = browser();
        viaGoogle.google(google, "login", "g-pia", claims("pia.personal@gmail.com", true, "Pia"));
        assertThat(viaGoogle.me().json().get("email").asText()).isEqualTo("pia@example.com");
    }

    @Test
    void ac12ConnectWithTheSameVerifiedEmailConfirmsTheAccount() throws Exception {
        GoogleBrowser b = browser();
        b.register("Quim", "quim@example.com");

        b.google(google, "connect", "g-quim", claims("Quim@Example.com", true, "Quim"));

        assertThat(b.me().json().get("emailVerified").asBoolean()).isTrue();
    }

    @Test
    void ac13GoogleAccountOfAnotherUserCantBeConnected() throws Exception {
        GoogleBrowser first = browser();
        first.google(google, "login", "g-rosa", claims("rosa@gmail.com", true, "Rosa"));
        GoogleBrowser second = browser();
        second.register("Rui", "rui@example.com");

        Resp r = second.google(google, "connect", "g-rosa", claims("rosa@gmail.com", true, "Rosa"));

        assertThat(r.location()).isEqualTo(IN_USE);
        assertThat(second.me().json().get("connections")).isEmpty();
        assertThat(second.me().json().get("email").asText()).isEqualTo("rui@example.com");
    }

    @Test
    void ac14Ac15DisconnectNeedsAPasswordAndFreesTheGoogleAccount() throws Exception {
        GoogleBrowser googleOnly = browser();
        googleOnly.google(google, "login", "g-sam", claims("sam@gmail.com", true, "Sam"));
        Resp refused = googleOnly.delete("/api/account/connections/google");
        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.json().get("code").asText()).isEqualTo("PASSWORD_REQUIRED");
        assertThat(googleOnly.me().json().get("connections")).hasSize(1);

        GoogleBrowser b = browser();
        b.register("Tea", "tea@example.com");
        b.google(google, "connect", "g-tea", claims("tea.g@gmail.com", true, "Tea"));
        String session = b.cookies.get("JSESSIONID");

        Resp r = b.delete("/api/account/connections/google");

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.json().get("connections")).isEmpty();
        assertThat(r.json().get("hasPassword").asBoolean()).isTrue();
        assertThat(r.setCookies()).isEmpty();
        assertThat(b.cookies.get("JSESSIONID")).isEqualTo(session);
        assertThat(auditLines("GOOGLE_UNLINKED")).containsExactly("SECURITY event=GOOGLE_UNLINKED"
                + " email=tea@example.com ip=127.0.0.1 path=/api/account/connections/google");
        assertThat(b.delete("/api/account/connections/google").status()).isEqualTo(404);

        // That Google account now follows the normal rules: its own email has no account, so one is created.
        GoogleBrowser later = browser();
        later.google(google, "login", "g-tea", claims("tea.g@gmail.com", true, "Tea"));
        assertThat(later.me().json().get("email").asText()).isEqualTo("tea.g@gmail.com");
    }

    // --- AC16

    @Test
    void ac16AttackersCallbackOpenedInTheVictimsBrowserSignsNobodyIn() throws Exception {
        GoogleBrowser attacker = browser();
        String attackersCallback = attacker.approvedCallback(google, "login", "g-mallory",
                claims("mallory@gmail.com", true, "Mallory"));

        // Victim without a session, victim with a pending flow of their own, and a signed-in victim.
        GoogleBrowser anonymous = browser();
        GoogleBrowser pending = browser();
        pending.startGoogle("login");
        GoogleBrowser signedIn = browser();
        signedIn.register("Victor", "victor@example.com");
        for (GoogleBrowser victim : List.of(anonymous, pending, signedIn)) {
            Resp r = victim.get(attackersCallback);
            assertThat(r.location()).isEqualTo(FAILED);
            assertThat(r.setCookie("JSESSIONID")).isNull();
        }
        assertThat(anonymous.me().status()).isEqualTo(401);
        assertThat(pending.me().status()).isEqualTo(401);
        assertThat(signedIn.me().json().get("email").asText()).isEqualTo("victor@example.com");
        assertThat(users("mallory@gmail.com")).isZero();
    }

    @Test
    void ac16StartNeedsTheCsrfTokenAndAPost() throws Exception {
        GoogleBrowser b = browser();

        Resp noToken = b.startGoogle("login", null);
        Resp wrongToken = b.startGoogle("login", "not-the-token");
        Resp viaGet = b.get(GoogleBrowser.START + "?intent=login");

        assertThat(noToken.status()).isEqualTo(403);
        assertThat(noToken.json().get("code").asText()).isEqualTo("CSRF_INVALID");
        assertThat(wrongToken.status()).isEqualTo(403);
        assertThat(viaGet.status()).isEqualTo(405);
        assertThat(b.cookies).doesNotContainKey("JSESSIONID");
    }

    @Test
    void ac16ConnectWhoseSessionEndedBeforeTheCallbackFailsToLogin() throws Exception {
        GoogleBrowser b = browser();
        b.register("Uma", "uma@example.com");
        String callback = b.approvedCallback(google, "connect", "g-uma", claims("uma@gmail.com", true, "Uma"));
        clock.advance(Duration.ofSeconds(1));
        GoogleBrowser other = browser();
        other.login("uma@example.com", GoogleBrowser.PASSWORD, false);
        assertThat(other.post("/api/account/logout-other-sessions", null).status()).isEqualTo(204);

        Resp r = b.get(callback);

        assertThat(r.location()).isEqualTo(FAILED);
        assertThat(other.me().json().get("connections")).isEmpty();
        // Connect without a session at the start fails the same way, without visiting Google.
        GoogleBrowser anonymous = browser();
        Resp start = anonymous.startGoogle("connect");
        assertThat(start.location()).isEqualTo(FAILED);
        assertThat(anonymous.cookies).doesNotContainKey("JSESSIONID");
    }

    @Test
    void loginIntentWhileSignedInReplacesTheSession() throws Exception {
        GoogleBrowser b = browser();
        b.register("Vera", "vera@example.com");

        Resp r = b.google(google, "login", "g-wes", claims("wes@gmail.com", true, "Wes"));

        assertThat(r.location()).isEqualTo(TASKS);
        assertThat(b.me().json().get("email").asText()).isEqualTo("wes@gmail.com");
    }

    // --- AC18

    @Test
    void ac18NoCodeStateTokenOrSecretIsEverLogged() throws Exception {
        GoogleBrowser b = browser();
        String callback = b.approvedCallback(google, "login", "g-xena", claims("xena@gmail.com", true, "Xena"));
        String code = FakeGoogle.param(callback, "code");
        String state = FakeGoogle.param(callback, "state");
        assertThat(b.get(callback).location()).isEqualTo(TASKS);
        // Failures too: a replay of the used code, and a cancel.
        b.get(callback);
        GoogleBrowser cancel = browser();
        String cancelState = FakeGoogle.param(cancel.startGoogle("login").location(), "state");
        cancel.get("/api/auth/oauth2/callback/google?error=access_denied&state=" + cancelState);

        List<String> everything = allLogs.list.stream().map(GoogleSignInIntegrationTest::fullText).toList();
        assertThat(everything).isNotEmpty();
        for (String secret : List.of(code, state, cancelState, FakeGoogle.CLIENT_SECRET, "eyJ")) {
            assertThat(everything).as("logs must not contain " + secret).noneMatch(line -> line.contains(secret));
        }
        assertThat(auditLines("GOOGLE_LOGIN_SUCCESS")).containsExactly("SECURITY event=GOOGLE_LOGIN_SUCCESS"
                + " email=xena@gmail.com ip=127.0.0.1 path=/api/auth/oauth2/callback/google");
    }

    private static String fullText(ILoggingEvent event) {
        StringBuilder text = new StringBuilder(event.getFormattedMessage());
        for (IThrowableProxy t = event.getThrowableProxy(); t != null; t = t.getCause()) {
            text.append('\n').append(t.getClassName()).append(": ").append(t.getMessage());
        }
        return text.toString();
    }

    // --- AC19 (limits: GoogleSignInRateLimitIntegrationTest)

    @Test
    void ac19ProvidersIsPublicAndDisconnectNeedsASessionAndCsrf() throws Exception {
        GoogleBrowser b = browser();
        assertThat(b.get("/api/auth/providers").body()).isEqualTo("{\"google\":true}");
        assertThat(b.delete("/api/account/connections/google").status()).isEqualTo(401);
        b.cookies.remove("XSRF-TOKEN");
        assertThat(b.delete("/api/account/connections/google").status()).isEqualTo(403);
    }
}
