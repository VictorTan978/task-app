package com.taskapp.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
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

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * T3a lists end to end over HTTP against MySQL 8.4 (specs/sharing-team-lists.md AC1-AC12, AC14, AC15; contract
 * 0.12.0).
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.rate-limit.login-per-ip-per-minute=10000",
        "app.rate-limit.register-per-ip-per-hour=10000"})
class ListIntegrationTest {

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
            .withUrlParam("connectionTimeZone", "UTC")
            .withUrlParam("forceConnectionTimeZoneToSession", "true");

    private static final String PASSWORD = "correct-horse";
    private static final ObjectMapper JSON = new ObjectMapper();

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    private final HttpClient http = HttpClient.newHttpClient();

    // ---- AC1: "My tasks" ----

    @Test
    void registerCreatesExactlyOneMyTasks() throws Exception {
        Browser alice = signUp("Alice");

        JsonNode lists = alice.get("/api/lists").json();
        assertThat(lists).hasSize(1);
        assertThat(lists.get(0).get("name").asText()).isEqualTo("My tasks");
        assertThat(lists.get(0).get("personal").asBoolean()).isTrue();
        // 0.13.0: role and memberCount; the owner is the only member.
        assertThat(lists.get(0).get("role").asText()).isEqualTo("OWNER");
        assertThat(lists.get(0).get("memberCount").asInt()).isEqualTo(1);
        assertThat(lists.get(0).size()).isEqualTo(5);
        assertThat(count("select count(*) from lists where owner_id = ?", alice.id)).isEqualTo(1);
        assertThat(count("select count(*) from list_members m join lists l on l.id = m.list_id"
                + " where l.owner_id = ? and m.user_id = l.owner_id", alice.id)).isEqualTo(1);
        // New tasks without listId go there.
        assertThat(alice.createTask("t", null).get("listId").asLong()).isEqualTo(alice.myTasks());
    }

    @Test
    void myTasksCanBeRenamedButNotDeleted() throws Exception {
        Browser alice = signUp("Alice");
        long mine = alice.myTasks();

        Resp renamed = alice.patch("/api/lists/" + mine, "{\"name\":\"  Inbox \"}");
        assertThat(renamed.status()).isEqualTo(200);
        assertThat(renamed.json().get("name").asText()).isEqualTo("Inbox");
        assertThat(renamed.json().get("personal").asBoolean()).isTrue();

        Resp delete = alice.delete("/api/lists/" + mine);
        expectProblem(delete, 409, "PERSONAL_LIST", "Your personal list can't be deleted");
        assertThat(alice.get("/api/lists").json().get(0).get("name").asText()).isEqualTo("Inbox");
    }

    // ---- AC2-AC5: create, order, rename, get, delete ----

    @Test
    void createRenameGetAndDeleteAList() throws Exception {
        Browser alice = signUp("Alice");

        Resp created = alice.post("/api/lists", "{\"name\":\"  Home \"}");
        assertThat(created.status()).isEqualTo(201);
        long home = created.json().get("id").asLong();
        assertThat(created.location()).isEqualTo("/api/lists/" + home);
        assertThat(created.json()).isEqualTo(JSON.readTree(
                "{\"id\":" + home + ",\"name\":\"Home\",\"personal\":false,\"role\":\"OWNER\",\"memberCount\":1}"));
        alice.post("/api/lists", "{\"name\":\"äpfel\"}");
        alice.post("/api/lists", "{\"name\":\"Zoo\"}");
        alice.post("/api/lists", "{\"name\":\"Home\"}");
        assertThat(names(alice.get("/api/lists").json())).containsExactly("My tasks", "äpfel", "Home", "Home", "Zoo");

        assertThat(alice.patch("/api/lists/" + home, "{\"name\":\"Groceries\"}").json().get("name").asText())
                .isEqualTo("Groceries");

        long done = alice.createTask("one", home).get("id").asLong();
        alice.patch("/api/tasks/" + done, "{\"status\":\"DONE\"}");
        alice.createTask("two", home);
        alice.createTask("elsewhere", null);
        assertThat(alice.get("/api/lists/" + home).json()).isEqualTo(JSON.readTree(
                "{\"id\":" + home + ",\"name\":\"Groceries\",\"personal\":false,\"role\":\"OWNER\","
                        + "\"memberCount\":1,\"taskCount\":2}"));
        assertThat(alice.get("/api/tasks?list=" + home).json().get("total").asLong()).isEqualTo(2);

        assertThat(alice.delete("/api/lists/" + home).status()).isEqualTo(204);
        assertThat(count("select count(*) from tasks where list_id = ?", home)).isZero();
        expectProblem(alice.get("/api/lists/" + home), 404, "LIST_NOT_FOUND", "List not found");
        expectProblem(alice.delete("/api/lists/" + home), 404, "LIST_NOT_FOUND", "List not found");
        expectProblem(alice.get("/api/tasks/" + done), 404, "NOT_FOUND", "Task not found");
        assertThat(alice.get("/api/tasks").json().get("total").asLong()).isEqualTo(1);
    }

    @Test
    void listNameValidation() throws Exception {
        Browser alice = signUp("Alice");

        Resp blank = alice.post("/api/lists", "{\"name\":\"   \"}");
        assertThat(blank.status()).isEqualTo(400);
        assertThat(blank.json().get("errors").get(0).get("code").asText()).isEqualTo("REQUIRED");
        Resp tooLong = alice.post("/api/lists", "{\"name\":\"" + "x".repeat(101) + "\"}");
        assertThat(tooLong.json().get("errors").get(0).get("code").asText()).isEqualTo("TOO_LONG");
        assertThat(alice.post("/api/lists", "{\"name\":\"" + "x".repeat(100) + "\"}").status()).isEqualTo(201);
        assertThat(alice.get("/api/lists").json()).hasSize(2);
    }

    /** AC2: at most 50 owned lists, "My tasks" included; the 51st is refused and nothing is created. */
    @Test
    void the51stListIsRefused() throws Exception {
        Browser alice = signUp("Alice");
        for (int i = 2; i <= 50; i++) {
            assertThat(alice.post("/api/lists", "{\"name\":\"L" + i + "\"}").status()).isEqualTo(201);
        }

        expectProblem(alice.post("/api/lists", "{\"name\":\"L51\"}"), 409, "LIST_LIMIT",
                "You can have at most 50 lists");
        assertThat(count("select count(*) from lists where owner_id = ?", alice.id)).isEqualTo(50);
        assertThat(alice.get("/api/lists").json()).hasSize(50);

        // Deleting one makes room again.
        long last = alice.get("/api/lists").json().get(49).get("id").asLong();
        assertThat(alice.delete("/api/lists/" + last).status()).isEqualTo(204);
        assertThat(alice.post("/api/lists", "{\"name\":\"Again\"}").status()).isEqualTo(201);
    }

    // ---- AC7 / AC14: another user's list is 404 everywhere, identical body ----

    @Test
    void anotherUsersListIs404OnEveryPath() throws Exception {
        Browser alice = signUp("Alice");
        Browser bob = signUp("Bob");
        long bobsMyTasks = bob.myTasks();
        long bobsHome = bob.post("/api/lists", "{\"name\":\"Home\"}").json().get("id").asLong();
        long bobsTask = bob.createTask("bob's", bobsHome).get("id").asLong();
        bob.put("/api/tasks/" + bobsTask, "{\"title\":\"bob's\",\"tags\":[\"Secret\"]}");
        long alicesTask = alice.createTask("mine", null).get("id").asLong();

        for (long list : new long[] {bobsHome, bobsMyTasks, 999_999_999L}) {
            List<Resp> responses = List.of(
                    alice.get("/api/lists/" + list),
                    alice.patch("/api/lists/" + list, "{\"name\":\"Mine now\"}"),
                    alice.delete("/api/lists/" + list),
                    alice.get("/api/tasks?list=" + list),
                    alice.get("/api/tags?list=" + list),
                    alice.post("/api/tasks", "{\"title\":\"x\",\"listId\":" + list + "}"),
                    alice.put("/api/tasks/" + alicesTask, "{\"title\":\"mine\",\"listId\":" + list + "}"));
            for (Resp r : responses) {
                expectProblem(r, 404, "LIST_NOT_FOUND", "List not found");
                assertThat(r.json().has("errors")).isFalse();
            }
        }
        // Bob's task stays NOT_FOUND for Alice, also with a valid list of hers.
        expectProblem(alice.get("/api/tasks/" + bobsTask), 404, "NOT_FOUND", "Task not found");
        expectProblem(alice.put("/api/tasks/" + bobsTask, "{\"title\":\"x\",\"listId\":" + alice.myTasks() + "}"),
                404, "NOT_FOUND", "Task not found");
        expectProblem(alice.delete("/api/tasks/" + bobsTask), 404, "NOT_FOUND", "Task not found");

        // Nothing of Bob's changed.
        assertThat(names(bob.get("/api/lists").json())).containsExactly("My tasks", "Home");
        assertThat(bob.get("/api/tasks?list=" + bobsHome).json().get("total").asLong()).isEqualTo(1);
        assertThat(bob.get("/api/tags?list=" + bobsHome).json().get(0).get("name").asText()).isEqualTo("Secret");
        assertThat(alice.get("/api/tasks/" + alicesTask).json().get("listId").asLong()).isEqualTo(alice.myTasks());
        assertThat(names(alice.get("/api/lists").json())).containsExactly("My tasks");
    }

    /** AC14: admins see only their own lists, like anyone else. */
    @Test
    void adminsSeeNoOneElsesLists() throws Exception {
        Browser admin = signUp("Admin");
        jdbc.update("update users set role = 'SUPER_ADMIN' where id = ?", admin.id);
        Browser admin2 = new Browser().start();
        admin2.login(admin);
        Browser bob = signUp("Bob");
        long bobsHome = bob.post("/api/lists", "{\"name\":\"Home\"}").json().get("id").asLong();

        assertThat(names(admin2.get("/api/lists").json())).containsExactly("My tasks");
        expectProblem(admin2.get("/api/lists/" + bobsHome), 404, "LIST_NOT_FOUND", "List not found");
        expectProblem(admin2.get("/api/tasks?list=" + bobsHome), 404, "LIST_NOT_FOUND", "List not found");
        assertThat(admin2.get("/api/admin/lists").status()).isEqualTo(404);
    }

    // ---- ?list= parsing (contract ListQuery) ----

    @Test
    void listParameterRules() throws Exception {
        Browser alice = signUp("Alice");
        alice.createTask("in my tasks", null);
        long home = alice.post("/api/lists", "{\"name\":\"Home\"}").json().get("id").asLong();
        alice.createTask("at home", home);

        assertThat(titles(alice.get("/api/tasks").json())).containsExactly("in my tasks");
        assertThat(titles(alice.get("/api/tasks?list=").json())).containsExactly("in my tasks");
        assertThat(titles(alice.get("/api/tasks?list=" + alice.myTasks()).json())).containsExactly("in my tasks");
        assertThat(titles(alice.get("/api/tasks?list=" + home).json())).containsExactly("at home");
        assertThat(titles(alice.get("/api/tasks?list=" + home + "&list=" + alice.myTasks()).json()))
                .containsExactly("at home");
        for (String bad : List.of("abc", "0", "-1", "1.5", q(" " + home), q(home + " "), q(" "), "1e3")) {
            expectProblem(alice.get("/api/tasks?list=" + bad), 404, "LIST_NOT_FOUND", "List not found");
            expectProblem(alice.get("/api/tags?list=" + bad), 404, "LIST_NOT_FOUND", "List not found");
        }
        // The list is checked before q and cursor.
        expectProblem(alice.get("/api/tasks?list=abc&q=" + "x".repeat(101) + "&cursor=@"), 404, "LIST_NOT_FOUND",
                "List not found");
    }

    @Test
    void malformedListIdInABodyIs400() throws Exception {
        Browser alice = signUp("Alice");
        long task = alice.createTask("t", null).get("id").asLong();

        for (String listId : List.of("\"abc\"", "1.5", "\"" + alice.myTasks() + "\"", "true")) {
            expectProblem(alice.post("/api/tasks", "{\"title\":\"x\",\"listId\":" + listId + "}"), 400,
                    "MALFORMED_REQUEST", "Malformed request body");
            expectProblem(alice.put("/api/tasks/" + task, "{\"title\":\"x\",\"listId\":" + listId + "}"), 400,
                    "MALFORMED_REQUEST", "Malformed request body");
        }
        for (String listId : List.of("0", "-1")) {
            expectProblem(alice.post("/api/tasks", "{\"title\":\"x\",\"listId\":" + listId + "}"), 404,
                    "LIST_NOT_FOUND", "List not found");
        }
        assertThat(alice.get("/api/tasks").json().get("total").asLong()).isEqualTo(1);
    }

    // ---- AC8: tags per list ----

    @Test
    void eachListHasItsOwnTags() throws Exception {
        Browser alice = signUp("Alice");
        long home = alice.post("/api/lists", "{\"name\":\"Home\"}").json().get("id").asLong();
        long inMine = alice.createTask("mine", null).get("id").asLong();
        long atHome = alice.createTask("home", home).get("id").asLong();

        alice.put("/api/tasks/" + inMine, "{\"title\":\"mine\",\"tags\":[\"Work\"]}");
        assertThat(alice.put("/api/tasks/" + atHome, "{\"title\":\"home\",\"tags\":[\"WORK\",\"Garden\"]}").json()
                .get("tags").toString()).isEqualTo("[\"Garden\",\"WORK\"]");

        assertThat(names(alice.get("/api/tags").json())).containsExactly("Work");
        assertThat(names(alice.get("/api/tags?list=" + home).json())).containsExactly("Garden", "WORK");
        assertThat(titles(alice.get("/api/tasks?tag=garden").json())).isEmpty();
        assertThat(titles(alice.get("/api/tasks?list=" + home + "&tag=garden").json())).containsExactly("home");
        assertThat(titles(alice.get("/api/tasks?tag=work").json())).containsExactly("mine");
        assertThat(count("select count(*) from tags where owner_id = ?", alice.id)).isEqualTo(3);
    }

    // ---- AC10 / AC11: moves ----

    @Test
    void moveTakesTheTagsAlongReusingTheTargetsSpellingAndCleansUpTheSource() throws Exception {
        Browser alice = signUp("Alice");
        long home = alice.post("/api/lists", "{\"name\":\"Home\"}").json().get("id").asLong();
        JsonNode task = alice.createTask("move me", null);
        long id = task.get("id").asLong();
        long stays = alice.createTask("stays", null).get("id").asLong();
        alice.put("/api/tasks/" + id, "{\"title\":\"move me\",\"tags\":[\"Work\",\"Only here\",\"Shared\"]}");
        alice.put("/api/tasks/" + stays, "{\"title\":\"stays\",\"tags\":[\"Shared\"]}");
        alice.put("/api/tasks/" + alice.createTask("h", home).get("id").asLong(),
                "{\"title\":\"h\",\"tags\":[\"wörk\"]}");

        Resp moved = alice.put("/api/tasks/" + id, "{\"title\":\"moved\",\"priority\":\"HIGH\",\"listId\":" + home + "}");

        assertThat(moved.status()).isEqualTo(200);
        JsonNode body = moved.json();
        assertThat(body.get("listId").asLong()).isEqualTo(home);
        assertThat(body.get("title").asText()).isEqualTo("moved");
        assertThat(body.get("priority").asText()).isEqualTo("HIGH");
        assertThat(body.get("tags").toString()).isEqualTo("[\"Only here\",\"Shared\",\"wörk\"]");
        assertThat(body.get("createdAt")).isEqualTo(task.get("createdAt"));
        assertThat(body.get("id").asLong()).isEqualTo(id);
        assertThat(alice.get("/api/tasks/" + id).json())
                .isEqualTo(withoutNextTask(body));

        assertThat(titles(alice.get("/api/tasks").json())).containsExactly("stays");
        assertThat(names(alice.get("/api/tags").json())).containsExactly("Shared");
        assertThat(names(alice.get("/api/tags?list=" + home).json())).containsExactly("Only here", "Shared", "wörk");
        assertConsistent();

        // Moving back with tags sent: those are the tags in the target.
        JsonNode back = alice.put("/api/tasks/" + id, "{\"title\":\"moved\",\"tags\":[\"shared\",\"New\"],\"listId\":"
                + alice.myTasks() + "}").json();
        assertThat(back.get("tags").toString()).isEqualTo("[\"New\",\"Shared\"]");
        assertThat(names(alice.get("/api/tags?list=" + home).json())).containsExactly("wörk");
        assertConsistent();
    }

    @Test
    void sameListIdIsNotAMove() throws Exception {
        Browser alice = signUp("Alice");
        long id = alice.createTask("t", null).get("id").asLong();
        alice.put("/api/tasks/" + id, "{\"title\":\"t\",\"tags\":[\"A\"]}");

        JsonNode same = alice.put("/api/tasks/" + id, "{\"title\":\"u\",\"listId\":" + alice.myTasks() + "}").json();

        assertThat(same.get("listId").asLong()).isEqualTo(alice.myTasks());
        assertThat(same.get("tags").toString()).isEqualTo("[\"A\"]");
    }

    /** AC11: the target would go over 200 tags: refused with TAG_LIMIT and nothing changes. */
    @Test
    void moveOverTheTargetsTagLimitChangesNothing() throws Exception {
        Browser alice = signUp("Alice");
        long home = alice.post("/api/lists", "{\"name\":\"Home\"}").json().get("id").asLong();
        long holder = alice.createTask("holder", home).get("id").asLong();
        fillTags(alice.id, home, holder, 199);
        long id = alice.createTask("move me", null).get("id").asLong();
        alice.put("/api/tasks/" + id, "{\"title\":\"move me\",\"tags\":[\"new 1\",\"new 2\"]}");
        JsonNode before = alice.get("/api/tasks/" + id).json();

        Resp r = alice.put("/api/tasks/" + id, "{\"title\":\"renamed\",\"listId\":" + home + "}");

        assertThat(r.status()).isEqualTo(400);
        assertThat(r.json().get("code").asText()).isEqualTo("VALIDATION_FAILED");
        assertThat(r.json().get("errors")).hasSize(1);
        assertThat(r.json().get("errors").get(0).get("field").asText()).isEqualTo("tags");
        assertThat(r.json().get("errors").get(0).get("code").asText()).isEqualTo("TAG_LIMIT");
        assertThat(r.json().get("errors").get(0).get("message").asText()).isEqualTo("A list can have at most 200 tags");
        assertThat(alice.get("/api/tasks/" + id).json()).isEqualTo(before);
        assertThat(alice.get("/api/tags?list=" + home).json()).hasSize(199);
        assertThat(names(alice.get("/api/tags").json())).containsExactly("new 1", "new 2");

        // Exactly 200 fits.
        assertThat(alice.put("/api/tasks/" + id, "{\"title\":\"renamed\",\"tags\":[\"new 1\"],\"listId\":" + home + "}")
                .status()).isEqualTo(200);
        assertThat(alice.get("/api/tags?list=" + home).json()).hasSize(200);
    }

    @Test
    void writesOnATaskWhoseListWasDeletedAre404() throws Exception {
        Browser alice = signUp("Alice");
        long home = alice.post("/api/lists", "{\"name\":\"Home\"}").json().get("id").asLong();
        long id = alice.createTask("t", home).get("id").asLong();
        long other = alice.createTask("o", null).get("id").asLong();
        alice.delete("/api/lists/" + home);

        expectProblem(alice.put("/api/tasks/" + id, "{\"title\":\"x\"}"), 404, "NOT_FOUND", "Task not found");
        expectProblem(alice.put("/api/tasks/" + id, "{\"title\":\"x\",\"tags\":[\"a\"]}"), 404, "NOT_FOUND",
                "Task not found");
        expectProblem(alice.patch("/api/tasks/" + id, "{\"done\":true}"), 404, "NOT_FOUND", "Task not found");
        expectProblem(alice.delete("/api/tasks/" + id), 404, "NOT_FOUND", "Task not found");
        // The task is checked first, then the target list.
        expectProblem(alice.put("/api/tasks/" + id, "{\"title\":\"x\",\"listId\":" + home + "}"), 404, "NOT_FOUND",
                "Task not found");
        expectProblem(alice.put("/api/tasks/" + other, "{\"title\":\"x\",\"listId\":" + home + "}"), 404,
                "LIST_NOT_FOUND", "List not found");
        expectProblem(alice.post("/api/tasks", "{\"title\":\"x\",\"listId\":" + home + "}"), 404, "LIST_NOT_FOUND",
                "List not found");
    }

    // ---- AC15: cursors ----

    @Test
    void aCursorFromAnotherListIsInvalid() throws Exception {
        Browser alice = signUp("Alice");
        long home = alice.post("/api/lists", "{\"name\":\"Home\"}").json().get("id").asLong();
        seedTasks(alice.id, alice.myTasks(), 60);
        seedTasks(alice.id, home, 60);

        String cursor = alice.get("/api/tasks").json().get("nextCursor").asText();
        assertThat(alice.get("/api/tasks?cursor=" + cursor).status()).isEqualTo(200);
        // A missing list and the id of "My tasks" are the same list.
        assertThat(alice.get("/api/tasks?list=" + alice.myTasks() + "&cursor=" + cursor).status()).isEqualTo(200);
        Resp other = alice.get("/api/tasks?list=" + home + "&cursor=" + cursor);
        assertThat(other.status()).isEqualTo(400);
        assertThat(other.json().get("errors").get(0).get("field").asText()).isEqualTo("cursor");
        assertThat(other.json().get("errors").get(0).get("code").asText()).isEqualTo("INVALID_CURSOR");

        // Paging inside a list over 50 tasks.
        JsonNode first = alice.get("/api/tasks?list=" + home).json();
        assertThat(first.get("items")).hasSize(50);
        assertThat(first.get("total").asLong()).isEqualTo(60);
        JsonNode second = alice.get("/api/tasks?list=" + home + "&cursor=" + first.get("nextCursor").asText()).json();
        assertThat(second.get("items")).hasSize(10);
        assertThat(second.get("nextCursor").isNull()).isTrue();
        for (JsonNode item : second.get("items")) {
            assertThat(item.get("listId").asLong()).isEqualTo(home);
        }
    }

    // ---- AC12: account deletion ----

    @Test
    void deletingMyAccountDeletesAllMyListsTasksAndTags() throws Exception {
        Browser alice = signUp("Alice");
        Browser bob = signUp("Bob");
        long home = alice.post("/api/lists", "{\"name\":\"Home\"}").json().get("id").asLong();
        long t = alice.createTask("t", home).get("id").asLong();
        alice.put("/api/tasks/" + t, "{\"title\":\"t\",\"tags\":[\"x\"]}");
        alice.put("/api/tasks/" + alice.createTask("m", null).get("id").asLong(), "{\"title\":\"m\",\"tags\":[\"y\"]}");
        bob.put("/api/tasks/" + bob.createTask("b", null).get("id").asLong(), "{\"title\":\"b\",\"tags\":[\"x\"]}");

        assertThat(alice.post("/api/account/delete", "{\"password\":\"" + PASSWORD + "\"}").status()).isEqualTo(204);

        assertThat(count("select count(*) from lists where owner_id = ?", alice.id)).isZero();
        assertThat(count("select count(*) from tasks where owner_id = ?", alice.id)).isZero();
        assertThat(count("select count(*) from tags where owner_id = ?", alice.id)).isZero();
        assertThat(count("select count(*) from lists where owner_id = ?", bob.id)).isEqualTo(1);
        assertThat(bob.get("/api/tasks").json().get("total").asLong()).isEqualTo(1);
        assertThat(names(bob.get("/api/tags").json())).containsExactly("x");
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
    }

    private void fillTags(long owner, long list, long holder, int n) {
        for (int i = 0; i < n; i++) {
            jdbc.update("insert into tags (owner_id, list_id, name) values (?, ?, ?)", owner, list, "filler " + i);
        }
        jdbc.update("insert into task_tags (task_id, tag_id) select ?, id from tags where list_id = ?"
                + " and name like 'filler %'", holder, list);
    }

    private void seedTasks(long owner, long list, int n) {
        Instant t0 = Instant.parse("2026-09-01T08:00:00Z");
        for (int i = 0; i < n; i++) {
            Timestamp at = Timestamp.from(t0.plusSeconds(i));
            jdbc.update("insert into tasks (owner_id, list_id, title, created_at, updated_at) values (?, ?, ?, ?, ?)",
                    owner, list, "seed " + i, at, at);
        }
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private static String q(String raw) {
        return URLEncoder.encode(raw, StandardCharsets.UTF_8);
    }

    private static List<String> names(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.get("name").asText()));
        return out;
    }

    private static List<String> titles(JsonNode page) {
        List<String> out = new ArrayList<>();
        page.get("items").forEach(n -> out.add(n.get("title").asText()));
        return out;
    }

    private static void expectProblem(Resp r, int status, String code, String detail) throws IOException {
        assertThat(r.status()).as(r.body()).isEqualTo(status);
        assertThat(r.contentType()).startsWith("application/problem+json");
        assertThat(r.json().get("code").asText()).isEqualTo(code);
        assertThat(r.json().get("detail").asText()).isEqualTo(detail);
    }

    private Browser signUp(String name) throws Exception {
        Browser b = new Browser().start();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        b.email = name.toLowerCase() + "-" + suffix + "@example.com";
        Resp r = b.post("/api/auth/register", "{\"displayName\":\"" + name + " " + suffix + "\",\"email\":\""
                + b.email + "\",\"password\":\"" + PASSWORD + "\"}");
        assertThat(r.status()).as(r.body()).isEqualTo(201);
        b.id = r.json().get("id").asLong();
        return b;
    }

    /** Minimal browser: cookies, XSRF header like Angular. Any 5xx fails the test. */
    class Browser {
        final Map<String, String> cookies = new LinkedHashMap<>();
        long id;
        String email;

        Browser start() throws Exception {
            get("/api/auth/me");
            assertThat(cookies).containsKey("XSRF-TOKEN");
            return this;
        }

        void login(Browser account) throws Exception {
            Resp r = post("/api/auth/login", "{\"email\":\"" + account.email + "\",\"password\":\"" + PASSWORD + "\"}");
            assertThat(r.status()).as(r.body()).isEqualTo(200);
            id = account.id;
            email = account.email;
        }

        long myTasks() throws Exception {
            return get("/api/lists").json().get(0).get("id").asLong();
        }

        JsonNode createTask(String title, Long listId) throws Exception {
            Resp r = post("/api/tasks", "{\"title\":\"" + title + "\"" + (listId == null ? "" : ",\"listId\":" + listId)
                    + "}");
            assertThat(r.status()).as(r.body()).isEqualTo(201);
            return r.json();
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
            return new Resp(r.statusCode(), r.headers().firstValue("Content-Type").orElse(""),
                    r.headers().firstValue("Location").orElse(null), r.body());
        }
    }

    record Resp(int status, String contentType, String location, String body) {
        JsonNode json() throws IOException {
            return JSON.readTree(body);
        }
    }

    /** PUT / PATCH answer with a Task plus {@code nextTask} (0.15.0); GET answers with the plain Task. */
    private static JsonNode withoutNextTask(JsonNode result) {
        tools.jackson.databind.node.ObjectNode copy = ((tools.jackson.databind.node.ObjectNode) result).deepCopy();
        copy.remove("nextTask");
        return copy;
    }
}
