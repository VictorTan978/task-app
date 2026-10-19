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
import java.util.Set;
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
 * AC13 (specs/sharing-team-lists.md): parallel writes on lists never answer 500, never leave broken data (orphan tags,
 * a link between a task and a tag of another list, a tag whose owner is not its list's owner) and never go over a
 * limit. Every response of 500 or more fails the test at once.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.rate-limit.login-per-ip-per-minute=100000",
        "app.rate-limit.register-per-ip-per-hour=100000"})
class ListConcurrencyIntegrationTest {

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

    /** Moves A→B racing moves B→A, together with tag saves in both lists. */
    @RepeatedTest(8)
    void movesInBothDirectionsAndTagSavesNeverFail() throws Exception {
        Browser u = register();
        long a = u.createList("A");
        long b = u.createList("B");
        List<Callable<Integer>> work = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            long inA = u.createTask(a, "[\"Shared\",\"A only " + i + "\"]");
            long inB = u.createTask(b, "[\"shared\",\"B only " + i + "\"]");
            long editA = u.createTask(a, "[\"Shared\"]");
            long editB = u.createTask(b, "[\"Shared\"]");
            work.add(() -> u.put("/api/tasks/" + inA, "{\"title\":\"t\",\"listId\":" + b + "}"));
            work.add(() -> u.put("/api/tasks/" + inB, "{\"title\":\"t\",\"listId\":" + a + "}"));
            work.add(() -> u.put("/api/tasks/" + editA, "{\"title\":\"t\",\"tags\":[\"Fresh\",\"A only 0\"]}"));
            work.add(() -> u.put("/api/tasks/" + editB, "{\"title\":\"t\",\"tags\":[\"fresh\",\"B only 0\"]}"));
        }

        assertThat(runTogether(work)).as("statuses").containsOnly(200);
        assertThat(count("select count(*) from tasks where list_id in (?, ?)", a, b)).isEqualTo(16);
        assertConsistent();
    }

    /** Edits, moves into/out of, creates and deletes of tasks of a list while the list itself is deleted. */
    @RepeatedTest(8)
    void writesRacingADeleteOfTheirListAre404NeverA500() throws Exception {
        Browser u = register();
        long mine = u.myTasks();
        long doomed = u.createList("Doomed");
        long tagged = u.createTask(doomed, "[\"Work\",\"Home\"]");
        long movedOut = u.createTask(doomed, "[\"Work\"]");
        long movedIn = u.createTask(mine, "[\"Mine\"]");
        long patched = u.createTask(doomed, null);
        long deleted = u.createTask(doomed, "[\"Home\"]");
        long plain = u.createTask(doomed, null);
        List<Callable<Integer>> work = List.of(
                () -> u.delete("/api/lists/" + doomed),
                () -> u.put("/api/tasks/" + tagged, "{\"title\":\"t\",\"tags\":[\"Other\"]}"),
                () -> u.put("/api/tasks/" + movedOut, "{\"title\":\"t\",\"listId\":" + mine + "}"),
                () -> u.put("/api/tasks/" + movedIn, "{\"title\":\"t\",\"listId\":" + doomed + "}"),
                () -> u.patch("/api/tasks/" + patched, "{\"status\":\"DONE\"}"),
                () -> u.delete("/api/tasks/" + deleted),
                () -> u.put("/api/tasks/" + plain, "{\"title\":\"renamed\"}"),
                () -> u.post("/api/tasks", "{\"title\":\"late\",\"listId\":" + doomed + "}"));

        List<Integer> statuses = runTogether(work);

        assertThat(statuses.get(0)).isEqualTo(204);
        assertThat(statuses.subList(1, statuses.size())).allMatch(s -> Set.of(200, 201, 204, 404).contains(s));
        assertThat(count("select count(*) from lists where id = ?", doomed)).isZero();
        assertThat(count("select count(*) from tasks where list_id = ?", doomed)).isZero();
        assertThat(count("select count(*) from tags where list_id = ?", doomed)).isZero();
        // Moved out in time, or deleted with the list; never anywhere else.
        assertThat(count("select count(*) from tasks where id = ? and list_id <> ?", movedOut, mine)).isZero();
        assertConsistent();
    }

    /** Two (or more) creates racing at 49 lists: exactly one wins, the others get LIST_LIMIT. */
    @RepeatedTest(8)
    void createsRacingAtTheLimitNeverMakeMoreThan50() throws Exception {
        Browser u = register();
        for (int i = 2; i <= 49; i++) {
            u.createList("L" + i);
        }
        List<Callable<Integer>> work = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            String name = "Racer " + i;
            work.add(() -> u.post("/api/lists", "{\"name\":\"" + name + "\"}"));
        }

        List<Integer> statuses = runTogether(work);

        assertThat(statuses.stream().filter(s -> s == 201).count()).isEqualTo(1);
        assertThat(statuses).containsOnly(201, 409);
        assertThat(count("select count(*) from lists where owner_id = ?", u.id)).isEqualTo(50);
    }

    /** Account deletion racing the user's own writes: the writes finish first or find the account gone. */
    @RepeatedTest(5)
    void writesRacingAnAccountDeletionNeverFail() throws Exception {
        Browser u = register();
        long home = u.createList("Home");
        long t1 = u.createTask(home, "[\"A\"]");
        long t2 = u.createTask(u.myTasks(), "[\"B\"]");
        long t3 = u.createTask(home, null);
        List<Callable<Integer>> work = List.of(
                () -> u.post("/api/account/delete", "{\"password\":\"" + PASSWORD + "\"}"),
                () -> u.put("/api/tasks/" + t1, "{\"title\":\"t\",\"tags\":[\"C\"]}"),
                () -> u.put("/api/tasks/" + t2, "{\"title\":\"t\",\"listId\":" + home + "}"),
                () -> u.patch("/api/tasks/" + t3, "{\"done\":true}"),
                () -> u.post("/api/lists", "{\"name\":\"More\"}"),
                () -> u.delete("/api/lists/" + home));

        List<Integer> statuses = runTogether(work);

        assertThat(statuses.get(0)).isEqualTo(204);
        assertThat(statuses).allMatch(s -> Set.of(200, 201, 204, 401, 404).contains(s));
        assertThat(count("select count(*) from lists where owner_id = ?", u.id)).isZero();
        assertThat(count("select count(*) from tasks where owner_id = ?", u.id)).isZero();
        assertConsistent();
    }

    // ---- helpers ----

    private void assertConsistent() {
        assertThat(count("select count(*) from tags t where not exists (select 1 from task_tags tt where tt.tag_id = t.id)"))
                .as("orphan tags").isZero();
        assertThat(count("select count(*) from task_tags tt join tasks k on k.id = tt.task_id"
                + " join tags t on t.id = tt.tag_id where k.list_id <> t.list_id")).as("cross-list links").isZero();
        assertThat(count("select count(*) from tags g join lists l on l.id = g.list_id where g.owner_id <> l.owner_id"))
                .as("tag owner = list owner").isZero();
        assertThat(count("select max(c) from (select count(*) c from tags group by list_id) x union all select 0"
                + " order by 1 desc limit 1")).as("tags per list").isLessThanOrEqualTo(200);
    }

    private int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
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
        Browser b = new Browser().start();
        String body = b.send("POST", "/api/auth/register", "{\"displayName\":\"Lists " + n + "\",\"email\":\"lists" + n
                + "@example.com\",\"password\":\"" + PASSWORD + "\"}", 201);
        Matcher m = ID.matcher(body);
        assertThat(m.find()).isTrue();
        b.id = Long.parseLong(m.group(1));
        return b;
    }

    /** A minimal cookie jar with the XSRF header, like the browser + Angular. Safe to share between threads. */
    class Browser {
        final Map<String, String> cookies = new LinkedHashMap<>();
        long id;

        Browser start() throws Exception {
            status("GET", "/api/auth/me", null);
            assertThat(cookies).containsKey("XSRF-TOKEN");
            return this;
        }

        long myTasks() throws Exception {
            Matcher m = ID.matcher(send("GET", "/api/lists", null, 200));
            assertThat(m.find()).isTrue();
            return Long.parseLong(m.group(1));
        }

        long createList(String name) throws Exception {
            Matcher m = ID.matcher(send("POST", "/api/lists", "{\"name\":\"" + name + "\"}", 201));
            assertThat(m.find()).isTrue();
            return Long.parseLong(m.group(1));
        }

        long createTask(long list, String tagsJson) throws Exception {
            Matcher m = ID.matcher(send("POST", "/api/tasks", "{\"title\":\"t\",\"listId\":" + list + "}", 201));
            assertThat(m.find()).isTrue();
            long task = Long.parseLong(m.group(1));
            if (tagsJson != null) {
                send("PUT", "/api/tasks/" + task, "{\"title\":\"t\",\"tags\":" + tagsJson + "}", 200);
            }
            return task;
        }

        int post(String path, String body) throws Exception {
            return status("POST", path, body);
        }

        int put(String path, String body) throws Exception {
            return status("PUT", path, body);
        }

        int patch(String path, String body) throws Exception {
            return status("PATCH", path, body);
        }

        int delete(String path) throws Exception {
            return status("DELETE", path, null);
        }

        String send(String method, String path, String body, int expected) throws Exception {
            HttpResponse<String> r = exchange(method, path, body);
            assertThat(r.statusCode()).as(method + " " + path + " " + r.body()).isEqualTo(expected);
            return r.body();
        }

        int status(String method, String path, String body) throws Exception {
            return exchange(method, path, body).statusCode();
        }

        HttpResponse<String> exchange(String method, String path, String body) throws Exception {
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
            return r;
        }
    }
}
