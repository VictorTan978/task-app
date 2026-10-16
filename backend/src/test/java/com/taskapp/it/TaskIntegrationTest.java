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
        list.json().get("items").forEach(t -> titles.add(t.get("title").asText()));
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

        assertThat(alice.get("/api/tasks").json().get("items")).isEmpty();
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
        assertThat(alice.get("/api/tasks").json().get("items")).isEmpty();
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
        assertThat(texts(alice.get("/api/tasks?tag=home").json().get("items").get(0).get("tags"))).containsExactly("Home", "Work");

        // Unknown or invalid tag → empty page; missing/empty/blank → no tag filter; repeated → first one.
        for (String none : List.of("Nope", q("a,b"), q("Work,"), q("Wo\trk"), "x".repeat(31), "Wor", q("Work  x"))) {
            Resp r = alice.get("/api/tasks?tag=" + none);
            assertThat(r.status()).as(none).isEqualTo(200);
            assertThat(r.json().toString()).as(none).isEqualTo("{\"items\":[],\"total\":0,\"nextCursor\":null}");
        }
        for (String blank : List.of("", "%20", q("   "))) {
            assertThat(titles(alice.get("/api/tasks?filter=in_progress&tag=" + blank))).as(blank)
                    .containsExactly("progress high", "progress");
        }
        assertThat(titles(alice.get("/api/tasks?tag=Home&tag=Nope"))).containsExactly("progress");
        assertThat(alice.get("/api/tasks?tag=Nope&tag=Home").json().get("items")).isEmpty();
        assertThat(alice.get("/api/tasks?tag=Home,Work").json().get("items")).isEmpty();
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
        assertThat(alice.get("/api/tasks?tag=Work").json().get("items")).isEmpty();
        assertThat(alice.get("/api/tasks?tag=" + q("Bob only")).json().get("items")).isEmpty();

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
        assertThat(alice.get("/api/tasks?tag=fresh").json().get("items")).hasSize(6);

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

    // ---- T2: search, sort, pagination (contract 0.11.0) ----

    private static final Instant SEED_T0 = Instant.parse("2026-09-01T08:00:00Z");
    private static final java.time.LocalDate SEED_D0 = java.time.LocalDate.parse("2026-11-01");
    private static final java.util.regex.Pattern CURSOR = java.util.regex.Pattern.compile("[A-Za-z0-9_-]{1,1024}");

    /** A seeded task as the list orders it. */
    record Seeded(long id, String title, String priority, boolean done, java.time.LocalDate due, Instant createdAt) {
    }

    private long ownerOf(Browser b) throws Exception {
        long probe = b.create("probe").get("id").asLong();
        long owner = jdbc.queryForObject("select owner_id from tasks where id = ?", Long.class, probe);
        jdbc.update("delete from tasks where id = ?", probe);
        return owner;
    }

    /** {@code count} tasks with many ties on every sort key (title case, priority, due incl. none, createdAt). */
    private List<Seeded> seed(long owner, int count) {
        String[] titles = {"alpha", "Alpha", "beta", "BETA", "gamma", "Delta"};
        String[] priorities = {"NONE", "LOW", "MEDIUM", "HIGH"};
        String[] statuses = {"TODO", "IN_PROGRESS", "DONE"};
        List<Seeded> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String status = statuses[i % 3];
            java.time.LocalDate due = i % 5 == 0 ? null : SEED_D0.plusDays(i % 7);
            Instant created = SEED_T0.plusSeconds(i / 4);
            jdbc.update("insert into tasks (owner_id, title, priority, status, done, due_date, created_at, updated_at)"
                    + " values (?, ?, ?, ?, ?, ?, ?, ?)", owner, titles[i % 6], priorities[i % 4], status,
                    status.equals("DONE"), due, java.sql.Timestamp.from(created), java.sql.Timestamp.from(created));
            long id = jdbc.queryForObject("select last_insert_id()", Long.class);
            out.add(new Seeded(id, titles[i % 6], priorities[i % 4], status.equals("DONE"), due, created));
        }
        return out;
    }

    private static int rank(String priority) {
        return List.of("HIGH", "MEDIUM", "LOW", "NONE").indexOf(priority);
    }

    /** The contract's order for filter=all (ASCII titles: case-insensitive = the column collation). */
    private static java.util.Comparator<Seeded> order(String sort) {
        java.util.Comparator<Seeded> newest = java.util.Comparator.comparing(Seeded::createdAt)
                .thenComparingLong(Seeded::id).reversed();
        java.util.Comparator<Seeded> group = java.util.Comparator.comparing(Seeded::done);
        return switch (sort) {
            case "oldest" -> group.thenComparing(java.util.Comparator.comparing(Seeded::createdAt)
                    .thenComparingLong(Seeded::id));
            case "due" -> group.thenComparing(Seeded::due,
                    java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())).thenComparing(newest);
            case "priority" -> group.thenComparingInt(t -> rank(t.priority())).thenComparing(newest);
            case "title" -> group.thenComparing(Seeded::title, String.CASE_INSENSITIVE_ORDER).thenComparing(newest);
            default -> group.thenComparing(newest);
        };
    }

    private static List<Long> ids(JsonNode page) {
        List<Long> ids = new ArrayList<>();
        page.get("items").forEach(t -> ids.add(t.get("id").asLong()));
        return ids;
    }

    /** Follows nextCursor to the end; checks the page shape on the way. */
    private List<List<Long>> pages(Browser b, String params, long expectedTotal) throws Exception {
        List<List<Long>> pages = new ArrayList<>();
        String cursor = null;
        for (int n = 0; n < 20; n++) {
            Resp r = b.get("/api/tasks?" + params + (cursor == null ? "" : "&cursor=" + cursor));
            assertThat(r.status()).as(params + " page " + n + ": " + r.body()).isEqualTo(200);
            JsonNode page = r.json();
            assertThat(page.size()).isEqualTo(3);
            assertThat(page.get("total").asLong()).isEqualTo(expectedTotal);
            assertThat(page.get("items").size()).isLessThanOrEqualTo(50);
            pages.add(ids(page));
            if (page.get("nextCursor").isNull()) {
                return pages;
            }
            cursor = page.get("nextCursor").asText();
            assertThat(cursor).matches(CURSOR);
        }
        throw new AssertionError("never ended");
    }

    private static void expectProblem(Resp r, String... fieldAndCode) throws IOException {
        assertThat(r.status()).as(r.body()).isEqualTo(400);
        assertThat(r.contentType()).startsWith("application/problem+json");
        JsonNode body = r.json();
        assertThat(body.get("code").asText()).isEqualTo("VALIDATION_FAILED");
        assertThat(body.get("errors")).hasSize(fieldAndCode.length / 2);
        for (int i = 0; i < fieldAndCode.length; i += 2) {
            assertThat(body.get("errors").get(i / 2).get("field").asText()).isEqualTo(fieldAndCode[i]);
            assertThat(body.get("errors").get(i / 2).get("code").asText()).isEqualTo(fieldAndCode[i + 1]);
        }
    }

    /** T2 AC1, AC2, AC4, AC14: search over title and description. */
    @Test
    void searchIgnoresCaseAndAccentsAndTreatsWildcardsLiterally() throws Exception {
        Browser alice = new Browser().signUp("Alice");
        Browser bob = new Browser().signUp("Bob");
        alice.create("Buy milk");
        alice.create("Call Bob");
        long groceries = alice.create("Groceries").get("id").asLong();
        alice.put("/api/tasks/" + groceries, "{\"title\":\"Groceries\",\"description\":\"eggs and MILK\"}");
        // Titles are spliced into raw JSON, so the backslash is escaped there: the stored title is back\slash.
        for (String title : List.of("Café meeting", "50% off", "5000 off", "a_b", "axb", "back\\\\slash", "backslash")) {
            alice.create(title);
        }
        bob.create("Bob's milk");

        assertThat(titles(alice.get("/api/tasks?q=MILK"))).containsExactly("Groceries", "Buy milk");
        assertThat(alice.get("/api/tasks?q=MILK").json().get("total").asLong()).isEqualTo(2);
        assertThat(titles(alice.get("/api/tasks?q=" + q("  milk  ")))).containsExactly("Groceries", "Buy milk");
        assertThat(titles(alice.get("/api/tasks?q=cafe"))).containsExactly("Café meeting");
        assertThat(titles(alice.get("/api/tasks?q=" + q("50%")))).containsExactly("50% off");
        assertThat(titles(alice.get("/api/tasks?q=a_b"))).containsExactly("a_b");
        assertThat(titles(alice.get("/api/tasks?q=" + q("\\")))).containsExactly("back\\slash");
        assertThat(alice.get("/api/tasks?q=nothing").json().toString())
                .isEqualTo("{\"items\":[],\"total\":0,\"nextCursor\":null}");
        assertThat(titles(bob.get("/api/tasks?q=milk"))).containsExactly("Bob's milk");

        // Blank = no search; repeated = first; 100 characters after trimming is the limit.
        assertThat(alice.get("/api/tasks?q=" + q("   ")).json().get("total").asLong()).isEqualTo(10);
        assertThat(titles(alice.get("/api/tasks?q=cafe&q=milk"))).containsExactly("Café meeting");
        assertThat(alice.get("/api/tasks?q=" + q("  " + "x".repeat(100) + "  ")).status()).isEqualTo(200);
        Resp tooLong = alice.get("/api/tasks?q=" + "x".repeat(101));
        expectProblem(tooLong, "q", "TOO_LONG");
        assertThat(tooLong.json().get("errors").get(0).get("message").asText())
                .isEqualTo("Search must be at most 100 characters");
        assertThat(tooLong.json().get("instance").asText()).isEqualTo("/api/tasks");
    }

    /** T2 AC5, AC6, AC7, AC8, AC9, AC14: every sort pages through 120 tasks without repeats or gaps. */
    @Test
    void everySortPagesThrough120TasksInTheContractOrder() throws Exception {
        Browser alice = new Browser().signUp("Alice");
        Browser bob = new Browser().signUp("Bob");
        List<Seeded> tasks = seed(ownerOf(alice), 120);
        List<Seeded> bobs = seed(ownerOf(bob), 30);

        for (String sort : List.of("newest", "oldest", "due", "priority", "title")) {
            List<List<Long>> pages = pages(alice, "sort=" + sort, 120);
            assertThat(pages).extracting(List::size).as(sort).containsExactly(50, 50, 20);
            List<Long> all = pages.stream().flatMap(List::stream).toList();
            List<Long> expected = tasks.stream().sorted(order(sort)).map(Seeded::id).toList();
            assertThat(all).as(sort).isEqualTo(expected);
        }
        // Unknown sort = newest, also for the cursor.
        assertThat(pages(alice, "sort=Title", 120).get(0))
                .isEqualTo(tasks.stream().sorted(order("newest")).map(Seeded::id).limit(50).toList());

        // Combined with a filter and a search (seeded titles: "alpha", "Alpha", ... → "ALP" matches 40).
        List<Seeded> activeAlpha = tasks.stream()
                .filter(t -> !t.done() && t.title().equalsIgnoreCase("alpha")).toList();
        List<Long> activePaged = pages(alice, "filter=active&q=ALP&sort=due", activeAlpha.size()).stream()
                .flatMap(List::stream).toList();
        assertThat(activePaged).isEqualTo(activeAlpha.stream().sorted(order("due")).map(Seeded::id).toList());

        List<Long> bobsIds = bobs.stream().map(Seeded::id).toList();
        assertThat(pages(bob, "sort=title", 30).stream().flatMap(List::stream).toList())
                .containsExactlyInAnyOrderElementsOf(bobsIds);
    }

    /** T2 AC10: the next page is exact even if the last task of the previous page was edited or deleted. */
    @Test
    void nextPageIsExactAfterTheBoundaryTaskIsEditedOrDeleted() throws Exception {
        for (String sort : List.of("newest", "oldest", "due", "priority", "title")) {
            Browser alice = new Browser().signUp("Alice");
            List<Long> expected = seed(ownerOf(alice), 120).stream().sorted(order(sort)).map(Seeded::id).toList();
            JsonNode first = alice.get("/api/tasks?sort=" + sort).json();
            assertThat(ids(first)).isEqualTo(expected.subList(0, 50));
            long boundary = expected.get(49);
            String cursor = first.get("nextCursor").asText();

            // Edited so it moves in every order (it may come back later: clients de-duplicate by id).
            assertThat(alice.put("/api/tasks/" + boundary, "{\"title\":\"zzz moved\",\"priority\":\"NONE\","
                    + "\"status\":\"TODO\",\"dueDate\":null}").status()).isEqualTo(200);
            List<Long> second = ids(alice.get("/api/tasks?sort=" + sort + "&cursor=" + cursor).json()).stream()
                    .filter(id -> id != boundary).toList();
            assertThat(second).as(sort + " after edit").isEqualTo(expected.subList(50, 50 + second.size()));

            // Deleted: page 2 is still exactly the next 50, and the total follows.
            assertThat(alice.delete("/api/tasks/" + boundary).status()).isEqualTo(204);
            JsonNode again = alice.get("/api/tasks?sort=" + sort + "&cursor=" + cursor).json();
            assertThat(ids(again)).as(sort + " after delete").isEqualTo(expected.subList(50, 100));
            assertThat(again.get("total").asLong()).isEqualTo(119);
        }
    }

    /** T2 contract: cursor problems are 400 cursor/INVALID_CURSOR; a cursor never reaches another user's tasks. */
    @Test
    void invalidOrMismatchedCursorIs400() throws Exception {
        Browser alice = new Browser().signUp("Alice");
        Browser bob = new Browser().signUp("Bob");
        seed(ownerOf(alice), 100);
        List<Seeded> bobs = seed(ownerOf(bob), 3);
        String cursor = alice.get("/api/tasks?sort=title&filter=active&q=a").json().get("nextCursor").asText();
        String base = "/api/tasks?sort=title&filter=active&q=a&cursor=";
        assertThat(alice.get(base + cursor).status()).isEqualTo(200);
        // Same list after normalising: ?q= trimmed.
        assertThat(alice.get("/api/tasks?sort=title&filter=active&q=" + q(" a ") + "&cursor=" + cursor).status())
                .isEqualTo(200);

        char last = cursor.charAt(cursor.length() - 1);
        List<String> broken = List.of(
                cursor.substring(0, cursor.length() - 1),
                cursor.substring(0, cursor.length() - 1) + (last == 'A' ? 'B' : 'A'),
                cursor + "AAAA",
                cursor + "%3D",
                cursor.replace('-', '+').replace('_', '/') + "%2B",
                "x".repeat(1025),
                "not-a-cursor",
                q("{\"v\":1}"));
        for (String bad : broken) {
            Resp r = alice.get(base + bad);
            expectProblem(r, "cursor", "INVALID_CURSOR");
            assertThat(r.json().get("errors").get(0).get("message").asText())
                    .isEqualTo("The list changed, reload from the first page");
        }
        for (String other : List.of("sort=due&filter=active&q=a", "sort=title&filter=all&q=a",
                "sort=title&filter=active&q=b", "sort=title&filter=active", "sort=title&filter=active&q=a&tag=x")) {
            expectProblem(alice.get("/api/tasks?" + other + "&cursor=" + cursor), "cursor", "INVALID_CURSOR");
        }
        // Both problems at once, sorted by field.
        expectProblem(alice.get("/api/tasks?cursor=nope&q=" + "y".repeat(101)), "cursor", "INVALID_CURSOR", "q", "TOO_LONG");
        // Empty cursor = first page.
        assertThat(alice.get(base).json().get("items")).hasSize(50);

        // Alice's cursor in Bob's session only positions within Bob's own list.
        Resp bobWithAlicesCursor = bob.get(base + cursor);
        assertThat(bobWithAlicesCursor.status()).isEqualTo(200);
        List<Long> bobsIds = bobs.stream().map(Seeded::id).toList();
        assertThat(ids(bobWithAlicesCursor.json())).isSubsetOf(bobsIds);
    }
}
