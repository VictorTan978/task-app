package com.taskapp.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskapp.auth.EmailJobs;
import com.taskapp.auth.EmailTokenPurpose;
import com.taskapp.auth.EmailTokenService;
import com.taskapp.security.audit.SecurityLogCapture;

/**
 * Roles and admin users (specs/roles-admin.md) end-to-end over real HTTP against MySQL 8.4 with the Flyway migrations
 * (V8): the Roles table and AC11-AC20 as seen by the admin, and the disabled / demoted user's next requests. Accounts
 * are registered over HTTP; roles are given in the database, like the documented SQL fallback. Every test uses its
 * own accounts.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.rate-limit.login-per-ip-per-minute=10000",
        "app.rate-limit.register-per-ip-per-hour=10000",
        "app.rate-limit.login-failures-per-email=10000",
        "app.rate-limit.forgot-password-per-ip-per-hour=10000",
        "app.rate-limit.verify-email-per-ip-per-hour=10000",
        "app.rate-limit.check-reset-token-per-ip-per-hour=10000",
        "app.rate-limit.reset-password-per-ip-per-hour=10000"})
class AdminIntegrationTest {

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
            .withUrlParam("connectionTimeZone", "UTC")
            .withUrlParam("forceConnectionTimeZoneToSession", "true");

    private static final String PASSWORD = "correct-horse";
    private static final String USERS = "/api/admin/users";

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    EmailTokenService emailTokens;

    @Autowired
    EmailJobs emailJobs;

    private final HttpClient http = HttpClient.newHttpClient();
    private final SecurityLogCapture capture = new SecurityLogCapture();

    @BeforeEach
    void startCapture() {
        capture.start();
    }

    @AfterEach
    void stopCapture() {
        capture.stop();
    }

    // ---- a cookie jar that behaves like a browser + Angular HttpClient ----

    class Browser {
        final Map<String, String> cookies = new LinkedHashMap<>();
        boolean sendCsrfHeader = true;
        long id;
        String email;

        Resp get(String path) throws Exception {
            return send("GET", path, null);
        }

        Resp post(String path, String body) throws Exception {
            return send("POST", path, body);
        }

        Resp put(String path, String body) throws Exception {
            return send("PUT", path, body);
        }

        Resp delete(String path) throws Exception {
            return send("DELETE", path, null);
        }

        Resp send(String method, String path, String body) throws Exception {
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
            return new Resp(r.statusCode(), r.body(), r.headers().firstValue("Retry-After").orElse(null));
        }

        Browser start() throws Exception {
            get("/api/auth/me");
            assertThat(cookies).containsKey("XSRF-TOKEN");
            return this;
        }

        Resp login(String email, String password, boolean rememberMe) throws Exception {
            return post("/api/auth/login", "{\"email\":\"" + email + "\",\"password\":\"" + password
                    + "\",\"rememberMe\":" + rememberMe + "}");
        }

        /** Closing the browser: only the persistent remember-me cookie survives. */
        Browser restart() {
            cookies.remove("JSESSIONID");
            cookies.remove("XSRF-TOKEN");
            return this;
        }

        Browser copy() {
            Browser copy = new Browser();
            copy.cookies.putAll(cookies);
            copy.id = id;
            copy.email = email;
            return copy;
        }

        // admin actions on another account
        Resp disable(Browser target) throws Exception {
            return post(USERS + "/" + target.id + "/disable", null);
        }

        Resp enable(Browser target) throws Exception {
            return post(USERS + "/" + target.id + "/enable", null);
        }

        Resp revoke(Browser target) throws Exception {
            return post(USERS + "/" + target.id + "/revoke-sessions", null);
        }

        Resp role(Browser target, String role) throws Exception {
            return put(USERS + "/" + target.id + "/role", "{\"role\":\"" + role + "\"}");
        }

        Resp remove(Browser target) throws Exception {
            return delete(USERS + "/" + target.id);
        }
    }

    record Resp(int status, String body, String retryAfter) {
        JsonNode json() throws IOException {
            return new ObjectMapper().readTree(body);
        }

        String code() throws IOException {
            return json().get("code").asText();
        }
    }

    // ---- helpers ----

    /** Registers (and so signs in) an account with this role. */
    private Browser account(String name, String role) throws Exception {
        Browser b = new Browser().start();
        b.email = name.toLowerCase() + "@example.com";
        Resp r = b.post("/api/auth/register", "{\"displayName\":\"" + name + "\",\"email\":\"" + b.email
                + "\",\"password\":\"" + PASSWORD + "\"}");
        assertThat(r.status()).as(r.body()).isEqualTo(201);
        b.id = r.json().get("id").asLong();
        jdbc.update("update users set role = ? where id = ?", role, b.id);
        return b;
    }

    private Browser device(Browser account, boolean rememberMe) throws Exception {
        Browser b = new Browser().start();
        b.id = account.id;
        b.email = account.email;
        Resp r = b.login(account.email, PASSWORD, rememberMe);
        assertThat(r.status()).as(r.body()).isEqualTo(200);
        return b;
    }

    private String role(Browser b) {
        return jdbc.queryForObject("select role from users where id = ?", String.class, b.id);
    }

    private boolean disabled(Browser b) {
        return jdbc.queryForObject("select disabled_at is not null from users where id = ?", Boolean.class, b.id);
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private List<String> audit(String event) {
        return capture.lines().stream().filter(l -> l.startsWith("SECURITY event=" + event + " ")).toList();
    }

    private static void expect(Resp r, int status, String code) throws IOException {
        assertThat(r.status()).as(r.body()).isEqualTo(status);
        if (code != null) {
            assertThat(r.code()).isEqualTo(code);
        }
    }

    // ---- AC7, AC20: who may call /api/admin/** ----

    @Test
    void signedOutIs401AUserIs403ForbiddenAndChangesNeedTheCsrfToken() throws Exception {
        Browser admin = account("Gate", "ADMIN");
        Browser user = account("Gus", "USER");

        expect(new Browser().start().get(USERS), 401, "UNAUTHENTICATED");
        expect(new Browser().start().disable(user), 401, "UNAUTHENTICATED");
        expect(user.get(USERS), 403, "FORBIDDEN");
        expect(user.disable(admin), 403, "FORBIDDEN");
        assertThat(disabled(admin)).isFalse();
        assertThat(audit("ADMIN_ACCESS_DENIED")).contains("SECURITY event=ADMIN_ACCESS_DENIED actor_id=" + user.id
                + " target_id=" + admin.id + " action=disable reason=not_admin email=" + user.email
                + " ip=127.0.0.1 path=/api/admin/users/" + admin.id + "/disable");

        admin.sendCsrfHeader = false;
        expect(admin.disable(user), 403, "CSRF_INVALID");
        assertThat(disabled(user)).isFalse();
        admin.sendCsrfHeader = true;
        expect(admin.get(USERS), 200, null);
    }

    // ---- Roles table, AC15 ----

    @Test
    void rolesTableIsEnforcedAgainstTheDatabase() throws Exception {
        Browser admin = account("Ada", "ADMIN");
        Browser boss = account("Boss", "SUPER_ADMIN");
        Browser otherBoss = account("Otto", "SUPER_ADMIN");
        Browser otherAdmin = account("Abe", "ADMIN");
        Browser user = account("Uma", "USER");

        // An admin: users only, no role changes.
        expect(admin.revoke(user), 200, null);
        expect(admin.role(user, "ADMIN"), 403, "INSUFFICIENT_ROLE");
        expect(admin.disable(otherAdmin), 403, "INSUFFICIENT_ROLE");
        expect(admin.remove(otherAdmin), 403, "INSUFFICIENT_ROLE");
        expect(admin.disable(boss), 403, "INSUFFICIENT_ROLE");
        expect(admin.disable(admin), 409, "CANNOT_MODIFY_SELF");

        // A super admin: users and admins, roles USER <-> ADMIN; never a super admin, never SUPER_ADMIN.
        expect(boss.revoke(otherAdmin), 200, null);
        expect(boss.role(user, "SUPER_ADMIN"), 400, "VALIDATION_FAILED");
        expect(boss.disable(otherBoss), 403, "INSUFFICIENT_ROLE");
        expect(boss.role(otherBoss, "USER"), 403, "INSUFFICIENT_ROLE");
        expect(boss.remove(otherBoss), 403, "INSUFFICIENT_ROLE");
        expect(boss.role(boss, "USER"), 409, "CANNOT_MODIFY_SELF");
        expect(boss.remove(boss), 409, "CANNOT_MODIFY_SELF");

        assertThat(role(user)).isEqualTo("USER");
        assertThat(role(otherBoss)).isEqualTo("SUPER_ADMIN");
        assertThat(role(boss)).isEqualTo("SUPER_ADMIN");
        assertThat(disabled(otherAdmin)).isFalse();
        assertThat(disabled(boss)).isFalse();
        assertThat(audit("ADMIN_ACCESS_DENIED")).contains(
                "SECURITY event=ADMIN_ACCESS_DENIED actor_id=" + admin.id + " target_id=" + user.id
                        + " action=change_role reason=insufficient_role email=" + admin.email
                        + " ip=127.0.0.1 path=/api/admin/users/" + user.id + "/role",
                "SECURITY event=ADMIN_ACCESS_DENIED actor_id=" + boss.id + " target_id=" + otherBoss.id
                        + " action=disable reason=target_super_admin email=" + boss.email
                        + " ip=127.0.0.1 path=/api/admin/users/" + otherBoss.id + "/disable",
                "SECURITY event=ADMIN_ACCESS_DENIED actor_id=" + admin.id + " target_id=" + admin.id
                        + " action=disable reason=self email=" + admin.email
                        + " ip=127.0.0.1 path=/api/admin/users/" + admin.id + "/disable");

        // The super admin makes the user an admin, and can then act on that admin.
        Resp promoted = boss.role(user, "ADMIN");
        expect(promoted, 200, null);
        assertThat(promoted.json().get("role").asText()).isEqualTo("ADMIN");
        expect(admin.disable(user), 403, "INSUFFICIENT_ROLE");
        expect(boss.disable(user), 200, null);
        assertThat(audit("ADMIN_ROLE_CHANGED")).containsExactly("SECURITY event=ADMIN_ROLE_CHANGED actor_id="
                + boss.id + " target_id=" + user.id + " action=change_role from=USER to=ADMIN email=" + boss.email
                + " ip=127.0.0.1 path=/api/admin/users/" + user.id + "/role");
    }

    @Test
    void roleBodyMustBeUserOrAdminAndBadIdsAreMalformed() throws Exception {
        Browser boss = account("Rita", "SUPER_ADMIN");
        Browser user = account("Ron", "USER");

        for (String body : List.of("{\"role\":\"SUPER_ADMIN\"}", "{\"role\":\"admin\"}", "{\"role\":null}", "{}")) {
            Resp r = boss.put(USERS + "/" + user.id + "/role", body);
            expect(r, 400, "VALIDATION_FAILED");
            assertThat(r.json().get("errors")).hasSize(1);
            assertThat(r.json().get("errors").get(0).get("field").asText()).isEqualTo("role");
            assertThat(r.json().get("errors").get(0).get("code").asText()).isEqualTo("INVALID_ROLE");
        }
        expect(boss.put(USERS + "/" + user.id + "/role", "nope"), 400, "MALFORMED_REQUEST");
        expect(boss.post(USERS + "/abc/disable", null), 400, "MALFORMED_REQUEST");
        expect(boss.post(USERS + "/999999999/disable", null), 404, "USER_NOT_FOUND");
        assertThat(role(user)).isEqualTo("USER");
    }

    // ---- AC11, AC18, AC19: disable / enable ----

    @Test
    void disabledAccountIsSignedOutEverywhereAndCantSignInUntilEnabled() throws Exception {
        Browser admin = account("Dan", "ADMIN");
        Browser user = account("Della", "USER");
        user.post("/api/tasks", "{\"title\":\"Keep me\"}");
        Browser laptop = device(user, true);
        assertThat(count("select count(*) from remember_me_tokens where user_id = ?", user.id)).isEqualTo(1);

        Resp r = admin.disable(user);

        expect(r, 200, null);
        assertThat(r.json().get("disabled").asBoolean()).isTrue();
        assertThat(r.json().get("email").asText()).isEqualTo(user.email);
        assertThat(r.json().size()).isEqualTo(8);
        assertThat(count("select count(*) from remember_me_tokens where user_id = ?", user.id)).isZero();
        assertThat(audit("ADMIN_USER_DISABLED")).containsExactly("SECURITY event=ADMIN_USER_DISABLED actor_id="
                + admin.id + " target_id=" + user.id + " action=disable email=" + admin.email
                + " ip=127.0.0.1 path=/api/admin/users/" + user.id + "/disable");

        // Open session: the very next request ends it with ACCOUNT_DISABLED, then it is simply signed out.
        Resp next = user.get("/api/tasks");
        expect(next, 401, "ACCOUNT_DISABLED");
        assertThat(next.json().get("detail").asText()).isEqualTo("This account has been disabled.");
        expect(user.get("/api/tasks"), 401, "UNAUTHENTICATED");
        assertThat(audit("ACCOUNT_DISABLED_SESSION_ENDED")).hasSize(1);
        // The remember-me device (session ended too): its login was deleted, so the cookie is just unknown.
        expect(laptop.get("/api/auth/me"), 401, "ACCOUNT_DISABLED");
        expect(laptop.restart().get("/api/auth/me"), 401, "UNAUTHENTICATED");
        assertThat(laptop.cookies).doesNotContainKey("remember-me");

        // Password login: the right password says disabled, a wrong one the usual answer.
        Browser fresh = new Browser().start();
        expect(fresh.login(user.email, PASSWORD, true), 403, "ACCOUNT_DISABLED");
        assertThat(fresh.cookies).doesNotContainKeys("JSESSIONID", "remember-me");
        expect(fresh.login(user.email, "wrong-horse", false), 401, "INVALID_CREDENTIALS");
        assertThat(capture.lines()).contains("SECURITY event=LOGIN_FAILURE reason=account_disabled email="
                + user.email + " ip=127.0.0.1 path=/api/auth/login");

        // Enable: signs in normally, data kept, the old sessions stay ended.
        Browser oldSession = user.copy();
        Resp enabled = admin.enable(user);
        expect(enabled, 200, null);
        assertThat(enabled.json().get("disabled").asBoolean()).isFalse();
        expect(oldSession.get("/api/tasks"), 401, "UNAUTHENTICATED");
        Browser back = device(user, false);
        Resp tasks = back.get("/api/tasks");
        expect(tasks, 200, null);
        assertThat(tasks.json().get(0).get("title").asText()).isEqualTo("Keep me");
        assertThat(audit("ADMIN_USER_ENABLED")).hasSize(1);
    }

    @Test
    void rememberMeLoginOfAnAccountDisabledByHandIsDeletedAndRefused() throws Exception {
        Browser user = account("Hank", "USER");
        Browser phone = device(user, true).restart();
        jdbc.update("update users set disabled_at = now(6) where id = ?", user.id);

        expect(phone.get("/api/tasks"), 401, "ACCOUNT_DISABLED");

        assertThat(phone.cookies).doesNotContainKey("remember-me");
        assertThat(count("select count(*) from remember_me_tokens where user_id = ?", user.id)).isZero();
        assertThat(capture.lines()).contains("SECURITY event=REMEMBER_ME_REJECTED reason=account_disabled email="
                + user.email + " ip=127.0.0.1 path=/api/tasks");
    }

    @Test
    void anAdminWhoIsDisabledOrDemotedLosesAccessOnTheNextRequest() throws Exception {
        Browser admin = account("Dora", "ADMIN");
        Browser demoted = account("Dirk", "ADMIN");
        expect(admin.get(USERS), 200, null);
        expect(demoted.get(USERS), 200, null);

        jdbc.update("update users set disabled_at = now(6) where id = ?", admin.id);
        jdbc.update("update users set role = 'USER' where id = ?", demoted.id);

        expect(admin.get(USERS), 401, "ACCOUNT_DISABLED");
        expect(demoted.get(USERS), 403, "FORBIDDEN");
        expect(demoted.revoke(admin), 403, "FORBIDDEN");
        assertThat(demoted.get("/api/auth/me").json().get("role").asText()).isEqualTo("USER");
    }

    // ---- AC12: sign out everywhere ----

    @Test
    void revokeSessionsEndsEverySessionAndRememberMeLoginButKeepsTheAccountActive() throws Exception {
        Browser admin = account("Rex", "ADMIN");
        Browser user = account("Rosa", "USER");
        Browser phone = device(user, true);

        expect(admin.revoke(user), 200, null);

        expect(user.get("/api/tasks"), 401, "UNAUTHENTICATED");
        expect(phone.restart().get("/api/tasks"), 401, "UNAUTHENTICATED");
        assertThat(count("select count(*) from remember_me_tokens where user_id = ?", user.id)).isZero();
        assertThat(disabled(user)).isFalse();
        device(user, false);
        assertThat(audit("ADMIN_USER_SESSIONS_REVOKED")).hasSize(1);
    }

    // ---- AC13: role changes apply on the next request ----

    @Test
    void roleChangeTakesEffectOnTheUsersNextRequestWithoutSigningInAgain() throws Exception {
        Browser boss = account("Sue", "SUPER_ADMIN");
        Browser user = account("Sam", "USER");
        expect(user.get(USERS), 403, "FORBIDDEN");
        assertThat(user.get("/api/auth/me").json().get("role").asText()).isEqualTo("USER");

        expect(boss.role(user, "ADMIN"), 200, null);

        expect(user.get(USERS), 200, null);
        assertThat(user.get("/api/auth/me").json().get("role").asText()).isEqualTo("ADMIN");

        expect(boss.role(user, "USER"), 200, null);
        expect(user.get(USERS), 403, "FORBIDDEN");
    }

    // ---- AC14: delete ----

    @Test
    void deleteRemovesTheAccountWithItsDataAndEndsItsSessions() throws Exception {
        Browser admin = account("Del", "ADMIN");
        Browser user = account("Vic", "USER");
        long task = user.post("/api/tasks", "{\"title\":\"Gone soon\"}").json().get("id").asLong();
        expect(user.put("/api/tasks/" + task, "{\"title\":\"Gone soon\",\"tags\":[\"Mine\"]}"), 200, null);
        Browser phone = device(user, true);
        jdbc.update("insert into user_connections (user_id, provider, provider_subject, provider_email)"
                + " values (?, 'google', 'g-vic', 'vic@gmail.com')", user.id);
        emailTokens.issue(user.id, EmailTokenPurpose.RESET_PASSWORD);

        Resp r = admin.remove(user);

        assertThat(r.status()).isEqualTo(204);
        assertThat(r.body()).isEmpty();
        assertThat(count("select count(*) from users where id = ?", user.id)).isZero();
        assertThat(count("select count(*) from tasks where owner_id = ?", user.id)).isZero();
        assertThat(count("select count(*) from tags where owner_id = ?", user.id)).isZero();
        assertThat(count("select count(*) from task_tags where task_id = ?", task)).isZero();
        assertThat(count("select count(*) from user_connections where user_id = ?", user.id)).isZero();
        assertThat(count("select count(*) from remember_me_tokens where user_id = ?", user.id)).isZero();
        assertThat(count("select count(*) from email_tokens where user_id = ?", user.id)).isZero();
        expect(user.get("/api/tasks"), 401, "UNAUTHENTICATED");
        expect(phone.restart().get("/api/tasks"), 401, "UNAUTHENTICATED");
        expect(admin.remove(user), 404, "USER_NOT_FOUND");
        expect(admin.disable(user), 404, "USER_NOT_FOUND");
        assertThat(audit("ADMIN_USER_DELETED")).containsExactly("SECURITY event=ADMIN_USER_DELETED actor_id="
                + admin.id + " target_id=" + user.id + " action=delete email=" + admin.email
                + " ip=127.0.0.1 path=/api/admin/users/" + user.id);
        assertThat(audit("ADMIN_ACCESS_DENIED")).hasSize(2).allMatch(l -> l.contains("reason=target_not_found"));
    }

    // ---- idempotency ----

    @Test
    void changesAlreadyInEffectSucceedAndAreNotLogged() throws Exception {
        Browser boss = account("Ivy", "SUPER_ADMIN");
        Browser user = account("Ian", "USER");

        expect(boss.enable(user), 200, null);
        expect(boss.role(user, "USER"), 200, null);
        expect(boss.disable(user), 200, null);
        Resp again = boss.disable(user);

        expect(again, 200, null);
        assertThat(again.json().get("disabled").asBoolean()).isTrue();
        assertThat(audit("ADMIN_USER_DISABLED")).hasSize(1);
        assertThat(audit("ADMIN_USER_ENABLED")).isEmpty();
        assertThat(audit("ADMIN_ROLE_CHANGED")).isEmpty();
    }

    // ---- AC20: rate limit ----

    @Test
    void sixtyChangesPerMinutePerAdminCountingRefusedOnesAndTheListIsNotLimited() throws Exception {
        Browser admin = account("Lim", "ADMIN");
        Browser other = account("Lou", "ADMIN");
        for (int i = 0; i < 60; i++) {
            expect(admin.disable(admin), 409, "CANNOT_MODIFY_SELF");
        }

        Resp limited = admin.revoke(other);

        expect(limited, 429, "RATE_LIMITED");
        assertThat(Integer.parseInt(limited.retryAfter())).isBetween(1, 60);
        assertThat(capture.lines()).contains("SECURITY event=RATE_LIMITED limit=admin-action-user email="
                + admin.email + " ip=127.0.0.1 path=/api/admin/users/" + other.id + "/revoke-sessions");
        expect(admin.get(USERS), 200, null);
        // Another admin has their own budget.
        expect(other.disable(other), 409, "CANNOT_MODIFY_SELF");
    }

    // ---- AC8, AC9, AC10: the list ----

    @Test
    void listShowsOnlyTheAdminFieldsNewestFirstWithSearchAndPaging() throws Exception {
        Browser admin = account("Lister", "ADMIN");
        Browser zoe = account("Zoë", "USER");
        Browser zed = account("Zed", "SUPER_ADMIN");
        // Before she is disabled, so the task and tag really exist.
        long zoesTask = zoe.post("/api/tasks", "{\"title\":\"Private\"}").json().get("id").asLong();
        expect(zoe.put("/api/tasks/" + zoesTask, "{\"title\":\"Private\",\"tags\":[\"Secret\"]}"), 200, null);
        jdbc.update("update users set email_verified_at = now(6), disabled_at = now(6) where id = ?", zoe.id);
        jdbc.update("insert into user_connections (user_id, provider, provider_subject, provider_email)"
                + " values (?, 'google', 'g-zoe', 'zoe@gmail.com')", zoe.id);

        Resp all = admin.get(USERS + "?q=%20ZOE%20");
        expect(all, 200, null);
        JsonNode page = all.json();
        assertThat(page.get("totalElements").asLong()).isEqualTo(1);
        assertThat(page.get("totalPages").asInt()).isEqualTo(1);
        assertThat(page.get("page").asInt()).isZero();
        assertThat(page.get("size").asInt()).isEqualTo(20);
        JsonNode row = page.get("content").get(0);
        assertThat(row.get("id").asLong()).isEqualTo(zoe.id);
        assertThat(row.get("email").asText()).isEqualTo("zoë@example.com");
        assertThat(row.get("displayName").asText()).isEqualTo("Zoë");
        assertThat(row.get("createdAt").asText()).endsWith("Z");
        assertThat(row.get("emailVerified").asBoolean()).isTrue();
        assertThat(row.get("googleConnected").asBoolean()).isTrue();
        assertThat(row.get("role").asText()).isEqualTo("USER");
        assertThat(row.get("disabled").asBoolean()).isTrue();
        assertThat(row.size()).isEqualTo(8);
        assertThat(all.body()).doesNotContain("Private", "Secret", "password", "$2a$", "g-zoe");
        // T1 AC14: an admin's own task/tag endpoints never show another user's tasks or tags.
        assertThat(admin.get("/api/tags").json()).isEmpty();
        assertThat(admin.get("/api/tasks?tag=Secret").json()).isEmpty();
        expect(admin.get("/api/tasks/" + zoesTask), 404, "NOT_FOUND");

        // Newest first, paged.
        Resp first = admin.get(USERS + "?q=z&size=1");
        assertThat(first.json().get("content").get(0).get("id").asLong()).isEqualTo(zed.id);
        assertThat(first.json().get("totalElements").asLong()).isGreaterThanOrEqualTo(2);
        Resp past = admin.get(USERS + "?q=z&size=1&page=999");
        assertThat(past.json().get("content")).isEmpty();
        assertThat(past.json().get("totalElements").asLong()).isEqualTo(first.json().get("totalElements").asLong());
        Resp none = admin.get(USERS + "?q=no-such-user-at-all");
        assertThat(none.json().get("totalElements").asLong()).isZero();
        assertThat(none.json().get("totalPages").asInt()).isZero();

        Resp invalid = admin.get(USERS + "?page=-1&size=101&q=" + "x".repeat(101));
        expect(invalid, 400, "VALIDATION_FAILED");
        assertThat(invalid.json().get("errors").findValuesAsText("field")).containsExactly("page", "q", "size");
        assertThat(invalid.json().get("errors").findValuesAsText("code"))
                .containsExactly("OUT_OF_RANGE", "TOO_LONG", "OUT_OF_RANGE");
        expect(admin.get(USERS + "?size=abc"), 400, "MALFORMED_REQUEST");
    }

    // ---- AC18: reset links, forgot password and confirmation links of disabled accounts ----

    @Test
    void disablingDeletesUnusedResetLinksAndTheyStayDeadAfterEnable() throws Exception {
        Browser admin = account("Res", "ADMIN");
        Browser user = account("Rae", "USER");
        String token = emailTokens.issue(user.id, EmailTokenPurpose.RESET_PASSWORD).orElseThrow().token();
        Browser anyone = new Browser().start();
        expect(anyone.post("/api/auth/check-reset-token", "{\"token\":\"" + token + "\"}"), 204, null);

        admin.disable(user);
        admin.enable(user);

        expect(anyone.post("/api/auth/check-reset-token", "{\"token\":\"" + token + "\"}"), 400, "TOKEN_INVALID");
    }

    @Test
    void resetLinkOfAnAccountDisabledByHandIsInvalidAndConfirmationLinksStillWork() throws Exception {
        Browser user = account("Ula", "USER");
        String reset = emailTokens.issue(user.id, EmailTokenPurpose.RESET_PASSWORD).orElseThrow().token();
        String verify = emailTokens.issue(user.id, EmailTokenPurpose.VERIFY_EMAIL).orElseThrow().token();
        jdbc.update("update users set disabled_at = now(6) where id = ?", user.id);
        Browser anyone = new Browser().start();

        expect(anyone.post("/api/auth/check-reset-token", "{\"token\":\"" + reset + "\"}"), 400, "TOKEN_INVALID");
        expect(anyone.post("/api/auth/reset-password", "{\"token\":\"" + reset
                + "\",\"newPassword\":\"battery-staple-42\"}"), 400, "TOKEN_INVALID");
        assertThat(capture.lines()).contains("SECURITY event=EMAIL_TOKEN_REJECTED reason=account_disabled email="
                + user.email + " ip=127.0.0.1 path=/api/auth/check-reset-token");
        assertThat(emailTokens.issue(user.id, EmailTokenPurpose.RESET_PASSWORD)).isEmpty();

        expect(anyone.post("/api/auth/verify-email", "{\"token\":\"" + verify + "\"}"), 204, null);
        assertThat(count("select count(*) from users where id = ? and email_verified_at is not null", user.id))
                .isEqualTo(1);
    }

    @Test
    void forgotPasswordForADisabledAccountAnswersAsUsualButSendsNothing() throws Exception {
        Browser user = account("Fred", "USER");
        jdbc.update("update users set disabled_at = now(6) where id = ?", user.id);

        expect(new Browser().start().post("/api/auth/forgot-password", "{\"email\":\"FRED@example.com\"}"), 202,
                null);

        Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> capture.allLines().stream()
                .anyMatch(l -> l.startsWith("SECURITY event=PASSWORD_RESET_REQUESTED")
                        && l.contains("email=fred@example.com")));
        assertThat(capture.allLines()).contains("SECURITY event=PASSWORD_RESET_REQUESTED outcome=account_disabled"
                + " email=fred@example.com ip=127.0.0.1 path=/api/auth/forgot-password");
        assertThat(count("select count(*) from email_tokens where user_id = ? and purpose = 'RESET_PASSWORD'",
                user.id)).isZero();
        Awaitility.await().atMost(Duration.ofSeconds(20)).until(emailJobs::isIdle);
    }
}
