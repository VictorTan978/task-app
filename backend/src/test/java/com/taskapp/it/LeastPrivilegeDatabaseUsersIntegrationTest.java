package com.taskapp.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import tools.jackson.databind.ObjectMapper;

/**
 * AC13 / AC14 / AC15: the app runs with a data-only database user, Flyway with a separate migration user.
 *
 * <p>The container is set up like docker-compose's "db" service: MYSQL_USER (here "taskapp") is the
 * migration user with ALL on the database, and the real init script {@code docker/mysql-init/01-app-user.sh}
 * (copied with mode 755, as committed, so the entrypoint executes it) creates the restricted app user.
 * {@link MysqlInitScriptTest} covers both the executed and the sourced path of the script.
 * No {@code @ServiceConnection}: it would hand the container's single user to both the app and Flyway.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.rate-limit.login-per-ip-per-minute=10000",
        "app.rate-limit.register-per-ip-per-hour=10000"})
class LeastPrivilegeDatabaseUsersIntegrationTest {

    static final String MIGRATION_USER = "taskapp";
    static final String MIGRATION_PASSWORD = "migration-pw";
    static final String APP_USER = "taskapp_app";
    // Quote and backslash prove the script escapes the password correctly.
    static final String APP_PASSWORD = "app'pw\\1";

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("taskapp")
            .withUsername(MIGRATION_USER)
            .withPassword(MIGRATION_PASSWORD)
            .withEnv("APP_DB_USER", APP_USER)
            .withEnv("APP_DB_PASSWORD", APP_PASSWORD)
            .withCopyFileToContainer(
                    MountableFile.forHostPath(Path.of("docker/mysql-init/01-app-user.sh"), 0755),
                    "/docker-entrypoint-initdb.d/01-app-user.sh")
            .withUrlParam("connectionTimeZone", "UTC")
            .withUrlParam("forceConnectionTimeZoneToSession", "true");

    @DynamicPropertySource
    static void databaseUsers(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", () -> APP_USER);
        registry.add("spring.datasource.password", () -> APP_PASSWORD);
        registry.add("spring.flyway.user", () -> MIGRATION_USER);
        registry.add("spring.flyway.password", () -> MIGRATION_PASSWORD);
    }

    @LocalServerPort
    int port;

    /** Backed by the app's DataSource, i.e. the restricted user. */
    @Autowired
    JdbcTemplate jdbc;

    private final HttpClient http = HttpClient.newHttpClient();
    private final Map<String, String> cookies = new LinkedHashMap<>();

    @Test
    void appConnectsAsRestrictedUserWithDataOnlyGrants() {
        assertThat(jdbc.queryForObject("SELECT CURRENT_USER()", String.class)).isEqualTo(APP_USER + "@%");

        List<String> grants = jdbc.queryForList("SHOW GRANTS", String.class);
        assertThat(grants).contains(
                "GRANT SELECT, INSERT, UPDATE, DELETE ON `taskapp`.* TO `" + APP_USER + "`@`%`");
        assertThat(grants).noneMatch(g -> g.contains("ALL PRIVILEGES") || g.contains("CREATE")
                || g.contains("DROP") || g.contains("ALTER"));
    }

    @Test
    void flywayMigratedWithTheMigrationUser() {
        List<Map<String, Object>> history = jdbc.queryForList(
                "SELECT installed_by, success FROM flyway_schema_history WHERE version IS NOT NULL");
        assertThat(history).isNotEmpty();
        assertThat(history).allSatisfy(row -> {
            assertThat(row.get("installed_by")).isEqualTo(MIGRATION_USER);
            assertThat(row.get("success")).isIn(true, 1, (byte) 1);
        });
    }

    @Test
    void appUserCannotChangeTheSchema() {
        assertThatThrownBy(() -> jdbc.execute("DROP TABLE tasks"))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("DROP command denied to user '" + APP_USER + "'@");
        assertThatThrownBy(() -> jdbc.execute("CREATE TABLE x (id int)"))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("CREATE command denied to user '" + APP_USER + "'@");
        assertThatThrownBy(() -> jdbc.execute("ALTER TABLE tasks ADD COLUMN x int"))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("ALTER command denied to user '" + APP_USER + "'@");

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tasks", Integer.class)).isNotNull();
    }

    @Test
    void registerAndTaskRoundTripWorkWithTheRestrictedUser() throws Exception {
        send("GET", "/api/auth/me", null);
        assertThat(cookies).containsKey("XSRF-TOKEN");

        String suffix = UUID.randomUUID().toString();
        HttpResponse<String> registered = send("POST", "/api/auth/register",
                "{\"displayName\":\"Least " + suffix.substring(0, 8) + "\",\"email\":\"least-" + suffix
                        + "@example.com\",\"password\":\"correct-horse\"}");
        assertThat(registered.statusCode()).isEqualTo(201);

        HttpResponse<String> created = send("POST", "/api/tasks", "{\"title\":\"Least privilege\"}");
        assertThat(created.statusCode()).isEqualTo(201);
        long id = new ObjectMapper().readTree(created.body()).get("id").asLong();

        HttpResponse<String> updated = send("PATCH", "/api/tasks/" + id, "{\"done\":true}");
        assertThat(updated.statusCode()).isEqualTo(200);

        HttpResponse<String> list = send("GET", "/api/tasks", null);
        assertThat(list.statusCode()).isEqualTo(200);
        assertThat(list.body()).contains("Least privilege");

        assertThat(send("DELETE", "/api/tasks/" + id, null).statusCode()).isEqualTo(204);
    }

    /** Minimal browser: stores cookies and echoes XSRF-TOKEN like Angular. */
    private HttpResponse<String> send(String method, String path, String body) throws Exception {
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
        if (!method.equals("GET") && cookies.containsKey("XSRF-TOKEN")) {
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
        return r;
    }
}
