package com.taskapp.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

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

/** Task API end-to-end over real HTTP against MySQL 8.4 with the Flyway migrations applied. */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        // Many registrations/logins from 127.0.0.1; the per-email failure limit keeps its default.
        "app.rate-limit.login-per-ip-per-minute=10000",
        "app.rate-limit.register-per-ip-per-hour=10000"})
class TaskIntegrationTest {

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
            .withUrlParam("connectionTimeZone", "UTC")
            .withUrlParam("forceConnectionTimeZoneToSession", "true");

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    private final HttpClient http = HttpClient.newHttpClient();

    /** Minimal browser: stores cookies, echoes XSRF-TOKEN in X-XSRF-TOKEN like Angular. */
    class Browser {
        final Map<String, String> cookies = new LinkedHashMap<>();
        boolean sendCsrfHeader = true;

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

        Resp send(String method, String path, String body) throws IOException, InterruptedException {
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
            if (!method.equals("GET") && sendCsrfHeader && cookies.containsKey("XSRF-TOKEN")) {
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
            return new Resp(r.statusCode(), r.headers().firstValue("Content-Type").orElse(""),
                    r.headers().firstValue("Location").orElse(null), r.body());
        }

        Browser start() throws Exception {
            get("/api/auth/me");
            assertThat(cookies).containsKey("XSRF-TOKEN");
            return this;
        }

        /** Registers a fresh user (unique email and display name, both must be unique) and stays signed in. */
        Browser signUp(String name) throws Exception {
            start();
            String suffix = UUID.randomUUID().toString();
            String email = name.toLowerCase() + "-" + suffix + "@example.com";
            String displayName = name + " " + suffix.substring(0, 8);
            Resp r = post("/api/auth/register",
                    "{\"displayName\":\"" + displayName + "\",\"email\":\"" + email + "\",\"password\":\"correct-horse\"}");
            assertThat(r.status()).isEqualTo(201);
            return this;
        }

        JsonNode create(String title) throws Exception {
            Resp r = post("/api/tasks", "{\"title\":\"" + title + "\"}");
            assertThat(r.status()).isEqualTo(201);
            return r.json();
        }
    }

    record Resp(int status, String contentType, String location, String body) {
        JsonNode json() throws IOException {
            return new ObjectMapper().readTree(body);
        }
    }

    private static List<String> titles(Resp list) throws IOException {
        List<String> titles = new ArrayList<>();
        list.json().forEach(t -> titles.add(t.get("title").asText()));
        return titles;
    }

    @Test
    void createGetAndContractShape() throws Exception {
        Browser alice = new Browser().signUp("Alice");

        Resp created = alice.post("/api/tasks", """
                {"title":"  Buy milk ","description":"   ","dueDate":"2026-10-10","done":true,"ownerId":1}""");

        assertThat(created.status()).isEqualTo(201);
        assertThat(created.contentType()).startsWith("application/json");
        JsonNode task = created.json();
        long id = task.get("id").asLong();
        assertThat(created.location()).isEqualTo("/api/tasks/" + id);
        assertThat(task.get("title").asText()).isEqualTo("Buy milk");
        assertThat(task.get("description").isNull()).isTrue();
        assertThat(task.get("done").asBoolean()).isFalse();
        assertThat(task.get("dueDate").asText()).isEqualTo("2026-10-10");
        assertThat(task.get("createdAt").asText()).endsWith("Z");
        assertThat(task.get("updatedAt")).isEqualTo(task.get("createdAt"));
        assertThat(task.get("priority").asText()).isEqualTo("NONE");
        assertThat(task.get("status").asText()).isEqualTo("TODO");
        assertThat(task.get("tags").isArray()).isTrue();
        assertThat(task.get("tags")).isEmpty();
        assertThat(task.has("ownerId")).isFalse();
        assertThat(task.size()).isEqualTo(10);

        Resp fetched = alice.get("/api/tasks/" + id);
        assertThat(fetched.status()).isEqualTo(200);
        assertThat(fetched.json()).isEqualTo(task);
        assertThat(jdbc.queryForObject("select description from tasks where id = ?", String.class, id)).isNull();
    }

    @Test
    void validationAndMalformedInput() throws Exception {
        Browser alice = new Browser().signUp("Alice");

        Resp blank = alice.post("/api/tasks", "{\"title\":\"   \"}");
        assertThat(blank.status()).isEqualTo(400);
        assertThat(blank.contentType()).startsWith("application/problem+json");
        assertThat(blank.json().get("code").asText()).isEqualTo("VALIDATION_FAILED");
        assertThat(blank.json().get("errors").get(0).get("message").asText()).isEqualTo("Title is required");

        Resp tooLong = alice.post("/api/tasks", "{\"title\":\"" + "t".repeat(201) + "\"}");
        assertThat(tooLong.status()).isEqualTo(400);
        assertThat(tooLong.json().get("errors").get(0).get("code").asText()).isEqualTo("TOO_LONG");

        Resp badDate = alice.post("/api/tasks", "{\"title\":\"x\",\"dueDate\":\"2026-02-30\"}");
        assertThat(badDate.status()).isEqualTo(400);
        assertThat(badDate.json().get("code").asText()).isEqualTo("MALFORMED_REQUEST");
        assertThat(badDate.json().has("errors")).isFalse();

        Resp badId = alice.get("/api/tasks/abc");
        assertThat(badId.status()).isEqualTo(400);
        assertThat(badId.json().get("code").asText()).isEqualTo("MALFORMED_REQUEST");
        assertThat(badId.json().get("detail").asText()).isEqualTo("Failed to convert 'id' with value 'abc'");

        assertThat(alice.get("/api/tasks").json()).isEmpty();
    }

    @Test
    void sortingAndFiltering() throws Exception {
        Browser alice = new Browser().signUp("Alice");
        long t1 = alice.create("t1").get("id").asLong();
        alice.create("t2");
        long t3 = alice.create("t3").get("id").asLong();
        alice.create("t4");
        assertThat(alice.patch("/api/tasks/" + t1, "{\"done\":true}").status()).isEqualTo(200);
        assertThat(alice.patch("/api/tasks/" + t3, "{\"done\":true}").status()).isEqualTo(200);

        assertThat(titles(alice.get("/api/tasks"))).containsExactly("t4", "t2", "t3", "t1");
        assertThat(titles(alice.get("/api/tasks?filter=active"))).containsExactly("t4", "t2");
        assertThat(titles(alice.get("/api/tasks?filter=done"))).containsExactly("t3", "t1");
        for (String unknown : List.of("all", "", "foo", "Done")) {
            assertThat(titles(alice.get("/api/tasks?filter=" + unknown))).containsExactly("t4", "t2", "t3", "t1");
        }

        // Unticking moves it back into the active group at its createdAt position.
        alice.patch("/api/tasks/" + t3, "{\"done\":false}");
        assertThat(titles(alice.get("/api/tasks"))).containsExactly("t4", "t3", "t2", "t1");
    }

    @Test
    void putReplacesAndClearsFieldsAndPatchSetsDone() throws Exception {
        Browser alice = new Browser().signUp("Alice");
        JsonNode created = alice.post("/api/tasks",
                "{\"title\":\"Buy milk\",\"description\":\"2%\",\"dueDate\":\"2026-10-10\"}").json();
        String path = "/api/tasks/" + created.get("id").asLong();
        Instant createdAt = Instant.parse(created.get("createdAt").asText());

        // null description and omitted dueDate both clear the field.
        Resp put = alice.put(path, "{\"title\":\" Buy oat milk \",\"description\":null,\"done\":true}");
        assertThat(put.status()).isEqualTo(200);
        JsonNode replaced = put.json();
        assertThat(replaced.get("title").asText()).isEqualTo("Buy oat milk");
        assertThat(replaced.get("description").isNull()).isTrue();
        assertThat(replaced.get("dueDate").isNull()).isTrue();
        assertThat(replaced.get("done").asBoolean()).isTrue();
        assertThat(replaced.get("createdAt")).isEqualTo(created.get("createdAt"));
        Instant updatedAfterPut = Instant.parse(replaced.get("updatedAt").asText());
        assertThat(updatedAfterPut).isAfter(createdAt);
        assertThat(alice.get(path).json()).isEqualTo(replaced);

        Resp put2 = alice.put(path, "{\"title\":\"x\",\"description\":\"  \",\"dueDate\":\"2020-01-01\",\"done\":false}");
        assertThat(put2.json().get("description").isNull()).isTrue();
        assertThat(put2.json().get("dueDate").asText()).isEqualTo("2020-01-01");

        // 0.10.0: done is optional; missing leaves the status (and done) unchanged.
        Resp missingDone = alice.put(path, "{\"title\":\"x\"}");
        assertThat(missingDone.status()).isEqualTo(200);
        assertThat(missingDone.json().get("done").asBoolean()).isFalse();
        assertThat(missingDone.json().get("status").asText()).isEqualTo("TODO");

        // PATCH sets (does not toggle): repeating it keeps done=true, and updatedAt moves every time.
        JsonNode p1 = alice.patch(path, "{\"done\":true}").json();
        JsonNode p2 = alice.patch(path, "{\"done\":true}").json();
        assertThat(p1.get("done").asBoolean()).isTrue();
        assertThat(p2.get("done").asBoolean()).isTrue();
        assertThat(Instant.parse(p2.get("updatedAt").asText())).isAfterOrEqualTo(Instant.parse(p1.get("updatedAt").asText()));
        assertThat(Instant.parse(p1.get("updatedAt").asText()))
                .isAfter(Instant.parse(put2.json().get("updatedAt").asText()));
        assertThat(p2.get("title").asText()).isEqualTo("x");
        assertThat(alice.get(path).json().get("done").asBoolean()).isTrue();
    }

    @Test
    void deleteIsPermanent() throws Exception {
        Browser alice = new Browser().signUp("Alice");
        String path = "/api/tasks/" + alice.create("bye").get("id").asLong();

        Resp deleted = alice.delete(path);
        assertThat(deleted.status()).isEqualTo(204);
        assertThat(deleted.body()).isEmpty();

        assertThat(alice.get(path).status()).isEqualTo(404);
        Resp again = alice.delete(path);
        assertThat(again.status()).isEqualTo(404);
        assertThat(again.json().get("code").asText()).isEqualTo("NOT_FOUND");
        assertThat(alice.get("/api/tasks").json()).isEmpty();
    }

    /** AC12 + AC13. */
    @Test
    void usersOnlySeeAndTouchTheirOwnTasks() throws Exception {
        Browser alice = new Browser().signUp("Alice");
        Browser bob = new Browser().signUp("Bob");
        alice.create("alice's");
        long bobsId = bob.create("bob's").get("id").asLong();
        String bobsPath = "/api/tasks/" + bobsId;
        JsonNode before = bob.get(bobsPath).json();

        assertThat(titles(alice.get("/api/tasks"))).containsExactly("alice's");
        assertThat(titles(bob.get("/api/tasks"))).containsExactly("bob's");

        Resp missing = alice.get("/api/tasks/" + (bobsId + 100_000));
        List<Resp> attempts = List.of(
                alice.get(bobsPath),
                alice.put(bobsPath, "{\"title\":\"hacked\",\"description\":null,\"dueDate\":null,\"done\":true}"),
                alice.patch(bobsPath, "{\"done\":true}"),
                alice.delete(bobsPath));
        for (Resp r : attempts) {
            assertThat(r.status()).isEqualTo(404);
            assertThat(r.contentType()).startsWith("application/problem+json");
            JsonNode body = r.json();
            assertThat(body.get("code").asText()).isEqualTo("NOT_FOUND");
            assertThat(body.get("detail").asText()).isEqualTo("Task not found");
            assertThat(body.get("instance").asText()).isEqualTo(bobsPath);
        }
        // Same body as a task that doesn't exist at all (apart from the path).
        assertThat(missing.json().get("detail")).isEqualTo(attempts.get(0).json().get("detail"));
        assertThat(missing.json().get("code")).isEqualTo(attempts.get(0).json().get("code"));

        assertThat(bob.get(bobsPath).json()).isEqualTo(before);
        assertThat(jdbc.queryForObject("select count(*) from tasks where id = ?", Integer.class, bobsId)).isEqualTo(1);
    }

    @Test
    void signedOutCallsAre401() throws Exception {
        Browser alice = new Browser().signUp("Alice");
        String path = "/api/tasks/" + alice.create("mine").get("id").asLong();
        assertThat(alice.post("/api/auth/logout", null).status()).isEqualTo(204);

        for (Resp r : List.of(
                alice.get("/api/tasks"),
                alice.get(path),
                alice.post("/api/tasks", "{\"title\":\"x\"}"),
                alice.put(path, "{\"title\":\"x\",\"done\":true}"),
                alice.patch(path, "{\"done\":true}"),
                alice.delete(path))) {
            assertThat(r.status()).isEqualTo(401);
            assertThat(r.contentType()).startsWith("application/problem+json");
            assertThat(r.json().get("code").asText()).isEqualTo("UNAUTHENTICATED");
        }
        assertThat(jdbc.queryForObject("select count(*) from tasks where title = 'x'", Integer.class)).isZero();
    }

    @Test
    void stateChangingCallsWithoutCsrfAre403AndChangeNothing() throws Exception {
        Browser alice = new Browser().signUp("Alice");
        long id = alice.create("keep me").get("id").asLong();
        String path = "/api/tasks/" + id;
        JsonNode before = alice.get(path).json();
        alice.sendCsrfHeader = false;

        for (Resp r : List.of(
                alice.post("/api/tasks", "{\"title\":\"sneaky\"}"),
                alice.put(path, "{\"title\":\"changed\",\"done\":true}"),
                alice.patch(path, "{\"done\":true}"),
                alice.delete(path))) {
            assertThat(r.status()).isEqualTo(403);
            assertThat(r.contentType()).startsWith("application/problem+json");
            assertThat(r.json().get("code").asText()).isEqualTo("CSRF_INVALID");
        }

        assertThat(alice.get(path).json()).isEqualTo(before);
        assertThat(jdbc.queryForObject("select count(*) from tasks where title = 'sneaky'", Integer.class)).isZero();
    }

    // ---- 0.10.0: priority, status, tags (specs/tasks-priority-status-tags.md) ----

    private static String q(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static List<String> texts(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asText()));
        return out;
    }

    private static List<String> tagNames(Resp tags) throws IOException {
        List<String> out = new ArrayList<>();
        tags.json().forEach(t -> {
            assertThat(t.size()).isEqualTo(1);
            out.add(t.get("name").asText());
        });
        return out;
    }

    private static String tagsBody(String title, String tagsArray) {
        return "{\"title\":\"" + title + "\",\"description\":null,\"dueDate\":null,\"tags\":" + tagsArray + "}";
    }

    private long dbCount(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    /** AC1, AC2, AC3, AC4, AC5, AC16. */
    @Test
    void priorityStatusAndDoneMirrorRoundTrip() throws Exception {
        Browser alice = new Browser().signUp("Alice");
        long id = alice.create("Plan trip").get("id").asLong();
        String path = "/api/tasks/" + id;

        JsonNode put = alice.put(path, """
                {"title":"Plan trip","description":null,"dueDate":"2026-01-01","priority":"HIGH",
                 "status":"IN_PROGRESS","tags":[" Home ","Errands","errands"]}""").json();
        assertThat(put.get("priority").asText()).isEqualTo("HIGH");
        assertThat(put.get("status").asText()).isEqualTo("IN_PROGRESS");
        assertThat(put.get("done").asBoolean()).isFalse();
        assertThat(texts(put.get("tags"))).containsExactly("Errands", "Home");
        assertThat(alice.get(path).json()).isEqualTo(put);

        // An old client's PUT (no new fields) changes nothing of them.
        JsonNode old = alice.put(path, "{\"title\":\"Plan trip!\",\"description\":null,\"dueDate\":null}").json();
        assertThat(old.get("title").asText()).isEqualTo("Plan trip!");
        assertThat(old.get("priority").asText()).isEqualTo("HIGH");
        assertThat(old.get("status").asText()).isEqualTo("IN_PROGRESS");
        assertThat(texts(old.get("tags"))).containsExactly("Errands", "Home");
        JsonNode nulls = alice.put(path, "{\"title\":\"Plan trip!\",\"priority\":null,\"status\":null,\"done\":null,"
                + "\"tags\":null}").json();
        assertThat(nulls.get("priority").asText()).isEqualTo("HIGH");
        assertThat(texts(nulls.get("tags"))).containsExactly("Errands", "Home");

        // done:false alone leaves IN_PROGRESS; ticking from IN_PROGRESS → DONE; unticking → TODO.
        assertThat(alice.patch(path, "{\"done\":false}").json().get("status").asText()).isEqualTo("IN_PROGRESS");
        JsonNode ticked = alice.patch(path, "{\"done\":true}").json();
        assertThat(ticked.get("status").asText()).isEqualTo("DONE");
        assertThat(ticked.get("done").asBoolean()).isTrue();
        assertThat(texts(ticked.get("tags"))).containsExactly("Errands", "Home");
        JsonNode unticked = alice.patch(path, "{\"done\":false}").json();
        assertThat(unticked.get("status").asText()).isEqualTo("TODO");
        assertThat(unticked.get("done").asBoolean()).isFalse();

        // PATCH {status}, and PUT status from DONE back to IN_PROGRESS.
        assertThat(alice.patch(path, "{\"status\":\"DONE\"}").json().get("done").asBoolean()).isTrue();
        JsonNode reopened = alice.put(path, "{\"title\":\"Plan trip!\",\"status\":\"IN_PROGRESS\"}").json();
        assertThat(reopened.get("status").asText()).isEqualTo("IN_PROGRESS");
        assertThat(reopened.get("done").asBoolean()).isFalse();
        assertThat(alice.put(path, "{\"title\":\"Plan trip!\",\"done\":true}").json().get("status").asText())
                .isEqualTo("DONE");
        assertThat(alice.put(path, "{\"title\":\"Plan trip!\",\"priority\":\"NONE\"}").json().get("priority").asText())
                .isEqualTo("NONE");

        // Conflicts and bad values are rejected and change nothing.
        JsonNode before = alice.get(path).json();
        Resp conflict = alice.patch(path, "{\"status\":\"TODO\",\"done\":true}");
        assertThat(conflict.status()).isEqualTo(400);
        assertThat(conflict.json().get("errors").get(0).get("code").asText()).isEqualTo("STATUS_CONFLICT");
        Resp putConflict = alice.put(path, "{\"title\":\"changed\",\"status\":\"IN_PROGRESS\",\"done\":true}");
        assertThat(putConflict.json().get("errors").get(0).get("field").asText()).isEqualTo("status");
        Resp neither = alice.patch(path, "{}");
        assertThat(neither.json().get("errors").get(0).get("message").asText()).isEqualTo("Done or status is required");
        assertThat(alice.put(path, "{\"title\":\"changed\",\"priority\":\"high\"}").json().get("code").asText())
                .isEqualTo("MALFORMED_REQUEST");
        assertThat(alice.patch(path, "{\"status\":\"done\"}").json().get("code").asText()).isEqualTo("MALFORMED_REQUEST");
        assertThat(alice.put(path, "{\"title\":\"changed\",\"tags\":[1]}").json().get("code").asText())
                .isEqualTo("MALFORMED_REQUEST");
        assertThat(alice.get(path).json()).isEqualTo(before);

        assertThat(dbCount("select count(*) from tasks where (status = 'DONE') <> done")).isZero();
    }

    /** AC7, AC8, AC12. */
    @Test
    void filtersAndTagFilterCombine() throws Exception {
        Browser alice = new Browser().signUp("Alice");
        long todo = alice.create("todo").get("id").asLong();
        long progress = alice.create("progress").get("id").asLong();
        long done = alice.create("done").get("id").asLong();
        long progressHigh = alice.create("progress high").get("id").asLong();
        alice.put("/api/tasks/" + todo, tagsBody("todo", "[\"Work\"]"));
        alice.put("/api/tasks/" + progress, "{\"title\":\"progress\",\"status\":\"IN_PROGRESS\",\"tags\":[\"work\",\"Home\"]}");
        alice.put("/api/tasks/" + done, "{\"title\":\"done\",\"status\":\"DONE\",\"tags\":[\"WÖRK\"]}");
        alice.put("/api/tasks/" + progressHigh, "{\"title\":\"progress high\",\"status\":\"IN_PROGRESS\",\"priority\":\"HIGH\"}");

        // Priority and IN_PROGRESS never change the order: not done first, newest first.
        assertThat(titles(alice.get("/api/tasks"))).containsExactly("progress high", "progress", "todo", "done");
        assertThat(titles(alice.get("/api/tasks?filter=active"))).containsExactly("progress high", "progress", "todo");
        assertThat(titles(alice.get("/api/tasks?filter=in_progress"))).containsExactly("progress high", "progress");
        assertThat(titles(alice.get("/api/tasks?filter=done"))).containsExactly("done");
        assertThat(titles(alice.get("/api/tasks?filter=IN_PROGRESS"))).hasSize(4);

        assertThat(titles(alice.get("/api/tasks?tag=Work"))).containsExactly("progress", "todo", "done");
        assertThat(titles(alice.get("/api/tasks?tag=" + q(" wörk ")))).containsExactly("progress", "todo", "done");
        assertThat(titles(alice.get("/api/tasks?filter=active&tag=WORK"))).containsExactly("progress", "todo");
        assertThat(titles(alice.get("/api/tasks?filter=in_progress&tag=work"))).containsExactly("progress");
        assertThat(titles(alice.get("/api/tasks?filter=done&tag=work"))).containsExactly("done");
        assertThat(titles(alice.get("/api/tasks?filter=done&tag=home"))).isEmpty();
        // Tasks in a tag-filtered list still carry all their tags.
        assertThat(texts(alice.get("/api/tasks?tag=home").json().get(0).get("tags"))).containsExactly("Home", "Work");

        // Unknown or invalid tag → []; missing/empty/blank → no tag filter; repeated → first one.
        for (String none : List.of("Nope", q("a,b"), q("Work,"), q("Wo\trk"), "x".repeat(31), "Wor", q("Work  x"))) {
            Resp r = alice.get("/api/tasks?tag=" + none);
            assertThat(r.status()).as(none).isEqualTo(200);
            assertThat(r.json()).as(none).isEmpty();
        }
        for (String blank : List.of("", "%20", q("   "))) {
            assertThat(titles(alice.get("/api/tasks?filter=in_progress&tag=" + blank))).as(blank)
                    .containsExactly("progress high", "progress");
        }
        assertThat(titles(alice.get("/api/tasks?tag=Home&tag=Nope"))).containsExactly("progress");
        assertThat(alice.get("/api/tasks?tag=Nope&tag=Home").json()).isEmpty();
        assertThat(alice.get("/api/tasks?tag=Home,Work").json()).isEmpty();
    }

    /** AC9, AC10, AC11, AC13. */
    @Test
    void tagsAreNormalisedDedupedReusedAndDeletedWhenUnused() throws Exception {
        Browser alice = new Browser().signUp("Alice");
        long first = alice.create("first").get("id").asLong();
        long second = alice.create("second").get("id").asLong();
        assertThat(alice.get("/api/tags").json()).isEmpty();

        JsonNode t1 = alice.put("/api/tasks/" + first,
                tagsBody("first", "[\"  Deep \\u00a0\\u2003 Work \",\"Old\",\"\\ud83d\\ude00 fun\"]")).json();
        assertThat(texts(t1.get("tags"))).containsExactly("😀 fun", "Deep Work", "Old");
        assertThat(dbCount("select count(*) from tags where name = 'Deep Work' collate utf8mb4_0900_bin")).isEqualTo(1);

        // Other spellings reuse the first one; duplicates in one request count once.
        JsonNode t2 = alice.put("/api/tasks/" + second,
                tagsBody("second", "[\"deep work\",\"DÉEP WORK\",\"Errands\",\"errands\"]")).json();
        assertThat(texts(t2.get("tags"))).containsExactly("Deep Work", "Errands");
        assertThat(tagNames(alice.get("/api/tags"))).containsExactly("😀 fun", "Deep Work", "Errands", "Old");

        // Removing "Old" from its only task deletes it; "Deep Work" is still used by the second task.
        alice.put("/api/tasks/" + first, tagsBody("first", "[]"));
        assertThat(tagNames(alice.get("/api/tags"))).containsExactly("Deep Work", "Errands");
        // Deleting the last task using a tag deletes it.
        assertThat(alice.delete("/api/tasks/" + second).status()).isEqualTo(204);
        assertThat(alice.get("/api/tags").json()).isEmpty();
        assertThat(dbCount("select count(*) from tags t join users u on u.id = t.owner_id"
                + " where u.email like 'alice-%' and t.id not in (select tag_id from task_tags)")).isZero();

        // Re-adding later uses the new spelling.
        assertThat(texts(alice.put("/api/tasks/" + first, tagsBody("first", "[\"OLD\"]")).json().get("tags")))
                .containsExactly("OLD");
    }

    /** AC11 + contract error codes, over real HTTP. */
    @Test
    void tagValidationErrors() throws Exception {
        Browser alice = new Browser().signUp("Alice");
        String path = "/api/tasks/" + alice.create("x").get("id").asLong();
        JsonNode before = alice.get(path).json();

        record Case(String tags, String code, String message) {
        }
        String eleven = "[\"a\",\"b\",\"c\",\"d\",\"e\",\"f\",\"g\",\"h\",\"i\",\"j\",\"k\"]";
        for (Case c : List.of(
                new Case("[\" \"]", "REQUIRED", "Tags can't be blank"),
                new Case("[null]", "REQUIRED", "Tags can't be blank"),
                new Case("[\"a,b\"]", "INVALID_CHARACTERS", "Tags can't contain commas or control characters"),
                new Case("[\"Work\\t\"]", "INVALID_CHARACTERS", "Tags can't contain commas or control characters"),
                new Case("[\"" + "t".repeat(31) + "\"]", "TOO_LONG", "Tags must be 30 characters or fewer"),
                new Case(eleven, "TOO_MANY", "A task can have at most 10 tags"))) {
            Resp r = alice.put(path, tagsBody("changed", c.tags()));
            assertThat(r.status()).as(c.tags()).isEqualTo(400);
            assertThat(r.contentType()).startsWith("application/problem+json");
            JsonNode body = r.json();
            assertThat(body.get("code").asText()).isEqualTo("VALIDATION_FAILED");
            assertThat(body.get("errors")).hasSize(1);
            assertThat(body.get("errors").get(0).get("field").asText()).isEqualTo("tags");
            assertThat(body.get("errors").get(0).get("code").asText()).as(c.tags()).isEqualTo(c.code());
            assertThat(body.get("errors").get(0).get("message").asText()).isEqualTo(c.message());
        }
        assertThat(alice.get(path).json()).isEqualTo(before);

        // Exactly 10 after dedupe is fine.
        String tenWithDupes = "[\"a\",\"A\",\"b\",\"c\",\"d\",\"e\",\"f\",\"g\",\"h\",\"i\",\"j\",\"á\"]";
        assertThat(alice.put(path, tagsBody("x", tenWithDupes)).json().get("tags")).hasSize(10);
    }

    /** 200 tags per user: TAG_LIMIT is the only error and nothing is saved. */
    @Test
    void tagLimitRejectsTheChangeAndSavesNothing() throws Exception {
        Browser alice = new Browser().signUp("Alice");
        for (int i = 0; i < 20; i++) {
            long id = alice.create("t" + i).get("id").asLong();
            StringBuilder tags = new StringBuilder("[");
            for (int j = 0; j < 10; j++) {
                tags.append(j == 0 ? "" : ",").append("\"tag ").append(i * 10 + j).append("\"");
            }
            assertThat(alice.put("/api/tasks/" + id, tagsBody("t" + i, tags.append("]").toString())).status())
                    .isEqualTo(200);
        }
        assertThat(alice.get("/api/tags").json()).hasSize(200);
        long id = alice.create("one more").get("id").asLong();
        String path = "/api/tasks/" + id;
        JsonNode before = alice.get(path).json();

        Resp r = alice.put(path, "{\"title\":\"renamed\",\"priority\":\"HIGH\",\"status\":\"DONE\","
                + "\"tags\":[\"tag 0\",\"brand new\"]}");

        assertThat(r.status()).isEqualTo(400);
        JsonNode body = r.json();
        assertThat(body.get("code").asText()).isEqualTo("VALIDATION_FAILED");
        assertThat(body.get("errors")).hasSize(1);
        assertThat(body.get("errors").get(0).get("field").asText()).isEqualTo("tags");
        assertThat(body.get("errors").get(0).get("code").asText()).isEqualTo("TAG_LIMIT");
        assertThat(body.get("errors").get(0).get("message").asText()).isEqualTo("You can have at most 200 tags");
        assertThat(alice.get(path).json()).isEqualTo(before);
        assertThat(alice.get("/api/tags").json()).hasSize(200);
        assertThat(dbCount("select count(*) from tags where name = 'brand new'")).isZero();
        assertThat(dbCount("select count(*) from task_tags where task_id = ?", id)).isZero();

        // Reusing existing tags still works at the limit.
        assertThat(texts(alice.put(path, tagsBody("one more", "[\"TAG 0\",\"tag 199\"]")).json().get("tags")))
                .containsExactly("tag 0", "tag 199");
    }

    /** AC14: tags are private; another user's task stays 404 and their tags are never touched. */
    @Test
    void tagsArePrivatePerUser() throws Exception {
        Browser alice = new Browser().signUp("Alice");
        Browser bob = new Browser().signUp("Bob");
        long bobs = bob.create("bob's").get("id").asLong();
        bob.put("/api/tasks/" + bobs, tagsBody("bob's", "[\"Work\",\"Bob only\"]"));
        long alices = alice.create("alice's").get("id").asLong();

        assertThat(alice.get("/api/tags").json()).isEmpty();
        assertThat(alice.get("/api/tasks?tag=Work").json()).isEmpty();
        assertThat(alice.get("/api/tasks?tag=" + q("Bob only")).json()).isEmpty();

        // "work" is a new tag of Alice's (own spelling), not Bob's "Work".
        JsonNode mine = alice.put("/api/tasks/" + alices, tagsBody("alice's", "[\"work\"]")).json();
        assertThat(texts(mine.get("tags"))).containsExactly("work");
        assertThat(tagNames(alice.get("/api/tags"))).containsExactly("work");
        assertThat(titles(alice.get("/api/tasks?tag=Work"))).containsExactly("alice's");
        assertThat(tagNames(bob.get("/api/tags"))).containsExactly("Bob only", "Work");
        assertThat(titles(bob.get("/api/tasks?tag=work"))).containsExactly("bob's");

        // Alice can't read, retag, untag or delete Bob's task.
        JsonNode before = bob.get("/api/tasks/" + bobs).json();
        assertThat(alice.put("/api/tasks/" + bobs, tagsBody("hacked", "[]")).status()).isEqualTo(404);
        assertThat(alice.patch("/api/tasks/" + bobs, "{\"status\":\"DONE\"}").status()).isEqualTo(404);
        assertThat(alice.delete("/api/tasks/" + bobs).status()).isEqualTo(404);
        assertThat(bob.get("/api/tasks/" + bobs).json()).isEqualTo(before);
        assertThat(tagNames(bob.get("/api/tags"))).containsExactly("Bob only", "Work");

        // Alice removing her "work" deletes only hers.
        alice.delete("/api/tasks/" + alices);
        assertThat(alice.get("/api/tags").json()).isEmpty();
        assertThat(tagNames(bob.get("/api/tags"))).containsExactly("Bob only", "Work");
    }

    @Test
    void tagsEndpointRequiresSignIn() throws Exception {
        Browser anonymous = new Browser().start();
        Resp r = anonymous.get("/api/tags");
        assertThat(r.status()).isEqualTo(401);
        assertThat(r.json().get("code").asText()).isEqualTo("UNAUTHENTICATED");
    }

    private List<Resp> concurrently(List<Callable<Resp>> calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Resp>> futures = new ArrayList<>();
            for (Callable<Resp> call : calls) {
                futures.add(pool.submit(() -> {
                    go.await();
                    return call.call();
                }));
            }
            go.countDown();
            List<Resp> out = new ArrayList<>();
            for (Future<Resp> f : futures) {
                out.add(f.get());
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    /** The owner lock makes concurrent tag changes safe: one tag per name, and the 200 cap is exact. */
    @Test
    void concurrentTagChangesNeverDuplicateTagsOrExceedTheLimit() throws Exception {
        Browser alice = new Browser().signUp("Alice");
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            ids.add(alice.create("c" + i).get("id").asLong());
        }
        List<Callable<Resp>> sameNewTag = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            String spelling = i % 2 == 0 ? "Fresh" : "frésh";
            long id = ids.get(i);
            sameNewTag.add(() -> alice.put("/api/tasks/" + id, tagsBody("c", "[\"" + spelling + "\"]")));
        }
        for (Resp r : concurrently(sameNewTag)) {
            assertThat(r.status()).isEqualTo(200);
        }
        assertThat(alice.get("/api/tags").json()).hasSize(1);
        assertThat(alice.get("/api/tasks?tag=fresh").json()).hasSize(6);

        // Fill to 199 tags, then several requests each try to add a different new tag: exactly one fits.
        long holder = alice.create("holder").get("id").asLong();
        jdbc.update("insert into tags (owner_id, name) select owner_id, concat('bulk ', n) from tasks"
                + " join (with recursive s (n) as (select 1 union all select n + 1 from s where n < 198) select n from s) nums"
                + " where tasks.id = ?", holder);
        jdbc.update("insert into task_tags (task_id, tag_id) select ?, id from tags where name like 'bulk %'"
                + " and owner_id = (select owner_id from tasks where id = ?)", holder, holder);
        assertThat(alice.get("/api/tags").json()).hasSize(199);
        List<Callable<Resp>> racing = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            long id = ids.get(i);
            String name = "racer " + i;
            racing.add(() -> alice.put("/api/tasks/" + id, tagsBody("c", "[\"Fresh\",\"" + name + "\"]")));
        }
        List<Resp> results = concurrently(racing);
        assertThat(results.stream().filter(r -> r.status() == 200).count()).isEqualTo(1);
        for (Resp r : results) {
            if (r.status() != 200) {
                assertThat(r.status()).isEqualTo(400);
                assertThat(r.json().get("errors").get(0).get("code").asText()).isEqualTo("TAG_LIMIT");
            }
        }
        assertThat(alice.get("/api/tags").json()).hasSize(200);
    }
}
