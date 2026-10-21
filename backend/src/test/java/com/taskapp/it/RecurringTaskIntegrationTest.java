package com.taskapp.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
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

import com.taskapp.security.ratelimit.MutableClock;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * T4b end to end (specs/due-times-reminders.md AC27-AC33, contract 0.15.0): real HTTP, MySQL 8.4 with the migrations.
 * The server clock is a {@link MutableClock} that every test resets to 2026-10-20T08:00Z ("today" is Oct 20, UTC).
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.rate-limit.login-per-ip-per-minute=100000",
        "app.rate-limit.register-per-ip-per-hour=100000",
        "app.session.max-length=36500d"})
class RecurringTaskIntegrationTest {

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

    // ---- AC27, AC28: the rule, the copy ----

    @Test
    void completingByPatchCreatesTheCopyAndMovesTheRule() throws Exception {
        Browser ana = signUp("Ana");
        long list = ana.createList("Home");
        long id = ana.createTask("Water plants", list).get("id").asLong();
        Resp set = ana.put("/api/tasks/" + id, "{\"title\":\"Water plants\",\"description\":\"Ficus\","
                + "\"dueDate\":\"2026-10-20\",\"dueTime\":\"18:30\",\"priority\":\"HIGH\",\"tags\":[\"Green\",\"Home\"],"
                + "\"repeat\":{\"frequency\":\"DAILY\"}}");
        assertThat(set.status()).as(set.body()).isEqualTo(200);
        assertThat(set.json().get("repeat").toString()).isEqualTo("{\"frequency\":\"DAILY\",\"weekdays\":[]}");
        assertThat(set.json().get("nextTask").isNull()).isTrue();
        assertThat(ana.get("/api/tasks/" + id).json().get("repeat").toString())
                .isEqualTo("{\"frequency\":\"DAILY\",\"weekdays\":[]}");

        Resp done = ana.patch("/api/tasks/" + id, "{\"done\":true}");
        assertThat(done.status()).as(done.body()).isEqualTo(200);
        JsonNode body = done.json();
        assertThat(body.get("status").asText()).isEqualTo("DONE");
        assertThat(body.get("repeat").isNull()).isTrue();
        assertThat(body.get("dueDate").asText()).isEqualTo("2026-10-20");
        JsonNode next = body.get("nextTask");
        assertThat(next.get("id").asLong()).isNotEqualTo(id);
        assertThat(next.get("listId").asLong()).isEqualTo(list);
        assertThat(next.get("title").asText()).isEqualTo("Water plants");
        assertThat(next.get("description").asText()).isEqualTo("Ficus");
        assertThat(next.get("priority").asText()).isEqualTo("HIGH");
        assertThat(next.get("status").asText()).isEqualTo("TODO");
        assertThat(next.get("done").asBoolean()).isFalse();
        assertThat(next.get("dueDate").asText()).isEqualTo("2026-10-21");
        assertThat(next.get("dueTime").asText()).isEqualTo("18:30");
        assertThat(next.get("tags").toString()).isEqualTo("[\"Green\",\"Home\"]");
        assertThat(next.get("repeat").toString()).isEqualTo("{\"frequency\":\"DAILY\",\"weekdays\":[]}");
        assertThat(next.get("addedBy").get("displayName").asText()).isEqualTo(ana.displayName);
        assertThat(next.has("nextTask")).isFalse();
        assertThat(fieldNames(next)).containsExactly("id", "listId", "addedBy", "title", "description", "priority",
                "status", "tags", "done", "dueDate", "dueTime", "repeat", "myReminder", "myReminderPassed",
                "createdAt", "updatedAt");
        // GET shows exactly what nextTask showed.
        assertThat(ana.get("/api/tasks/" + next.get("id").asLong()).json()).isEqualTo(next);
        assertThat(ana.get("/api/tasks/" + id).json().get("repeat").isNull()).isTrue();
        assertThat(columns(id)).isEqualTo("null|null|null");
        assertThat(count("select count(*) from tasks where list_id = ?", list)).isEqualTo(2);
    }

    @Test
    void completingByPutUsesThePostUpdateValuesAndTheCopyIsAddedByTheCompleter() throws Exception {
        Browser ana = signUp("Ana");
        Browser bob = signUp("Bob");
        long list = ana.createList("Shared");
        member(list, bob);
        long other = ana.createList("Elsewhere");
        member(other, bob);
        long id = ana.createTask("Gym", list).get("id").asLong();
        assertThat(ana.put("/api/tasks/" + id, "{\"title\":\"Gym\",\"dueDate\":\"2026-10-20\","
                + "\"repeat\":{\"frequency\":\"WEEKLY\",\"weekdays\":[\"MON\",\"THU\"]}}").status()).isEqualTo(200);

        // Bob edits the title, the due date (Tue Oct 27), the tags and moves it, and completes it in the same call.
        Resp done = bob.put("/api/tasks/" + id, "{\"title\":\"Gym class\",\"dueDate\":\"2026-10-27\","
                + "\"status\":\"DONE\",\"tags\":[\"Fit\"],\"listId\":" + other + "}");
        assertThat(done.status()).as(done.body()).isEqualTo(200);
        JsonNode next = done.json().get("nextTask");
        assertThat(done.json().get("listId").asLong()).isEqualTo(other);
        assertThat(done.json().get("repeat").isNull()).isTrue();
        assertThat(next.get("title").asText()).isEqualTo("Gym class");
        assertThat(next.get("listId").asLong()).isEqualTo(other);
        assertThat(next.get("tags").toString()).isEqualTo("[\"Fit\"]");
        // Tue Oct 27 -> next selected weekday strictly after: Thu Oct 29.
        assertThat(next.get("dueDate").asText()).isEqualTo("2026-10-29");
        assertThat(next.get("repeat").toString()).isEqualTo("{\"frequency\":\"WEEKLY\",\"weekdays\":[\"MON\",\"THU\"]}");
        assertThat(next.get("addedBy").get("displayName").asText()).isEqualTo(bob.displayName);
        assertThat(columns(next.get("id").asLong())).isEqualTo("WEEKLY|9|2026-10-27");
    }

    @Test
    void weeklyRuleSavesAsBitmaskAndCollapsesDuplicates() throws Exception {
        Browser ana = signUp("Ana");
        long id = ana.createTask("W", null).get("id").asLong();
        Resp r = ana.put("/api/tasks/" + id, "{\"title\":\"W\",\"dueDate\":\"2026-10-20\","
                + "\"repeat\":{\"frequency\":\"WEEKLY\",\"weekdays\":[\"SUN\",\"MON\",\"MON\",\"SUN\"]}}");
        assertThat(r.status()).as(r.body()).isEqualTo(200);
        assertThat(r.json().get("repeat").toString()).isEqualTo("{\"frequency\":\"WEEKLY\",\"weekdays\":[\"MON\",\"SUN\"]}");
        assertThat(columns(id)).isEqualTo("WEEKLY|65|2026-10-20");
    }

    // ---- AC29: late completion ----

    @Test
    void completedLateSkipsToTodayOrLaterInTheCompletersZone() throws Exception {
        Browser ana = signUp("Ana");
        long id = repeating(ana, "Late", "2026-10-10", "{\"frequency\":\"DAILY\"}");
        clock.advance(Duration.ofDays(10)); // 2026-10-30T08:00Z
        JsonNode next = ana.patch("/api/tasks/" + id, "{\"done\":true}").json().get("nextTask");
        assertThat(next.get("dueDate").asText()).isEqualTo("2026-10-30");

        // 20:00Z on Oct 30 is already Oct 31 in Auckland (UTC+13).
        Browser bob = zoned(signUp("Bob"), "Pacific/Auckland");
        clock.advance(Duration.ofHours(12));
        long id2 = repeating(bob, "Late NZ", "2026-10-10", "{\"frequency\":\"DAILY\"}");
        JsonNode next2 = bob.patch("/api/tasks/" + id2, "{\"done\":true}").json().get("nextTask");
        assertThat(next2.get("dueDate").asText()).isEqualTo("2026-10-31");
    }

    @Test
    void monthlyKeepsItsAnchorAcrossShortMonthsAndUnrelatedEdits() throws Exception {
        Browser ana = signUp("Ana");
        long id = repeating(ana, "Rent", "2026-10-31", "{\"frequency\":\"MONTHLY\"}");
        JsonNode second = ana.patch("/api/tasks/" + id, "{\"done\":true}").json().get("nextTask");
        assertThat(second.get("dueDate").asText()).isEqualTo("2026-11-30");
        // Re-sending the same rule and date (an unrelated title edit) must keep the anchor (31).
        long secondId = second.get("id").asLong();
        Resp edit = ana.put("/api/tasks/" + secondId, "{\"title\":\"Rent!\",\"dueDate\":\"2026-11-30\","
                + "\"repeat\":{\"frequency\":\"MONTHLY\",\"weekdays\":[]}}");
        assertThat(edit.status()).as(edit.body()).isEqualTo(200);
        assertThat(columns(secondId)).isEqualTo("MONTHLY|null|2026-10-31");
        JsonNode third = ana.patch("/api/tasks/" + secondId, "{\"done\":true}").json().get("nextTask");
        assertThat(third.get("dueDate").asText()).isEqualTo("2026-12-31");

        // Changing the due date restarts the count from the new date.
        long fourth = third.get("id").asLong();
        assertThat(ana.put("/api/tasks/" + fourth, "{\"title\":\"Rent!\",\"dueDate\":\"2026-12-15\"}").status())
                .isEqualTo(200);
        assertThat(columns(fourth)).isEqualTo("MONTHLY|null|2026-12-15");
        assertThat(ana.patch("/api/tasks/" + fourth, "{\"done\":true}").json().get("nextTask").get("dueDate")
                .asText()).isEqualTo("2027-01-15");
    }

    @Test
    void yearlyFeb29FallsBackToFeb28() throws Exception {
        Browser ana = signUp("Ana");
        long id = repeating(ana, "Birthday", "2028-02-29", "{\"frequency\":\"YEARLY\"}");
        // Completed (early) in 2026: next is the first occurrence after Feb 29 2028 = Feb 28 2029.
        JsonNode next = ana.patch("/api/tasks/" + id, "{\"done\":true}").json().get("nextTask");
        assertThat(next.get("dueDate").asText()).isEqualTo("2029-02-28");
        assertThat(columns(next.get("id").asLong())).isEqualTo("YEARLY|null|2028-02-29");
    }

    // ---- AC30, AC31: reopen, never, delete, no repeat ----

    @Test
    void reopeningAndCompletingAgainCreatesNothing() throws Exception {
        Browser ana = signUp("Ana");
        long list = ana.createList("L");
        long id = repeating(ana, "Once", list, "2026-10-20", "{\"frequency\":\"DAILY\"}");
        assertThat(ana.patch("/api/tasks/" + id, "{\"done\":true}").json().get("nextTask").isNull()).isFalse();
        assertThat(count("select count(*) from tasks where list_id = ?", list)).isEqualTo(2);

        Resp reopened = ana.patch("/api/tasks/" + id, "{\"status\":\"TODO\"}");
        assertThat(reopened.json().get("nextTask").isNull()).isTrue();
        Resp again = ana.patch("/api/tasks/" + id, "{\"status\":\"DONE\"}");
        assertThat(again.status()).isEqualTo(200);
        assertThat(again.json().get("nextTask").isNull()).isTrue();
        assertThat(count("select count(*) from tasks where list_id = ?", list)).isEqualTo(2);

        // A rule may be set on a done task: nothing happens until it is reopened and completed.
        Resp onDone = ana.put("/api/tasks/" + id, "{\"title\":\"Once\",\"dueDate\":\"2026-10-20\",\"status\":\"DONE\","
                + "\"repeat\":{\"frequency\":\"DAILY\"}}");
        assertThat(onDone.status()).as(onDone.body()).isEqualTo(200);
        assertThat(onDone.json().get("nextTask").isNull()).isTrue();
        assertThat(onDone.json().get("repeat").isNull()).isFalse();
        assertThat(count("select count(*) from tasks where list_id = ?", list)).isEqualTo(2);
        // DONE -> DONE by PATCH keeps the rule and creates nothing.
        assertThat(ana.patch("/api/tasks/" + id, "{\"done\":true}").json().get("nextTask").isNull()).isTrue();
        ana.patch("/api/tasks/" + id, "{\"done\":false}");
        assertThat(ana.patch("/api/tasks/" + id, "{\"done\":true}").json().get("nextTask").isNull()).isFalse();
        assertThat(count("select count(*) from tasks where list_id = ?", list)).isEqualTo(3);
    }

    @Test
    void stopRepeatingThenCompletingCreatesNoCopy() throws Exception {
        Browser ana = signUp("Ana");
        long list = ana.createList("L");
        long id = repeating(ana, "Stop", list, "2026-10-20", "{\"frequency\":\"DAILY\"}");
        Resp stop = ana.put("/api/tasks/" + id, "{\"title\":\"Stop\",\"dueDate\":\"2026-10-20\",\"repeat\":\"NEVER\"}");
        assertThat(stop.json().get("repeat").isNull()).isTrue();
        assertThat(columns(id)).isEqualTo("null|null|null");
        assertThat(ana.patch("/api/tasks/" + id, "{\"done\":true}").json().get("nextTask").isNull()).isTrue();

        // NEVER and complete in the same call.
        long id2 = repeating(ana, "Stop2", list, "2026-10-20", "{\"frequency\":\"DAILY\"}");
        Resp both = ana.put("/api/tasks/" + id2, "{\"title\":\"Stop2\",\"dueDate\":\"2026-10-20\","
                + "\"repeat\":\"NEVER\",\"done\":true}");
        assertThat(both.status()).as(both.body()).isEqualTo(200);
        assertThat(both.json().get("nextTask").isNull()).isTrue();
        assertThat(count("select count(*) from tasks where list_id = ?", list)).isEqualTo(2);
    }

    @Test
    void deletingARepeatingTaskCreatesNothing() throws Exception {
        Browser ana = signUp("Ana");
        long list = ana.createList("L");
        long id = repeating(ana, "Gone", list, "2026-10-20", "{\"frequency\":\"DAILY\"}");
        assertThat(ana.delete("/api/tasks/" + id).status()).isEqualTo(204);
        // Moved to the trash (0.16.0): nothing is left in the list, and no copy was made.
        assertThat(count("select count(*) from tasks where list_id = ? and deleted_at is null", list)).isZero();
        assertThat(count("select count(*) from tasks where list_id = ?", list)).isEqualTo(1);
    }

    @Test
    void aPlainTaskHasNullRepeatAndNextTask() throws Exception {
        Browser ana = signUp("Ana");
        long id = ana.createTask("Plain", null).get("id").asLong();
        assertThat(ana.get("/api/tasks/" + id).json().get("repeat").isNull()).isTrue();
        JsonNode done = ana.patch("/api/tasks/" + id, "{\"done\":true}").json();
        assertThat(done.has("nextTask")).isTrue();
        assertThat(done.get("nextTask").isNull()).isTrue();
        assertThat(done.get("repeat").isNull()).isTrue();
    }

    // ---- clearing the due date clears the rule ----

    @Test
    void clearingTheDueDateClearsTheRule() throws Exception {
        Browser ana = signUp("Ana");
        long id = repeating(ana, "Clear", "2026-10-20", "{\"frequency\":\"DAILY\"}");
        Resp r = ana.put("/api/tasks/" + id, "{\"title\":\"Clear\"}");
        assertThat(r.status()).as(r.body()).isEqualTo(200);
        assertThat(r.json().get("dueDate").isNull()).isTrue();
        assertThat(r.json().get("repeat").isNull()).isTrue();
        assertThat(columns(id)).isEqualTo("null|null|null");

        // Also when the same call moves it to another list and completes it.
        long list = ana.createList("Other");
        long id2 = repeating(ana, "Clear2", "2026-10-20", "{\"frequency\":\"DAILY\"}");
        Resp moved = ana.put("/api/tasks/" + id2, "{\"title\":\"Clear2\",\"listId\":" + list + ",\"done\":true}");
        assertThat(moved.status()).as(moved.body()).isEqualTo(200);
        assertThat(moved.json().get("nextTask").isNull()).isTrue();
        assertThat(columns(id2)).isEqualTo("null|null|null");
        assertThat(count("select count(*) from tasks where list_id = ?", list)).isEqualTo(1);
    }

    @Test
    void movingARepeatingTaskKeepsItsRule() throws Exception {
        Browser ana = signUp("Ana");
        long id = repeating(ana, "Move", "2026-10-20", "{\"frequency\":\"DAILY\"}");
        long list = ana.createList("Other");
        Resp moved = ana.put("/api/tasks/" + id, "{\"title\":\"Move\",\"dueDate\":\"2026-10-20\",\"listId\":" + list + "}");
        assertThat(moved.status()).as(moved.body()).isEqualTo(200);
        assertThat(moved.json().get("repeat").isNull()).isFalse();
        assertThat(columns(id)).isEqualTo("DAILY|null|2026-10-20");
    }

    // ---- validation ----

    @Test
    void validationErrorsOnRepeat() throws Exception {
        Browser ana = signUp("Ana");
        long id = ana.createTask("V", null).get("id").asLong();
        String put = "/api/tasks/" + id;
        String head = "{\"title\":\"V\",\"dueDate\":\"2026-10-20\",\"repeat\":";
        expectField(ana.put(put, head + "{}}"), "repeat", "REQUIRED");
        expectField(ana.put(put, head + "{\"weekdays\":[\"MON\"]}}"), "repeat", "REQUIRED");
        expectField(ana.put(put, head + "{\"frequency\":\"WEEKLY\"}}"), "repeat", "REQUIRED");
        expectField(ana.put(put, head + "{\"frequency\":\"WEEKLY\",\"weekdays\":[]}}"), "repeat", "REQUIRED");
        expectField(ana.put(put, head + "{\"frequency\":\"HOURLY\"}}"), "repeat", "INVALID_REPEAT");
        expectField(ana.put(put, head + "{\"frequency\":\"daily\"}}"), "repeat", "INVALID_REPEAT");
        expectField(ana.put(put, head + "{\"frequency\":\"WEEKLY\",\"weekdays\":[\"Mon\"]}}"), "repeat", "INVALID_REPEAT");
        expectField(ana.put(put, head + "{\"frequency\":\"DAILY\",\"weekdays\":[\"MON\"]}}"), "repeat", "INVALID_REPEAT");
        expectField(ana.put(put, head + "\"never\"}"), "repeat", "INVALID_REPEAT");
        expectField(ana.put(put, head + "\"DAILY\"}"), "repeat", "INVALID_REPEAT");
        expectField(ana.put(put, "{\"title\":\"V\",\"repeat\":{\"frequency\":\"DAILY\"}}"), "repeat",
                "REQUIRES_DUE_DATE");
        expectField(ana.put(put, "{\"title\":\"V\",\"dueDate\":null,\"repeat\":{\"frequency\":\"DAILY\"}}"), "repeat",
                "REQUIRES_DUE_DATE");
        for (String wrong : List.of("5", "true", "[]", "[\"DAILY\"]", "{\"frequency\":5}",
                "{\"frequency\":\"WEEKLY\",\"weekdays\":\"MON\"}", "{\"frequency\":\"WEEKLY\",\"weekdays\":[1]}")) {
            Resp r = ana.put(put, head + wrong + "}");
            expectProblem(r, 400, "MALFORMED_REQUEST");
        }
        // Reported with the other body errors, and before 404.
        Resp both = ana.put(put, "{\"title\":\"\",\"dueDate\":\"2026-10-20\",\"repeat\":{\"frequency\":\"X\"}}");
        expectProblem(both, 400, "VALIDATION_FAILED");
        assertThat(both.json().get("errors")).hasSize(2);
        expectField(ana.put("/api/tasks/999999", head + "{\"frequency\":\"X\"}}"), "repeat", "INVALID_REPEAT");
        // Nothing was saved.
        assertThat(columns(id)).isEqualTo("null|null|null");
        // null and a missing repeat both mean unchanged.
        long kept = repeating(ana, "Keep", "2026-10-20", "{\"frequency\":\"DAILY\"}");
        assertThat(ana.put("/api/tasks/" + kept, "{\"title\":\"Keep\",\"dueDate\":\"2026-10-20\",\"repeat\":null}")
                .json().get("repeat").isNull()).isFalse();
        assertThat(ana.put("/api/tasks/" + kept, "{\"title\":\"Keep\",\"dueDate\":\"2026-10-20\"}")
                .json().get("repeat").isNull()).isFalse();
        // POST takes no repeat.
        Resp created = ana.post("/api/tasks", "{\"title\":\"P\",\"repeat\":{\"frequency\":\"DAILY\"}}");
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.json().get("repeat").isNull()).isTrue();
    }

    // ---- reminders on the copy ----

    @Test
    void remindersAreCopiedOnlyForCurrentMembersAndRecomputedInTheirZones() throws Exception {
        Browser ana = zoned(signUp("Ana"), "Europe/Bucharest");
        Browser bob = zoned(signUp("Bob"), "Asia/Tokyo");
        Browser carol = signUp("Carol");
        long list = ana.createList("Team");
        member(list, bob);
        member(list, carol);
        long id = ana.createTask("Standup", list).get("id").asLong();
        String body = "{\"title\":\"Standup\",\"dueDate\":\"2026-10-21\",\"dueTime\":\"09:00\","
                + "\"repeat\":{\"frequency\":\"DAILY\"},\"reminder\":\"ONE_HOUR\"}";
        assertThat(ana.put("/api/tasks/" + id, body).status()).isEqualTo(200);
        assertThat(bob.put("/api/tasks/" + id, body.replace("ONE_HOUR", "TEN_MINUTES")).status()).isEqualTo(200);
        assertThat(carol.put("/api/tasks/" + id, body.replace("ONE_HOUR", "AT_DUE")).status()).isEqualTo(200);
        assertThat(count("select count(*) from reminders where task_id = ?", id)).isEqualTo(3);
        // Carol is no longer a member (her reminder row is still there).
        jdbc.update("delete from list_members where list_id = ? and user_id = ?", list, carol.id);

        Resp done = bob.patch("/api/tasks/" + id, "{\"done\":true}");
        assertThat(done.status()).as(done.body()).isEqualTo(200);
        JsonNode next = done.json().get("nextTask");
        long nextId = next.get("id").asLong();
        // The caller sees their own reminder on the copy.
        assertThat(next.get("myReminder").asText()).isEqualTo("TEN_MINUTES");
        assertThat(next.get("myReminderPassed").asBoolean()).isFalse();
        assertThat(done.json().get("myReminder").asText()).isEqualTo("TEN_MINUTES");
        assertThat(count("select count(*) from reminders where task_id = ?", nextId)).isEqualTo(2);
        assertThat(count("select count(*) from reminders where task_id = ? and user_id = ?", nextId, carol.id)).isZero();
        // Due Oct 22 09:00: Bucharest (UTC+3) minus 1 h = 05:00Z; Tokyo (UTC+9) minus 10 min = Oct 21 23:50Z.
        assertThat(fireAt(nextId, ana.id)).isEqualTo(Instant.parse("2026-10-22T05:00:00Z"));
        assertThat(fireAt(nextId, bob.id)).isEqualTo(Instant.parse("2026-10-21T23:50:00Z"));
        assertThat(jdbc.queryForObject("select status from reminders where task_id = ? and user_id = ?", String.class,
                nextId, ana.id)).isEqualTo("PENDING");
        // The completed task keeps its reminders.
        assertThat(count("select count(*) from reminders where task_id = ?", id)).isEqualTo(3);
        assertThat(ana.get("/api/tasks/" + nextId).json().get("myReminder").asText()).isEqualTo("ONE_HOUR");
    }

    @Test
    void aCopiedReminderWhoseFireTimeHasPassedIsSkipped() throws Exception {
        Browser ana = signUp("Ana");
        long id = ana.createTask("Early", null).get("id").asLong();
        assertThat(ana.put("/api/tasks/" + id, "{\"title\":\"Early\",\"dueDate\":\"2026-10-19\",\"dueTime\":\"07:00\","
                + "\"repeat\":{\"frequency\":\"DAILY\"},\"reminder\":\"AT_DUE\"}").status()).isEqualTo(200);
        // Completed Oct 20 08:00Z: the next date is today (Oct 20) at 07:00, already past.
        JsonNode next = ana.patch("/api/tasks/" + id, "{\"done\":true}").json().get("nextTask");
        assertThat(next.get("dueDate").asText()).isEqualTo("2026-10-20");
        assertThat(next.get("myReminder").asText()).isEqualTo("AT_DUE");
        assertThat(next.get("myReminderPassed").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("select status from reminders where task_id = ?", String.class,
                next.get("id").asLong())).isEqualTo("SKIPPED");
    }

    // ---- AC32: concurrency ----

    @RepeatedTest(25)
    void twoSimultaneousCompletionsCreateExactlyOneCopy() throws Exception {
        Browser ana = signUp("Ana");
        Browser bob = signUp("Bob");
        long list = ana.createList("Race");
        member(list, bob);
        long id = repeating(ana, "Race", list, "2026-10-20", "{\"frequency\":\"DAILY\"}");

        boolean patchAndPut = jdbc.queryForObject("select count(*) from tasks", Integer.class) % 2 == 0;
        String put = "{\"title\":\"Race\",\"dueDate\":\"2026-10-20\",\"status\":\"DONE\"}";
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Resp> first = pool.submit(() -> {
                ready.countDown();
                go.await();
                return ana.patch("/api/tasks/" + id, "{\"done\":true}");
            });
            Future<Resp> second = pool.submit(() -> {
                ready.countDown();
                go.await();
                return patchAndPut ? bob.put("/api/tasks/" + id, put)
                        : bob.patch("/api/tasks/" + id, "{\"status\":\"DONE\"}");
            });
            ready.await();
            go.countDown();
            Resp a = first.get(30, java.util.concurrent.TimeUnit.SECONDS);
            Resp b = second.get(30, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(a.status()).as(a.body()).isEqualTo(200);
            assertThat(b.status()).as(b.body()).isEqualTo(200);
            int copies = 0;
            for (Resp r : List.of(a, b)) {
                if (!r.json().get("nextTask").isNull()) {
                    copies++;
                }
                assertThat(r.json().get("status").asText()).isEqualTo("DONE");
                assertThat(r.json().get("repeat").isNull()).as("the rule moved to the copy: " + r.body()).isTrue();
            }
            assertThat(copies).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(count("select count(*) from tasks where list_id = ?", list)).isEqualTo(2);
        assertThat(columns(id)).isEqualTo("null|null|null");
        assertThat(count("select count(*) from tasks where list_id = ? and repeat_frequency is not null", list))
                .isEqualTo(1);
    }

    // ---- helpers ----

    private long repeating(Browser b, String title, String date, String repeat) throws Exception {
        return repeating(b, title, b.myTasks(), date, repeat);
    }

    private long repeating(Browser b, String title, long list, String date, String repeat) throws Exception {
        long id = b.createTask(title, list).get("id").asLong();
        Resp r = b.put("/api/tasks/" + id, "{\"title\":\"" + title + "\",\"dueDate\":\"" + date + "\",\"repeat\":"
                + repeat + "}");
        assertThat(r.status()).as(r.body()).isEqualTo(200);
        return id;
    }

    /** repeat_frequency|repeat_weekdays|repeat_anchor of a task, "null" for NULL. */
    private String columns(long taskId) {
        return jdbc.queryForObject("select concat_ws('|', ifnull(repeat_frequency, 'null'),"
                + " ifnull(repeat_weekdays, 'null'), ifnull(repeat_anchor, 'null')) from tasks where id = ?",
                String.class, taskId);
    }

    private int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    private Instant fireAt(long taskId, long userId) {
        return jdbc.queryForObject("select fire_at from reminders where task_id = ? and user_id = ?",
                LocalDateTime.class, taskId, userId).toInstant(ZoneOffset.UTC);
    }

    private void member(long list, Browser b) {
        jdbc.update("insert into list_members (list_id, user_id) values (?, ?)", list, b.id);
    }

    private Browser zoned(Browser b, String zone) throws Exception {
        Resp r = b.put("/api/account/time-zone", "{\"timeZone\":\"" + zone + "\"}");
        assertThat(r.status()).as(r.body()).isEqualTo(200);
        return b;
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
            return new Resp(r.statusCode(), r.headers().firstValue("Content-Type").orElse(""), r.body());
        }
    }

    record Resp(int status, String contentType, String body) {
        JsonNode json() throws IOException {
            return JSON.readTree(body);
        }
    }
}
