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
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.taskapp.reminder.ReminderProcessor;
import com.taskapp.security.ratelimit.MutableClock;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * T4a end to end (specs/due-times-reminders.md AC1-AC24, contract 0.14.0): real HTTP, MySQL 8.4 with the migrations
 * and Mailpit. The server clock is a {@link MutableClock}; the scheduler is off (test application.properties) and
 * the tests drive {@link ReminderProcessor#runOnce()} themselves. The hourly email cap is lowered to 3.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.rate-limit.login-per-ip-per-minute=10000",
        "app.rate-limit.register-per-ip-per-hour=10000",
        "app.rate-limit.reminder-emails-per-account-per-hour=3",
        // The test clock jumps by days and years; a session must not end because of that.
        "app.session.max-length=36500d"})
class ReminderIntegrationTest {

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
            .withUrlParam("connectionTimeZone", "UTC")
            .withUrlParam("forceConnectionTimeZoneToSession", "true");

    @Container
    static GenericContainer<?> mailpitContainer = Mailpit.container();

    @DynamicPropertySource
    static void mailServer(DynamicPropertyRegistry registry) {
        Mailpit.register(mailpitContainer, registry);
    }

    @TestConfiguration
    static class ClockConfig {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(T0);
        }
    }

    /** 08:00Z on the day before the tasks are due. */
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

    private final HttpClient http = HttpClient.newHttpClient();
    private Mailpit mailpit;

    @BeforeEach
    void start() {
        mailpit = new Mailpit(mailpitContainer);
        setClock(T0);
        jdbc.update("delete from notifications");
        jdbc.update("delete from reminders");
    }

    private void setClock(Instant to) {
        clock.advance(Duration.between(clock.instant(), to));
    }

    // ---- AC1, AC2, AC16: account fields ----

    @Test
    void timeZoneIsSetOnceByTheBrowserAndEditableInAccount() throws Exception {
        Browser ana = signUp("Ana");
        JsonNode me = ana.get("/api/auth/me").json();
        assertThat(me.get("timeZone").isNull()).isTrue();
        assertThat(me.get("reminderEmails").asBoolean()).isTrue();
        assertThat(fieldNames(me)).containsExactly("id", "email", "displayName", "emailVerified", "hasPassword",
                "connections", "role", "timeZone", "reminderEmails");

        Resp first = ana.put("/api/account/time-zone", "{\"timeZone\":\"Europe/Bucharest\",\"ifUnset\":true}");
        assertThat(first.status()).as(first.body()).isEqualTo(200);
        assertThat(first.json().get("timeZone").asText()).isEqualTo("Europe/Bucharest");
        // ifUnset never overwrites, and still answers 200 with the stored zone.
        Resp second = ana.put("/api/account/time-zone", "{\"timeZone\":\"Asia/Tokyo\",\"ifUnset\":true}");
        assertThat(second.status()).isEqualTo(200);
        assertThat(second.json().get("timeZone").asText()).isEqualTo("Europe/Bucharest");
        assertThat(ana.get("/api/auth/me").json().get("timeZone").asText()).isEqualTo("Europe/Bucharest");
        // Without ifUnset the user can change it.
        Resp changed = ana.put("/api/account/time-zone", "{\"timeZone\":\"UTC\"}");
        assertThat(changed.json().get("timeZone").asText()).isEqualTo("UTC");

        Resp off = ana.put("/api/account/reminder-emails", "{\"enabled\":false}");
        assertThat(off.status()).as(off.body()).isEqualTo(200);
        assertThat(off.json().get("reminderEmails").asBoolean()).isFalse();
        assertThat(ana.get("/api/auth/me").json().get("reminderEmails").asBoolean()).isFalse();
        assertThat(ana.put("/api/account/reminder-emails", "{\"enabled\":true}").json().get("reminderEmails")
                .asBoolean()).isTrue();
    }

    @Test
    void invalidTimeZonesAndBodiesAreRefused() throws Exception {
        Browser ana = signUp("Ana");
        for (String bad : List.of("+02:00", "UTC+2", "EST", "europe/bucharest", "Mars/Base", " UTC",
                "Europe/" + "x".repeat(60))) {
            Resp r = ana.put("/api/account/time-zone", "{\"timeZone\":\"" + bad + "\"}");
            expectField(r, "timeZone", "INVALID_TIME_ZONE");
        }
        expectField(ana.put("/api/account/time-zone", "{\"timeZone\":\"\"}"), "timeZone", "REQUIRED");
        expectField(ana.put("/api/account/time-zone", "{}"), "timeZone", "REQUIRED");
        expectField(ana.put("/api/account/reminder-emails", "{}"), "enabled", "REQUIRED");
        expectField(ana.put("/api/account/reminder-emails", "{\"enabled\":null}"), "enabled", "REQUIRED");
        expectProblem(ana.put("/api/account/reminder-emails", "{\"enabled\":\"yes\"}"), 400, "MALFORMED_REQUEST");
        expectProblem(ana.put("/api/account/time-zone", "{\"timeZone\":\"UTC\",\"ifUnset\":\"x\"}"), 400,
                "MALFORMED_REQUEST");
        assertThat(ana.get("/api/auth/me").json().get("timeZone").isNull()).isTrue();

        // Session and CSRF like the other account endpoints.
        Browser anonymous = new Browser().start();
        assertThat(anonymous.put("/api/account/time-zone", "{\"timeZone\":\"UTC\"}").status()).isEqualTo(401);
        assertThat(anonymous.put("/api/account/reminder-emails", "{\"enabled\":true}").status()).isEqualTo(401);
        Resp noCsrf = ana.sendWithoutCsrf("PUT", "/api/account/time-zone", "{\"timeZone\":\"UTC\"}");
        assertThat(noCsrf.status()).isEqualTo(403);
    }

    // ---- AC3, AC4, AC8, AC9, AC12: task PUT ----

    @Test
    void putSetsTheDueTimeAndMyReminderAndEveryReadShowsThem() throws Exception {
        Browser ana = zoned(signUp("Ana"), "Europe/Bucharest");
        long id = ana.createTask("Dentist", null).get("id").asLong();

        Resp r = ana.put("/api/tasks/" + id, task("Dentist", "2026-10-21", "09:30", "ONE_HOUR"));
        assertThat(r.status()).as(r.body()).isEqualTo(200);
        JsonNode task = r.json();
        assertThat(fieldNames(task)).containsExactly("id", "listId", "addedBy", "title", "description", "priority",
                "status", "tags", "done", "dueDate", "dueTime", "repeat", "myReminder", "myReminderPassed", "createdAt",
                "updatedAt", "nextTask");
        assertThat(task.get("dueDate").asText()).isEqualTo("2026-10-21");
        assertThat(task.get("dueTime").asText()).isEqualTo("09:30");
        assertThat(task.get("myReminder").asText()).isEqualTo("ONE_HOUR");
        assertThat(task.get("myReminderPassed").asBoolean()).isFalse();
        // 09:30 in Bucharest (+3) is 06:30Z; one hour before is 05:30Z.
        assertThat(fireAt(id, ana.id)).isEqualTo(Instant.parse("2026-10-21T05:30:00Z"));

        assertThat(ana.get("/api/tasks/" + id).json())
                .isEqualTo(((tools.jackson.databind.node.ObjectNode) task.deepCopy()).without("nextTask"));
        JsonNode listed = ana.get("/api/tasks").json().get("items").get(0);
        assertThat(listed.get("dueTime").asText()).isEqualTo("09:30");
        assertThat(listed.get("myReminder").asText()).isEqualTo("ONE_HOUR");

        // A new task has no time and no reminder.
        JsonNode plain = ana.createTask("Plain", null);
        assertThat(plain.get("dueTime").isNull()).isTrue();
        assertThat(plain.get("myReminder").isNull()).isTrue();
        assertThat(plain.get("myReminderPassed").asBoolean()).isFalse();
    }

    @Test
    void dueTimeIsUnchangedWhenMissingClearedByNullAndDroppedWithTheDate() throws Exception {
        Browser ana = zoned(signUp("Ana"), "UTC");
        long id = ana.createTask("T", null).get("id").asLong();
        ana.put("/api/tasks/" + id, task("T", "2026-10-21", "09:30", "AT_DUE"));

        // Missing = unchanged (an older client editing the title), the reminder stays too.
        Resp unchanged = ana.put("/api/tasks/" + id, "{\"title\":\"T2\",\"dueDate\":\"2026-10-22\"}");
        assertThat(unchanged.json().get("dueTime").asText()).isEqualTo("09:30");
        assertThat(unchanged.json().get("myReminder").asText()).isEqualTo("AT_DUE");
        // The reminder follows the new date.
        assertThat(fireAt(id, ana.id)).isEqualTo(Instant.parse("2026-10-22T09:30:00Z"));

        // null = cleared, the date stays; AT_DUE needs a time, so the reminder goes.
        Resp cleared = ana.put("/api/tasks/" + id, "{\"title\":\"T2\",\"dueDate\":\"2026-10-22\",\"dueTime\":null}");
        assertThat(cleared.json().get("dueTime").isNull()).isTrue();
        assertThat(cleared.json().get("dueDate").asText()).isEqualTo("2026-10-22");
        assertThat(cleared.json().get("myReminder").isNull()).isTrue();
        assertThat(count("select count(*) from reminders where task_id = ?", id)).isZero();

        // Set a time and a reminder again, then clear the date: the time and the reminder go.
        ana.put("/api/tasks/" + id, task("T2", "2026-10-22", "10:00", "ONE_HOUR"));
        Resp noDate = ana.put("/api/tasks/" + id, "{\"title\":\"T2\"}");
        assertThat(noDate.json().get("dueDate").isNull()).isTrue();
        assertThat(noDate.json().get("dueTime").isNull()).isTrue();
        assertThat(noDate.json().get("myReminder").isNull()).isTrue();
        assertThat(count("select count(*) from reminders where task_id = ?", id)).isZero();
        // The database never holds a time without a date.
        assertThat(count("select count(*) from tasks where due_time is not null and due_date is null")).isZero();
    }

    @Test
    void remindersFollowTheTaskTypeAndCanBeRemovedWithNone() throws Exception {
        Browser ana = zoned(signUp("Ana"), "UTC");
        long id = ana.createTask("T", null).get("id").asLong();

        // Date-only: ON_THE_DAY at 09:00, ONE_DAY at 09:00 the day before.
        Resp dateOnly = ana.put("/api/tasks/" + id, task("T", "2026-10-22", null, "ON_THE_DAY"));
        assertThat(dateOnly.json().get("myReminder").asText()).isEqualTo("ON_THE_DAY");
        assertThat(fireAt(id, ana.id)).isEqualTo(Instant.parse("2026-10-22T09:00:00Z"));
        Resp dayBefore = ana.put("/api/tasks/" + id, task("T", "2026-10-22", null, "ONE_DAY"));
        assertThat(dayBefore.json().get("myReminder").asText()).isEqualTo("ONE_DAY");
        assertThat(fireAt(id, ana.id)).isEqualTo(Instant.parse("2026-10-21T09:00:00Z"));

        // Adding a time keeps ONE_DAY (it fits both) and moves it to the time minus one day.
        Resp timed = ana.put("/api/tasks/" + id, "{\"title\":\"T\",\"dueDate\":\"2026-10-22\",\"dueTime\":\"15:00\"}");
        assertThat(timed.json().get("myReminder").asText()).isEqualTo("ONE_DAY");
        assertThat(fireAt(id, ana.id)).isEqualTo(Instant.parse("2026-10-21T15:00:00Z"));

        // The date-only offset ON_THE_DAY no longer fits a timed task: set it, then add a time: gone.
        ana.put("/api/tasks/" + id, task("T", "2026-10-22", null, "ON_THE_DAY"));
        Resp lost = ana.put("/api/tasks/" + id, "{\"title\":\"T\",\"dueDate\":\"2026-10-22\",\"dueTime\":\"15:00\"}");
        assertThat(lost.json().get("myReminder").isNull()).isTrue();
        assertThat(count("select count(*) from reminders where task_id = ?", id)).isZero();

        // NONE removes mine; a missing or null reminder leaves it.
        ana.put("/api/tasks/" + id, task("T", "2026-10-22", "15:00", "TEN_MINUTES"));
        assertThat(ana.put("/api/tasks/" + id, "{\"title\":\"T\",\"dueDate\":\"2026-10-22\"}").json()
                .get("myReminder").asText()).isEqualTo("TEN_MINUTES");
        assertThat(ana.put("/api/tasks/" + id, "{\"title\":\"T\",\"dueDate\":\"2026-10-22\",\"reminder\":null}")
                .json().get("myReminder").asText()).isEqualTo("TEN_MINUTES");
        assertThat(ana.put("/api/tasks/" + id, task("T", "2026-10-22", "15:00", "NONE")).json().get("myReminder")
                .isNull()).isTrue();
        assertThat(count("select count(*) from reminders where task_id = ?", id)).isZero();

        // Status changes (PATCH) never touch the time or the reminder.
        ana.put("/api/tasks/" + id, task("T", "2026-10-22", "15:00", "ONE_HOUR"));
        JsonNode done = ana.patch("/api/tasks/" + id, "{\"done\":true}").json();
        assertThat(done.get("dueTime").asText()).isEqualTo("15:00");
        assertThat(done.get("myReminder").asText()).isEqualTo("ONE_HOUR");
    }

    @Test
    void validationErrorsUseTheFieldErrorShape() throws Exception {
        Browser ana = zoned(signUp("Ana"), "UTC");
        long id = ana.createTask("T", null).get("id").asLong();

        expectField(ana.put("/api/tasks/" + id, "{\"title\":\"T\",\"dueTime\":\"09:30\"}"), "dueTime",
                "REQUIRES_DUE_DATE");
        expectField(ana.put("/api/tasks/" + id, "{\"title\":\"T\",\"reminder\":\"AT_DUE\"}"), "reminder",
                "REQUIRES_DUE_DATE");
        Resp both = ana.put("/api/tasks/" + id, "{\"title\":\"T\",\"dueTime\":\"09:30\",\"reminder\":\"ONE_DAY\"}");
        assertThat(both.status()).isEqualTo(400);
        assertThat(both.json().get("errors")).hasSize(2);
        // NONE needs no date; null neither.
        assertThat(ana.put("/api/tasks/" + id, "{\"title\":\"T\",\"reminder\":\"NONE\"}").status()).isEqualTo(200);
        assertThat(ana.put("/api/tasks/" + id, "{\"title\":\"T\",\"dueTime\":null,\"reminder\":null}").status())
                .isEqualTo(200);

        for (String bad : List.of("\"one_hour\"", "\"WEEKLY\"", "\"\"", "\"none\"")) {
            expectField(ana.put("/api/tasks/" + id, "{\"title\":\"T\",\"dueDate\":\"2026-10-21\",\"reminder\":"
                    + bad + "}"), "reminder", "INVALID_REMINDER");
        }
        // Wrong for the task type: reported once the task is found, nothing is saved.
        expectField(ana.put("/api/tasks/" + id, task("Changed", "2026-10-21", "09:30", "ON_THE_DAY")), "reminder",
                "INVALID_REMINDER");
        expectField(ana.put("/api/tasks/" + id, task("Changed", "2026-10-21", null, "AT_DUE")), "reminder",
                "INVALID_REMINDER");
        assertThat(ana.get("/api/tasks/" + id).json().get("title").asText()).isEqualTo("T");
        // A task that does not exist is a 404 before the offset is judged.
        expectProblem(ana.put("/api/tasks/99999999", task("X", "2026-10-21", null, "AT_DUE")), 404, "NOT_FOUND");

        // Strict HH:mm; anything else is a malformed body, like a bad dueDate.
        for (String bad : List.of("\"9:30\"", "\"09:30:15\"", "\"24:00\"", "\"09:60\"", "\"0930\"", "930", "true",
                "\"09:30 \"", "[9,30]")) {
            expectProblem(ana.put("/api/tasks/" + id, "{\"title\":\"T\",\"dueDate\":\"2026-10-21\",\"dueTime\":"
                    + bad + "}"), 400, "MALFORMED_REQUEST");
        }
        expectProblem(ana.put("/api/tasks/" + id, "{\"title\":\"T\",\"dueDate\":\"2026-10-21\",\"reminder\":1}"),
                400, "MALFORMED_REQUEST");
        assertThat(ana.put("/api/tasks/" + id, "{\"title\":\"T\",\"dueDate\":\"2026-10-21\",\"dueTime\":\"23:59\"}")
                .json().get("dueTime").asText()).isEqualTo("23:59");
        assertThat(ana.put("/api/tasks/" + id, "{\"title\":\"T\",\"dueDate\":\"2026-10-21\",\"dueTime\":\"00:00\"}")
                .json().get("dueTime").asText()).isEqualTo("00:00");
    }

    // ---- AC9, AC7: shared lists ----

    @Test
    void everyMemberHasTheirOwnReminderInTheirOwnZone() throws Exception {
        Browser ana = zoned(signUp("Ana"), "Europe/Bucharest");
        Browser bob = zoned(signUp("Bob"), "America/New_York");
        long list = ana.createList("Home");
        member(list, bob);
        long id = ana.createTask("Shared", list).get("id").asLong();

        ana.put("/api/tasks/" + id, task("Shared", "2026-10-21", "09:30", "AT_DUE"));
        // Bob sees the same date and time, and no reminder of Ana's.
        JsonNode seenByBob = bob.get("/api/tasks/" + id).json();
        assertThat(seenByBob.get("dueTime").asText()).isEqualTo("09:30");
        assertThat(seenByBob.get("myReminder").isNull()).isTrue();
        Resp bobs = bob.put("/api/tasks/" + id, task("Shared", "2026-10-21", "09:30", "ONE_HOUR"));
        assertThat(bobs.status()).as(bobs.body()).isEqualTo(200);
        assertThat(bobs.json().get("myReminder").asText()).isEqualTo("ONE_HOUR");
        assertThat(ana.get("/api/tasks/" + id).json().get("myReminder").asText()).isEqualTo("AT_DUE");
        assertThat(ana.get("/api/tasks?list=" + list).json().get("items").get(0).get("myReminder").asText())
                .isEqualTo("AT_DUE");

        // 09:30 is 06:30Z in Bucharest and 13:30Z in New York.
        assertThat(fireAt(id, ana.id)).isEqualTo(Instant.parse("2026-10-21T06:30:00Z"));
        assertThat(fireAt(id, bob.id)).isEqualTo(Instant.parse("2026-10-21T12:30:00Z"));

        // Ana changes the time (without sending a reminder): both are recomputed in their own zones.
        ana.put("/api/tasks/" + id, "{\"title\":\"Shared\",\"dueDate\":\"2026-10-21\",\"dueTime\":\"10:00\"}");
        assertThat(fireAt(id, ana.id)).isEqualTo(Instant.parse("2026-10-21T07:00:00Z"));
        assertThat(fireAt(id, bob.id)).isEqualTo(Instant.parse("2026-10-21T13:00:00Z"));
        assertThat(bob.get("/api/tasks/" + id).json().get("myReminder").asText()).isEqualTo("ONE_HOUR");

        // Bob removes his only; Ana's stays.
        bob.put("/api/tasks/" + id, task("Shared", "2026-10-21", "10:00", "NONE"));
        assertThat(count("select count(*) from reminders where task_id = ?", id)).isEqualTo(1);

        // Clearing the date drops everyone's reminder.
        bob.put("/api/tasks/" + id, task("Shared", "2026-10-21", "10:00", "TEN_MINUTES"));
        ana.put("/api/tasks/" + id, "{\"title\":\"Shared\"}");
        assertThat(count("select count(*) from reminders where task_id = ?", id)).isZero();
    }

    // ---- AC10, AC14, AC15: firing, the notification, the email ----

    @Test
    void aReminderFiresOnceWithANotificationAndAnEmail() throws Exception {
        Browser ana = verified(zoned(signUp("Ana"), "Europe/Bucharest"));
        long id = ana.createTask("Buy <b>milk</b> & eggs", null).get("id").asLong();
        ana.put("/api/tasks/" + id, "{\"title\":\"Buy <b>milk</b> & eggs\",\"description\":\"SECRET-DESCRIPTION\","
                + "\"dueDate\":\"2026-10-21\",\"dueTime\":\"09:30\",\"reminder\":\"ONE_HOUR\"}");

        setClock(Instant.parse("2026-10-21T05:29:59Z"));
        processor.runOnce();
        assertThat(count("select count(*) from notifications where user_id = ?", ana.id)).isZero();
        assertThat(reminderEmails(ana.email)).isEmpty();

        setClock(Instant.parse("2026-10-21T05:30:20Z"));
        processor.runOnce();
        processor.runOnce();
        setClock(Instant.parse("2026-10-21T05:35:00Z"));
        processor.runOnce();
        assertThat(ana.get("/api/notifications/count").body()).isEqualTo("{\"count\":1}");
        JsonNode page = ana.get("/api/notifications").json();
        assertThat(fieldNames(page)).containsExactly("items", "nextCursor");
        assertThat(page.get("nextCursor").isNull()).isTrue();
        JsonNode n = page.get("items").get(0);
        assertThat(fieldNames(n)).containsExactly("id", "type", "read", "createdAt", "late", "title", "dueDate",
                "dueTime", "taskId", "listId", "taskTrashed");
        assertThat(n.get("type").asText()).isEqualTo("REMINDER");
        assertThat(n.get("read").asBoolean()).isFalse();
        assertThat(n.get("late").asBoolean()).isFalse();
        assertThat(n.get("title").asText()).isEqualTo("Buy <b>milk</b> & eggs");
        assertThat(n.get("dueDate").asText()).isEqualTo("2026-10-21");
        assertThat(n.get("dueTime").asText()).isEqualTo("09:30");
        assertThat(n.get("taskId").asLong()).isEqualTo(id);
        assertThat(n.get("listId").asLong()).isEqualTo(ana.myTasks());
        assertThat(Instant.parse(n.get("createdAt").asText())).isEqualTo(Instant.parse("2026-10-21T05:30:20Z"));
        assertThat(count("select count(*) from notifications where user_id = ?", ana.id)).isEqualTo(1);
        assertThat(status(id, ana.id)).isEqualTo("SENT");

        Mailpit.Message mail = awaitReminderEmail(ana.email, 1);
        assertThat(mail.subject()).isEqualTo("Reminder: Buy <b>milk</b> & eggs");
        assertThat(mail.text()).contains("Hi " + ana.displayName + ",", "\"Buy <b>milk</b> & eggs\"",
                "Wed, 21 Oct 2026 at 09:30", "List: My tasks", "https://app.taskapp.test/tasks",
                "switch off \"Reminder emails\"").doesNotContain("SECRET-DESCRIPTION", "late");
        assertThat(mail.html()).contains("Buy &lt;b&gt;milk&lt;/b&gt; &amp; eggs", "https://app.taskapp.test/tasks")
                .doesNotContain("<b>milk", "SECRET-DESCRIPTION");
    }

    @Test
    void theEmailLinksToTheListOfASharedList() throws Exception {
        Browser ana = verified(zoned(signUp("Ana"), "UTC"));
        long list = ana.createList("Team <Home>");
        long id = ana.createTask("Pay", list).get("id").asLong();
        ana.put("/api/tasks/" + id, task("Pay", "2026-10-21", null, "ON_THE_DAY"));

        setClock(Instant.parse("2026-10-21T09:00:30Z"));
        processor.runOnce();
        Mailpit.Message mail = awaitReminderEmail(ana.email, 1);
        assertThat(mail.text()).contains("due on Wed, 21 Oct 2026.", "List: Team <Home>",
                "https://app.taskapp.test/lists/" + list).doesNotContain(" at ");
        assertThat(mail.html()).contains("Team &lt;Home&gt;");
        JsonNode n = ana.get("/api/notifications").json().get("items").get(0);
        assertThat(n.get("dueTime").isNull()).isTrue();
        assertThat(n.get("listId").asLong()).isEqualTo(list);
    }

    // ---- AC21: late and skipped ----

    @Test
    void aReminderThatIsLateIsFlaggedAndOneAnHourLateIsDropped() throws Exception {
        Browser ana = verified(zoned(signUp("Ana"), "UTC"));
        long late = timedTask(ana, "Late", "2026-10-21", "09:00", "AT_DUE");
        long veryLate = timedTask(ana, "Very late", "2026-10-21", "08:00", "AT_DUE");
        long justLate = timedTask(ana, "Just late", "2026-10-21", "09:00", "AT_DUE");

        // The server was down: it is 09:10, the 09:00 ones are 10 minutes late; the 08:00 one is 70 minutes late.
        setClock(Instant.parse("2026-10-21T09:10:00Z"));
        processor.runOnce();
        assertThat(status(veryLate, ana.id)).isEqualTo("SKIPPED");
        assertThat(count("select count(*) from notifications where user_id = ? and title = 'Very late'", ana.id))
                .isZero();
        JsonNode items = ana.get("/api/notifications").json().get("items");
        assertThat(items).hasSize(2);
        items.forEach(i -> assertThat(i.get("late").asBoolean()).isTrue());
        Mailpit.Message mail = mailpit.awaitLatest(ana.email, 3); // confirmation + 2 reminders
        assertThat(reminderEmails(ana.email)).hasSize(2);
        assertThat(reminderEmails(ana.email)).allSatisfy(m -> assertThat(m.text()).contains("This reminder is late."));
        assertThat(mail).isNotNull();

        // Exactly 5 minutes late is not late; exactly one hour late is dropped.
        long five = timedTask(ana, "Five", "2026-10-22", "09:00", "AT_DUE");
        long hour = timedTask(ana, "Hour", "2026-10-22", "09:00", "TEN_MINUTES");
        jdbc.update("update reminders set fire_at = ?, next_attempt_at = ? where task_id = ?",
                db(Instant.parse("2026-10-21T09:05:00Z")), db(Instant.parse("2026-10-21T09:05:00Z")), five);
        jdbc.update("update reminders set fire_at = ?, next_attempt_at = ? where task_id = ?",
                db(Instant.parse("2026-10-21T08:10:00Z")), db(Instant.parse("2026-10-21T08:10:00Z")), hour);
        processor.runOnce();
        assertThat(status(five, ana.id)).isEqualTo("SENT");
        assertThat(jdbc.queryForObject("select late from notifications where task_id = ?", Boolean.class, five))
                .isFalse();
        assertThat(status(hour, ana.id)).isEqualTo("SKIPPED");
        assertThat(count("select count(*) from notifications where task_id = ?", hour)).isZero();
        assertThat(status(late, ana.id)).isEqualTo("SENT");
        assertThat(status(justLate, ana.id)).isEqualTo("SENT");
    }

    // ---- AC13: no reminder when the task is Done, deleted, moved away or the user left ----

    @Test
    void noReminderFiresForADoneDeletedOrInaccessibleTask() throws Exception {
        Browser ana = verified(zoned(signUp("Ana"), "UTC"));
        Browser bob = verified(zoned(signUp("Bob"), "UTC"));
        long list = ana.createList("Home");
        member(list, bob);

        long done = timedTask(ana, "Done", "2026-10-21", "09:00", "AT_DUE");
        ana.patch("/api/tasks/" + done, "{\"done\":true}");
        long deleted = timedTask(ana, "Deleted", "2026-10-21", "09:00", "AT_DUE");
        ana.delete("/api/tasks/" + deleted);
        long left = sharedTimedTask(ana, bob, list, "Left", "2026-10-21", "09:00");
        long removed = sharedTimedTask(ana, bob, list, "Removed", "2026-10-21", "09:00");
        long moved = sharedTimedTask(ana, bob, list, "Moved", "2026-10-21", "09:00");
        long reopened = timedTask(ana, "Reopened", "2026-10-21", "09:00", "AT_DUE");
        ana.patch("/api/tasks/" + reopened, "{\"done\":true}");
        ana.patch("/api/tasks/" + reopened, "{\"done\":false}");
        long keep = sharedTimedTask(ana, bob, list, "Kept", "2026-10-21", "09:00");

        // Trashed (0.16.0): its reminder is kept for a restore but never fires (checked below).
        assertThat(count("select count(*) from reminders where task_id = ?", deleted)).isEqualTo(1);
        // Bob leaves the list (all his tasks there stop reminding him); Ana's own reminders stay.
        Resp leave = bob.post("/api/lists/" + list + "/leave", null);
        assertThat(leave.status()).as(leave.body()).isEqualTo(204);
        // Moving a task to a list Bob is not in; he can't edit it afterwards.
        long elsewhere = ana.createList("Elsewhere");
        member(list, bob);
        assertThat(ana.put("/api/tasks/" + moved, "{\"title\":\"Moved\",\"dueDate\":\"2026-10-21\","
                + "\"dueTime\":\"09:00\",\"listId\":" + elsewhere + "}").status()).isEqualTo(200);
        jdbc.update("delete from list_members where list_id = ? and user_id = ?", list, bob.id);

        setClock(Instant.parse("2026-10-21T09:00:30Z"));
        processor.runOnce();

        assertThat(status(done, ana.id)).isEqualTo("SKIPPED");
        assertThat(status(left, bob.id)).isEqualTo("SKIPPED");
        assertThat(status(removed, bob.id)).isEqualTo("SKIPPED");
        assertThat(status(moved, bob.id)).isEqualTo("SKIPPED");
        assertThat(count("select count(*) from notifications where user_id = ?", bob.id)).isZero();
        assertThat(status(left, ana.id)).isEqualTo("SENT");
        assertThat(status(keep, ana.id)).isEqualTo("SENT");
        assertThat(status(reopened, ana.id)).isEqualTo("SENT");
        List<String> titles = new ArrayList<>();
        ana.get("/api/notifications").json().get("items").forEach(i -> titles.add(i.get("title").asText()));
        assertThat(titles).containsExactlyInAnyOrder("Reopened", "Kept", "Left", "Removed", "Moved");
    }

    // ---- AC14: who gets an email ----

    @Test
    void emailsOnlyGoToActiveVerifiedAccountsWithTheToggleOnUnderTheCap() throws Exception {
        Browser unverified = zoned(signUp("Unverified"), "UTC");
        Browser off = verified(zoned(signUp("Off"), "UTC"));
        Browser disabled = verified(zoned(signUp("Disabled"), "UTC"));
        Browser busy = verified(zoned(signUp("Busy"), "UTC"));
        off.put("/api/account/reminder-emails", "{\"enabled\":false}");
        timedTask(unverified, "U", "2026-10-21", "09:00", "AT_DUE");
        timedTask(off, "O", "2026-10-21", "09:00", "AT_DUE");
        timedTask(disabled, "D", "2026-10-21", "09:00", "AT_DUE");
        jdbc.update("update users set disabled_at = ? where id = ?", db(T0), disabled.id);
        // The cap is 3 per hour: four reminders fire for Busy, all four notify, three email.
        for (int i = 0; i < 4; i++) {
            timedTask(busy, "B" + i, "2026-10-21", "09:00", "AT_DUE");
        }

        setClock(Instant.parse("2026-10-21T09:00:30Z"));
        processor.runOnce();

        for (Browser b : List.of(unverified, off, busy)) {
            assertThat(count("select count(*) from notifications where user_id = ?", b.id))
                    .as(b.displayName).isEqualTo(b == busy ? 4 : 1);
        }
        // The disabled account's session is ended, but its notification row exists (it just can't read it).
        assertThat(count("select count(*) from notifications where user_id = ?", disabled.id)).isEqualTo(1);
        awaitReminderEmail(busy.email, 3);
        Thread.sleep(500);
        assertThat(reminderEmails(unverified.email)).isEmpty();
        assertThat(reminderEmails(off.email)).isEmpty();
        assertThat(reminderEmails(disabled.email)).isEmpty();
        assertThat(reminderEmails(busy.email)).hasSize(3);
        assertThat(jdbc.queryForList("select status from reminders where user_id = ?", String.class, busy.id))
                .containsOnly("SENT");
    }

    // ---- AC11, AC12: recompute ----

    @Test
    void changingTheTimeTheOffsetOrTheZoneRecomputesAndAFiredReminderCanFireAgain() throws Exception {
        Browser ana = verified(zoned(signUp("Ana"), "UTC"));
        long id = timedTask(ana, "Move me", "2026-10-21", "09:00", "AT_DUE");
        setClock(Instant.parse("2026-10-21T09:00:30Z"));
        processor.runOnce();
        assertThat(status(id, ana.id)).isEqualTo("SENT");
        assertThat(ana.get("/api/tasks/" + id).json().get("myReminderPassed").asBoolean()).isTrue();

        // Edited to a time still in the past: it stays fired and does not fire again.
        ana.put("/api/tasks/" + id, task("Move me", "2026-10-21", "08:00", "AT_DUE"));
        assertThat(status(id, ana.id)).isEqualTo("SENT");
        setClock(Instant.parse("2026-10-21T09:05:00Z"));
        processor.runOnce();
        assertThat(count("select count(*) from notifications where task_id = ?", id)).isEqualTo(1);

        // Edited to a future time: re-armed, fires again at the new time.
        ana.put("/api/tasks/" + id, task("Move me", "2026-10-21", "11:00", "AT_DUE"));
        assertThat(status(id, ana.id)).isEqualTo("PENDING");
        assertThat(ana.get("/api/tasks/" + id).json().get("myReminderPassed").asBoolean()).isFalse();
        setClock(Instant.parse("2026-10-21T11:00:10Z"));
        processor.runOnce();
        assertThat(count("select count(*) from notifications where task_id = ?", id)).isEqualTo(2);

        // The offset changes: 11:00 - 1 h = 10:00 is in the past, so it is kept but never fires.
        Resp past = ana.put("/api/tasks/" + id, task("Move me", "2026-10-21", "11:00", "ONE_HOUR"));
        assertThat(past.json().get("myReminderPassed").asBoolean()).isTrue();
        assertThat(fireAt(id, ana.id)).isEqualTo(Instant.parse("2026-10-21T10:00:00Z"));
        setClock(Instant.parse("2026-10-21T12:00:00Z"));
        processor.runOnce();
        assertThat(count("select count(*) from notifications where task_id = ?", id)).isEqualTo(2);

        // The zone changes: a pending reminder is recomputed in the new zone.
        setClock(T0);
        long zoned = timedTask(ana, "Zone", "2026-10-21", "09:00", "AT_DUE");
        assertThat(fireAt(zoned, ana.id)).isEqualTo(Instant.parse("2026-10-21T09:00:00Z"));
        ana.put("/api/account/time-zone", "{\"timeZone\":\"Europe/Bucharest\"}");
        assertThat(fireAt(zoned, ana.id)).isEqualTo(Instant.parse("2026-10-21T06:00:00Z"));
        assertThat(status(zoned, ana.id)).isEqualTo("PENDING");
        // A zone that moves the fire time into the past drops it (it never fires).
        setClock(Instant.parse("2026-10-21T07:00:00Z"));
        ana.put("/api/account/time-zone", "{\"timeZone\":\"America/New_York\"}");
        assertThat(fireAt(zoned, ana.id)).isEqualTo(Instant.parse("2026-10-21T13:00:00Z"));
        assertThat(status(zoned, ana.id)).isEqualTo("PENDING");
        ana.put("/api/account/time-zone", "{\"timeZone\":\"Asia/Tokyo\"}");
        assertThat(fireAt(zoned, ana.id)).isEqualTo(Instant.parse("2026-10-21T00:00:00Z"));
        assertThat(status(zoned, ana.id)).isEqualTo("SKIPPED");
        assertThat(ana.get("/api/tasks/" + zoned).json().get("myReminderPassed").asBoolean()).isTrue();
    }

    @Test
    void anAccountWithoutAZoneIsComputedInUtcUntilItSetsOne() throws Exception {
        Browser ana = signUp("Ana");
        long id = timedTask(ana, "T", "2026-10-21", "09:00", "AT_DUE");
        assertThat(fireAt(id, ana.id)).isEqualTo(Instant.parse("2026-10-21T09:00:00Z"));
        ana.put("/api/account/time-zone", "{\"timeZone\":\"Europe/Bucharest\",\"ifUnset\":true}");
        assertThat(fireAt(id, ana.id)).isEqualTo(Instant.parse("2026-10-21T06:00:00Z"));
    }

    @Test
    void aReminderSavedInThePastIsKeptButNeverFires() throws Exception {
        Browser ana = verified(zoned(signUp("Ana"), "UTC"));
        setClock(Instant.parse("2026-10-21T12:00:00Z"));
        long id = ana.createTask("Late save", null).get("id").asLong();
        Resp r = ana.put("/api/tasks/" + id, task("Late save", "2026-10-21", "09:00", "AT_DUE"));
        assertThat(r.json().get("myReminder").asText()).isEqualTo("AT_DUE");
        assertThat(r.json().get("myReminderPassed").asBoolean()).isTrue();
        assertThat(status(id, ana.id)).isEqualTo("SKIPPED");
        processor.runOnce();
        setClock(Instant.parse("2026-10-21T12:30:00Z"));
        processor.runOnce();
        assertThat(count("select count(*) from notifications where user_id = ?", ana.id)).isZero();
        assertThat(ana.get("/api/notifications/count").body()).isEqualTo("{\"count\":0}");
    }

    // ---- AC23: daylight saving ----

    @Test
    void aDueTimeInASpringForwardGapFiresWhenTheClocksJumpAndAnOverlapUsesTheFirstTime() throws Exception {
        Browser ana = verified(zoned(signUp("Ana"), "Europe/Bucharest"));
        // 2027-03-28 03:00 -> 04:00 in Bucharest; 03:30 does not exist.
        long gap = timedTask(ana, "Gap", "2027-03-28", "03:30", "AT_DUE");
        assertThat(fireAt(gap, ana.id)).isEqualTo(Instant.parse("2027-03-28T01:00:00Z"));
        // 2027-10-31 04:00 -> 03:00; 03:30 happens twice.
        long overlap = timedTask(ana, "Overlap", "2027-10-31", "03:30", "AT_DUE");
        assertThat(fireAt(overlap, ana.id)).isEqualTo(Instant.parse("2027-10-31T00:30:00Z"));

        setClock(Instant.parse("2027-03-28T00:59:59Z"));
        processor.runOnce();
        assertThat(count("select count(*) from notifications where task_id = ?", gap)).isZero();
        setClock(Instant.parse("2027-03-28T01:00:05Z"));
        processor.runOnce();
        assertThat(count("select count(*) from notifications where task_id = ?", gap)).isEqualTo(1);
        // The stored and shown time stays the wall-clock value.
        assertThat(jdbc.queryForObject("select due_time from tasks where id = ?", String.class, gap))
                .isEqualTo("03:30:00");
    }

    // ---- AC17-AC19: notifications API ----

    @Test
    void notificationsAreMineNewestFirstPagedAndMarkedRead() throws Exception {
        Browser ana = verified(zoned(signUp("Ana"), "UTC"));
        Browser bob = verified(zoned(signUp("Bob"), "UTC"));
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            ids.add(timedTask(ana, "N" + i, "2026-10-21", "09:0" + i, "AT_DUE"));
        }
        timedTask(bob, "Bobs", "2026-10-21", "09:00", "AT_DUE");
        // One at a time so created_at differs.
        for (int i = 0; i < 5; i++) {
            setClock(Instant.parse("2026-10-21T09:0" + i + ":10Z"));
            processor.runOnce();
        }
        assertThat(ana.get("/api/notifications/count").body()).isEqualTo("{\"count\":5}");
        assertThat(bob.get("/api/notifications/count").body()).isEqualTo("{\"count\":1}");

        List<String> titles = new ArrayList<>();
        Resp first = ana.get("/api/notifications?limit=2");
        first.json().get("items").forEach(i -> titles.add(i.get("title").asText()));
        String cursor = first.json().get("nextCursor").asText();
        assertThat(titles).containsExactly("N4", "N3");
        Resp second = ana.get("/api/notifications?limit=2&cursor=" + cursor);
        second.json().get("items").forEach(i -> titles.add(i.get("title").asText()));
        Resp third = ana.get("/api/notifications?limit=2&cursor=" + second.json().get("nextCursor").asText());
        third.json().get("items").forEach(i -> titles.add(i.get("title").asText()));
        assertThat(titles).containsExactly("N4", "N3", "N2", "N1", "N0");
        assertThat(third.json().get("nextCursor").isNull()).isTrue();
        assertThat(ana.get("/api/notifications").json().get("items")).hasSize(5);

        // Mark one read: idempotent, count drops once.
        long target = first.json().get("items").get(0).get("id").asLong();
        Resp read = ana.post("/api/notifications/" + target + "/read", null);
        assertThat(read.status()).isEqualTo(200);
        assertThat(read.json().get("read").asBoolean()).isTrue();
        assertThat(ana.post("/api/notifications/" + target + "/read", null).json()).isEqualTo(read.json());
        assertThat(ana.get("/api/notifications/count").body()).isEqualTo("{\"count\":4}");

        // Another user's notification (and an unknown one) answers 404 NOTIFICATION_NOT_FOUND, identical.
        Resp other = bob.post("/api/notifications/" + target + "/read", null);
        Resp unknown = bob.post("/api/notifications/99999999/read", null);
        expectProblem(other, 404, "NOTIFICATION_NOT_FOUND");
        assertThat(other.json().get("detail")).isEqualTo(unknown.json().get("detail"));
        expectProblem(unknown, 404, "NOTIFICATION_NOT_FOUND");
        assertThat(ana.get("/api/notifications/count").body()).isEqualTo("{\"count\":4}");
        expectProblem(ana.post("/api/notifications/abc/read", null), 400, "MALFORMED_REQUEST");
        // Bob never sees Ana's notifications.
        assertThat(bob.get("/api/notifications").json().get("items")).hasSize(1);

        // Mark all read.
        assertThat(ana.post("/api/notifications/read-all", null).status()).isEqualTo(204);
        assertThat(ana.get("/api/notifications/count").body()).isEqualTo("{\"count\":0}");
        assertThat(ana.post("/api/notifications/read-all", null).status()).isEqualTo(204);
        assertThat(bob.get("/api/notifications/count").body()).isEqualTo("{\"count\":1}");
    }

    @Test
    void notificationQueryParametersAreValidated() throws Exception {
        Browser ana = signUp("Ana");
        for (String limit : List.of("0", "51", "-1", "abc", "1.5", "")) {
            expectField(ana.get("/api/notifications?limit=" + limit), "limit", "OUT_OF_RANGE");
        }
        assertThat(ana.get("/api/notifications?limit=50").status()).isEqualTo(200);
        assertThat(ana.get("/api/notifications?limit=1").status()).isEqualTo(200);
        for (String cursor : List.of("garbage", "AAAA", "x".repeat(80))) {
            expectField(ana.get("/api/notifications?cursor=" + cursor), "cursor", "INVALID_CURSOR");
        }
        Resp both = ana.get("/api/notifications?limit=0&cursor=zzz");
        assertThat(both.status()).isEqualTo(400);
        assertThat(both.json().get("errors")).hasSize(2);
        Browser anonymous = new Browser().start();
        assertThat(anonymous.get("/api/notifications").status()).isEqualTo(401);
        assertThat(anonymous.get("/api/notifications/count").status()).isEqualTo(401);
        assertThat(anonymous.post("/api/notifications/read-all", null).status()).isEqualTo(401);
        assertThat(ana.sendWithoutCsrf("POST", "/api/notifications/read-all", null).status()).isEqualTo(403);
        assertThat(ana.sendWithoutCsrf("POST", "/api/notifications/1/read", null).status()).isEqualTo(403);
    }

    @Test
    void aNotificationKeepsItsSnapshotAndLosesTheTaskLinkWhenTheTaskIsGoneOrInaccessible() throws Exception {
        Browser ana = verified(zoned(signUp("Ana"), "UTC"));
        Browser bob = verified(zoned(signUp("Bob"), "UTC"));
        long list = ana.createList("Home");
        member(list, bob);
        long gone = timedTask(ana, "Original title", "2026-10-21", "09:00", "AT_DUE");
        long shared = sharedTimedTask(ana, bob, list, "Shared one", "2026-10-21", "09:00");
        setClock(Instant.parse("2026-10-21T09:00:10Z"));
        processor.runOnce();

        // Edited and then deleted: the snapshot title stays, the links go.
        ana.put("/api/tasks/" + gone, task("Renamed", "2026-10-22", "10:00", "AT_DUE"));
        JsonNode edited = ana.get("/api/notifications").json().get("items");
        assertThat(titlesOf(edited)).contains("Original title");
        ana.delete("/api/tasks/" + gone);
        // In the trash (0.16.0) the links stay, with taskTrashed set; deleted for good they go.
        JsonNode trashed = byTitle(ana.get("/api/notifications").json().get("items"), "Original title");
        assertThat(trashed.get("taskTrashed").asBoolean()).isTrue();
        assertThat(trashed.get("taskId").asLong()).isEqualTo(gone);
        ana.delete("/api/trash/" + gone);
        JsonNode after = ana.get("/api/notifications").json().get("items");
        JsonNode goneItem = byTitle(after, "Original title");
        assertThat(goneItem.get("taskId").isNull()).isTrue();
        assertThat(goneItem.get("listId").isNull()).isTrue();
        assertThat(goneItem.get("dueDate").asText()).isEqualTo("2026-10-21");

        // Bob leaves the list: his notification stays but no longer points at the task.
        JsonNode before = byTitle(bob.get("/api/notifications").json().get("items"), "Shared one");
        assertThat(before.get("taskId").asLong()).isEqualTo(shared);
        assertThat(before.get("listId").asLong()).isEqualTo(list);
        bob.post("/api/lists/" + list + "/leave", null);
        JsonNode left = byTitle(bob.get("/api/notifications").json().get("items"), "Shared one");
        assertThat(left.get("taskId").isNull()).isTrue();
        assertThat(left.get("listId").isNull()).isTrue();
        assertThat(left.get("title").asText()).isEqualTo("Shared one");
    }

    @Test
    void notificationsOlderThanNinetyDaysAreDeletedInBatches() throws Exception {
        Browser ana = signUp("Ana");
        Instant now = clock.instant();
        for (int i = 0; i < 2500; i++) {
            insertNotification(ana.id, now.minus(Duration.ofDays(91)).minusSeconds(i));
        }
        insertNotification(ana.id, now.minus(Duration.ofDays(89)));
        insertNotification(ana.id, now);
        assertThat(processor.purgeOldNotifications()).isEqualTo(2500);
        assertThat(count("select count(*) from notifications where user_id = ?", ana.id)).isEqualTo(2);
        assertThat(processor.purgeOldNotifications()).isZero();
    }

    @Test
    void deletingAnAccountDeletesItsRemindersAndNotifications() throws Exception {
        Browser ana = verified(zoned(signUp("Ana"), "UTC"));
        Browser bob = verified(zoned(signUp("Bob"), "UTC"));
        long list = ana.createList("Home");
        member(list, bob);
        long id = sharedTimedTask(ana, bob, list, "Both", "2026-10-21", "09:00");
        setClock(Instant.parse("2026-10-21T09:00:10Z"));
        processor.runOnce();
        timedTask(bob, "Bobs own", "2026-10-22", "09:00", "AT_DUE");
        Resp deleted = bob.post("/api/account/delete", "{\"password\":\"" + PASSWORD + "\"}");
        assertThat(deleted.status()).as(deleted.body()).isEqualTo(204);
        assertThat(count("select count(*) from reminders where user_id = ?", bob.id)).isZero();
        assertThat(count("select count(*) from notifications where user_id = ?", bob.id)).isZero();
        assertThat(count("select count(*) from reminders where task_id = ? and user_id = ?", id, ana.id)).isEqualTo(1);
        assertThat(ana.get("/api/notifications/count").body()).isEqualTo("{\"count\":1}");
    }

    // ---- AC6: the due sort with times ----

    @Test
    void theDueSortPutsTimedTasksBeforeDateOnlyOnesAndPagesWithoutGapsOrRepeats() throws Exception {
        Browser ana = signUp("Ana");
        long list = ana.myTasks();
        // Created oldest to newest: ties keep "newest first".
        long noDate = rawTask(ana.id, list, "no date", null, null);
        long dayDateOnly = rawTask(ana.id, list, "d1 date-only", "2026-10-21", null);
        long day10 = rawTask(ana.id, list, "d1 10:00", "2026-10-21", "10:00:00");
        long day0900a = rawTask(ana.id, list, "d1 09:00 a", "2026-10-21", "09:00:00");
        long day0900b = rawTask(ana.id, list, "d1 09:00 b", "2026-10-21", "09:00:00");
        long dayDateOnly2 = rawTask(ana.id, list, "d1 date-only 2", "2026-10-21", null);
        long earlier = rawTask(ana.id, list, "d0 23:59", "2026-10-20", "23:59:00");
        long later = rawTask(ana.id, list, "d2 date-only", "2026-10-22", null);

        List<Long> expected = List.of(earlier, day0900b, day0900a, day10, dayDateOnly2, dayDateOnly, later, noDate);
        assertThat(ids(ana.get("/api/tasks?sort=due").json())).isEqualTo(expected);

        // A cursor from 0.13 / before the due time (version 2) is refused with INVALID_CURSOR; a fresh one works.
        for (int i = 0; i < 70; i++) {
            rawTask(ana.id, list, "bulk " + i, "2026-10-23", i % 3 == 0 ? null : String.format("%02d:%02d:00", i % 24, i % 60));
        }
        List<Long> all = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            Resp r = ana.get("/api/tasks?sort=due" + (cursor == null ? "" : "&cursor=" + cursor));
            assertThat(r.status()).as(r.body()).isEqualTo(200);
            all.addAll(ids(r.json()));
            cursor = r.json().get("nextCursor").isNull() ? null : r.json().get("nextCursor").asText();
            pages++;
        } while (cursor != null);
        assertThat(pages).isEqualTo(2);
        assertThat(all).hasSize(78).doesNotHaveDuplicates();
        // Same order as a single query by the database's rules.
        List<Long> byRule = jdbc.queryForList("select id from tasks where list_id = ? order by done, (due_date is null),"
                + " due_date, (due_time is null), due_time, created_at desc, id desc", Long.class, list);
        assertThat(all).isEqualTo(byRule);
        // The page boundary may fall between timed tasks of one date: every cursor step stays consistent.
        assertThat(ana.get("/api/tasks?sort=due&cursor=AQID").status()).isEqualTo(400);
    }

    // ---- AC20, AC24: two claimers, concurrent edits ----

    @Test
    void twoClaimersNeverNotifyTwice() throws Exception {
        List<Browser> users = new ArrayList<>();
        for (int u = 0; u < 3; u++) {
            users.add(zoned(signUp("U" + u), "UTC"));
        }
        for (int i = 0; i < 40; i++) {
            Browser b = users.get(i % 3);
            timedTask(b, "T" + i, "2026-10-21", "09:00", "AT_DUE");
        }
        setClock(Instant.parse("2026-10-21T09:00:30Z"));
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Integer>> runs = new ArrayList<>();
            for (int t = 0; t < 4; t++) {
                runs.add(pool.submit(() -> {
                    go.await();
                    return processor.runOnce();
                }));
            }
            go.countDown();
            for (Future<Integer> f : runs) {
                f.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(count("select count(*) from notifications")).isEqualTo(40);
        assertThat(count("select count(distinct task_id) from notifications")).isEqualTo(40);
        assertThat(count("select count(*) from reminders where status = 'SENT'")).isEqualTo(40);
        // Unverified accounts: no email at all.
        assertThat(reminderEmails(users.get(0).email)).isEmpty();
    }

    @Test
    void editsDeletesLeavesAndZoneChangesWhileRemindersFireNeverFailOrSendForADeletedTask() throws Exception {
        Browser ana = verified(zoned(signUp("Ana"), "UTC"));
        Browser bob = verified(zoned(signUp("Bob"), "UTC"));
        long list = ana.createList("Home");
        member(list, bob);
        List<Long> tasks = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            long id = ana.createTask("C" + i, list).get("id").asLong();
            ana.put("/api/tasks/" + id, task("C" + i, "2026-10-21", "09:00", "AT_DUE"));
            bob.put("/api/tasks/" + id, task("C" + i, "2026-10-21", "09:00", "TEN_MINUTES"));
            tasks.add(id);
        }
        setClock(Instant.parse("2026-10-21T09:00:30Z"));
        ExecutorService pool = Executors.newFixedThreadPool(5);
        List<Future<?>> work = new ArrayList<>();
        try {
            work.add(go(pool, () -> {
                for (int i = 0; i < 6; i++) {
                    processor.runOnce();
                }
            }));
            work.add(go(pool, processor::runOnce));
            work.add(go(pool, () -> {
                for (long id : tasks) {
                    if (id % 3 == 0) {
                        ana.delete("/api/tasks/" + id);
                    } else if (id % 3 == 1) {
                        ana.put("/api/tasks/" + id,
                                task("E", "2026-10-21", "09:" + (10 + ThreadLocalRandom.current().nextInt(40)), "AT_DUE"));
                    }
                }
            }));
            work.add(go(pool, () -> {
                for (String zone : List.of("Asia/Tokyo", "America/New_York", "UTC", "Europe/Bucharest")) {
                    bob.put("/api/account/time-zone", "{\"timeZone\":\"" + zone + "\"}");
                }
            }));
            work.add(go(pool, () -> {
                assertThat(bob.post("/api/lists/" + list + "/leave", null).status()).isIn(204, 404);
            }));
            for (Future<?> f : work) {
                f.get(120, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        // Nothing failed (no 5xx: the Browser throws on one). Everything left is consistent.
        processor.runOnce();
        // (Reminders of tasks in the trash are left alone until a restore, so they are not counted.)
        assertThat(count("select count(*) from reminders where status = 'PENDING' and next_attempt_at <= ?"
                + " and task_id not in (select id from tasks where deleted_at is not null)",
                db(clock.instant()))).isZero();
        assertThat(count("select count(*) from (select task_id from notifications where user_id = ?"
                + " and task_id is not null group by task_id having count(*) > 1) twice", ana.id)).isZero();
        // Every reminder left on a surviving task is either still waiting or finished, none stuck mid-way.
        assertThat(count("select count(*) from reminders where status = 'PENDING' and notified_at is not null")).isZero();
    }

    // ---- helpers ----

    private interface Job {
        void run() throws Exception;
    }

    private static Future<?> go(ExecutorService pool, Job job) {
        return pool.submit(() -> {
            job.run();
            return null;
        });
    }

    private Map<String, Object> bodyOf(String title, String date, String time, String reminder) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", title);
        body.put("dueDate", date);
        body.put("dueTime", time);
        if (reminder != null) {
            body.put("reminder", reminder);
        }
        return body;
    }

    /** A full PUT body: dueTime is sent (null clears), reminder only when given. */
    private String task(String title, String date, String time, String reminder) {
        return JSON.writeValueAsString(bodyOf(title, date, time, reminder));
    }

    /** A task in the user's "My tasks" with a time and a reminder; returns its id. */
    private long timedTask(Browser b, String title, String date, String time, String reminder) throws Exception {
        long id = b.createTask(title, null).get("id").asLong();
        Resp r = b.put("/api/tasks/" + id, task(title, date, time, reminder));
        assertThat(r.status()).as(r.body()).isEqualTo(200);
        return id;
    }

    /** A task in a shared list; both members get a reminder (AT_DUE). */
    private long sharedTimedTask(Browser owner, Browser member, long list, String title, String date, String time)
            throws Exception {
        long id = owner.createTask(title, list).get("id").asLong();
        assertThat(owner.put("/api/tasks/" + id, task(title, date, time, "AT_DUE")).status()).isEqualTo(200);
        assertThat(member.put("/api/tasks/" + id, task(title, date, time, "AT_DUE")).status()).isEqualTo(200);
        return id;
    }

    private long rawTask(long ownerId, long listId, String title, String date, String time) {
        jdbc.update("insert into tasks (owner_id, list_id, title, due_date, due_time) values (?, ?, ?, ?, ?)", ownerId,
                listId, title, date, time);
        Long id = jdbc.queryForObject("select max(id) from tasks", Long.class);
        // Strictly increasing created_at, so "newest first" ties are predictable.
        jdbc.update("update tasks set created_at = ? where id = ?", db(T0.plusSeconds(id)), id);
        return id;
    }

    private void insertNotification(long userId, Instant createdAt) {
        jdbc.update("insert into notifications (user_id, title, due_date, created_at) values (?, 'old', '2026-01-01', ?)",
                userId, db(createdAt));
    }

    private void member(long list, Browser b) {
        jdbc.update("insert into list_members (list_id, user_id) values (?, ?)", list, b.id);
    }

    private Browser zoned(Browser b, String zone) throws Exception {
        Resp r = b.put("/api/account/time-zone", "{\"timeZone\":\"" + zone + "\"}");
        assertThat(r.status()).as(r.body()).isEqualTo(200);
        return b;
    }

    private Browser verified(Browser b) {
        jdbc.update("update users set email_verified_at = now(6) where id = ?", b.id);
        return b;
    }

    private static LocalDateTime db(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private Instant fireAt(long taskId, long userId) {
        return jdbc.queryForObject("select fire_at from reminders where task_id = ? and user_id = ?",
                LocalDateTime.class, taskId, userId).toInstant(ZoneOffset.UTC);
    }

    private String status(long taskId, long userId) {
        return jdbc.queryForObject("select status from reminders where task_id = ? and user_id = ?", String.class,
                taskId, userId);
    }

    private int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    private List<Mailpit.Message> reminderEmails(String address) {
        return mailpit.to(address).stream().filter(m -> m.subject().startsWith("Reminder: ")).toList();
    }

    private Mailpit.Message awaitReminderEmail(String address, int count) {
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> assertThat(reminderEmails(address)).hasSizeGreaterThanOrEqualTo(count));
        List<Mailpit.Message> found = reminderEmails(address);
        assertThat(found).hasSize(count);
        return found.get(found.size() - 1);
    }

    private static List<Long> ids(JsonNode page) {
        List<Long> out = new ArrayList<>();
        page.get("items").forEach(t -> out.add(t.get("id").asLong()));
        return out;
    }

    private static List<String> titlesOf(JsonNode items) {
        List<String> out = new ArrayList<>();
        items.forEach(i -> out.add(i.get("title").asText()));
        return out;
    }

    private static JsonNode byTitle(JsonNode items, String title) {
        for (JsonNode i : items) {
            if (title.equals(i.get("title").asText())) {
                return i;
            }
        }
        throw new AssertionError("no notification titled " + title + " in " + items);
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> out = new ArrayList<>();
        for (Iterator<String> it = node.propertyNames().iterator(); it.hasNext();) {
            out.add(it.next());
        }
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
            Resp r = post("/api/lists", "{\"name\":\"" + name.replace("\"", "\\\"") + "\"}");
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
