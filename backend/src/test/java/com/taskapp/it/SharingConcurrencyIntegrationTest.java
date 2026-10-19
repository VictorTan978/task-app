package com.taskapp.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.taskapp.security.ratelimit.MutableClock;

/**
 * AC33 (specs/sharing-team-lists.md): parallel load on shared lists never answers 500, never breaks data and never
 * goes over a limit. Every response of 500 or more fails the test at once. Covers: tag writes from two members of one
 * list; accept racing cancel and expiry; accepts racing at the 20-member limit; removing (or the member leaving) while
 * they write; deleting a list while members write; account deletions (owner or member, self or admin) while others
 * write in the shared list.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.rate-limit.login-per-ip-per-minute=100000",
        "app.rate-limit.register-per-ip-per-hour=100000",
        "app.rate-limit.admin-actions-per-user-per-minute=100000",
        "app.rate-limit.invites-per-user-per-hour=100000",
        "app.rate-limit.invite-emails-per-account-per-hour=100000"})
class SharingConcurrencyIntegrationTest {

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
            .withUrlParam("connectionTimeZone", "UTC")
            .withUrlParam("forceConnectionTimeZoneToSession", "true");

    @TestConfiguration
    static class ClockConfig {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(Instant.now());
        }
    }

    private static final String PASSWORD = "correct-horse";
    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final Pattern ID = Pattern.compile("\"id\":(\\d+)");
    private static final Set<Integer> WRITE_OK = Set.of(200, 201, 204, 404);

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MutableClock clock;

    private final HttpClient http = HttpClient.newHttpClient();

    /** Two members (owner and member) saving tags of tasks in the same list at once. */
    @RepeatedTest(6)
    void tagWritesFromTwoMembersOfOneList() throws Exception {
        Browser ana = register();
        Browser bob = register();
        long home = ana.createList("Home");
        join(ana, bob, home);
        List<Callable<Integer>> work = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            long byAna = ana.createTask(home, "[\"Shared\",\"Ana " + i + "\"]");
            long byBob = bob.createTask(home, "[\"shared\",\"Bob " + i + "\"]");
            int n = i;
            work.add(() -> ana.put("/api/tasks/" + byBob, "{\"title\":\"t\",\"tags\":[\"Fresh\",\"Bob " + n + "\"]}"));
            work.add(() -> bob.put("/api/tasks/" + byAna, "{\"title\":\"t\",\"tags\":[\"fresh\",\"Ana 0\"]}"));
            work.add(() -> bob.patch("/api/tasks/" + byBob, "{\"status\":\"DONE\"}"));
            work.add(() -> ana.put("/api/tasks/" + byAna, "{\"title\":\"t\",\"tags\":[]}"));
        }

        assertThat(runTogether(work)).as("statuses").containsOnly(200);
        assertThat(count("select count(*) from tasks where list_id = ?", home)).isEqualTo(8);
        assertConsistent();
    }

    /** The owner cancels while the invitee accepts: exactly one wins; never a member without having accepted. */
    @RepeatedTest(8)
    void acceptRacingCancel() throws Exception {
        Browser ana = register();
        Browser bob = register();
        long home = ana.createList("Home");
        long id = invite(ana, home, bob);

        List<Integer> statuses = runTogether(List.of(
                () -> bob.post("/api/invitations/" + id + "/accept", null),
                () -> ana.delete("/api/lists/" + home + "/invitations/" + id)));

        assertThat(statuses).isIn(List.of(200, 404), List.of(404, 204));
        boolean member = isMember(home, bob.id);
        assertThat(member).isEqualTo(statuses.get(0) == 200);
        assertThat(count("select count(*) from list_invitations where id = ?", id)).isZero();
        assertConsistent();
    }

    /** The invitation expires (the clock passes expiresAt) while it is accepted: 200 or 404, never both states. */
    @RepeatedTest(8)
    void acceptRacingExpiry() throws Exception {
        Browser ana = register();
        Browser bob = register();
        long home = ana.createList("Home");
        long id = invite(ana, home, bob);
        Instant expires = jdbc.queryForObject("select expires_at from list_invitations where id = ?",
                java.sql.Timestamp.class, id).toInstant();
        // Sessions end after 12 hours: sign in again just before the expiry.
        clock.advance(Duration.between(clock.instant(), expires).minusMillis(5));
        ana.login(ana);
        bob.login(bob);

        List<Integer> statuses = runTogether(List.of(
                () -> bob.post("/api/invitations/" + id + "/accept", null),
                () -> {
                    clock.advance(Duration.ofMillis(10));
                    return 0;
                }));

        assertThat(statuses.get(0)).isIn(200, 404);
        boolean member = isMember(home, bob.id);
        assertThat(member).isEqualTo(statuses.get(0) == 200);
        if (member) {
            assertThat(count("select count(*) from list_invitations where id = ?", id)).isZero();
        }
        assertConsistent();
    }

    /** Four invitees accept at 19 members: exactly one becomes the 20th, the others get MEMBER_LIMIT. */
    @RepeatedTest(8)
    void acceptsRacingAtTheTwentyMemberLimit() throws Exception {
        Browser ana = register();
        long team = ana.createList("Team");
        for (int i = 0; i < 18; i++) {
            String s = UUID.randomUUID().toString().substring(0, 12);
            jdbc.update("insert into users (email, password_hash, display_name) values (?, 'h', ?)",
                    "m-" + s + "@example.com", "Member " + s);
            jdbc.update("insert into list_members (list_id, user_id) select ?, id from users where email = ?", team,
                    "m-" + s + "@example.com");
        }
        List<Callable<Integer>> work = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Browser b = register();
            long id = invite(ana, team, b);
            work.add(() -> b.post("/api/invitations/" + id + "/accept", null));
        }

        List<Integer> statuses = runTogether(work);

        assertThat(statuses.stream().filter(s -> s == 200).count()).isEqualTo(1);
        assertThat(statuses).containsOnly(200, 409);
        assertThat(count("select count(*) from list_members where list_id = ?", team)).isEqualTo(20);
        assertConsistent();
    }

    /** The owner removes a member, and another member leaves, while both write: finish first, or 404 after. */
    @RepeatedTest(8)
    void removingOrLeavingWhileWritingIs404NeverA500() throws Exception {
        Browser ana = register();
        Browser bob = register();
        Browser carol = register();
        long home = ana.createList("Home");
        long bobsMine = bob.myTasks();
        join(ana, bob, home);
        join(ana, carol, home);
        long t1 = ana.createTask(home, "[\"A\"]");
        long t2 = bob.createTask(home, "[\"B\"]");
        long t3 = carol.createTask(home, null);
        long t4 = ana.createTask(home, null);
        List<Callable<Integer>> work = List.of(
                () -> ana.delete("/api/lists/" + home + "/members/" + bob.id),
                () -> carol.post("/api/lists/" + home + "/leave", null),
                () -> bob.put("/api/tasks/" + t1, "{\"title\":\"t\",\"tags\":[\"C\"]}"),
                () -> bob.patch("/api/tasks/" + t2, "{\"done\":true}"),
                () -> bob.put("/api/tasks/" + t4, "{\"title\":\"t\",\"listId\":" + bobsMine + "}"),
                () -> bob.post("/api/tasks", "{\"title\":\"late\",\"listId\":" + home + "}"),
                () -> carol.put("/api/tasks/" + t3, "{\"title\":\"t\",\"tags\":[\"D\"]}"),
                () -> carol.delete("/api/tasks/" + t2),
                () -> bob.status("GET", "/api/tasks?list=" + home, null));

        List<Integer> statuses = runTogether(work);

        assertThat(statuses.subList(0, 2)).containsExactly(204, 204);
        assertThat(statuses.subList(2, statuses.size())).allMatch(WRITE_OK::contains);
        assertThat(count("select count(*) from list_members where list_id = ?", home)).isEqualTo(1);
        assertThat(bob.status("GET", "/api/lists/" + home, null)).isEqualTo(404);
        assertThat(carol.status("GET", "/api/tasks/" + t3, null)).isEqualTo(404);
        assertConsistent();
    }

    /** The owner deletes the list while members write in it. */
    @RepeatedTest(8)
    void deletingAListWhileMembersWrite() throws Exception {
        Browser ana = register();
        Browser bob = register();
        Browser carol = register();
        long doomed = ana.createList("Doomed");
        join(ana, bob, doomed);
        join(ana, carol, doomed);
        long bobsMine = bob.myTasks();
        long t1 = bob.createTask(doomed, "[\"Work\"]");
        long t2 = carol.createTask(doomed, "[\"Home\"]");
        long t3 = ana.createTask(doomed, null);
        long movedIn = bob.createTask(bobsMine, "[\"Mine\"]");
        List<Callable<Integer>> work = List.of(
                () -> ana.delete("/api/lists/" + doomed),
                () -> bob.put("/api/tasks/" + t1, "{\"title\":\"t\",\"tags\":[\"Other\"]}"),
                () -> carol.patch("/api/tasks/" + t2, "{\"status\":\"DONE\"}"),
                () -> bob.put("/api/tasks/" + t3, "{\"title\":\"t\",\"listId\":" + bobsMine + "}"),
                () -> bob.put("/api/tasks/" + movedIn, "{\"title\":\"t\",\"listId\":" + doomed + "}"),
                () -> carol.post("/api/tasks", "{\"title\":\"late\",\"listId\":" + doomed + "}"),
                () -> carol.delete("/api/tasks/" + t2),
                () -> bob.post("/api/lists/" + doomed + "/leave", null));

        List<Integer> statuses = runTogether(work);

        assertThat(statuses.get(0)).isEqualTo(204);
        assertThat(statuses.subList(1, statuses.size())).allMatch(WRITE_OK::contains);
        assertThat(count("select count(*) from lists where id = ?", doomed)).isZero();
        assertThat(count("select count(*) from tasks where list_id = ?", doomed)).isZero();
        assertThat(count("select count(*) from list_members where list_id = ?", doomed)).isZero();
        assertConsistent();
    }

    /**
     * Account deletions while others write in the shared list: the owner deletes their account (the list goes) while
     * members write in it; a member deletes theirs (self, or an admin does it) while the owner edits the tasks that
     * member added (the cascade sets their owner to NULL).
     */
    @RepeatedTest(6)
    void accountDeletionsWhileOthersWriteInTheSharedList() throws Exception {
        Browser admin = register();
        jdbc.update("update users set role = 'SUPER_ADMIN' where id = ?", admin.id);
        admin.login(admin);
        for (int round = 0; round < 3; round++) {
            Browser ana = register();
            Browser bob = register();
            Browser carol = register();
            long home = ana.createList("Home");
            join(ana, bob, home);
            join(ana, carol, home);
            long bobsList = bob.createList("Bob's");
            join(bob, ana, bobsList);
            long byBob = bob.createTask(home, "[\"B\"]");
            long byBob2 = bob.createTask(home, null);
            long byCarol = carol.createTask(home, "[\"C\"]");
            long anaInBobs = ana.createTask(bobsList, "[\"X\"]");
            List<Callable<Integer>> work = new ArrayList<>();
            if (round == 0) {
                // The owner deletes their account while members write in the list.
                work.add(() -> ana.post("/api/account/delete", "{\"password\":\"" + PASSWORD + "\"}"));
                work.add(() -> bob.put("/api/tasks/" + byCarol, "{\"title\":\"t\",\"tags\":[\"New\"]}"));
                work.add(() -> carol.patch("/api/tasks/" + byBob, "{\"done\":true}"));
                work.add(() -> bob.post("/api/tasks", "{\"title\":\"late\",\"listId\":" + home + "}"));
                work.add(() -> bob.put("/api/tasks/" + byBob2, "{\"title\":\"t\",\"listId\":" + bobsList + "}"));
                work.add(() -> bob.put("/api/tasks/" + anaInBobs, "{\"title\":\"t\",\"tags\":[\"Y\"]}"));
            } else {
                // A member deletes their account (round 1) or an admin does (round 2) while the others edit,
                // tag, move and delete the tasks that member added.
                work.add(round == 1 ? () -> bob.post("/api/account/delete", "{\"password\":\"" + PASSWORD + "\"}")
                        : () -> admin.delete("/api/admin/users/" + bob.id));
                work.add(() -> ana.put("/api/tasks/" + byBob, "{\"title\":\"t\",\"tags\":[\"New\"]}"));
                work.add(() -> carol.patch("/api/tasks/" + byBob2, "{\"status\":\"IN_PROGRESS\"}"));
                work.add(() -> carol.put("/api/tasks/" + byCarol, "{\"title\":\"t\",\"tags\":[\"B\"]}"));
                work.add(() -> ana.put("/api/tasks/" + anaInBobs, "{\"title\":\"t\",\"listId\":" + home + "}"));
                work.add(() -> ana.post("/api/tasks", "{\"title\":\"late\",\"listId\":" + bobsList + "}"));
                work.add(() -> bob.put("/api/tasks/" + byBob2, "{\"title\":\"t\",\"tags\":[\"Z\"]}"));
            }

            List<Integer> statuses = runTogether(work);

            assertThat(statuses.get(0)).as("delete").isEqualTo(204);
            assertThat(statuses.subList(1, statuses.size()))
                    .allMatch(s -> Set.of(200, 201, 204, 401, 404).contains(s));
            if (round == 0) {
                assertThat(count("select count(*) from lists where id = ?", home)).isZero();
                assertThat(count("select count(*) from tasks where list_id = ?", home)).isZero();
            } else {
                assertThat(count("select count(*) from users where id = ?", bob.id)).isZero();
                assertThat(count("select count(*) from tasks where id in (?, ?)", byBob, byBob2)).isEqualTo(2);
                assertThat(count("select count(*) from tasks where owner_id = ?", bob.id)).isZero();
                assertThat(count("select count(*) from lists where id = ?", bobsList)).isZero();
                assertThat(count("select count(*) from tags where list_id = ? and name = 'B'", home)).isEqualTo(1);
            }
            assertConsistent();
        }
    }

    // ---- helpers ----

    private void assertConsistent() {
        assertThat(count("select count(*) from tags t"
                + " where not exists (select 1 from task_tags tt where tt.tag_id = t.id)"))
                .as("orphan tags").isZero();
        assertThat(count("select count(*) from task_tags tt join tasks k on k.id = tt.task_id"
                + " join tags t on t.id = tt.tag_id where k.list_id <> t.list_id")).as("cross-list links").isZero();
        assertThat(count("select count(*) from tags g join lists l on l.id = g.list_id where g.owner_id <> l.owner_id"))
                .as("tag owner = list owner").isZero();
        assertThat(count("select count(*) from lists l where not exists (select 1 from list_members m"
                + " where m.list_id = l.id and m.user_id = l.owner_id)")).as("owner is a member").isZero();
        assertThat(count("select coalesce(max(c), 0) from (select count(*) c from list_members group by list_id) x"))
                .as("members per list").isLessThanOrEqualTo(20);
        assertThat(count("select coalesce(max(c), 0) from (select count(*) c from list_invitations"
                + " group by list_id) x")).as("invitations per list").isLessThanOrEqualTo(20);
        assertThat(count("select count(*) from lists where personal and id in (select list_id from list_members m"
                + " join lists l2 on l2.id = m.list_id where m.user_id <> l2.owner_id)")).as("My tasks shared")
                .isZero();
    }

    private boolean isMember(long listId, long userId) {
        return count("select count(*) from list_members where list_id = ? and user_id = ?", listId, userId) == 1;
    }

    private int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    private long invite(Browser owner, long listId, Browser invitee) throws Exception {
        Matcher m = ID.matcher(owner.send("POST", "/api/lists/" + listId + "/invitations",
                "{\"email\":\"" + invitee.email + "\"}", 201));
        assertThat(m.find()).isTrue();
        return Long.parseLong(m.group(1));
    }

    private void join(Browser owner, Browser member, long listId) throws Exception {
        long id = invite(owner, listId, member);
        member.send("POST", "/api/invitations/" + id + "/accept", null, 200);
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

    /** Registers a user with a verified email (so they can accept invitations). */
    private Browser register() throws Exception {
        int n = SEQ.incrementAndGet();
        Browser b = new Browser().start();
        b.email = "share" + n + "-" + UUID.randomUUID().toString().substring(0, 6) + "@example.com";
        Matcher m = ID.matcher(b.send("POST", "/api/auth/register", "{\"displayName\":\"Share " + n + " "
                + b.email.substring(b.email.indexOf('-') + 1, b.email.indexOf('@')) + "\",\"email\":\"" + b.email
                + "\",\"password\":\"" + PASSWORD + "\"}", 201));
        assertThat(m.find()).isTrue();
        b.id = Long.parseLong(m.group(1));
        jdbc.update("update users set email_verified_at = now(6) where id = ?", b.id);
        return b;
    }

    /** A minimal cookie jar with the XSRF header, like the browser + Angular. Safe to share between threads. */
    class Browser {
        final Map<String, String> cookies = new LinkedHashMap<>();
        long id;
        String email;

        Browser start() throws Exception {
            status("GET", "/api/auth/me", null);
            assertThat(cookies).containsKey("XSRF-TOKEN");
            return this;
        }

        void login(Browser account) throws Exception {
            send("POST", "/api/auth/login", "{\"email\":\"" + account.email + "\",\"password\":\"" + PASSWORD
                    + "\"}", 200);
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
