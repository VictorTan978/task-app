package com.taskapp.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Regression for the T1 bug (specs/tasks-priority-status-tags.md): tag saves of different users deadlocked in MySQL
 * ({@code DELETE FROM task_tags WHERE task_id = ?} on an empty range took a next-key lock on the supremum, then both
 * INSERTs waited on the other's gap lock) and answered 500. Parallel tag saves, creates and deletes of tagged tasks
 * (different users, and several tasks of one user) must never fail and must leave no orphan or missing tag. Where
 * no tag name is re-created right after being deleted, InnoDB must not report a single deadlock (so the test fails
 * on the old code even with the {@code DeadlockRetry} safety net).
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.rate-limit.login-per-ip-per-minute=100000",
        "app.rate-limit.register-per-ip-per-hour=100000"})
class TaskTagsConcurrencyIntegrationTest {

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
            .withUrlParam("connectionTimeZone", "UTC")
            .withUrlParam("forceConnectionTimeZoneToSession", "true");

    private static final String PASSWORD = "correct-horse";
    private static final int USERS = 8;
    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final Pattern ID = Pattern.compile("\"id\":(\\d+)");

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    private final HttpClient http = HttpClient.newHttpClient();

    // ---- scenarios ----

    /** The tester's repro: every user creates a task and saves the same tag names on it, all at once. */
    @RepeatedTest(10)
    void parallelTagSavesOfDifferentUsersNeverFail() throws Exception {
        List<Browser> users = new ArrayList<>();
        for (int i = 0; i < USERS; i++) {
            users.add(register());
        }
        long deadlocksBefore = deadlocks();
        for (int round = 0; round < 5; round++) {
            String extra = "Round " + round;
            List<Integer> statuses = runTogether(users.stream().<Callable<Integer>>map(u -> () -> {
                long id = u.createTask();
                return u.put("/api/tasks/" + id, "{\"title\":\"t\",\"tags\":[\"Work\",\"Home\",\"Errands\",\""
                        + extra + "\"]}").status();
            }).toList());
            assertThat(statuses).as("round " + round).containsOnly(200);
        }
        assertConsistent();
        // The fix itself, not the retry safety net: no name is re-created here, so nothing may deadlock at all.
        assertThat(deadlocks() - deadlocksBefore).as("InnoDB deadlocks").isZero();
    }

    /**
     * Each user, at once: deletes a tagged task (its last tag goes), re-tags another task (adds, removes, and
     * recreates the name just deleted), and creates + tags new tasks; also several saves of one user in parallel.
     */
    @RepeatedTest(10)
    void parallelCreateRetagAndDeleteOfTaggedTasksNeverFail() throws Exception {
        List<Browser> users = new ArrayList<>();
        List<Callable<Integer>> work = new ArrayList<>();
        for (int i = 0; i < USERS; i++) {
            Browser u = register();
            users.add(u);
            long toDelete = u.createTask("[\"Only here\",\"Work\"]");
            long toRetag = u.createTask("[\"Work\",\"Home\"]");
            long toClear = u.createTask("[\"Gone\"]");
            work.add(() -> u.delete("/api/tasks/" + toDelete).status());
            work.add(() -> u.put("/api/tasks/" + toRetag,
                    "{\"title\":\"t\",\"tags\":[\"Home\",\"only HERE\",\"New one\"]}").status());
            work.add(() -> u.put("/api/tasks/" + toClear, "{\"title\":\"t\",\"tags\":[]}").status());
            work.add(() -> u.put("/api/tasks/" + u.createTask(), "{\"title\":\"t\",\"tags\":[\"gone\",\"Fresh\"]}")
                    .status());
            work.add(() -> u.put("/api/tasks/" + u.createTask(), "{\"title\":\"t\",\"tags\":[\"Work\"]}").status());
        }

        List<Integer> statuses = runTogether(work);

        assertThat(statuses).as("statuses").allMatch(s -> s == 200 || s == 204);
        assertConsistent();
        for (Browser u : users) {
            // 2 tasks left from setup (one cleared) + 2 new; "Gone" was recreated as "gone" or kept.
            String tasks = u.get("/api/tasks").body();
            assertThat(tasks).containsIgnoringCase("\"only here\"").contains("\"New one\"", "\"Fresh\"");
        }
    }

    /** Deleting tagged tasks of many users at once, while others create and tag (tags get deleted and recreated). */
    @RepeatedTest(10)
    void parallelDeletesAndTagSavesOfDifferentUsersNeverFail() throws Exception {
        List<Callable<Integer>> work = new ArrayList<>();
        for (int i = 0; i < USERS; i++) {
            Browser u = register();
            long tagged = u.createTask("[\"Work\",\"Home\"]");
            if (i % 2 == 0) {
                work.add(() -> u.delete("/api/tasks/" + tagged).status());
            } else {
                work.add(() -> u.put("/api/tasks/" + u.createTask(), "{\"title\":\"t\",\"tags\":[\"Work\",\"Other\"]}")
                        .status());
            }
            work.add(() -> u.delete("/api/tasks/" + u.createTask()).status());
        }
        long deadlocksBefore = deadlocks();

        assertThat(runTogether(work)).as("statuses").allMatch(s -> s == 200 || s == 204);
        assertConsistent();
        assertThat(deadlocks() - deadlocksBefore).as("InnoDB deadlocks").isZero();
    }

    // ---- helpers ----

    /** No tag without a task, and every link points to a task and a tag of the same owner. */
    private void assertConsistent() {
        assertThat(jdbc.queryForObject("""
                select count(*) from tags t where not exists (select 1 from task_tags tt where tt.tag_id = t.id)""",
                Integer.class)).as("orphan tags").isZero();
        assertThat(jdbc.queryForObject("""
                select count(*) from task_tags tt join tasks k on k.id = tt.task_id join tags t on t.id = tt.tag_id
                where k.owner_id <> t.owner_id""", Integer.class)).as("cross-owner links").isZero();
    }

    /** Deadlocks InnoDB detected so far (server-wide counter; read as root, the app user has no PROCESS grant). */
    private static long deadlocks() throws Exception {
        try (Connection c = DriverManager.getConnection(mysql.getJdbcUrl(), "root", mysql.getPassword());
                ResultSet rs = c.createStatement().executeQuery(
                        "SELECT `COUNT` FROM information_schema.INNODB_METRICS WHERE NAME = 'lock_deadlocks'")) {
            assertThat(rs.next()).isTrue();
            return rs.getLong(1);
        }
    }

    /** Starts all tasks at the same moment; any exception (e.g. a 500) fails the test. */
    private static <T> List<T> runTogether(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    go.await();
                    return task.call();
                }));
            }
            go.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) {
                results.add(f.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private Browser register() throws Exception {
        int n = SEQ.incrementAndGet();
        String email = "tags" + n + "@example.com";
        Browser b = new Browser().start();
        Resp r = b.post("/api/auth/register", "{\"displayName\":\"Tags " + n + "\",\"email\":\"" + email
                + "\",\"password\":\"" + PASSWORD + "\"}");
        assertThat(r.status()).as(r.body()).isEqualTo(201);
        return b;
    }

    /** A minimal cookie jar with the XSRF header, like the browser + Angular. Safe to share between threads. */
    class Browser {
        final Map<String, String> cookies = new LinkedHashMap<>();

        Browser start() throws Exception {
            send("GET", "/api/auth/me", null);
            assertThat(cookies).containsKey("XSRF-TOKEN");
            return this;
        }

        long createTask() throws Exception {
            Resp r = post("/api/tasks", "{\"title\":\"t\"}");
            assertThat(r.status()).as(r.body()).isEqualTo(201);
            Matcher m = ID.matcher(r.body());
            assertThat(m.find()).isTrue();
            return Long.parseLong(m.group(1));
        }

        long createTask(String tagsJson) throws Exception {
            long id = createTask();
            Resp r = put("/api/tasks/" + id, "{\"title\":\"t\",\"tags\":" + tagsJson + "}");
            assertThat(r.status()).as(r.body()).isEqualTo(200);
            return id;
        }

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
            synchronized (cookies) {
                if (!cookies.isEmpty()) {
                    b.header("Cookie", cookies.entrySet().stream()
                            .map(e -> e.getKey() + "=" + e.getValue()).collect(Collectors.joining("; ")));
                }
                if (!"GET".equals(method) && cookies.containsKey("XSRF-TOKEN")) {
                    b.header("X-XSRF-TOKEN", cookies.get("XSRF-TOKEN"));
                }
            }
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            synchronized (cookies) {
                for (String sc : r.headers().allValues("Set-Cookie")) {
                    String[] nameValue = sc.split(";", 2)[0].split("=", 2);
                    if (sc.contains("Max-Age=0")) {
                        cookies.remove(nameValue[0]);
                    } else {
                        cookies.put(nameValue[0], nameValue[1]);
                    }
                }
            }
            if (r.statusCode() >= 500) {
                throw new AssertionError(method + " " + path + " -> " + r.statusCode() + " " + r.body());
            }
            return new Resp(r.statusCode(), r.body());
        }
    }

    record Resp(int status, String body) {
    }
}
