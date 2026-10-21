package com.taskapp.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * T5b end to end (specs/undo-trash-dnd-bulk.md AC18-AC22, contract 0.17.0): real HTTP, MySQL 8.4 with the migrations.
 * The server clock is a {@link MutableClock} reset to 2026-10-20T08:00Z before every test.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.rate-limit.login-per-ip-per-minute=100000",
        "app.rate-limit.register-per-ip-per-hour=100000",
        "app.session.max-length=36500d"})
class BulkIntegrationTest {

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
            return new MutableClock(T0);
        }
    }

    private static final Instant T0 = Instant.parse("2026-10-20T08:00:00Z");
    private static final String PASSWORD = "correct-horse";
    private static final ObjectMapper JSON = new ObjectMapper();

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MutableClock clock;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void resetClock() {
        clock.advance(Duration.between(clock.instant(), T0));
    }

    // ---- helpers ----

    private static String ids(long... ids) {
        return java.util.Arrays.stream(ids).mapToObj(Long::toString).collect(Collectors.joining(",", "[", "]"));
    }

    private static String action(String action, String value, long... ids) {
        return "{\"ids\":" + ids(ids) + ",\"action\":\"" + action + "\""
                + (value == null ? "" : ",\"value\":" + value) + "}";
    }

    private long task(Browser b, String title, long list) throws Exception {
        return b.createTask(title, list).get("id").asLong();
    }

    /** A daily repeating task due today (2026-10-20). */
    private long repeating(Browser b, String title, long list) throws Exception {
        long id = task(b, title, list);
        Resp r = b.put("/api/tasks/" + id, "{\"title\":\"" + title + "\",\"dueDate\":\"2026-10-20\","
                + "\"repeat\":{\"frequency\":\"DAILY\"}}");
        assertThat(r.status()).as(r.body()).isEqualTo(200);
        return id;
    }

    private JsonNode one(Browser b, long id) throws Exception {
        Resp r = b.get("/api/tasks/" + id);
        assertThat(r.status()).as(r.body()).isEqualTo(200);
        return r.json();
    }

    private static List<String> tags(JsonNode task) {
        List<String> out = new ArrayList<>();
        task.get("tags").forEach(t -> out.add(t.asText()));
        return out;
    }

    private static void expectFailedIds(Resp r, int status, String code, long... failed) throws IOException {
        expectProblem(r, status, code);
        List<Long> got = new ArrayList<>();
        r.json().get("failedIds").forEach(n -> got.add(n.asLong()));
        assertThat(got).as(r.body()).containsExactlyElementsOf(java.util.Arrays.stream(failed).boxed().toList());
    }

    // ---- every action ----

    @Test
    void completeReopenSetStatusAndPriority() throws Exception {
        Browser ana = signUp("Ana");
        long list = ana.createList("Home");
        long a = task(ana, "A", list);
        long b = task(ana, "B", list);
        long rep = repeating(ana, "Rep", list);

        Resp done = ana.post("/api/tasks/bulk", action("COMPLETE", null, a, b, rep));
        assertThat(done.status()).as(done.body()).isEqualTo(200);
        JsonNode result = done.json();
        assertThat(result.get("count").asInt()).isEqualTo(3);
        assertThat(result.get("tasks")).hasSize(3);
        assertThat(result.get("tasks").get(0).get("id").asLong()).isEqualTo(a);
        assertThat(result.get("tasks").get(0).get("status").asText()).isEqualTo("DONE");
        assertThat(result.get("tasks").get(0).get("done").asBoolean()).isTrue();
        assertThat(result.get("tasks").get(0).get("nextTask").isNull()).isTrue();
        JsonNode copy = result.get("tasks").get(2).get("nextTask");
        assertThat(copy.get("status").asText()).isEqualTo("TODO");
        assertThat(copy.get("repeat").get("frequency").asText()).isEqualTo("DAILY");
        assertThat(result.get("tasks").get(2).get("repeat").isNull()).isTrue();
        assertThat(count("select count(*) from tasks where list_id = ?", list)).isEqualTo(4);

        // Already done: returned unchanged, no copy, updatedAt untouched.
        String before = one(ana, a).get("updatedAt").asText();
        Resp again = ana.post("/api/tasks/bulk", action("COMPLETE", null, a, rep));
        assertThat(again.status()).isEqualTo(200);
        assertThat(again.json().get("tasks").get(1).get("nextTask").isNull()).isTrue();
        assertThat(one(ana, a).get("updatedAt").asText()).isEqualTo(before);
        assertThat(count("select count(*) from tasks where list_id = ?", list)).isEqualTo(4);

        assertThat(ana.post("/api/tasks/bulk", action("REOPEN", null, a, b)).status()).isEqualTo(200);
        assertThat(one(ana, a).get("status").asText()).isEqualTo("TODO");
        assertThat(one(ana, b).get("done").asBoolean()).isFalse();

        assertThat(ana.post("/api/tasks/bulk", action("SET_STATUS", "\"IN_PROGRESS\"", a, b)).status())
                .isEqualTo(200);
        assertThat(one(ana, a).get("status").asText()).isEqualTo("IN_PROGRESS");
        assertThat(ana.post("/api/tasks/bulk", action("SET_STATUS", "\"DONE\"", a)).status()).isEqualTo(200);
        assertThat(one(ana, a).get("done").asBoolean()).isTrue();

        Resp priority = ana.post("/api/tasks/bulk", action("SET_PRIORITY", "\"HIGH\"", a, b));
        assertThat(priority.status()).as(priority.body()).isEqualTo(200);
        assertThat(one(ana, b).get("priority").asText()).isEqualTo("HIGH");
        // Same priority again: nothing is touched.
        String stamp = one(ana, b).get("updatedAt").asText();
        assertThat(ana.post("/api/tasks/bulk", action("SET_PRIORITY", "\"HIGH\"", b)).status()).isEqualTo(200);
        assertThat(one(ana, b).get("updatedAt").asText()).isEqualTo(stamp);
    }

    @Test
    void addAndRemoveATagKeepTheOtherTags() throws Exception {
        Browser ana = signUp("Ana");
        long list = ana.createList("Home");
        long a = task(ana, "A", list);
        long b = task(ana, "B", list);
        long c = task(ana, "C", list);
        assertThat(ana.put("/api/tasks/" + a, "{\"title\":\"A\",\"tags\":[\"Home\",\"Errands\"]}").status())
                .isEqualTo(200);
        assertThat(ana.put("/api/tasks/" + b, "{\"title\":\"B\",\"tags\":[\"errands\"]}").status())
                .isEqualTo(200);

        Resp add = ana.post("/api/tasks/bulk", action("ADD_TAG", "\" Errands \"", a, b, c));
        assertThat(add.status()).as(add.body()).isEqualTo(200);
        assertThat(tags(one(ana, a))).containsExactlyInAnyOrder("Errands", "Home");
        assertThat(tags(one(ana, b))).containsExactly("Errands");
        assertThat(tags(one(ana, c))).containsExactly("Errands");
        assertThat(tags(add.json().get("tasks").get(2))).containsExactly("Errands");
        // Task b already had it (case ignored): untouched.
        assertThat(count("select count(*) from tags where list_id = ?", list)).isEqualTo(2);

        Resp remove = ana.post("/api/tasks/bulk", action("REMOVE_TAG", "\"ERRANDS\"", a, b, c));
        assertThat(remove.status()).as(remove.body()).isEqualTo(200);
        assertThat(tags(one(ana, a))).containsExactly("Home");
        assertThat(tags(one(ana, b))).isEmpty();
        assertThat(tags(one(ana, c))).isEmpty();
        // The tag nobody uses is gone.
        assertThat(count("select count(*) from tags where list_id = ?", list)).isEqualTo(1);
        // Removing a tag nobody has is fine.
        assertThat(ana.post("/api/tasks/bulk", action("REMOVE_TAG", "\"Nope\"", a, b)).status()).isEqualTo(200);
    }

    @Test
    void moveKeepsTagsAndUndoByBulkPatchPutsThemBack() throws Exception {
        Browser ana = signUp("Ana");
        long home = ana.createList("Home");
        long work = ana.createList("Work");
        long a = task(ana, "A", home);
        long b = task(ana, "B", home);
        assertThat(ana.put("/api/tasks/" + a, "{\"title\":\"A\",\"tags\":[\"Red\"]}").status()).isEqualTo(200);
        assertThat(ana.put("/api/tasks/" + b, "{\"title\":\"B\",\"tags\":[\"Red\",\"Blue\"]}").status())
                .isEqualTo(200);

        Resp move = ana.post("/api/tasks/bulk", action("MOVE", Long.toString(work), a, b));
        assertThat(move.status()).as(move.body()).isEqualTo(200);
        assertThat(move.json().get("tasks").get(0).get("listId").asLong()).isEqualTo(work);
        assertThat(tags(move.json().get("tasks").get(1))).containsExactlyInAnyOrder("Blue", "Red");
        assertThat(count("select count(*) from tags where list_id = ?", home)).isZero();
        assertThat(count("select count(*) from tags where list_id = ?", work)).isEqualTo(2);

        // Undo: each task back to Home with its own old tags, one call.
        Resp undo = ana.post("/api/tasks/bulk-patch", "{\"items\":["
                + "{\"id\":" + a + ",\"patch\":{\"listId\":" + home + ",\"tags\":[\"Red\"]}},"
                + "{\"id\":" + b + ",\"patch\":{\"listId\":" + home + ",\"tags\":[\"Red\",\"Blue\"]}}]}");
        assertThat(undo.status()).as(undo.body()).isEqualTo(200);
        assertThat(one(ana, a).get("listId").asLong()).isEqualTo(home);
        assertThat(tags(one(ana, a))).containsExactly("Red");
        assertThat(tags(one(ana, b))).containsExactlyInAnyOrder("Blue", "Red");
        assertThat(count("select count(*) from tags where list_id = ?", work)).isZero();

        // Moving to the list they are in changes nothing; to a list that is not mine is LIST_NOT_FOUND.
        assertThat(ana.post("/api/tasks/bulk", action("MOVE", Long.toString(home), a, b)).status()).isEqualTo(200);
        Browser ben = signUp("Ben");
        expectProblem(ana.post("/api/tasks/bulk", action("MOVE", Long.toString(ben.myTasks()), a)), 404,
                "LIST_NOT_FOUND");
        assertThat(one(ana, a).get("listId").asLong()).isEqualTo(home);
    }

    @Test
    void moveOverTheTagLimitChangesNothing() throws Exception {
        Browser ana = signUp("Ana");
        long home = ana.createList("Home");
        long full = ana.createList("Full");
        long target = task(ana, "Holder", full);
        long a = task(ana, "A", home);
        long b = task(ana, "B", home);
        // 200 tags in "Full" (through the database: only the cap is under test), none shared with a / b.
        for (int i = 0; i < 200; i++) {
            Long tag = insertTag(full, "t" + i);
            jdbc.update("insert into task_tags (task_id, tag_id) values (?, ?)", target, tag);
        }
        assertThat(ana.put("/api/tasks/" + a, "{\"title\":\"A\",\"tags\":[\"x1\"]}").status()).isEqualTo(200);
        assertThat(ana.put("/api/tasks/" + b, "{\"title\":\"B\",\"tags\":[\"x2\"]}").status()).isEqualTo(200);

        Resp r = ana.post("/api/tasks/bulk", action("MOVE", Long.toString(full), a, b));
        expectField(r, "tags", "TAG_LIMIT");
        assertThat(r.json().has("failedIds")).isFalse();
        assertThat(one(ana, a).get("listId").asLong()).isEqualTo(home);
        assertThat(one(ana, b).get("listId").asLong()).isEqualTo(home);
        assertThat(tags(one(ana, a))).containsExactly("x1");
        assertThat(count("select count(*) from tags where list_id = ?", full)).isEqualTo(200);
    }

    @Test
    void addTagOverTheListLimitChangesNothing() throws Exception {
        Browser ana = signUp("Ana");
        long list = ana.createList("Full");
        long holder = task(ana, "Holder", list);
        long a = task(ana, "A", list);
        long b = task(ana, "B", list);
        for (int i = 0; i < 200; i++) {
            jdbc.update("insert into task_tags (task_id, tag_id) values (?, ?)", holder, insertTag(list, "t" + i));
        }
        Resp r = ana.post("/api/tasks/bulk", action("ADD_TAG", "\"one-too-many\"", a, b));
        expectField(r, "tags", "TAG_LIMIT");
        assertThat(tags(one(ana, a))).isEmpty();
        assertThat(tags(one(ana, b))).isEmpty();
    }

    @Test
    void addTagToATaskWithTenTagsIsTooManyAndListsTheTasks() throws Exception {
        Browser ana = signUp("Ana");
        long list = ana.createList("Home");
        long a = task(ana, "A", list);
        long b = task(ana, "B", list);
        long c = task(ana, "C", list);
        String ten = IntStream.range(0, 10).mapToObj(i -> "\"k" + i + "\"").collect(Collectors.joining(","));
        assertThat(ana.put("/api/tasks/" + a, "{\"title\":\"A\",\"tags\":[" + ten + "]}").status()).isEqualTo(200);
        assertThat(ana.put("/api/tasks/" + c, "{\"title\":\"C\",\"tags\":[" + ten + "]}").status()).isEqualTo(200);

        Resp r = ana.post("/api/tasks/bulk", action("ADD_TAG", "\"eleven\"", c, b, a));
        expectProblem(r, 400, "VALIDATION_FAILED");
        assertThat(r.json().get("errors").get(0).get("field").asText()).isEqualTo("tags");
        assertThat(r.json().get("errors").get(0).get("code").asText()).isEqualTo("TOO_MANY");
        assertThat(r.json().get("failedIds")).hasSize(2);
        assertThat(r.json().get("failedIds").get(0).asLong()).isEqualTo(a);
        assertThat(r.json().get("failedIds").get(1).asLong()).isEqualTo(c);
        // Nothing changed, not even task b, which would have fitted.
        assertThat(tags(one(ana, b))).isEmpty();
        // A task that already has the tag is not "too many".
        assertThat(ana.post("/api/tasks/bulk", action("ADD_TAG", "\"k3\"", a, c)).status()).isEqualTo(200);
    }

    // ---- delete and restore ----

    @Test
    void deleteTrashesAllAndRestoreBringsThemBackInOneCall() throws Exception {
        Browser ana = signUp("Ana");
        long home = ana.createList("Home");
        long a = task(ana, "A", home);
        long b = task(ana, "B", home);
        long c = task(ana, "C", home);
        assertThat(ana.put("/api/tasks/" + a, "{\"title\":\"A\",\"tags\":[\"Red\"]}").status()).isEqualTo(200);

        Resp del = ana.post("/api/tasks/bulk", action("DELETE", null, a, b));
        assertThat(del.status()).as(del.body()).isEqualTo(200);
        assertThat(del.json().get("count").asInt()).isEqualTo(2);
        assertThat(del.json().get("tasks")).isEmpty();
        assertThat(ana.get("/api/tasks?list=" + home).json().get("total").asLong()).isEqualTo(1);
        assertThat(ana.get("/api/trash").json().get("total").asLong()).isEqualTo(2);
        assertThat(ana.get("/api/tasks/" + a).status()).isEqualTo(404);
        // Deleting a trashed one again: 404, and c is untouched.
        expectFailedIds(ana.post("/api/tasks/bulk", action("DELETE", null, a, c)), 404, "NOT_FOUND", a);
        assertThat(ana.get("/api/tasks/" + c).status()).isEqualTo(200);

        Resp restore = ana.post("/api/trash/restore", "{\"ids\":" + ids(b, a) + "}");
        assertThat(restore.status()).as(restore.body()).isEqualTo(200);
        assertThat(restore.json().get("tasks")).hasSize(2);
        assertThat(restore.json().get("tasks").get(0).get("id").asLong()).isEqualTo(b);
        assertThat(tags(restore.json().get("tasks").get(1))).containsExactly("Red");
        assertThat(ana.get("/api/trash").json().get("total").asLong()).isZero();
        assertThat(ana.get("/api/tasks?list=" + home).json().get("total").asLong()).isEqualTo(3);
    }

    @Test
    void restoreIsAllOrNothingAndMayCrossLists() throws Exception {
        Browser ana = signUp("Ana");
        long home = ana.createList("Home");
        long work = ana.createList("Work");
        long a = task(ana, "A", home);
        long b = task(ana, "B", work);
        long live = task(ana, "Live", home);
        assertThat(ana.delete("/api/tasks/" + a).status()).isEqualTo(204);
        assertThat(ana.delete("/api/tasks/" + b).status()).isEqualTo(204);

        // One id is not trashed: nothing is restored.
        expectFailedIds(ana.post("/api/trash/restore", "{\"ids\":" + ids(a, live, b) + "}"), 404, "NOT_FOUND", live);
        assertThat(ana.get("/api/trash").json().get("total").asLong()).isEqualTo(2);

        Resp ok = ana.post("/api/trash/restore", "{\"ids\":" + ids(a, b) + "}");
        assertThat(ok.status()).as(ok.body()).isEqualTo(200);
        assertThat(ana.get("/api/trash").json().get("total").asLong()).isZero();
        // Someone else's trashed task looks the same as a missing one.
        Browser ben = signUp("Ben");
        long theirs = task(ben, "Theirs", ben.myTasks());
        assertThat(ben.delete("/api/tasks/" + theirs).status()).isEqualTo(204);
        expectFailedIds(ana.post("/api/trash/restore", "{\"ids\":" + ids(theirs, 999999999L) + "}"), 404,
                "NOT_FOUND", theirs, 999999999L);
    }

    // ---- undo endpoints ----

    @Test
    void bulkPatchUndoesPrioritiesAndStatusesThatDiffered() throws Exception {
        Browser ana = signUp("Ana");
        long list = ana.createList("Home");
        long a = task(ana, "A", list);
        long b = task(ana, "B", list);
        assertThat(ana.patch("/api/tasks/" + a, "{\"priority\":\"LOW\"}").status()).isEqualTo(200);
        assertThat(ana.patch("/api/tasks/" + b, "{\"priority\":\"MEDIUM\",\"status\":\"IN_PROGRESS\"}").status())
                .isEqualTo(200);
        assertThat(ana.post("/api/tasks/bulk", action("SET_PRIORITY", "\"HIGH\"", a, b)).status()).isEqualTo(200);
        assertThat(ana.post("/api/tasks/bulk", action("COMPLETE", null, a, b)).status()).isEqualTo(200);

        Resp undo = ana.post("/api/tasks/bulk-patch", "{\"items\":["
                + "{\"id\":" + a + ",\"patch\":{\"priority\":\"LOW\",\"status\":\"TODO\"}},"
                + "{\"id\":" + b + ",\"patch\":{\"priority\":\"MEDIUM\",\"status\":\"IN_PROGRESS\"}}]}");
        assertThat(undo.status()).as(undo.body()).isEqualTo(200);
        assertThat(undo.json().get("count").asInt()).isEqualTo(2);
        assertThat(one(ana, a).get("priority").asText()).isEqualTo("LOW");
        assertThat(one(ana, a).get("status").asText()).isEqualTo("TODO");
        assertThat(one(ana, b).get("priority").asText()).isEqualTo("MEDIUM");
        assertThat(one(ana, b).get("status").asText()).isEqualTo("IN_PROGRESS");
    }

    @Test
    void bulkPatchItemProblemsAreReportedOnItemsAndRollBack() throws Exception {
        Browser ana = signUp("Ana");
        long list = ana.createList("Home");
        long a = task(ana, "A", list);
        long b = task(ana, "B", list);
        Resp r = ana.post("/api/tasks/bulk-patch", "{\"items\":["
                + "{\"id\":" + a + ",\"patch\":{\"priority\":\"HIGH\"}},"
                + "{\"id\":" + b + ",\"patch\":{\"status\":\"DONE\",\"done\":false}}]}");
        expectField(r, "items", "STATUS_CONFLICT");
        assertThat(r.json().get("errors").get(0).get("message").asText()).startsWith("Item 2: ");
        assertThat(one(ana, a).get("priority").asText()).isEqualTo("NONE");
        // An empty patch is an item problem too.
        expectField(ana.post("/api/tasks/bulk-patch", "{\"items\":[{\"id\":" + a + ",\"patch\":{}}]}"), "items",
                "REQUIRED");
        // Duplicate ids, nothing to do, too many.
        expectField(ana.post("/api/tasks/bulk-patch", "{\"items\":[{\"id\":" + a
                + ",\"patch\":{\"priority\":\"LOW\"}},{\"id\":" + a + ",\"patch\":{\"priority\":\"LOW\"}}]}"),
                "items", "DUPLICATE");
        expectField(ana.post("/api/tasks/bulk-patch", "{\"items\":[]}"), "items", "OUT_OF_RANGE");
        expectField(ana.post("/api/tasks/bulk-patch", "{}"), "items", "REQUIRED");
    }

    @Test
    void bulkUndoCompleteMixesRepeatingAndPlainTasksInOneCall() throws Exception {
        Browser ana = signUp("Ana");
        long list = ana.createList("Home");
        long plain = task(ana, "Plain", list);
        long rep = repeating(ana, "Rep", list);
        long edited = repeating(ana, "Edited", list);
        assertThat(ana.patch("/api/tasks/" + plain, "{\"status\":\"IN_PROGRESS\"}").status()).isEqualTo(200);

        Resp done = ana.post("/api/tasks/bulk", action("COMPLETE", null, plain, rep, edited));
        assertThat(done.status()).as(done.body()).isEqualTo(200);
        long repCopy = done.json().get("tasks").get(1).get("nextTask").get("id").asLong();
        long editedCopy = done.json().get("tasks").get(2).get("nextTask").get("id").asLong();
        // A teammate (here: the same person) edits one copy afterwards.
        clock.advance(Duration.ofSeconds(5));
        assertThat(ana.patch("/api/tasks/" + editedCopy, "{\"priority\":\"HIGH\"}").status()).isEqualTo(200);

        Resp undo = ana.post("/api/tasks/bulk-undo-complete", "{\"items\":["
                + "{\"id\":" + plain + ",\"status\":\"IN_PROGRESS\"},"
                + "{\"id\":" + rep + ",\"nextTaskId\":" + repCopy + "},"
                + "{\"id\":" + edited + ",\"nextTaskId\":" + editedCopy + "}]}");
        assertThat(undo.status()).as(undo.body()).isEqualTo(200);
        JsonNode results = undo.json().get("results");
        assertThat(results).hasSize(3);
        assertThat(results.get(0).get("id").asLong()).isEqualTo(plain);
        assertThat(results.get(0).get("nextTaskOutcome").asText()).isEqualTo("NONE");
        assertThat(results.get(0).get("status").asText()).isEqualTo("IN_PROGRESS");
        assertThat(results.get(1).get("nextTaskOutcome").asText()).isEqualTo("DELETED");
        assertThat(results.get(1).get("repeat").get("frequency").asText()).isEqualTo("DAILY");
        assertThat(results.get(2).get("nextTaskOutcome").asText()).isEqualTo("KEPT");
        assertThat(results.get(2).get("repeat").isNull()).isTrue();
        assertThat(ana.get("/api/tasks/" + repCopy).status()).isEqualTo(404);
        assertThat(ana.get("/api/tasks/" + editedCopy).status()).isEqualTo(200);
        assertThat(one(ana, rep).get("done").asBoolean()).isFalse();
        // Plain reopen touched nothing else (no rule invented).
        assertThat(one(ana, plain).get("repeat").isNull()).isTrue();
    }

    @Test
    void bulkUndoCompleteIsAllOrNothing() throws Exception {
        Browser ana = signUp("Ana");
        long list = ana.createList("Home");
        long rep = repeating(ana, "Rep", list);
        long other = task(ana, "Other", list);
        Resp done = ana.post("/api/tasks/bulk", action("COMPLETE", null, rep, other));
        long copy = done.json().get("tasks").get(0).get("nextTask").get("id").asLong();
        assertThat(ana.delete("/api/tasks/" + other).status()).isEqualTo(204);

        Resp undo = ana.post("/api/tasks/bulk-undo-complete", "{\"items\":["
                + "{\"id\":" + rep + ",\"nextTaskId\":" + copy + "},{\"id\":" + other + "}]}");
        expectFailedIds(undo, 404, "NOT_FOUND", other);
        // The first item was not undone either.
        assertThat(one(ana, rep).get("done").asBoolean()).isTrue();
        assertThat(ana.get("/api/tasks/" + copy).status()).isEqualTo(200);
        // DONE is not a status to go back to; a missing copy is not an error.
        expectField(ana.post("/api/tasks/bulk-undo-complete",
                "{\"items\":[{\"id\":" + rep + ",\"status\":\"DONE\"}]}"), "items", "OUT_OF_RANGE");
        Resp gone = ana.post("/api/tasks/bulk-undo-complete",
                "{\"items\":[{\"id\":" + rep + ",\"nextTaskId\":987654321}]}");
        assertThat(gone.status()).as(gone.body()).isEqualTo(200);
        assertThat(gone.json().get("results").get(0).get("nextTaskOutcome").asText()).isEqualTo("GONE");
    }

    // ---- failedIds, all-or-nothing, validation ----

    @Test
    void anyUnavailableTaskFailsTheCallWithEveryFailedIdAndChangesNothing() throws Exception {
        Browser ana = signUp("Ana");
        Browser ben = signUp("Ben");
        long list = ana.createList("Home");
        long a = task(ana, "A", list);
        long b = task(ana, "B", list);
        long trashed = task(ana, "T", list);
        assertThat(ana.delete("/api/tasks/" + trashed).status()).isEqualTo(204);
        long notMine = task(ben, "Ben's", ben.myTasks());
        long missing = 987654321L;

        for (String act : List.of("COMPLETE", "REOPEN", "DELETE")) {
            expectFailedIds(ana.post("/api/tasks/bulk", action(act, null, missing, a, notMine, b, trashed)), 404,
                    "NOT_FOUND", trashed, notMine, missing);
        }
        expectFailedIds(ana.post("/api/tasks/bulk", action("ADD_TAG", "\"x\"", a, notMine)), 404, "NOT_FOUND", notMine);
        expectFailedIds(ana.post("/api/tasks/bulk", action("SET_PRIORITY", "\"HIGH\"", a, notMine)), 404,
                "NOT_FOUND", notMine);
        expectFailedIds(ana.post("/api/tasks/bulk-patch", "{\"items\":[{\"id\":" + a
                + ",\"patch\":{\"done\":true}},{\"id\":" + notMine + ",\"patch\":{\"done\":true}}]}"), 404,
                "NOT_FOUND", notMine);
        assertThat(one(ana, a).get("done").asBoolean()).isFalse();
        assertThat(one(ana, b).get("done").asBoolean()).isFalse();
        assertThat(ana.get("/api/tasks?list=" + list).json().get("total").asLong()).isEqualTo(2);
        // Ben's task is untouched too.
        assertThat(one(ben, notMine).get("done").asBoolean()).isFalse();
        // A teammate loses access: same answer.
        member(list, ben);
        assertThat(ben.post("/api/tasks/bulk", action("COMPLETE", null, a, b)).status()).isEqualTo(200);
        jdbc.update("delete from list_members where list_id = ? and user_id = ?", list, ben.id);
        expectFailedIds(ben.post("/api/tasks/bulk", action("COMPLETE", null, a, b)), 404, "NOT_FOUND", a, b);
    }

    @Test
    void tasksOfSeveralListsAreRefusedAfterTheNotFoundCheck() throws Exception {
        Browser ana = signUp("Ana");
        Browser ben = signUp("Ben");
        long home = ana.createList("Home");
        long work = ana.createList("Work");
        long a = task(ana, "A", home);
        long b = task(ana, "B", work);
        long notMine = task(ben, "Ben's", ben.myTasks());

        expectField(ana.post("/api/tasks/bulk", action("COMPLETE", null, a, b)), "ids", "MIXED_LISTS");
        expectField(ana.post("/api/tasks/bulk-patch", "{\"items\":[{\"id\":" + a + ",\"patch\":{\"done\":true}},"
                + "{\"id\":" + b + ",\"patch\":{\"done\":true}}]}"), "items", "MIXED_LISTS");
        // The 404 comes first, so mixed lists never reveal anything about tasks the caller cannot see.
        expectFailedIds(ana.post("/api/tasks/bulk", action("COMPLETE", null, a, b, notMine)), 404, "NOT_FOUND",
                notMine);
        assertThat(one(ana, a).get("done").asBoolean()).isFalse();
        // undo-complete may mix lists.
        assertThat(ana.post("/api/tasks/bulk-undo-complete", "{\"items\":[{\"id\":" + a + "},{\"id\":" + b
                + "}]}").status()).isEqualTo(200);
    }

    @Test
    void requestValidation() throws Exception {
        Browser ana = signUp("Ana");
        long list = ana.createList("Home");
        long a = task(ana, "A", list);
        List<Long> many = IntStream.rangeClosed(1, 201).mapToObj(Long::valueOf).toList();

        expectField(ana.post("/api/tasks/bulk", "{\"ids\":[],\"action\":\"COMPLETE\"}"), "ids", "OUT_OF_RANGE");
        expectField(ana.post("/api/tasks/bulk", "{\"ids\":" + many + ",\"action\":\"COMPLETE\"}"), "ids",
                "OUT_OF_RANGE");
        expectField(ana.post("/api/tasks/bulk", "{\"action\":\"COMPLETE\"}"), "ids", "REQUIRED");
        expectField(ana.post("/api/tasks/bulk", "{\"ids\":" + ids(a, a) + ",\"action\":\"COMPLETE\"}"), "ids",
                "DUPLICATE");
        expectField(ana.post("/api/tasks/bulk", "{\"ids\":" + ids(a) + "}"), "action", "REQUIRED");
        expectField(ana.post("/api/tasks/bulk", action("SET_STATUS", null, a)), "value", "REQUIRED");
        expectField(ana.post("/api/tasks/bulk", action("ADD_TAG", "\"a,b\"", a)), "value", "INVALID_CHARACTERS");
        expectField(ana.post("/api/tasks/bulk", action("ADD_TAG", "\"   \"", a)), "value", "REQUIRED");
        expectField(ana.post("/api/tasks/bulk", action("ADD_TAG", "\"" + "x".repeat(31) + "\"", a)), "value",
                "TOO_LONG");
        // ids and value are reported together.
        Resp both = ana.post("/api/tasks/bulk", "{\"ids\":[],\"action\":\"MOVE\"}");
        expectProblem(both, 400, "VALIDATION_FAILED");
        assertThat(both.json().get("errors")).hasSize(2);
        // Wrong types and unknown values are a malformed request.
        for (String bad : List.of("{\"ids\":" + ids(a) + ",\"action\":\"EXPLODE\"}",
                action("SET_STATUS", "\"BLOCKED\"", a), action("SET_PRIORITY", "3", a), action("MOVE", "\"7\"", a),
                action("MOVE", "1.5", a), action("ADD_TAG", "5", a),
                "{\"ids\":[\"1\"],\"action\":\"COMPLETE\"}", "{\"ids\":[1.5],\"action\":\"COMPLETE\"}",
                "not json")) {
            expectProblem(ana.post("/api/tasks/bulk", bad), 400, "MALFORMED_REQUEST");
        }
        // Nothing above changed the task.
        assertThat(one(ana, a).get("done").asBoolean()).isFalse();
        // Exactly 200 is fine (ids that do not exist: 404 listing them all).
        List<Long> twoHundred = IntStream.rangeClosed(900_000_001, 900_000_200).mapToObj(Long::valueOf).toList();
        Resp edge = ana.post("/api/tasks/bulk", "{\"ids\":" + twoHundred + ",\"action\":\"COMPLETE\"}");
        expectProblem(edge, 404, "NOT_FOUND");
        assertThat(edge.json().get("failedIds")).hasSize(200);

        expectField(ana.post("/api/trash/restore", "{\"ids\":[]}"), "ids", "OUT_OF_RANGE");
        expectField(ana.post("/api/trash/restore", "{}"), "ids", "REQUIRED");
        expectField(ana.post("/api/trash/restore", "{\"ids\":" + ids(a, a) + "}"), "ids", "DUPLICATE");
        expectField(ana.post("/api/tasks/bulk-undo-complete", "{\"items\":[{\"id\":" + a + ",\"nextTaskId\":0}]}"),
                "items", "OUT_OF_RANGE");
        expectField(ana.post("/api/tasks/bulk-undo-complete", "{\"items\":[{\"nextTaskId\":5}]}"), "items",
                "REQUIRED");
    }

    @Test
    void signInAndCsrfAreRequired() throws Exception {
        Browser ana = signUp("Ana");
        long list = ana.createList("Home");
        long a = task(ana, "A", list);
        String[][] calls = {
                {"/api/tasks/bulk", action("COMPLETE", null, a)},
                {"/api/tasks/bulk-patch", "{\"items\":[{\"id\":" + a + ",\"patch\":{\"done\":true}}]}"},
                {"/api/tasks/bulk-undo-complete", "{\"items\":[{\"id\":" + a + "}]}"},
                {"/api/trash/restore", "{\"ids\":" + ids(a) + "}"}};
        Browser anonymous = new Browser().start();
        for (String[] call : calls) {
            Resp noCsrf = ana.sendWithoutCsrf("POST", call[0], call[1]);
            expectProblem(noCsrf, 403, "CSRF_INVALID");
            Resp anon = anonymous.post(call[0], call[1]);
            expectProblem(anon, 401, "UNAUTHENTICATED");
        }
        assertThat(one(ana, a).get("done").asBoolean()).isFalse();
    }

    @Test
    void membersOfASharedListCanBulkEditAndTheTrashIsShared() throws Exception {
        Browser ana = signUp("Ana");
        Browser ben = signUp("Ben");
        long list = ana.createList("Shared");
        member(list, ben);
        long a = task(ana, "A", list);
        long b = task(ben, "B", list);

        assertThat(ben.post("/api/tasks/bulk", action("SET_PRIORITY", "\"LOW\"", a, b)).status()).isEqualTo(200);
        assertThat(ben.post("/api/tasks/bulk", action("DELETE", null, a, b)).status()).isEqualTo(200);
        // Any member restores.
        assertThat(ana.post("/api/trash/restore", "{\"ids\":" + ids(a, b) + "}").status()).isEqualTo(200);
        assertThat(one(ana, a).get("priority").asText()).isEqualTo("LOW");
    }

    // ---- 200 tasks with recurrence ----

    @Test
    void aBatchOfTwoHundredTasksWithRecurrenceCompletesAndUndoes() throws Exception {
        Browser ana = signUp("Ana");
        long list = ana.createList("Big");
        long[] all = new long[200];
        for (int i = 0; i < 200; i++) {
            all[i] = i % 2 == 0 ? repeating(ana, "R" + i, list) : task(ana, "P" + i, list);
        }
        long start = System.nanoTime();
        Resp done = ana.post("/api/tasks/bulk", action("COMPLETE", null, all));
        long ms = (System.nanoTime() - start) / 1_000_000;
        assertThat(done.status()).as(done.body()).isEqualTo(200);
        assertThat(ms).as("200 completions in one request").isLessThan(30_000);
        JsonNode tasks = done.json().get("tasks");
        assertThat(tasks).hasSize(200);
        List<String> items = new ArrayList<>();
        int copies = 0;
        for (int i = 0; i < 200; i++) {
            assertThat(tasks.get(i).get("id").asLong()).isEqualTo(all[i]);
            assertThat(tasks.get(i).get("status").asText()).isEqualTo("DONE");
            JsonNode next = tasks.get(i).get("nextTask");
            if (i % 2 == 0) {
                assertThat(next.isNull()).isFalse();
                copies++;
                items.add("{\"id\":" + all[i] + ",\"nextTaskId\":" + next.get("id").asLong() + "}");
            } else {
                assertThat(next.isNull()).isTrue();
                items.add("{\"id\":" + all[i] + "}");
            }
        }
        assertThat(copies).isEqualTo(100);
        assertThat(count("select count(*) from tasks where list_id = ?", list)).isEqualTo(300);

        Resp undo = ana.post("/api/tasks/bulk-undo-complete", "{\"items\":" + items.stream()
                .collect(Collectors.joining(",", "[", "]")) + "}");
        assertThat(undo.status()).as(undo.body()).isEqualTo(200);
        JsonNode results = undo.json().get("results");
        assertThat(results).hasSize(200);
        for (int i = 0; i < 200; i++) {
            assertThat(results.get(i).get("nextTaskOutcome").asText()).isEqualTo(i % 2 == 0 ? "DELETED" : "NONE");
            assertThat(results.get(i).get("status").asText()).isEqualTo("TODO");
        }
        assertThat(count("select count(*) from tasks where list_id = ?", list)).isEqualTo(200);
        assertThat(count("select count(*) from tasks where list_id = ? and repeat_frequency is not null", list))
                .isEqualTo(100);

        // And all of it can go to the trash and come back in one call each.
        assertThat(ana.post("/api/tasks/bulk", action("DELETE", null, all)).status()).isEqualTo(200);
        assertThat(ana.get("/api/trash").json().get("total").asLong()).isEqualTo(200);
        assertThat(ana.post("/api/trash/restore", "{\"ids\":" + ids(all) + "}").status()).isEqualTo(200);
        assertThat(ana.get("/api/tasks?list=" + list).json().get("total").asLong()).isEqualTo(200);
    }

    // ---- concurrency: bulk against single writes on the same ids ----

    @Test
    void bulkAndSingleWritesOnOverlappingTasksNeverDeadlockOrFail() throws Exception {
        Browser ana = signUp("Ana");
        Browser ben = signUp("Ben");
        long home = ana.createList("Home");
        long work = ana.createList("Work");
        member(home, ben);
        member(work, ben);
        int n = 12;
        long[] all = new long[n];
        for (int i = 0; i < n; i++) {
            all[i] = i % 3 == 0 ? repeating(ana, "T" + i, home) : task(ana, "T" + i, home);
        }
        long[] firstHalf = java.util.Arrays.copyOfRange(all, 0, 8);
        long[] secondHalf = java.util.Arrays.copyOfRange(all, 4, 12);

        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<List<Integer>>> workers = new ArrayList<>();
            for (int w = 0; w < 8; w++) {
                final int worker = w;
                workers.add(() -> {
                    Browser who = worker % 2 == 0 ? ana : ben;
                    List<Integer> statuses = new ArrayList<>();
                    for (int i = 0; i < 25; i++) {
                        long one = all[(worker * 7 + i) % n];
                        int kind = (worker + i) % 10;
                        Resp r = switch (kind) {
                            case 0 -> who.post("/api/tasks/bulk", action("SET_PRIORITY", "\"HIGH\"", firstHalf));
                            case 1 -> who.post("/api/tasks/bulk", action("ADD_TAG", "\"t" + (i % 3) + "\"", secondHalf));
                            case 2 -> who.post("/api/tasks/bulk", action("REMOVE_TAG", "\"t" + (i % 3) + "\"", firstHalf));
                            case 3 -> who.post("/api/tasks/bulk", action("COMPLETE", null, secondHalf));
                            case 4 -> who.post("/api/tasks/bulk", action("MOVE", Long.toString(i % 2 == 0 ? work : home),
                                    firstHalf));
                            case 5 -> who.patch("/api/tasks/" + one, "{\"tags\":[\"s" + (i % 2) + "\"]}");
                            case 6 -> who.patch("/api/tasks/" + one, "{\"done\":" + (i % 2 == 0) + "}");
                            case 7 -> who.post("/api/tasks/bulk", action("DELETE", null, firstHalf));
                            case 8 -> who.post("/api/trash/restore", "{\"ids\":" + ids(secondHalf) + "}");
                            default -> who.post("/api/tasks/bulk", action("REOPEN", null, firstHalf));
                        };
                        statuses.add(r.status());
                    }
                    return statuses;
                });
            }
            List<Future<List<Integer>>> futures = pool.invokeAll(workers, 4, TimeUnit.MINUTES);
            Set<Integer> seen = new java.util.TreeSet<>();
            for (Future<List<Integer>> f : futures) {
                seen.addAll(f.get());   // an AssertionError from a 5xx surfaces here
            }
            assertThat(seen).isSubsetOf(200, 400, 404);
            assertThat(seen).contains(200);
        } finally {
            pool.shutdownNow();
        }
        // Whatever the interleaving, every task is either live or trashed, in one of the two lists, and no tag
        // is left without a task.
        assertThat(count("select count(*) from tasks where id in (" + ids(all).replaceAll("[\\[\\]]", "") + ")"))
                .isEqualTo(n);
        assertThat(count("select count(*) from tags where list_id in (?, ?) and id not in "
                + "(select tag_id from task_tags)", home, work)).isZero();
    }

    // ---- infrastructure ----

    private Long insertTag(long list, String name) {
        jdbc.update("insert into tags (owner_id, list_id, name) select owner_id, id, ? from lists where id = ?",
                name, list);
        return jdbc.queryForObject("select id from tags where list_id = ? and name = ?", Long.class, list, name);
    }

    private void member(long list, Browser b) {
        jdbc.update("insert into list_members (list_id, user_id) values (?, ?)", list, b.id);
    }

    private int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> out = new ArrayList<>();
        node.propertyNames().forEach(out::add);
        return out;
    }

    private static void expectProblem(Resp r, int status, String code) throws IOException {
        assertThat(r.status()).as(r.body()).isEqualTo(status);
        assertThat(r.contentType()).startsWith("application/problem+json");
        assertThat(r.json().get("code").asText()).isEqualTo(code);
    }

    private static void expectField(Resp r, String field, String code) throws IOException {
        expectProblem(r, 400, "VALIDATION_FAILED");
        JsonNode errors = r.json().get("errors");
        assertThat(errors).as(r.body()).hasSize(1);
        assertThat(errors.get(0).get("field").asText()).isEqualTo(field);
        assertThat(errors.get(0).get("code").asText()).isEqualTo(code);
    }

    private Browser signUp(String name) throws Exception {
        String email = name.toLowerCase() + "-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        Browser b = new Browser().start();
        b.email = email;
        b.displayName = name + " " + UUID.randomUUID().toString().substring(0, 8);
        Resp r = b.post("/api/auth/register", "{\"displayName\":\"" + b.displayName + "\",\"email\":\"" + email
                + "\",\"password\":\"" + PASSWORD + "\"}");
        assertThat(r.status()).as(r.body()).isEqualTo(201);
        b.id = r.json().get("id").asLong();
        return b;
    }

    /** Minimal browser: cookies, XSRF header like Angular. Any 5xx fails the test. */
    class Browser {
        final Map<String, String> cookies = new LinkedHashMap<>();
        long id;
        String email;
        String displayName;

        Browser start() throws Exception {
            get("/api/auth/me");
            assertThat(cookies).containsKey("XSRF-TOKEN");
            return this;
        }

        long myTasks() throws Exception {
            return get("/api/lists").json().get(0).get("id").asLong();
        }

        long createList(String name) throws Exception {
            Resp r = post("/api/lists", "{\"name\":\"" + name + "\"}");
            assertThat(r.status()).as(r.body()).isEqualTo(201);
            return r.json().get("id").asLong();
        }

        JsonNode createTask(String title, Long listId) throws Exception {
            String body = JSON.writeValueAsString(listId == null ? Map.of("title", title)
                    : Map.of("title", title, "listId", listId));
            Resp r = post("/api/tasks", body);
            assertThat(r.status()).as(r.body()).isEqualTo(201);
            return r.json();
        }

        Resp get(String path) throws Exception {
            return send("GET", path, null, true);
        }

        Resp post(String path, String body) throws Exception {
            return send("POST", path, body, true);
        }

        Resp put(String path, String body) throws Exception {
            return send("PUT", path, body, true);
        }

        Resp patch(String path, String body) throws Exception {
            return send("PATCH", path, body, true);
        }

        Resp delete(String path) throws Exception {
            return send("DELETE", path, null, true);
        }

        Resp sendWithoutCsrf(String method, String path, String body) throws Exception {
            return send(method, path, body, false);
        }

        Resp send(String method, String path, String body, boolean csrf) throws Exception {
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
                if (csrf && !"GET".equals(method) && cookies.containsKey("XSRF-TOKEN")) {
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
            return new Resp(r.statusCode(), r.headers().firstValue("Content-Type").orElse(""), r.body());
        }
    }

    record Resp(int status, String contentType, String body) {
        JsonNode json() throws IOException {
            return JSON.readTree(body);
        }
    }
}
