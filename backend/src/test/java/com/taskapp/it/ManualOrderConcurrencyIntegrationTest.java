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
 * T5c AC24 / contract 0.18.0 "Concurrency": members of one list reorder, create and edit at the same moment. Never a
 * 500 (the client throws on any), never an InnoDB deadlock, the last write wins, and "My order" always lists every task
 * exactly once. Several drops land between the same two neighbours, so a renumbering runs under contention.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.rate-limit.login-per-ip-per-minute=100000",
        "app.rate-limit.register-per-ip-per-hour=100000"})
class ManualOrderConcurrencyIntegrationTest {

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
            .withUrlParam("connectionTimeZone", "UTC")
            .withUrlParam("forceConnectionTimeZoneToSession", "true");

    private static final String PASSWORD = "correct-horse";
    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final Pattern ID = Pattern.compile("\"id\":(\\d+)");

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    private final HttpClient http = HttpClient.newHttpClient();

    /** 24 drops (two members) between the same neighbours, with creates and edits in between: nothing fails. */
    @RepeatedTest(5)
    void parallelDropsCreatesAndEditsNeverFailOrDeadlockAndKeepEveryTaskOnce() throws Exception {
        Browser ana = register("ana");
        Browser ben = register("ben");
        long list = ana.createList("Shared");
        jdbc.update("insert into list_members (list_id, user_id) values (?, ?)", list, ben.userId());
        long anchor = ana.createTask(list);
        long bottom = ana.createTask(list);
        List<Long> movers = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            movers.add(ana.createTask(list));
        }
        // Half the group has never been ordered (what the tasks look like after V15): materialising races too.
        jdbc.update("update tasks set position = null where list_id = ? and id % 2 = 0", list);
        long deadlocksBefore = deadlocks();

        List<Callable<Integer>> work = new ArrayList<>();
        for (int i = 0; i < movers.size(); i++) {
            Browser who = i % 2 == 0 ? ana : ben;
            long mover = movers.get(i);
            work.add(() -> who.put("/api/tasks/" + mover + "/position", "{\"afterId\":" + anchor + "}").status());
            if (i % 4 == 0) {
                work.add(() -> who.post("/api/tasks", "{\"title\":\"new\",\"listId\":" + list + "}").status() == 201 ? 204 : -1);
                work.add(() -> who.patch("/api/tasks/" + mover, "{\"priority\":\"HIGH\"}").status() == 200 ? 204 : -1);
            }
        }
        List<Integer> statuses = runTogether(work);

        assertThat(statuses).as("statuses").containsOnly(204);
        assertThat(deadlocks() - deadlocksBefore).as("InnoDB deadlocks").isZero();
        assertEveryTaskOnce(ana, list, 24 + 2 + 6);
        List<Long> order = ana.order(list);
        // All movers were dropped right after the anchor, one after the other, so they sit between anchor and bottom.
        assertThat(order.indexOf(anchor)).isLessThan(order.indexOf(bottom));
        assertThat(order.subList(order.indexOf(anchor) + 1, order.indexOf(bottom))).containsAll(movers);
        // No tie, no gap left unusable: a final drop still works.
        assertThat(ana.put("/api/tasks/" + bottom + "/position", "{\"afterId\":null}").status()).isEqualTo(204);
        assertThat(ana.order(list).get(0)).isEqualTo(bottom);
    }

    /** Two members drop the same task in two places at once: both succeed, the last write wins (one of the two places). */
    @RepeatedTest(10)
    void twoDropsOfTheSameTaskLeaveItInOneOfThePlaces() throws Exception {
        Browser ana = register("ana");
        Browser ben = register("ben");
        long list = ana.createList("Shared");
        jdbc.update("insert into list_members (list_id, user_id) values (?, ?)", list, ben.userId());
        long a = ana.createTask(list);
        long b = ana.createTask(list);
        long c = ana.createTask(list);
        long x = ana.createTask(list);
        long deadlocksBefore = deadlocks();

        List<Integer> statuses = runTogether(List.of(
                () -> ana.put("/api/tasks/" + x + "/position", "{\"afterId\":" + a + "}").status(),
                () -> ben.put("/api/tasks/" + x + "/position", "{\"afterId\":" + c + "}").status(),
                () -> ana.put("/api/tasks/" + b + "/position", "{\"afterId\":null}").status()));

        assertThat(statuses).containsOnly(204);
        assertThat(deadlocks() - deadlocksBefore).isZero();
        assertEveryTaskOnce(ana, list, 4);
        List<Long> order = ana.order(list);
        assertThat(order.get(0)).isEqualTo(b);
        int at = order.indexOf(x);
        assertThat(order.get(at - 1)).isIn(a, c);
    }

    /** Ticks, deletes and moves of the same list while others reorder: no failure, no lost task. */
    @RepeatedTest(5)
    void reordersRunBesideTicksTrashAndMoves() throws Exception {
        Browser ana = register("ana");
        Browser ben = register("ben");
        long list = ana.createList("Shared");
        long other = ana.createList("Other");
        jdbc.update("insert into list_members (list_id, user_id) values (?, ?)", list, ben.userId());
        List<Long> tasks = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            tasks.add(ana.createTask(list));
        }
        long deadlocksBefore = deadlocks();

        List<Callable<Integer>> work = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            Browser who = i % 2 == 0 ? ana : ben;
            long t = tasks.get(i);
            long after = tasks.get((i + 5) % 12);
            // 204 / 400 (the other task was ticked meanwhile: OTHER_GROUP) / 404 (trashed or moved meanwhile) are all fine.
            work.add(() -> who.put("/api/tasks/" + t + "/position", "{\"afterId\":" + after + "}").status());
        }
        work.add(() -> ben.patch("/api/tasks/" + tasks.get(1) + "", "{\"done\":true}").status());
        work.add(() -> ben.patch("/api/tasks/" + tasks.get(2) + "", "{\"done\":true}").status());
        work.add(() -> ana.delete("/api/tasks/" + tasks.get(3)).status());
        work.add(() -> ana.patch("/api/tasks/" + tasks.get(4), "{\"listId\":" + other + "}").status());
        work.add(() -> ben.post("/api/tasks/bulk", "{\"ids\":[" + tasks.get(5) + "," + tasks.get(6)
                + "],\"action\":\"MOVE\",\"value\":" + other + "}").status());

        List<Integer> statuses = runTogether(work);

        assertThat(statuses).allMatch(s -> s == 200 || s == 204 || s == 400 || s == 404);
        assertThat(deadlocks() - deadlocksBefore).as("InnoDB deadlocks").isZero();
        List<Long> order = ana.order(list);
        assertThat(order).doesNotHaveDuplicates();
        assertThat(order.size() + ana.order(other).size()).isEqualTo(12 - 1);
    }

    // ---- helpers ----

    private void assertEveryTaskOnce(Browser who, long list, int expected) throws Exception {
        List<Long> order = who.order(list);
        assertThat(order).hasSize(expected).doesNotHaveDuplicates();
        Integer inDb = jdbc.queryForObject("select count(*) from tasks where list_id = ? and deleted_at is null",
                Integer.class, list);
        assertThat(inDb).isEqualTo(expected);
        assertThat(order).containsExactlyInAnyOrderElementsOf(jdbc.queryForList(
                "select id from tasks where list_id = ? and deleted_at is null", Long.class, list));
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

    private Browser register(String name) throws Exception {
        int n = SEQ.incrementAndGet();
        Browser b = new Browser().start();
        Resp r = b.post("/api/auth/register", "{\"displayName\":\"Order " + name + n + "\",\"email\":\"order" + n
                + name + "@example.com\",\"password\":\"" + PASSWORD + "\"}");
        assertThat(r.status()).as(r.body()).isEqualTo(201);
        Matcher m = ID.matcher(r.body());
        assertThat(m.find()).isTrue();
        b.id = Long.parseLong(m.group(1));
        return b;
    }

    /** A minimal cookie jar with the XSRF header, like the browser + Angular. Safe to share between threads. */
    class Browser {
        final Map<String, String> cookies = new LinkedHashMap<>();
        long id;

        long userId() {
            return id;
        }

        Browser start() throws Exception {
            send("GET", "/api/auth/me", null);
            assertThat(cookies).containsKey("XSRF-TOKEN");
            return this;
        }

        long createList(String name) throws Exception {
            Resp r = post("/api/lists", "{\"name\":\"" + name + "\"}");
            assertThat(r.status()).as(r.body()).isEqualTo(201);
            Matcher m = ID.matcher(r.body());
            assertThat(m.find()).isTrue();
            return Long.parseLong(m.group(1));
        }

        long createTask(long list) throws Exception {
            Resp r = post("/api/tasks", "{\"title\":\"t\",\"listId\":" + list + "}");
            assertThat(r.status()).as(r.body()).isEqualTo(201);
            Matcher m = ID.matcher(r.body());
            assertThat(m.find()).isTrue();
            return Long.parseLong(m.group(1));
        }

        /** The first page (50 at most) of "My order" in ids. */
        List<Long> order(long list) throws Exception {
            Resp r = get("/api/tasks?list=" + list + "&sort=manual&filter=all");
            assertThat(r.status()).as(r.body()).isEqualTo(200);
            List<Long> ids = new ArrayList<>();
            new tools.jackson.databind.ObjectMapper().readTree(r.body()).get("items")
                    .forEach(t -> ids.add(t.get("id").asLong()));
            return ids;
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

        Resp patch(String path, String body) throws Exception {
            return send("PATCH", path, body);
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
