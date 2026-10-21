package com.taskapp.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.zip.CRC32;

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
 * T5c end to end (specs/undo-trash-dnd-bulk.md AC23-AC28, contract 0.18.0): "My order" ({@code sort=manual}), {@code PUT
 * /api/tasks/{id}/position}, every write that sets or keeps a position, and the anchor cursor. Real HTTP, MySQL 8.4
 * with the migrations.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.rate-limit.login-per-ip-per-minute=100000",
        "app.rate-limit.register-per-ip-per-hour=100000",
        "app.session.max-length=36500d"})
class ManualOrderIntegrationTest {

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

    // ---- helpers ----

    private long task(Browser b, String title, long list) throws Exception {
        return b.createTask(title, list).get("id").asLong();
    }

    private long repeating(Browser b, String title, long list) throws Exception {
        long id = task(b, title, list);
        Resp r = b.put("/api/tasks/" + id, "{\"title\":\"" + title + "\",\"dueDate\":\"2026-10-20\","
                + "\"repeat\":{\"frequency\":\"DAILY\"}}");
        assertThat(r.status()).as(r.body()).isEqualTo(200);
        return id;
    }

    private static String ids(long... ids) {
        return Arrays.stream(ids).mapToObj(Long::toString).collect(Collectors.joining(",", "[", "]"));
    }

    private Long pos(long id) {
        return jdbc.queryForObject("select position from tasks where id = ?", Long.class, id);
    }

    private long lowest(long list) {
        return jdbc.queryForObject("select min(position) from tasks where list_id = ? and deleted_at is null",
                Long.class, list);
    }

    /** The ids of the first page of "My order" (filter all), at most 50. */
    private List<Long> order(Browser b, long list) throws Exception {
        return order(b, list, "all");
    }

    private List<Long> order(Browser b, long list, String filter) throws Exception {
        Resp r = b.get("/api/tasks?list=" + list + "&sort=manual&filter=" + filter);
        assertThat(r.status()).as(r.body()).isEqualTo(200);
        return idsOf(r.json());
    }

    private static List<Long> idsOf(JsonNode page) {
        List<Long> out = new ArrayList<>();
        page.get("items").forEach(t -> out.add(t.get("id").asLong()));
        return out;
    }

    private static List<Long> list(long... ids) {
        return Arrays.stream(ids).boxed().toList();
    }

    private Resp reposition(Browser b, long id, Long afterId) throws Exception {
        return b.put("/api/tasks/" + id + "/position", "{\"afterId\":" + afterId + "}");
    }

    private void drop(Browser b, long id, Long afterId) throws Exception {
        Resp r = reposition(b, id, afterId);
        assertThat(r.status()).as(r.body()).isEqualTo(204);
        assertThat(r.body()).isEmpty();
    }

    private JsonNode one(Browser b, long id) throws Exception {
        Resp r = b.get("/api/tasks/" + id);
        assertThat(r.status()).as(r.body()).isEqualTo(200);
        return r.json();
    }

    private void member(long list, Browser b) {
        jdbc.update("insert into list_members (list_id, user_id) values (?, ?)", list, b.id);
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

    // ---- AC23: order, NULL fallback, new tasks on top ----

    @Test
    void unpositionedTasksComeNewestFirstAndNewTasksGoToTheTop() throws Exception {
        Browser ana = signUp("Ana");
        long list = ana.createList("Home");
        long a = task(ana, "A", list);
        long b = task(ana, "B", list);
        long c = task(ana, "C", list);
        // What the existing tasks look like after V15.
        jdbc.update("update tasks set position = null where list_id = ?", list);
        assertThat(order(ana, list)).isEqualTo(list(c, b, a));

        long d = task(ana, "D", list);
        assertThat(pos(d)).isEqualTo(-1024L);
        assertThat(order(ana, list)).isEqualTo(list(d, c, b, a));
        long e = task(ana, "E", list);
        assertThat(pos(e)).isEqualTo(-2048L);
        assertThat(order(ana, list)).isEqualTo(list(e, d, c, b, a));

        // The position is internal: it is in no response.
        assertThat(one(ana, e).propertyNames()).doesNotContain("position");
        assertThat(ana.get("/api/tasks?list=" + list + "&sort=manual").body()).doesNotContain("position");
        // Other sorts are unchanged.
        Resp oldest = ana.get("/api/tasks?list=" + list + "&sort=oldest");
        assertThat(idsOf(oldest.json())).isEqualTo(list(a, b, c, d, e));
    }

    @Test
    void manualKeepsTheDoneGroupLastAndAnyFilterTagAndSearchWork() throws Exception {
        Browser ana = signUp("Ana");
        long list = ana.createList("Home");
        long a = task(ana, "Apple", list);
        long b = task(ana, "Banana", list);
        long c = task(ana, "Cherry", list);
        assertThat(ana.patch("/api/tasks/" + b, "{\"done\":true}").status()).isEqualTo(200);
        assertThat(order(ana, list)).isEqualTo(list(c, a, b));
        assertThat(order(ana, list, "active")).isEqualTo(list(c, a));
        assertThat(order(ana, list, "done")).isEqualTo(list(b));
        assertThat(idsOf(ana.get("/api/tasks?list=" + list + "&sort=manual&q=an").json())).isEqualTo(list(b));
        assertThat(ana.put("/api/tasks/" + a, "{\"title\":\"Apple\",\"tags\":[\"Red\"]}").status()).isEqualTo(200);
        assertThat(idsOf(ana.get("/api/tasks?list=" + list + "&sort=manual&tag=red").json())).isEqualTo(list(a));
    }

    // ---- position-writing paths ----

    @Test
    void statusChangesUndoAndRestoresKeepThePosition() throws Exception {
        Browser ana = signUp("Ana");
        long list = ana.createList("Home");
        long a = task(ana, "A", list);
        long b = task(ana, "B", list);
        long c = task(ana, "C", list);
        long rep = repeating(ana, "Rep", list);
        long[] all = {a, b, c, rep};
        Map<Long, Long> before = new LinkedHashMap<>();
        for (long id : all) {
            before.put(id, pos(id));
        }

        assertThat(ana.patch("/api/tasks/" + a, "{\"done\":true}").status()).isEqualTo(200);
        assertThat(ana.patch("/api/tasks/" + a, "{\"done\":false}").status()).isEqualTo(200);
        assertThat(ana.put("/api/tasks/" + a, "{\"title\":\"A\",\"status\":\"DONE\"}").status()).isEqualTo(200);
        assertThat(ana.put("/api/tasks/" + a, "{\"title\":\"A\",\"status\":\"IN_PROGRESS\"}").status()).isEqualTo(200);
        assertThat(ana.post("/api/tasks/bulk", "{\"ids\":" + ids(a, b, c) + ",\"action\":\"COMPLETE\"}").status())
                .isEqualTo(200);
        assertThat(ana.post("/api/tasks/bulk", "{\"ids\":" + ids(a, b) + ",\"action\":\"REOPEN\"}").status())
                .isEqualTo(200);
        assertThat(ana.post("/api/tasks/bulk", "{\"ids\":" + ids(a, b)
                + ",\"action\":\"SET_STATUS\",\"value\":\"DONE\"}").status()).isEqualTo(200);
        assertThat(ana.post("/api/tasks/bulk-patch", "{\"items\":[{\"id\":" + a + ",\"patch\":{\"done\":false}},"
                + "{\"id\":" + b + ",\"patch\":{\"status\":\"TODO\"}}]}").status()).isEqualTo(200);
        assertThat(ana.post("/api/tasks/bulk", "{\"ids\":" + ids(a, b)
                + ",\"action\":\"SET_PRIORITY\",\"value\":\"HIGH\"}").status()).isEqualTo(200);
        for (long id : all) {
            if (id != rep) {
                assertThat(pos(id)).as("task " + id).isEqualTo(before.get(id));
            }
        }

        // Trash and restore (single and bulk) keep it.
        assertThat(ana.delete("/api/tasks/" + a).status()).isEqualTo(204);
        assertThat(ana.post("/api/trash/" + a + "/restore", "").status()).isEqualTo(200);
        assertThat(ana.post("/api/tasks/bulk", "{\"ids\":" + ids(b, c) + ",\"action\":\"DELETE\"}").status())
                .isEqualTo(200);
        assertThat(ana.post("/api/trash/restore", "{\"ids\":" + ids(b, c) + "}").status()).isEqualTo(200);
        for (long id : new long[] {a, b, c}) {
            assertThat(pos(id)).as("task " + id).isEqualTo(before.get(id));
        }

        // Completing a repeating task makes a copy on top; undo-complete (single and bulk) puts the task back where it was.
        long lowestBefore = lowest(list);
        Resp done = ana.patch("/api/tasks/" + rep, "{\"done\":true}");
        long copy = done.json().get("nextTask").get("id").asLong();
        assertThat(pos(copy)).isEqualTo(lowestBefore - 1024);
        assertThat(pos(rep)).isEqualTo(before.get(rep));
        Resp undo = ana.post("/api/tasks/" + rep + "/undo-complete", "{\"nextTaskId\":" + copy + "}");
        assertThat(undo.status()).as(undo.body()).isEqualTo(200);
        assertThat(pos(rep)).isEqualTo(before.get(rep));

        Resp again = ana.post("/api/tasks/bulk", "{\"ids\":" + ids(rep) + ",\"action\":\"COMPLETE\"}");
        long copy2 = again.json().get("tasks").get(0).get("nextTask").get("id").asLong();
        assertThat(pos(copy2)).isLessThan(before.get(rep));
        assertThat(ana.post("/api/tasks/bulk-undo-complete", "{\"items\":[{\"id\":" + rep + ",\"nextTaskId\":"
                + copy2 + "}]}").status()).isEqualTo(200);
        assertThat(pos(rep)).isEqualTo(before.get(rep));
    }

    @Test
    void theRepeatCopyIsFirstInItsGroup() throws Exception {
        Browser ana = signUp("Ana");
        long list = ana.createList("Home");
        long rep = repeating(ana, "Rep", list);
        long x = task(ana, "X", list);
        long y = task(ana, "Y", list);
        assertThat(order(ana, list)).isEqualTo(list(y, x, rep));

        long copy = ana.patch("/api/tasks/" + rep, "{\"done\":true}").json().get("nextTask").get("id").asLong();

        assertThat(order(ana, list)).isEqualTo(list(copy, y, x, rep));
        assertThat(order(ana, list, "active")).isEqualTo(list(copy, y, x));
    }

    @Test
    void aMoveToAnotherListPutsTheTaskOnTopThereWhicheverWriteDidIt() throws Exception {
        Browser ana = signUp("Ana");
        long home = ana.createList("Home");
        long work = ana.createList("Work");
        long w1 = task(ana, "W1", work);
        long w2 = task(ana, "W2", work);
        long h1 = task(ana, "H1", home);
        long h2 = task(ana, "H2", home);
        long h3 = task(ana, "H3", home);
        long h4 = task(ana, "H4", home);
        assertThat(order(ana, work)).isEqualTo(list(w2, w1));

        // PATCH, PUT and bulk-patch.
        long min = lowest(work);
        assertThat(ana.patch("/api/tasks/" + h1, "{\"listId\":" + work + "}").status()).isEqualTo(200);
        assertThat(pos(h1)).isEqualTo(min - 1024);
        assertThat(order(ana, work)).isEqualTo(list(h1, w2, w1));
        assertThat(ana.put("/api/tasks/" + h2, "{\"title\":\"H2\",\"listId\":" + work + "}").status()).isEqualTo(200);
        assertThat(pos(h2)).isEqualTo(min - 2048);
        assertThat(order(ana, work)).isEqualTo(list(h2, h1, w2, w1));
        assertThat(ana.post("/api/tasks/bulk-patch", "{\"items\":[{\"id\":" + h3 + ",\"patch\":{\"listId\":" + work
                + "}}]}").status()).isEqualTo(200);
        assertThat(order(ana, work)).isEqualTo(list(h3, h2, h1, w2, w1));
        // The undo of a move (a PATCH back) puts it on top of the old list too.
        assertThat(ana.patch("/api/tasks/" + h3, "{\"listId\":" + home + "}").status()).isEqualTo(200);
        assertThat(order(ana, home)).isEqualTo(list(h3, h4));
        assertThat(order(ana, work)).isEqualTo(list(h2, h1, w2, w1));
    }

    @Test
    void bulkMoveKeepsTheRequestOrderAtTheTopOfTheTargetList() throws Exception {
        Browser ana = signUp("Ana");
        long home = ana.createList("Home");
        long work = ana.createList("Work");
        long w1 = task(ana, "W1", work);
        long a = task(ana, "A", home);
        long b = task(ana, "B", home);
        long c = task(ana, "C", home);
        long d = task(ana, "D", home);

        // Request order c, a, d (b stays): the first id ends up first.
        Resp move = ana.post("/api/tasks/bulk", "{\"ids\":" + ids(c, a, d) + ",\"action\":\"MOVE\",\"value\":" + work + "}");
        assertThat(move.status()).as(move.body()).isEqualTo(200);
        assertThat(order(ana, work)).isEqualTo(list(c, a, d, w1));
        List<Long> answered = new ArrayList<>();
        move.json().get("tasks").forEach(t -> answered.add(t.get("id").asLong()));
        assertThat(answered).isEqualTo(list(c, a, d));
        assertThat(order(ana, home)).isEqualTo(list(b));

        // Moving a task that is already in the target list changes nothing.
        long before = pos(d);
        assertThat(ana.post("/api/tasks/bulk", "{\"ids\":" + ids(d) + ",\"action\":\"MOVE\",\"value\":" + work + "}")
                .status()).isEqualTo(200);
        assertThat(pos(d)).isEqualTo(before);
        assertThat(ana.post("/api/tasks/bulk", "{\"ids\":" + ids(b) + ",\"action\":\"MOVE\",\"value\":" + work + "}")
                .status()).isEqualTo(200);
        assertThat(order(ana, work)).isEqualTo(list(b, c, a, d, w1));
    }

    // ---- PUT /api/tasks/{id}/position ----

    @Test
    void aDropPutsTheTaskRightAfterAnotherOrAtTheTopWithoutTouchingUpdatedAt() throws Exception {
        Browser ana = signUp("Ana");
        long list = ana.createList("Home");
        long[] t = new long[6];
        for (int i = 1; i <= 5; i++) {
            t[i] = task(ana, "T" + i, list);
        }
        assertThat(order(ana, list)).isEqualTo(list(t[5], t[4], t[3], t[2], t[1]));
        Map<Long, String> stamps = new LinkedHashMap<>();
        for (int i = 1; i <= 5; i++) {
            stamps.put(t[i], one(ana, t[i]).get("updatedAt").asText());
        }

        drop(ana, t[1], t[5]);
        assertThat(order(ana, list)).isEqualTo(list(t[5], t[1], t[4], t[3], t[2]));
        drop(ana, t[2], null);
        assertThat(order(ana, list)).isEqualTo(list(t[2], t[5], t[1], t[4], t[3]));
        drop(ana, t[2], t[3]);
        assertThat(order(ana, list)).isEqualTo(list(t[5], t[1], t[4], t[3], t[2]));
        // Where it already is: succeeds, nothing visible changes.
        drop(ana, t[3], t[4]);
        drop(ana, t[5], null);
        assertThat(order(ana, list)).isEqualTo(list(t[5], t[1], t[4], t[3], t[2]));

        for (int i = 1; i <= 5; i++) {
            assertThat(one(ana, t[i]).get("updatedAt").asText()).as("updatedAt of " + i).isEqualTo(stamps.get(t[i]));
        }
        // A missing afterId means the top.
        assertThat(ana.put("/api/tasks/" + t[4] + "/position", "{}").status()).isEqualTo(204);
        assertThat(order(ana, list).get(0)).isEqualTo(t[4]);
    }

    @Test
    void aDropInAGroupWithUnpositionedTasksNumbersTheWholeGroupFirst() throws Exception {
        Browser ana = signUp("Ana");
        long list = ana.createList("Home");
        long a = task(ana, "A", list);
        long b = task(ana, "B", list);
        long c = task(ana, "C", list);
        long d = task(ana, "D", list);
        long done1 = task(ana, "Done1", list);
        assertThat(ana.patch("/api/tasks/" + done1, "{\"done\":true}").status()).isEqualTo(200);
        jdbc.update("update tasks set position = null where list_id = ?", list);
        assertThat(order(ana, list)).isEqualTo(list(d, c, b, a, done1));

        drop(ana, d, b);

        assertThat(order(ana, list)).isEqualTo(list(c, b, d, a, done1));
        // The group got 1024, 2048, ... in its old order (d c b a), then d moved between b and a.
        assertThat(pos(c)).isEqualTo(2048L);
        assertThat(pos(b)).isEqualTo(3072L);
        assertThat(pos(a)).isEqualTo(4096L);
        assertThat(pos(d)).isEqualTo(3072L + 512L);
        // The other group was not touched.
        assertThat(pos(done1)).isNull();
    }

    @Test
    void whenNoWholeNumberFitsTheGroupIsRenumberedAndTheOrderStaysRight() throws Exception {
        Browser ana = signUp("Ana");
        long list = ana.createList("Home");
        long bottom = task(ana, "Bottom", list);
        long anchor = task(ana, "Anchor", list);
        List<Long> expected = new ArrayList<>(list(anchor, bottom));
        // 14 drops between the same two neighbours: the gap of 1024 halves until it is gone (after 10).
        for (int i = 1; i <= 14; i++) {
            long n = task(ana, "N" + i, list);
            drop(ana, n, anchor);
            expected.add(1, n);
            assertThat(order(ana, list)).as("after drop " + i).isEqualTo(expected);
        }
        List<Long> positions = new ArrayList<>();
        expected.forEach(id -> positions.add(pos(id)));
        assertThat(positions).doesNotContainNull().doesNotHaveDuplicates().isSorted();
        // After a renumber the group is back to steps of 1024 somewhere.
        assertThat(positions.get(1) - positions.get(0)).isGreaterThanOrEqualTo(1L);
    }

    @Test
    void tiedPositionsAreRenumberedByADrop() throws Exception {
        Browser ana = signUp("Ana");
        long list = ana.createList("Home");
        long a = task(ana, "A", list);
        long b = task(ana, "B", list);
        long c = task(ana, "C", list);
        jdbc.update("update tasks set position = 5000 where list_id = ?", list);
        // Same position: newest first, so c, b, a.
        assertThat(order(ana, list)).isEqualTo(list(c, b, a));

        drop(ana, a, c);

        assertThat(order(ana, list)).isEqualTo(list(c, a, b));
        assertThat(Arrays.asList(pos(c), pos(a), pos(b))).doesNotHaveDuplicates().isSorted();
    }

    @Test
    void doneTasksAreOrderedInsideTheirOwnGroup() throws Exception {
        Browser ana = signUp("Ana");
        long list = ana.createList("Home");
        long t1 = task(ana, "T1", list);
        long t2 = task(ana, "T2", list);
        long t3 = task(ana, "T3", list);
        long open = task(ana, "Open", list);
        for (long id : new long[] {t1, t2, t3}) {
            assertThat(ana.patch("/api/tasks/" + id, "{\"done\":true}").status()).isEqualTo(200);
        }
        assertThat(order(ana, list)).isEqualTo(list(open, t3, t2, t1));

        drop(ana, t1, null);
        assertThat(order(ana, list)).isEqualTo(list(open, t1, t3, t2));
        drop(ana, t3, t2);
        assertThat(order(ana, list, "done")).isEqualTo(list(t1, t2, t3));

        expectField(reposition(ana, t1, open), "afterId", "OTHER_GROUP");
        expectField(reposition(ana, open, t2), "afterId", "OTHER_GROUP");
        assertThat(order(ana, list)).isEqualTo(list(open, t1, t2, t3));
    }

    @Test
    void positionErrorsFollowTheContractOrder() throws Exception {
        Browser ana = signUp("Ana");
        Browser ben = signUp("Ben");
        long home = ana.createList("Home");
        long work = ana.createList("Work");
        long a = task(ana, "A", home);
        long b = task(ana, "B", home);
        long other = task(ana, "Other list", work);
        long trashed = task(ana, "Trashed", home);
        assertThat(ana.delete("/api/tasks/" + trashed).status()).isEqualTo(204);
        long benTask = task(ben, "Ben's", ben.myTasks());
        List<Long> before = order(ana, home);

        // Validation: afterId below 1, and a body that is not JSON / not an integer.
        expectField(reposition(ana, a, 0L), "afterId", "OUT_OF_RANGE");
        expectField(reposition(ana, a, -4L), "afterId", "OUT_OF_RANGE");
        for (String body : new String[] {"{\"afterId\":\"7\"}", "{\"afterId\":1.5}", "{\"afterId\":true}", "[]", "nope"}) {
            expectProblem(ana.put("/api/tasks/" + a + "/position", body), 400, "MALFORMED_REQUEST");
        }
        expectProblem(ana.put("/api/tasks/abc/position", "{}"), 400, "MALFORMED_REQUEST");
        // A 400 on the body comes before the 404 on the task.
        expectField(reposition(ana, 999_999L, 0L), "afterId", "OUT_OF_RANGE");

        // 404, the same body for a missing / trashed / foreign task or afterId.
        Resp missingId = reposition(ana, 999_999L, a);
        expectProblem(missingId, 404, "NOT_FOUND");
        for (Resp r : List.of(reposition(ana, trashed, a), reposition(ana, benTask, a), reposition(ana, a, 999_999L),
                reposition(ana, a, trashed), reposition(ana, a, benTask), reposition(ben, a, b))) {
            expectProblem(r, 404, "NOT_FOUND");
            assertThat(r.json().get("title").asText()).isEqualTo(missingId.json().get("title").asText());
            assertThat(r.json().get("detail").asText()).isEqualTo(missingId.json().get("detail").asText());
        }
        // 404 before SELF: a task that does not exist is not "itself".
        expectProblem(reposition(ana, 999_999L, 999_999L), 404, "NOT_FOUND");

        expectField(reposition(ana, a, a), "afterId", "SELF");
        expectField(reposition(ana, a, other), "afterId", "OTHER_LIST");

        assertThat(order(ana, home)).isEqualTo(before);
        // Sign-in and CSRF.
        expectProblem(ana.sendWithoutCsrf("PUT", "/api/tasks/" + a + "/position", "{}"), 403, "CSRF_INVALID");
        expectProblem(new Browser().start().put("/api/tasks/" + a + "/position", "{}"), 401, "UNAUTHENTICATED");
    }

    @Test
    void everyMemberSeesTheSameOrderAndAnyMemberCanReorder() throws Exception {
        Browser ana = signUp("Ana");
        Browser ben = signUp("Ben");
        long list = ana.createList("Shared");
        member(list, ben);
        long a = task(ana, "A", list);
        long b = task(ben, "B", list);
        long c = task(ana, "C", list);

        drop(ben, a, null);
        assertThat(order(ana, list)).isEqualTo(list(a, c, b));
        assertThat(order(ben, list)).isEqualTo(list(a, c, b));
        drop(ana, b, a);
        assertThat(order(ben, list)).isEqualTo(list(a, b, c));
    }

    // ---- AC26: paging ----

    private List<Long> createMany(Browser b, long list, int n) throws Exception {
        List<Long> created = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            created.add(task(b, "T" + i, list));
        }
        Collections.reverse(created); // "My order": the newest first
        return created;
    }

    private Resp page(Browser b, long list, String cursor) throws Exception {
        return b.get("/api/tasks?list=" + list + "&sort=manual" + (cursor == null ? "" : "&cursor=" + cursor));
    }

    @Test
    void aDropAfterTheLastLoadedRowComesBackOnTheNextPageAndNothingIsSkipped() throws Exception {
        Browser ana = signUp("Ana");
        long list = ana.createList("Big");
        List<Long> order = createMany(ana, list, 60); // t60 ... t1
        Resp first = page(ana, list, null);
        List<Long> loaded = idsOf(first.json());
        assertThat(loaded).isEqualTo(order.subList(0, 50));
        String cursor = first.json().get("nextCursor").asText();
        long lastLoaded = loaded.get(49);

        // Drop the top row right after the last loaded row (the user dragged it to the bottom of the screen).
        drop(ana, order.get(0), lastLoaded);
        // ... and one task from the second page too.
        drop(ana, order.get(59), lastLoaded);

        Resp second = page(ana, list, cursor);
        assertThat(second.status()).as(second.body()).isEqualTo(200);
        List<Long> next = idsOf(second.json());
        assertThat(next.subList(0, 2)).containsExactly(order.get(59), order.get(0));
        assertThat(second.json().get("nextCursor").isNull()).isTrue();
        // The client de-duplicates by id: every task is there once, none missing.
        List<Long> merged = new ArrayList<>(loaded);
        next.stream().filter(id -> !merged.contains(id)).forEach(merged::add);
        assertThat(merged).hasSize(60).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(order);
        assertThat(second.json().get("total").asInt()).isEqualTo(60);
    }

    @Test
    void theCursorSurvivesARenumberingOfTheGroupBetweenPages() throws Exception {
        Browser ana = signUp("Ana");
        long list = ana.createList("Big");
        List<Long> order = createMany(ana, list, 55);
        Resp first = page(ana, list, null);
        String cursor = first.json().get("nextCursor").asText();

        // A real renumber: 12 drops between the same two neighbours at the top, past the loaded page's end.
        long x = order.get(0);
        long y = order.get(1);
        List<Long> top = new ArrayList<>(list(x, y));
        for (int i = 0; i < 12; i++) {
            long dropped = order.get(54 - i);
            drop(ana, dropped, x);
            top.add(1, dropped);
        }
        assertThat(order(ana, list).subList(0, 14)).isEqualTo(top);

        Resp second = page(ana, list, cursor);
        assertThat(second.status()).as(second.body()).isEqualTo(200);
        // Everything after the anchor (the 50th task of the old order) in the new order: the 5 tasks left, minus the
        // ones that moved to the top, plus nothing skipped.
        List<Long> all = idsOf(first.json());
        idsOf(second.json()).stream().filter(id -> !all.contains(id)).forEach(all::add);
        List<Long> expectedRest = new ArrayList<>(order.subList(50, 55));
        expectedRest.removeAll(top);
        assertThat(all).containsAll(expectedRest);
        assertThat(all).doesNotHaveDuplicates();
    }

    @Test
    void anAnchorTickedBetweenPagesStillContinuesThroughTheNotDoneGroupThenTheDoneGroup() throws Exception {
        Browser ana = signUp("Ana");
        long list = ana.createList("Big");
        List<Long> order = createMany(ana, list, 55);
        Resp first = page(ana, list, null);
        String cursor = first.json().get("nextCursor").asText();
        long anchor = order.get(49);

        assertThat(ana.patch("/api/tasks/" + anchor, "{\"done\":true}").status()).isEqualTo(200);

        List<Long> rest = idsOf(page(ana, list, cursor).json());
        // The 5 not-done rows after the anchor, then the done group (the anchor itself, shown there).
        assertThat(rest).isEqualTo(new ArrayList<>(order.subList(50, 55)) {{ add(anchor); }});
    }

    @Test
    void anAnchorThatIsGoneMakesTheCursorInvalid() throws Exception {
        Browser ana = signUp("Ana");
        long list = ana.createList("Big");
        long other = ana.createList("Other");
        List<Long> order = createMany(ana, list, 52);
        String cursor = page(ana, list, null).json().get("nextCursor").asText();
        long anchor = order.get(49);

        // Trashed.
        assertThat(ana.delete("/api/tasks/" + anchor).status()).isEqualTo(204);
        expectField(page(ana, list, cursor), "cursor", "INVALID_CURSOR");
        // Restored: valid again.
        assertThat(ana.post("/api/trash/" + anchor + "/restore", "").status()).isEqualTo(200);
        assertThat(page(ana, list, cursor).status()).isEqualTo(200);
        // Moved to another list.
        assertThat(ana.patch("/api/tasks/" + anchor, "{\"listId\":" + other + "}").status()).isEqualTo(200);
        expectField(page(ana, list, cursor), "cursor", "INVALID_CURSOR");
        // Deleted for good.
        jdbc.update("delete from tasks where id = ?", anchor);
        expectField(page(ana, list, cursor), "cursor", "INVALID_CURSOR");
    }

    @Test
    void anAnchorInAListOfSomeoneElseIsNeverReached() throws Exception {
        Browser ana = signUp("Ana");
        Browser ben = signUp("Ben");
        long list = ana.createList("Big");
        List<Long> order = createMany(ana, list, 51);
        String cursor = page(ana, list, null).json().get("nextCursor").asText();

        // Ben is not a member: the list is a 404 first, whatever the cursor.
        expectProblem(page(ben, list, cursor), 404, "LIST_NOT_FOUND");
        // Ben's own list with Ana's cursor: its fingerprint belongs to Ana's list.
        expectField(page(ben, ben.myTasks(), cursor), "cursor", "INVALID_CURSOR");
        // A cursor forged for Ben's own list whose anchor is Ana's task: the anchor is not in that list.
        long benList = ben.myTasks();
        task(ben, "b1", benList);
        String own = encodeManual(benList, order.get(0));
        expectField(page(ben, benList, own), "cursor", "INVALID_CURSOR");
    }

    /** A well-formed manual cursor for "My order" of {@code list}, filter all, no tag, no search; anchor {@code id}. */
    private String encodeManual(long list, long id) throws Exception {
        String canonical = list + "\nALL\nMANUAL\n\n";
        byte[] hash = java.security.MessageDigest.getInstance("SHA-256")
                .digest(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        ByteBuffer payload = ByteBuffer.allocate(1 + 1 + 8 + 1 + 8);
        payload.put((byte) 4).put((byte) 5).put(hash, 0, 8).put((byte) 0).putLong(id);
        return withChecksum(payload.array());
    }

    private static String withChecksum(byte[] payload) {
        CRC32 crc = new CRC32();
        crc.update(payload, 0, payload.length);
        byte[] all = ByteBuffer.allocate(payload.length + 4).put(payload).putInt((int) crc.getValue()).array();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(all);
    }

    @Test
    void cursorsIssuedBeforeVersion4AreInvalidForEverySort() throws Exception {
        Browser ana = signUp("Ana");
        long list = ana.createList("Big");
        createMany(ana, list, 51);
        for (String sort : new String[] {"newest", "oldest", "due", "priority", "title", "manual"}) {
            Resp first = ana.get("/api/tasks?list=" + list + "&sort=" + sort);
            String cursor = first.json().get("nextCursor").asText();
            byte[] raw = Base64.getUrlDecoder().decode(cursor);
            assertThat(raw[0]).as(sort).isEqualTo((byte) 4);
            assertThat(ana.get("/api/tasks?list=" + list + "&sort=" + sort + "&cursor=" + cursor).status())
                    .as(sort).isEqualTo(200);

            byte[] v3 = Arrays.copyOf(raw, raw.length - 4);
            v3[0] = 3;
            Resp old = ana.get("/api/tasks?list=" + list + "&sort=" + sort + "&cursor=" + withChecksum(v3));
            expectField(old, "cursor", "INVALID_CURSOR");
        }
        // A cursor of another sort is not usable with manual.
        String newest = ana.get("/api/tasks?list=" + list + "&sort=newest").json().get("nextCursor").asText();
        expectField(page(ana, list, newest), "cursor", "INVALID_CURSOR");
    }

    // ---- infrastructure ----

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
