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
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
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

import com.taskapp.reminder.ReminderProcessor;
import com.taskapp.security.ratelimit.MutableClock;
import com.taskapp.task.TrashService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * T5a end to end (specs/undo-trash-dnd-bulk.md AC1-AC17, contract 0.16.0): real HTTP, MySQL 8.4 with the migrations.
 * The server clock is a {@link MutableClock} that every test resets to 2026-10-20T08:00Z.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.rate-limit.login-per-ip-per-minute=100000",
        "app.rate-limit.register-per-ip-per-hour=100000",
        "app.session.max-length=36500d"})
class TrashIntegrationTest {

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

    @Autowired
    ReminderProcessor processor;

    @Autowired
    TrashService trash;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void resetClock() {
        setClock(T0);
        // Leftovers of other tests must not count in the purge and "empty" assertions.
        jdbc.update("delete from tasks where deleted_at is not null");
    }

    private void setClock(Instant to) {
        clock.advance(Duration.between(clock.instant(), to));
    }

    // ---- AC1, AC8: a deleted task is in the trash and nowhere else ----

    @Test
    void deleteMovesTheTaskToTheTrashAndHidesItEverywhere() throws Exception {
        Browser ana = signUp("Ana");
        long list = ana.createList("Home");
        long id = ana.createTask("Milk please", list).get("id").asLong();
        ana.createTask("Eggs", list);
        assertThat(ana.put("/api/tasks/" + id, "{\"title\":\"Milk please\",\"tags\":[\"Shop\"]}").status())
                .isEqualTo(200);

        Resp deleted = ana.delete("/api/tasks/" + id);
        assertThat(deleted.status()).as(deleted.body()).isEqualTo(204);
        assertThat(deleted.body()).isEmpty();

        // GET / PUT / PATCH / DELETE on it: 404 NOT_FOUND.
        expectProblem(ana.get("/api/tasks/" + id), 404, "NOT_FOUND");
        expectProblem(ana.put("/api/tasks/" + id, "{\"title\":\"x\"}"), 404, "NOT_FOUND");
        expectProblem(ana.patch("/api/tasks/" + id, "{\"done\":true}"), 404, "NOT_FOUND");
        expectProblem(ana.patch("/api/tasks/" + id, "{\"title\":\"x\",\"tags\":[\"a\"]}"), 404, "NOT_FOUND");
        expectProblem(ana.delete("/api/tasks/" + id), 404, "NOT_FOUND");

        // Page, total, search, tag filter and the list's count skip it.
        JsonNode page = ana.get("/api/tasks?list=" + list).json();
        assertThat(page.get("total").asLong()).isEqualTo(1);
        assertThat(page.get("items")).hasSize(1);
        assertThat(ana.get("/api/tasks?list=" + list + "&q=milk").json().get("total").asLong()).isZero();
        assertThat(ana.get("/api/tasks?list=" + list + "&tag=Shop").json().get("total").asLong()).isZero();
        assertThat(ana.get("/api/lists/" + list).json().get("taskCount").asLong()).isEqualTo(1);

        // Its tag is still in use (AC8), so it is still listed and still counted.
        assertThat(ana.get("/api/tags?list=" + list).body()).contains("Shop");
        assertThat(count("select count(*) from tags where list_id = ?", list)).isEqualTo(1);

        // The trash has it, with who and when, and 30 days to go.
        JsonNode trashPage = ana.get("/api/trash").json();
        assertThat(fieldNames(trashPage)).containsExactly("items", "total", "nextCursor");
        assertThat(trashPage.get("total").asLong()).isEqualTo(1);
        JsonNode item = trashPage.get("items").get(0);
        assertThat(item.get("id").asLong()).isEqualTo(id);
        assertThat(item.get("listId").asLong()).isEqualTo(list);
        assertThat(item.get("title").asText()).isEqualTo("Milk please");
        assertThat(item.get("tags").toString()).isEqualTo("[\"Shop\"]");
        assertThat(item.get("deletedBy").get("displayName").asText()).isEqualTo(ana.displayName);
        assertThat(Instant.parse(item.get("deletedAt").asText())).isEqualTo(T0);
        assertThat(Instant.parse(item.get("purgeAt").asText())).isEqualTo(T0.plus(Duration.ofDays(30)));
        assertThat(fieldNames(item)).endsWith("deletedAt", "deletedBy", "purgeAt");
    }

    @Test
    void deleteNeedsCsrfAndSignIn() throws Exception {
        Browser ana = signUp("Ana");
        long id = ana.createTask("A", null).get("id").asLong();
        Browser anonymous = new Browser().start();
        expectProblem(anonymous.delete("/api/tasks/" + id), 401, "UNAUTHENTICATED");
        expectProblem(anonymous.get("/api/trash"), 401, "UNAUTHENTICATED");
        Resp noCsrf = ana.sendWithoutCsrf("DELETE", "/api/tasks/" + id, null);
        expectProblem(noCsrf, 403, "CSRF_INVALID");
        expectProblem(ana.sendWithoutCsrf("POST", "/api/trash/" + id + "/restore", null), 403, "CSRF_INVALID");
        expectProblem(ana.sendWithoutCsrf("DELETE", "/api/trash/" + id, null), 403, "CSRF_INVALID");
        expectProblem(ana.sendWithoutCsrf("POST", "/api/trash/empty", null), 403, "CSRF_INVALID");
        expectProblem(ana.sendWithoutCsrf("POST", "/api/tasks/" + id + "/undo-complete",
                "{\"nextTaskId\":1}"), 403, "CSRF_INVALID");
        assertThat(ana.get("/api/tasks/" + id).status()).isEqualTo(200);
    }

    // ---- AC3, AC10: restore ----

    @Test
    void restoreBringsTheTaskBackExactlyAsItWas() throws Exception {
        Browser ana = signUp("Ana");
        long list = ana.createList("Home");
        long id = ana.createTask("Pay rent", list).get("id").asLong();
        Resp set = ana.put("/api/tasks/" + id, "{\"title\":\"Pay rent\",\"description\":\"Flat 4\","
                + "\"dueDate\":\"2026-11-01\",\"dueTime\":\"09:00\",\"priority\":\"HIGH\",\"status\":\"IN_PROGRESS\","
                + "\"tags\":[\"Money\",\"Home\"],\"repeat\":{\"frequency\":\"MONTHLY\"}}");
        assertThat(set.status()).as(set.body()).isEqualTo(200);
        JsonNode before = ana.get("/api/tasks/" + id).json();

        clock.advance(Duration.ofHours(5));
        assertThat(ana.delete("/api/tasks/" + id).status()).isEqualTo(204);
        clock.advance(Duration.ofDays(2));
        Resp restored = ana.post("/api/trash/" + id + "/restore", null);
        assertThat(restored.status()).as(restored.body()).isEqualTo(200);
        assertThat(restored.json()).isEqualTo(before);
        assertThat(ana.get("/api/tasks/" + id).json()).isEqualTo(before);
        assertThat(ana.get("/api/trash").json().get("total").asLong()).isZero();
        assertThat(ana.get("/api/tasks?list=" + list).json().get("total").asLong()).isEqualTo(1);

        // Not in the trash any more: a second restore and a delete-forever are 404.
        expectProblem(ana.post("/api/trash/" + id + "/restore", null), 404, "NOT_FOUND");
        expectProblem(ana.delete("/api/trash/" + id), 404, "NOT_FOUND");
    }

    @Test
    void trashCallsOnTasksThatAreNotTrashedOrUnknownOrMalformed() throws Exception {
        Browser ana = signUp("Ana");
        Browser bob = signUp("Bob");
        long live = ana.createTask("Live", null).get("id").asLong();
        long bobs = bob.createTask("Bob's", null).get("id").asLong();
        assertThat(bob.delete("/api/tasks/" + bobs).status()).isEqualTo(204);

        expectProblem(ana.post("/api/trash/" + live + "/restore", null), 404, "NOT_FOUND");
        expectProblem(ana.delete("/api/trash/" + live), 404, "NOT_FOUND");
        expectProblem(ana.post("/api/trash/999999999/restore", null), 404, "NOT_FOUND");
        // Someone else's trashed task is a 404, never a 403.
        expectProblem(ana.post("/api/trash/" + bobs + "/restore", null), 404, "NOT_FOUND");
        expectProblem(ana.delete("/api/trash/" + bobs), 404, "NOT_FOUND");
        expectProblem(ana.post("/api/trash/abc/restore", null), 400, "MALFORMED_REQUEST");
        expectProblem(ana.delete("/api/trash/abc"), 400, "MALFORMED_REQUEST");
        assertThat(ana.get("/api/trash").json().get("total").asLong()).isZero();
        assertThat(count("select count(*) from tasks where id = ? and deleted_at is not null", bobs)).isEqualTo(1);
    }

    // ---- AC4: delete forever, empty ----

    @Test
    void deleteForeverRemovesTheTaskAndTagsNothingElseUses() throws Exception {
        Browser ana = signUp("Ana");
        long list = ana.createList("Home");
        long a = ana.createTask("A", list).get("id").asLong();
        long b = ana.createTask("B", list).get("id").asLong();
        ana.put("/api/tasks/" + a, "{\"title\":\"A\",\"tags\":[\"Only-a\",\"Both\"]}");
        ana.put("/api/tasks/" + b, "{\"title\":\"B\",\"tags\":[\"Both\"]}");
        ana.delete("/api/tasks/" + a);

        Resp gone = ana.delete("/api/trash/" + a);
        assertThat(gone.status()).as(gone.body()).isEqualTo(204);
        assertThat(count("select count(*) from tasks where id = ?", a)).isZero();
        assertThat(count("select count(*) from task_tags where task_id = ?", a)).isZero();
        assertThat(ana.get("/api/tags?list=" + list).body()).contains("Both").doesNotContain("Only-a");
        expectProblem(ana.delete("/api/trash/" + a), 404, "NOT_FOUND");
    }

    @Test
    void emptyDeletesEverythingIOrOneListCanSee() throws Exception {
        Browser ana = signUp("Ana");
        long home = ana.createList("Home");
        long work = ana.createList("Work");
        for (int i = 0; i < 3; i++) {
            long id = ana.createTask("h" + i, home).get("id").asLong();
            ana.delete("/api/tasks/" + id);
        }
        long w = ana.createTask("w", work).get("id").asLong();
        ana.put("/api/tasks/" + w, "{\"title\":\"w\",\"tags\":[\"Wtag\"]}");
        ana.delete("/api/tasks/" + w);
        long liveId = ana.createTask("live", home).get("id").asLong();

        expectProblem(ana.post("/api/trash/empty?list=999999999", null), 404, "LIST_NOT_FOUND");
        assertThat(ana.get("/api/trash").json().get("total").asLong()).isEqualTo(4);

        Resp onlyWork = ana.post("/api/trash/empty?list=" + work, null);
        assertThat(onlyWork.status()).as(onlyWork.body()).isEqualTo(200);
        assertThat(onlyWork.body()).isEqualTo("{\"deleted\":1}");
        assertThat(ana.get("/api/tags?list=" + work).body()).isEqualTo("[]");
        assertThat(ana.get("/api/trash").json().get("total").asLong()).isEqualTo(3);

        Resp all = ana.post("/api/trash/empty", null);
        assertThat(all.body()).isEqualTo("{\"deleted\":3}");
        assertThat(ana.post("/api/trash/empty", null).body()).isEqualTo("{\"deleted\":0}");
        assertThat(ana.get("/api/tasks/" + liveId).status()).isEqualTo(200);
        assertThat(count("select count(*) from tasks where deleted_at is not null and list_id in (?, ?)", home, work))
                .isZero();
    }

    // ---- AC2: paging, list filter, order ----

    @Test
    void trashIsPagedNewestDeletedFirstAndFilteredByList() throws Exception {
        Browser ana = signUp("Ana");
        long home = ana.createList("Home");
        long work = ana.createList("Work");
        List<Long> deletedOrder = new ArrayList<>();
        for (int i = 0; i < 55; i++) {
            long id = ana.createTask("t" + i, i % 5 == 0 ? work : home).get("id").asLong();
            deletedOrder.add(id);
            ana.delete("/api/tasks/" + id);
            if (i % 7 == 0) {
                clock.advance(Duration.ofSeconds(1));
            }
        }
        JsonNode first = ana.get("/api/trash").json();
        assertThat(first.get("items")).hasSize(50);
        assertThat(first.get("total").asLong()).isEqualTo(55);
        assertThat(first.get("nextCursor").isNull()).isFalse();
        JsonNode second = ana.get("/api/trash?cursor=" + first.get("nextCursor").asText()).json();
        assertThat(second.get("items")).hasSize(5);
        assertThat(second.get("nextCursor").isNull()).isTrue();
        List<Long> seen = new ArrayList<>();
        first.get("items").forEach(n -> seen.add(n.get("id").asLong()));
        second.get("items").forEach(n -> seen.add(n.get("id").asLong()));
        // Newest deleted first (ties by id descending) = exactly the reverse of the order they were deleted in.
        List<Long> expected = new ArrayList<>(deletedOrder);
        java.util.Collections.reverse(expected);
        assertThat(seen).containsExactlyElementsOf(expected);

        JsonNode workOnly = ana.get("/api/trash?list=" + work).json();
        assertThat(workOnly.get("total").asLong()).isEqualTo(11);
        workOnly.get("items").forEach(n -> assertThat(n.get("listId").asLong()).isEqualTo(work));
        // An empty `list` means every list; an unknown one is 404 first, even with a broken cursor.
        assertThat(ana.get("/api/trash?list=").json().get("total").asLong()).isEqualTo(55);
        expectProblem(ana.get("/api/trash?list=999999999&cursor=zzz"), 404, "LIST_NOT_FOUND");
    }

    @Test
    void trashCursorsAreBoundToTheirListAndKind() throws Exception {
        Browser ana = signUp("Ana");
        long home = ana.createList("Home");
        for (int i = 0; i < 51; i++) {
            ana.delete("/api/tasks/" + ana.createTask("t" + i, home).get("id").asLong());
        }
        String cursor = ana.get("/api/trash").json().get("nextCursor").asText();
        expectField(ana.get("/api/trash?list=" + home + "&cursor=" + cursor), "cursor", "INVALID_CURSOR");
        expectField(ana.get("/api/trash?cursor=garbage"), "cursor", "INVALID_CURSOR");
        // A task-list cursor is not a trash cursor, and the reverse.
        ana.createTask("x", home);
        for (int i = 0; i < 50; i++) {
            ana.createTask("y" + i, home);
        }
        String taskCursor = ana.get("/api/tasks?list=" + home).json().get("nextCursor").asText();
        expectField(ana.get("/api/trash?cursor=" + taskCursor), "cursor", "INVALID_CURSOR");
        expectField(ana.get("/api/tasks?list=" + home + "&cursor=" + cursor), "cursor", "INVALID_CURSOR");
    }

    // ---- AC6, AC7: shared lists, lost access, deleted accounts ----

    @Test
    void membersShareOneTrashAndAnyMemberCanRestoreOrDeleteForever() throws Exception {
        Browser ana = signUp("Ana");
        Browser bob = signUp("Bob");
        long list = ana.createList("Team");
        member(list, bob);
        long a = ana.createTask("From Ana", list).get("id").asLong();
        long b = bob.createTask("From Bob", list).get("id").asLong();
        assertThat(ana.delete("/api/tasks/" + a).status()).isEqualTo(204);
        assertThat(bob.delete("/api/tasks/" + b).status()).isEqualTo(204);

        JsonNode bobsTrash = bob.get("/api/trash").json();
        assertThat(bobsTrash.get("total").asLong()).isEqualTo(2);
        assertThat(bobsTrash.get("items").get(0).get("deletedBy").get("displayName").asText())
                .isEqualTo(bob.displayName);
        assertThat(bobsTrash.get("items").get(1).get("deletedBy").get("displayName").asText())
                .isEqualTo(ana.displayName);
        assertThat(ana.get("/api/trash").json().get("total").asLong()).isEqualTo(2);

        // Bob restores Ana's task; Ana deletes Bob's for good.
        assertThat(bob.post("/api/trash/" + a + "/restore", null).status()).isEqualTo(200);
        assertThat(ana.delete("/api/trash/" + b).status()).isEqualTo(204);
        assertThat(ana.get("/api/tasks/" + a).status()).isEqualTo(200);
        assertThat(count("select count(*) from tasks where id = ?", b)).isZero();
    }

    @Test
    void leavingOrBeingRemovedHidesTheListsTrashAndEveryCallOnItIs404() throws Exception {
        Browser ana = signUp("Ana");
        Browser bob = signUp("Bob");
        long list = ana.createList("Team");
        member(list, bob);
        long id = ana.createTask("Shared", list).get("id").asLong();
        long other = ana.createTask("Other", list).get("id").asLong();
        ana.delete("/api/tasks/" + id);
        ana.delete("/api/tasks/" + other);
        assertThat(bob.get("/api/trash").json().get("total").asLong()).isEqualTo(2);

        assertThat(bob.post("/api/lists/" + list + "/leave", null).status()).isEqualTo(204);
        assertThat(bob.get("/api/trash").json().get("total").asLong()).isZero();
        expectProblem(bob.get("/api/trash?list=" + list), 404, "LIST_NOT_FOUND");
        expectProblem(bob.post("/api/trash/" + id + "/restore", null), 404, "NOT_FOUND");
        expectProblem(bob.delete("/api/trash/" + id), 404, "NOT_FOUND");
        expectProblem(bob.post("/api/trash/empty?list=" + list, null), 404, "LIST_NOT_FOUND");
        assertThat(bob.post("/api/trash/empty", null).body()).isEqualTo("{\"deleted\":0}");
        assertThat(count("select count(*) from tasks where list_id = ?", list)).isEqualTo(2);
    }

    @Test
    void deletingAListTakesItsTrashedTasksAndDeletingAnAccountKeepsTheirTrashedTasksWithoutAName() throws Exception {
        Browser ana = signUp("Ana");
        Browser bob = signUp("Bob");
        long list = ana.createList("Team");
        long doomed = ana.createList("Doomed");
        member(list, bob);
        long gone = ana.createTask("In doomed", doomed).get("id").asLong();
        ana.delete("/api/tasks/" + gone);
        long id = bob.createTask("Bob deletes", list).get("id").asLong();
        assertThat(bob.delete("/api/tasks/" + id).status()).isEqualTo(204);

        assertThat(ana.delete("/api/lists/" + doomed).status()).isEqualTo(204);
        assertThat(count("select count(*) from tasks where id = ?", gone)).isZero();

        // Bob's account goes while a task he trashed sits in Ana's list: it stays, with deletedBy null.
        Resp del = bob.post("/api/account/delete", "{\"password\":\"" + PASSWORD + "\"}");
        assertThat(del.status()).as(del.body()).isEqualTo(204);
        JsonNode item = ana.get("/api/trash").json().get("items").get(0);
        assertThat(item.get("id").asLong()).isEqualTo(id);
        assertThat(item.get("deletedBy").isNull()).isTrue();
        assertThat(item.get("addedBy").isNull()).isTrue();
        assertThat(ana.post("/api/trash/" + id + "/restore", null).status()).isEqualTo(200);
    }

    // ---- AC8: reminders, recurrence, notifications ----

    @Test
    void aTrashedTaskFiresNoReminderAndRestoreRearmsOnlyAFutureOne() throws Exception {
        Browser ana = signUp("Ana");
        long a = timed(ana, "Early", "2026-10-20", "10:00", "AT_DUE");
        long b = timed(ana, "Later", "2026-10-20", "12:00", "AT_DUE");
        ana.delete("/api/tasks/" + a);
        ana.delete("/api/tasks/" + b);

        setClock(Instant.parse("2026-10-20T10:00:30Z"));
        processor.runOnce();
        assertThat(count("select count(*) from notifications where user_id = ?", ana.id)).isZero();
        // The reminder rows are left alone while trashed.
        assertThat(count("select count(*) from reminders where task_id in (?, ?) and status = 'PENDING'", a, b))
                .isEqualTo(2);

        // 10:00 has passed, 12:00 has not: restore re-arms only the second.
        JsonNode early = ana.post("/api/trash/" + a + "/restore", null).json();
        assertThat(early.get("myReminder").asText()).isEqualTo("AT_DUE");
        assertThat(early.get("myReminderPassed").asBoolean()).isTrue();
        JsonNode later = ana.post("/api/trash/" + b + "/restore", null).json();
        assertThat(later.get("myReminderPassed").asBoolean()).isFalse();
        assertThat(count("select count(*) from reminders where task_id = ? and status = 'SKIPPED'", a)).isEqualTo(1);
        assertThat(count("select count(*) from reminders where task_id = ? and status = 'PENDING'", b)).isEqualTo(1);

        processor.runOnce();
        assertThat(count("select count(*) from notifications where user_id = ?", ana.id)).isZero();
        setClock(Instant.parse("2026-10-20T12:00:30Z"));
        processor.runOnce();
        assertThat(count("select count(*) from notifications where user_id = ? and task_id = ?", ana.id, b))
                .isEqualTo(1);
    }

    @Test
    void aReminderThatFiredKeepsItsNotificationAndItSaysTheTaskIsTrashed() throws Exception {
        Browser ana = signUp("Ana");
        long id = timed(ana, "Fired", "2026-10-20", "10:00", "AT_DUE");
        setClock(Instant.parse("2026-10-20T10:00:30Z"));
        processor.runOnce();
        JsonNode before = ana.get("/api/notifications").json().get("items").get(0);
        assertThat(before.get("taskTrashed").asBoolean()).isFalse();

        ana.delete("/api/tasks/" + id);
        JsonNode trashed = ana.get("/api/notifications").json().get("items").get(0);
        assertThat(trashed.get("taskTrashed").asBoolean()).isTrue();
        assertThat(trashed.get("taskId").asLong()).isEqualTo(id);
        assertThat(trashed.get("listId").asLong()).isEqualTo(ana.myTasks());
        assertThat(ana.post("/api/notifications/" + trashed.get("id").asLong() + "/read", null).json()
                .get("taskTrashed").asBoolean()).isTrue();

        ana.post("/api/trash/" + id + "/restore", null);
        assertThat(ana.get("/api/notifications").json().get("items").get(0).get("taskTrashed").asBoolean()).isFalse();

        // Deleted for good: the notification stays, with no task.
        ana.delete("/api/tasks/" + id);
        ana.delete("/api/trash/" + id);
        JsonNode gone = ana.get("/api/notifications").json().get("items").get(0);
        assertThat(gone.get("taskTrashed").asBoolean()).isFalse();
        assertThat(gone.get("taskId").isNull()).isTrue();
    }

    @Test
    void aTrashedRepeatingTaskCreatesNoCopyAndItsRuleSurvivesTheTrash() throws Exception {
        Browser ana = signUp("Ana");
        long list = ana.createList("Home");
        long id = ana.createTask("Water", list).get("id").asLong();
        ana.put("/api/tasks/" + id, "{\"title\":\"Water\",\"dueDate\":\"2026-10-20\",\"repeat\":{\"frequency\":\"DAILY\"}}");
        ana.delete("/api/tasks/" + id);

        expectProblem(ana.patch("/api/tasks/" + id, "{\"done\":true}"), 404, "NOT_FOUND");
        expectProblem(ana.put("/api/tasks/" + id, "{\"title\":\"Water\",\"status\":\"DONE\"}"), 404, "NOT_FOUND");
        assertThat(count("select count(*) from tasks where list_id = ?", list)).isEqualTo(1);

        JsonNode back = ana.post("/api/trash/" + id + "/restore", null).json();
        assertThat(back.get("repeat").toString()).isEqualTo("{\"frequency\":\"DAILY\",\"weekdays\":[]}");
        assertThat(back.get("status").asText()).isEqualTo("TODO");
    }

    // ---- AC5: purge ----

    @Test
    void thePurgeDeletesTasksTrashedForThirtyDaysWithTheTagCleanup() throws Exception {
        Browser ana = signUp("Ana");
        Browser bob = signUp("Bob");
        long list = ana.createList("Team");
        member(list, bob);
        long old = ana.createTask("Old", list).get("id").asLong();
        long recent = bob.createTask("Recent", list).get("id").asLong();
        ana.put("/api/tasks/" + old, "{\"title\":\"Old\",\"tags\":[\"Gone\",\"Kept\"]}");
        ana.put("/api/tasks/" + recent, "{\"title\":\"Recent\",\"tags\":[\"Kept\"]}");
        long live = ana.createTask("Live", list).get("id").asLong();

        ana.delete("/api/tasks/" + old);
        clock.advance(Duration.ofDays(10));
        bob.delete("/api/tasks/" + recent);

        // 30 days minus a second after `old` was trashed: nothing yet.
        setClock(T0.plus(Duration.ofDays(30)).minusSeconds(1));
        assertThat(trash.purgeExpired()).isZero();
        assertThat(count("select count(*) from tasks where id = ?", old)).isEqualTo(1);

        setClock(T0.plus(Duration.ofDays(30)).plusSeconds(1));
        assertThat(trash.purgeExpired()).isEqualTo(1);
        assertThat(count("select count(*) from tasks where id = ?", old)).isZero();
        assertThat(count("select count(*) from tasks where id = ?", recent)).isEqualTo(1);
        assertThat(count("select count(*) from tasks where id = ?", live)).isEqualTo(1);
        // "Gone" was only on the purged task; "Kept" is still used by the trashed one.
        assertThat(ana.get("/api/tags?list=" + list).body()).contains("Kept").doesNotContain("Gone");
        assertThat(trash.purgeExpired()).isZero();

        setClock(T0.plus(Duration.ofDays(40)).plusSeconds(1));
        assertThat(trash.purgeExpired()).isEqualTo(1);
        assertThat(ana.get("/api/tags?list=" + list).body()).isEqualTo("[]");
        assertThat(ana.get("/api/trash").json().get("total").asLong()).isZero();
    }

    // ---- PATCH with any subset of fields (0.16.0) ----

    @Test
    void patchChangesOnlyTheFieldsSentAndLeavesTheRest() throws Exception {
        Browser ana = signUp("Ana");
        long list = ana.createList("Home");
        long id = ana.createTask("Original", list).get("id").asLong();
        ana.put("/api/tasks/" + id, "{\"title\":\"Original\",\"description\":\"Desc\",\"dueDate\":\"2026-11-01\","
                + "\"dueTime\":\"09:00\",\"priority\":\"HIGH\",\"status\":\"IN_PROGRESS\",\"tags\":[\"A\",\"B\"],"
                + "\"reminder\":\"ONE_HOUR\",\"repeat\":{\"frequency\":\"WEEKLY\",\"weekdays\":[\"MON\"]}}");
        JsonNode before = ana.get("/api/tasks/" + id).json();

        clock.advance(Duration.ofSeconds(1));
        Resp title = ana.patch("/api/tasks/" + id, "{\"title\":\"  Renamed  \"}");
        assertThat(title.status()).as(title.body()).isEqualTo(200);
        JsonNode t = title.json();
        assertThat(t.get("title").asText()).isEqualTo("Renamed");
        assertThat(t.get("nextTask").isNull()).isTrue();
        for (String field : List.of("description", "dueDate", "dueTime", "priority", "status", "tags", "myReminder",
                "repeat", "listId", "createdAt", "addedBy")) {
            assertThat(t.get(field)).as(field).isEqualTo(before.get(field));
        }
        assertThat(t.get("updatedAt")).isNotEqualTo(before.get("updatedAt"));
        assertThat(ana.get("/api/tasks/" + id).json().get("title").asText()).isEqualTo("Renamed");

        // Explicit null clears the description and the due time; missing leaves the rest.
        JsonNode cleared = ana.patch("/api/tasks/" + id, "{\"description\":null,\"dueTime\":null}").json();
        assertThat(cleared.get("description").isNull()).isTrue();
        assertThat(cleared.get("dueTime").isNull()).isTrue();
        assertThat(cleared.get("dueDate").asText()).isEqualTo("2026-11-01");
        assertThat(cleared.get("priority").asText()).isEqualTo("HIGH");
        assertThat(cleared.get("tags").toString()).isEqualTo("[\"A\",\"B\"]");
        // A timed reminder no longer fits a date-only task: dropped, as PUT does.
        assertThat(cleared.get("myReminder").isNull()).isTrue();

        // null on a field PUT cannot clear means "unchanged".
        JsonNode same = ana.patch("/api/tasks/" + id, "{\"priority\":null,\"tags\":null,\"title\":\"Again\"}").json();
        assertThat(same.get("priority").asText()).isEqualTo("HIGH");
        assertThat(same.get("tags").toString()).isEqualTo("[\"A\",\"B\"]");

        // Several fields at once, tags replaced, rule removed.
        JsonNode multi = ana.patch("/api/tasks/" + id, "{\"priority\":\"LOW\",\"tags\":[\"C\"],\"repeat\":\"NEVER\","
                + "\"dueTime\":\"18:30\",\"reminder\":\"TEN_MINUTES\",\"status\":\"TODO\"}").json();
        assertThat(multi.get("priority").asText()).isEqualTo("LOW");
        assertThat(multi.get("tags").toString()).isEqualTo("[\"C\"]");
        assertThat(multi.get("repeat").isNull()).isTrue();
        assertThat(multi.get("dueTime").asText()).isEqualTo("18:30");
        assertThat(multi.get("myReminder").asText()).isEqualTo("TEN_MINUTES");
        assertThat(ana.get("/api/tags?list=" + list).body()).doesNotContain("\"A\"").contains("C");

        // Clearing the due date drops the time, the rule and the reminder.
        JsonNode nodate = ana.patch("/api/tasks/" + id, "{\"dueDate\":null}").json();
        assertThat(nodate.get("dueDate").isNull()).isTrue();
        assertThat(nodate.get("dueTime").isNull()).isTrue();
        assertThat(nodate.get("myReminder").isNull()).isTrue();
        assertThat(count("select count(*) from reminders where task_id = ?", id)).isZero();
    }

    @Test
    void patchMovesATaskWithTheSameRulesAsPutAndCompletesRepeatingOnes() throws Exception {
        Browser ana = signUp("Ana");
        long home = ana.createList("Home");
        long work = ana.createList("Work");
        long id = ana.createTask("Mover", home).get("id").asLong();
        ana.put("/api/tasks/" + id, "{\"title\":\"Mover\",\"tags\":[\"X\"]}");

        JsonNode moved = ana.patch("/api/tasks/" + id, "{\"listId\":" + work + "}").json();
        assertThat(moved.get("listId").asLong()).isEqualTo(work);
        assertThat(moved.get("tags").toString()).isEqualTo("[\"X\"]");
        assertThat(ana.get("/api/tags?list=" + home).body()).isEqualTo("[]");
        expectProblem(ana.patch("/api/tasks/" + id, "{\"listId\":999999999}"), 404, "LIST_NOT_FOUND");

        ana.put("/api/tasks/" + id, "{\"title\":\"Mover\",\"dueDate\":\"2026-10-20\",\"repeat\":{\"frequency\":\"DAILY\"}}");
        JsonNode done = ana.patch("/api/tasks/" + id, "{\"status\":\"DONE\",\"priority\":\"HIGH\"}").json();
        assertThat(done.get("repeat").isNull()).isTrue();
        assertThat(done.get("nextTask").get("priority").asText()).isEqualTo("HIGH");
        assertThat(done.get("nextTask").get("dueDate").asText()).isEqualTo("2026-10-21");
    }

    @Test
    void patchRestoresAPastReminderWithItPassed() throws Exception {
        Browser ana = signUp("Ana");
        long id = ana.createTask("Past", null).get("id").asLong();
        ana.put("/api/tasks/" + id, "{\"title\":\"Past\",\"dueDate\":\"2026-10-19\",\"dueTime\":\"09:00\"}");
        Resp r = ana.patch("/api/tasks/" + id, "{\"reminder\":\"ONE_HOUR\"}");
        assertThat(r.status()).as(r.body()).isEqualTo(200);
        assertThat(r.json().get("myReminder").asText()).isEqualTo("ONE_HOUR");
        assertThat(r.json().get("myReminderPassed").asBoolean()).isTrue();
        assertThat(ana.patch("/api/tasks/" + id, "{\"reminder\":\"NONE\"}").json().get("myReminder").isNull())
                .isTrue();
    }

    @Test
    void patchValidatesLikePut() throws Exception {
        Browser ana = signUp("Ana");
        long id = ana.createTask("V", null).get("id").asLong();
        expectField(ana.patch("/api/tasks/" + id, "{}"), "done", "REQUIRED");
        expectField(ana.patch("/api/tasks/" + id, "{\"priority\":null}"), "done", "REQUIRED");
        expectField(ana.patch("/api/tasks/" + id, "{\"title\":\"   \"}"), "title", "REQUIRED");
        expectField(ana.patch("/api/tasks/" + id, "{\"title\":\"" + "x".repeat(201) + "\"}"), "title", "TOO_LONG");
        expectField(ana.patch("/api/tasks/" + id, "{\"description\":\"" + "x".repeat(2001) + "\"}"), "description",
                "TOO_LONG");
        expectField(ana.patch("/api/tasks/" + id, "{\"dueTime\":\"09:00\"}"), "dueTime", "REQUIRES_DUE_DATE");
        expectField(ana.patch("/api/tasks/" + id, "{\"reminder\":\"ONE_DAY\"}"), "reminder", "REQUIRES_DUE_DATE");
        expectField(ana.patch("/api/tasks/" + id, "{\"repeat\":{\"frequency\":\"DAILY\"}}"), "repeat",
                "REQUIRES_DUE_DATE");
        expectField(ana.patch("/api/tasks/" + id, "{\"reminder\":\"SOON\"}"), "reminder", "INVALID_REMINDER");
        expectField(ana.patch("/api/tasks/" + id, "{\"repeat\":\"SOMETIMES\"}"), "repeat", "INVALID_REPEAT");
        expectField(ana.patch("/api/tasks/" + id, "{\"tags\":[\"a,b\"]}"), "tags", "INVALID_CHARACTERS");
        expectField(ana.patch("/api/tasks/" + id, "{\"done\":false,\"status\":\"DONE\"}"), "status", "STATUS_CONFLICT");
        expectProblem(ana.patch("/api/tasks/" + id, "{\"dueDate\":\"nope\"}"), 400, "MALFORMED_REQUEST");
        expectProblem(ana.patch("/api/tasks/" + id, "{\"description\":5}"), 400, "MALFORMED_REQUEST");
        // A date being cleared in the same body cannot carry a time.
        ana.put("/api/tasks/" + id, "{\"title\":\"V\",\"dueDate\":\"2026-11-01\"}");
        expectField(ana.patch("/api/tasks/" + id, "{\"dueDate\":null,\"dueTime\":\"09:00\"}"), "dueTime",
                "REQUIRES_DUE_DATE");
        // Nothing was saved by any failed call.
        assertThat(ana.get("/api/tasks/" + id).json().get("title").asText()).isEqualTo("V");
    }

    @Test
    void patchOverwritesOnlyItsOwnFieldsSoAnUndoNeverRevertsATeammate() throws Exception {
        Browser ana = signUp("Ana");
        Browser bob = signUp("Bob");
        long list = ana.createList("Team");
        member(list, bob);
        long id = ana.createTask("Shared", list).get("id").asLong();
        assertThat(ana.patch("/api/tasks/" + id, "{\"priority\":\"HIGH\"}").status()).isEqualTo(200);
        // Bob changes the title meanwhile; Ana's "undo" of her priority change sends only the priority.
        assertThat(bob.patch("/api/tasks/" + id, "{\"title\":\"Bob's title\"}").status()).isEqualTo(200);
        JsonNode undone = ana.patch("/api/tasks/" + id, "{\"priority\":\"NONE\"}").json();
        assertThat(undone.get("title").asText()).isEqualTo("Bob's title");
        assertThat(undone.get("priority").asText()).isEqualTo("NONE");
    }

    // ---- undo-complete (AC11) ----

    @Test
    void undoCompleteDeletesAnUntouchedCopyAndPutsTheRuleBack() throws Exception {
        Browser ana = signUp("Ana");
        long list = ana.createList("Home");
        long id = repeating(ana, "Water", list);
        ana.put("/api/tasks/" + id, "{\"title\":\"Water\",\"dueDate\":\"2026-10-20\",\"tags\":[\"Green\"],"
                + "\"repeat\":{\"frequency\":\"DAILY\"},\"status\":\"IN_PROGRESS\"}");
        JsonNode done = ana.patch("/api/tasks/" + id, "{\"done\":true}").json();
        long copy = done.get("nextTask").get("id").asLong();

        Resp r = ana.post("/api/tasks/" + id + "/undo-complete", "{\"nextTaskId\":" + copy + ",\"status\":\"IN_PROGRESS\"}");
        assertThat(r.status()).as(r.body()).isEqualTo(200);
        JsonNode body = r.json();
        assertThat(body.get("nextTaskOutcome").asText()).isEqualTo("DELETED");
        assertThat(body.get("status").asText()).isEqualTo("IN_PROGRESS");
        assertThat(body.get("done").asBoolean()).isFalse();
        assertThat(body.get("repeat").toString()).isEqualTo("{\"frequency\":\"DAILY\",\"weekdays\":[]}");
        assertThat(body.get("dueDate").asText()).isEqualTo("2026-10-20");
        assertThat(count("select count(*) from tasks where id = ?", copy)).isZero();
        tools.jackson.databind.node.ObjectNode withoutOutcome = ((tools.jackson.databind.node.ObjectNode) body).deepCopy();
        withoutOutcome.remove("nextTaskOutcome");
        assertThat(ana.get("/api/tasks/" + id).json()).isEqualTo(withoutOutcome);
        // Completing again works as before (the rule is back): one new copy.
        assertThat(ana.patch("/api/tasks/" + id, "{\"done\":true}").json().get("nextTask").isNull()).isFalse();
    }

    @Test
    void undoCompleteKeepsACopyThatWasChangedAndReopensWithoutTheRule() throws Exception {
        Browser ana = signUp("Ana");
        Browser bob = signUp("Bob");
        long list = ana.createList("Team");
        member(list, bob);
        long id = repeating(ana, "Water", list);
        long copy = ana.patch("/api/tasks/" + id, "{\"done\":true}").json().get("nextTask").get("id").asLong();
        clock.advance(Duration.ofSeconds(1));
        assertThat(bob.patch("/api/tasks/" + copy, "{\"priority\":\"HIGH\"}").status()).isEqualTo(200);

        Resp r = ana.post("/api/tasks/" + id + "/undo-complete", "{\"nextTaskId\":" + copy + "}");
        assertThat(r.status()).as(r.body()).isEqualTo(200);
        assertThat(r.json().get("nextTaskOutcome").asText()).isEqualTo("KEPT");
        assertThat(r.json().get("status").asText()).isEqualTo("TODO");
        assertThat(r.json().get("repeat").isNull()).isTrue();
        JsonNode kept = ana.get("/api/tasks/" + copy).json();
        assertThat(kept.get("priority").asText()).isEqualTo("HIGH");
        assertThat(kept.get("repeat").isNull()).isFalse();
    }

    @Test
    void undoCompleteKeepsACopyThatIsTrashedOrMovedAndReportsAGoneOne() throws Exception {
        Browser ana = signUp("Ana");
        long home = ana.createList("Home");
        long work = ana.createList("Work");

        long a = repeating(ana, "Trashed copy", home);
        long copyA = ana.patch("/api/tasks/" + a, "{\"done\":true}").json().get("nextTask").get("id").asLong();
        ana.delete("/api/tasks/" + copyA);
        assertThat(undo(ana, a, copyA).get("nextTaskOutcome").asText()).isEqualTo("KEPT");
        assertThat(count("select count(*) from tasks where id = ? and deleted_at is not null", copyA)).isEqualTo(1);

        long b = repeating(ana, "Moved copy", home);
        long copyB = ana.patch("/api/tasks/" + b, "{\"done\":true}").json().get("nextTask").get("id").asLong();
        ana.patch("/api/tasks/" + copyB, "{\"listId\":" + work + "}");
        assertThat(undo(ana, b, copyB).get("nextTaskOutcome").asText()).isEqualTo("KEPT");

        long c = repeating(ana, "Gone copy", home);
        long copyC = ana.patch("/api/tasks/" + c, "{\"done\":true}").json().get("nextTask").get("id").asLong();
        ana.delete("/api/tasks/" + copyC);
        ana.delete("/api/trash/" + copyC);
        JsonNode gone = undo(ana, c, copyC);
        assertThat(gone.get("nextTaskOutcome").asText()).isEqualTo("GONE");
        assertThat(gone.get("repeat").isNull()).isTrue();
        assertThat(gone.get("status").asText()).isEqualTo("TODO");

        // An id that is not a copy of a repeating task is never deleted: an untouched plain task is KEPT.
        long plain = ana.createTask("Plain", home).get("id").asLong();
        long d = repeating(ana, "Other", home);
        ana.patch("/api/tasks/" + d, "{\"done\":true}");
        assertThat(undo(ana, d, plain).get("nextTaskOutcome").asText()).isEqualTo("KEPT");
        assertThat(ana.get("/api/tasks/" + plain).status()).isEqualTo(200);
        // So is the task itself, and a task of someone else's list is GONE.
        assertThat(undo(ana, d, d).get("nextTaskOutcome").asText()).isEqualTo("KEPT");
        Browser bob = signUp("Bob");
        long bobs = bob.createTask("Bob's", null).get("id").asLong();
        assertThat(undo(ana, d, bobs).get("nextTaskOutcome").asText()).isEqualTo("GONE");
        assertThat(bob.get("/api/tasks/" + bobs).status()).isEqualTo(200);
    }

    @Test
    void undoCompleteErrors() throws Exception {
        Browser ana = signUp("Ana");
        Browser bob = signUp("Bob");
        long id = repeating(ana, "Water", ana.myTasks());
        long copy = ana.patch("/api/tasks/" + id, "{\"done\":true}").json().get("nextTask").get("id").asLong();
        String ok = "{\"nextTaskId\":" + copy + "}";

        expectProblem(bob.post("/api/tasks/" + id + "/undo-complete", ok), 404, "NOT_FOUND");
        expectProblem(ana.post("/api/tasks/999999999/undo-complete", ok), 404, "NOT_FOUND");
        expectProblem(ana.post("/api/tasks/abc/undo-complete", ok), 400, "MALFORMED_REQUEST");
        expectField(ana.post("/api/tasks/" + id + "/undo-complete", "{}"), "nextTaskId", "REQUIRED");
        expectField(ana.post("/api/tasks/" + id + "/undo-complete", "{\"nextTaskId\":null}"), "nextTaskId", "REQUIRED");
        expectField(ana.post("/api/tasks/" + id + "/undo-complete", "{\"nextTaskId\":0}"), "nextTaskId", "OUT_OF_RANGE");
        expectField(ana.post("/api/tasks/" + id + "/undo-complete", "{\"nextTaskId\":" + copy + ",\"status\":\"DONE\"}"),
                "status", "OUT_OF_RANGE");
        expectProblem(ana.post("/api/tasks/" + id + "/undo-complete", "{\"nextTaskId\":\"x\"}"), 400, "MALFORMED_REQUEST");
        expectProblem(ana.post("/api/tasks/" + id + "/undo-complete", "{\"nextTaskId\":" + copy + ",\"status\":\"NOPE\"}"),
                400, "MALFORMED_REQUEST");
        // A trashed task cannot be undone (404), and nothing above changed anything.
        ana.delete("/api/tasks/" + id);
        expectProblem(ana.post("/api/tasks/" + id + "/undo-complete", ok), 404, "NOT_FOUND");
        assertThat(count("select count(*) from tasks where id = ?", copy)).isEqualTo(1);
    }

    @RepeatedTest(5)
    void twoSimultaneousUndoCompletesDeleteTheCopyOnceAndNeverFail() throws Exception {
        Browser ana = signUp("Ana");
        Browser bob = signUp("Bob");
        long list = ana.createList("Team");
        member(list, bob);
        long id = repeating(ana, "Water", list);
        long copy = ana.patch("/api/tasks/" + id, "{\"done\":true}").json().get("nextTask").get("id").asLong();
        String body = "{\"nextTaskId\":" + copy + "}";

        List<Resp> results = race(() -> ana.post("/api/tasks/" + id + "/undo-complete", body),
                () -> bob.post("/api/tasks/" + id + "/undo-complete", body));
        for (Resp r : results) {
            assertThat(r.status()).as(r.body()).isEqualTo(200);
        }
        List<String> outcomes = results.stream().map(r -> outcome(r)).sorted().toList();
        // One of them found the copy and deleted it; the other found it gone.
        assertThat(outcomes).containsExactly("DELETED", "GONE");
        assertThat(count("select count(*) from tasks where list_id = ?", list)).isEqualTo(1);
        assertThat(ana.get("/api/tasks/" + id).json().get("repeat").isNull()).isFalse();
    }

    @RepeatedTest(5)
    void anEditOfTheCopyRacingTheUndoEndsInOneConsistentOutcome() throws Exception {
        Browser ana = signUp("Ana");
        Browser bob = signUp("Bob");
        long list = ana.createList("Team");
        member(list, bob);
        long id = repeating(ana, "Water", list);
        long copy = ana.patch("/api/tasks/" + id, "{\"done\":true}").json().get("nextTask").get("id").asLong();

        clock.advance(Duration.ofSeconds(1));
        List<Resp> results = race(
                () -> ana.post("/api/tasks/" + id + "/undo-complete", "{\"nextTaskId\":" + copy + "}"),
                () -> bob.patch("/api/tasks/" + copy, "{\"priority\":\"HIGH\"}"));
        assertThat(results.get(0).status()).as(results.get(0).body()).isEqualTo(200);
        String outcome = outcome(results.get(0));
        int copies = count("select count(*) from tasks where id = ?", copy);
        if (outcome.equals("DELETED")) {
            // The undo went first: the teammate's edit then found no task.
            assertThat(results.get(1).status()).isEqualTo(404);
            assertThat(copies).isZero();
            assertThat(ana.get("/api/tasks/" + id).json().get("repeat").isNull()).isFalse();
        } else {
            // The edit went first: the copy was kept with the teammate's change, and the task has no rule.
            assertThat(outcome).isEqualTo("KEPT");
            assertThat(results.get(1).status()).isEqualTo(200);
            assertThat(copies).isEqualTo(1);
            assertThat(ana.get("/api/tasks/" + id).json().get("repeat").isNull()).isTrue();
        }
    }

    @RepeatedTest(3)
    void trashingRestoringAndEditingTheSameTaskConcurrentlyNeverFails() throws Exception {
        Browser ana = signUp("Ana");
        Browser bob = signUp("Bob");
        long list = ana.createList("Team");
        member(list, bob);
        long id = ana.createTask("Contended", list).get("id").asLong();
        List<Resp> results = race(() -> ana.delete("/api/tasks/" + id),
                () -> bob.patch("/api/tasks/" + id, "{\"priority\":\"HIGH\"}"),
                () -> bob.put("/api/tasks/" + id, "{\"title\":\"Contended\",\"tags\":[\"t\"]}"));
        for (Resp r : results) {
            assertThat(r.status()).as(r.body()).isIn(200, 204, 404);
        }
        assertThat(ana.get("/api/trash").json().get("total").asLong()).isEqualTo(1);
        assertThat(ana.get("/api/tasks/" + id).status()).isEqualTo(404);
        assertThat(ana.post("/api/trash/" + id + "/restore", null).status()).isEqualTo(200);
    }

    // ---- helpers ----

    @SafeVarargs
    private static List<Resp> race(java.util.concurrent.Callable<Resp>... calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.length);
        try {
            CountDownLatch ready = new CountDownLatch(calls.length);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Resp>> futures = new ArrayList<>();
            for (var call : calls) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return call.call();
                }));
            }
            ready.await();
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

    private static String outcome(Resp r) {
        try {
            return r.json().get("nextTaskOutcome").asText();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private JsonNode undo(Browser b, long id, long nextTaskId) throws Exception {
        Resp r = b.post("/api/tasks/" + id + "/undo-complete", "{\"nextTaskId\":" + nextTaskId + "}");
        assertThat(r.status()).as(r.body()).isEqualTo(200);
        return r.json();
    }

    /** A daily repeating task due today (2026-10-20) in the list. */
    private long repeating(Browser b, String title, long list) throws Exception {
        long id = b.createTask(title, list).get("id").asLong();
        Resp r = b.put("/api/tasks/" + id, "{\"title\":\"" + title + "\",\"dueDate\":\"2026-10-20\","
                + "\"repeat\":{\"frequency\":\"DAILY\"}}");
        assertThat(r.status()).as(r.body()).isEqualTo(200);
        return id;
    }

    /** A task in "My tasks" with a time and a reminder; returns its id. */
    private long timed(Browser b, String title, String date, String time, String reminder) throws Exception {
        long id = b.createTask(title, null).get("id").asLong();
        Resp r = b.put("/api/tasks/" + id, "{\"title\":\"" + title + "\",\"dueDate\":\"" + date + "\",\"dueTime\":\""
                + time + "\",\"reminder\":\"" + reminder + "\"}");
        assertThat(r.status()).as(r.body()).isEqualTo(200);
        return id;
    }

    private int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    private void member(long list, Browser b) {
        jdbc.update("insert into list_members (list_id, user_id) values (?, ?)", list, b.id);
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
