package com.taskapp.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
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
        assertThat(task.has("ownerId")).isFalse();
        assertThat(task.size()).isEqualTo(7);

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

        Resp missingDone = alice.put(path, "{\"title\":\"x\"}");
        assertThat(missingDone.status()).isEqualTo(400);
        assertThat(missingDone.json().get("errors").get(0).get("field").asText()).isEqualTo("done");

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
}
