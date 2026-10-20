package com.taskapp.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.taskapp.reminder.ReminderScheduler;

/**
 * The real timer (specs/due-times-reminders.md AC10): with {@code app.reminders.enabled=true} and a short poll
 * interval, a reminder whose time arrives is fired by the background job within about a second, on the real clock.
 */
@Testcontainers
// The timer must stop with this class: a cached context would keep polling a database that is gone.
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(properties = {"app.reminders.enabled=true", "app.reminders.poll-interval=300ms"})
class ReminderSchedulerIntegrationTest {

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
            .withUrlParam("connectionTimeZone", "UTC")
            .withUrlParam("forceConnectionTimeZoneToSession", "true");

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    org.springframework.context.ApplicationContext context;

    @Test
    void theTimerFiresAReminderWhenItsTimeArrives() {
        assertThat(context.getBeanNamesForType(ReminderScheduler.class)).hasSize(1);
        jdbc.update("insert into users (email, password_hash, display_name, time_zone) values"
                + " ('timer@example.com', 'h', 'Timer', 'UTC')");
        long user = jdbc.queryForObject("select id from users where email = 'timer@example.com'", Long.class);
        long list = com.taskapp.task.TestLists.personal(jdbc, user);
        jdbc.update("insert into tasks (owner_id, list_id, title, due_date, due_time) values (?, ?, 'Timer task',"
                + " '2026-10-21', '09:00:00')", user, list);
        long task = jdbc.queryForObject("select id from tasks where owner_id = ?", Long.class, user);
        LocalDateTime soon = LocalDateTime.ofInstant(Instant.now().plusSeconds(1), ZoneOffset.UTC);
        jdbc.update("insert into reminders (task_id, user_id, reminder_offset, fire_at, next_attempt_at)"
                + " values (?, ?, 'AT_DUE', ?, ?)", task, user, soon, soon);

        Awaitility.await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(200)).untilAsserted(() ->
                assertThat(jdbc.queryForObject("select count(*) from notifications where user_id = ?", Integer.class,
                        user)).isEqualTo(1));
        // Unverified account: the notification exists, no email was tried, and the reminder is done.
        assertThat(jdbc.queryForObject("select status from reminders where task_id = ?", String.class, task))
                .isEqualTo("SENT");
    }
}
