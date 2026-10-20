package com.taskapp.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.taskapp.email.MailDelivery;
import com.taskapp.reminder.ReminderProcessor;
import com.taskapp.security.ratelimit.MutableClock;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * T4a delivery rules that need a broken or slow mail server (specs/due-times-reminders.md AC22, AC20): the test
 * settings point SMTP at a closed port, so every send fails like a mail server that is down. Data is inserted with
 * SQL; time is a {@link MutableClock}; {@link ReminderProcessor#runOnce()} is called by the tests.
 */
@Testcontainers
@SpringBootTest
class ReminderDeliveryIntegrationTest {

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

    private static final Instant T0 = Instant.parse("2026-10-21T09:00:30Z");
    private static final Instant FIRE = Instant.parse("2026-10-21T09:00:00Z");

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MutableClock clock;

    @Autowired
    ReminderProcessor processor;

    @Autowired
    org.springframework.context.ApplicationContext context;

    @MockitoSpyBean
    MailDelivery delivery;

    private final ListAppender<ILoggingEvent> log = new ListAppender<>();
    private final Logger processorLogger = (Logger) LoggerFactory.getLogger(ReminderProcessor.class);

    @BeforeEach
    void reset() {
        Mockito.reset(delivery);
        setClock(T0);
        jdbc.update("delete from notifications");
        jdbc.update("delete from reminders");
        log.start();
        processorLogger.addAppender(log);
    }

    @AfterEach
    void detach() {
        processorLogger.detachAppender(log);
    }

    private void setClock(Instant to) {
        clock.advance(Duration.between(clock.instant(), to));
    }

    @Test
    void whenTheMailServerIsDownTheNotificationStaysAndTheEmailIsTriedThreeTimesThenFailed() {
        long a = reminder("a", true, FIRE);
        long b = reminder("b", true, FIRE);

        processor.runOnce();
        // Both got their notification; neither email went out; both wait for a retry.
        assertThat(notifications()).isEqualTo(2);
        assertThat(row(a)).containsEntry("status", "PENDING").containsEntry("attempts", 1);
        assertThat(row(b)).containsEntry("status", "PENDING").containsEntry("attempts", 1);

        // Not before a minute has passed.
        setClock(T0.plusSeconds(30));
        processor.runOnce();
        assertThat(row(a)).containsEntry("attempts", 1);

        setClock(T0.plusSeconds(61));
        processor.runOnce();
        assertThat(row(a)).containsEntry("status", "PENDING").containsEntry("attempts", 2);
        assertThat(row(b)).containsEntry("status", "PENDING").containsEntry("attempts", 2);

        setClock(T0.plusSeconds(130));
        processor.runOnce();
        assertThat(row(a)).containsEntry("status", "FAILED").containsEntry("attempts", 3);
        assertThat(row(b)).containsEntry("status", "FAILED").containsEntry("attempts", 3);

        // Nothing more happens, and there was never a second notification.
        setClock(T0.plusSeconds(3600));
        processor.runOnce();
        assertThat(notifications()).isEqualTo(2);
        assertThat(row(a)).containsEntry("attempts", 3);

        // Logged, without any address or text.
        List<String> messages = log.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        assertThat(messages).anySatisfy(m -> assertThat(m).contains("failed for reminder " + a + " after 3 attempts"));
        assertThat(String.join("\n", messages)).doesNotContain("@example.com", "Buy milk", "Reminder:");
    }

    @Test
    void aClaimedAttemptThatNeverReportedBackIsRetriedAfterTheLeaseAndNotBefore() {
        long id = reminder("lease", true, FIRE);
        // The state a crashed instance leaves: notified, attempt 1 claimed, leased for 5 minutes.
        jdbc.update("update reminders set notified_at = ?, attempts = 1, next_attempt_at = ? where id = ?", db(T0),
                db(T0.plus(Duration.ofMinutes(5))), id);
        processor.runOnce();
        setClock(T0.plus(Duration.ofMinutes(4)));
        processor.runOnce();
        verify(delivery, never()).send(anyString(), any());
        assertThat(row(id)).containsEntry("attempts", 1);

        doNothing().when(delivery).send(anyString(), any());
        setClock(T0.plus(Duration.ofMinutes(5)).plusSeconds(1));
        processor.runOnce();
        assertThat(row(id)).containsEntry("status", "SENT").containsEntry("attempts", 2);
        // The crashed attempt's notification is not duplicated by the retry.
        assertThat(notifications()).isZero();
    }

    @Test
    void anAttemptThatWasClaimedThreeTimesIsFailedWithoutSendingAgain() {
        long id = reminder("poison", true, FIRE);
        jdbc.update("update reminders set notified_at = ?, attempts = 3, next_attempt_at = ? where id = ?", db(T0),
                db(T0.minusSeconds(1)), id);
        processor.runOnce();
        verify(delivery, never()).send(anyString(), any());
        assertThat(row(id)).containsEntry("status", "FAILED");
    }

    @Test
    void anEditDuringTheSendIsNotOverwrittenByTheFinishingEmail() {
        long id = reminder("edit", true, FIRE);
        Instant later = Instant.parse("2026-10-21T12:00:00Z");
        doAnswer(inv -> {
            // The user moves the task to 12:00 while the email is being sent: the reminder is re-armed.
            jdbc.update("update reminders set fire_at = ?, next_attempt_at = ?, status = 'PENDING', attempts = 0,"
                    + " notified_at = null where id = ?", db(later), db(later), id);
            return null;
        }).when(delivery).send(anyString(), any());

        processor.runOnce();

        assertThat(row(id)).containsEntry("status", "PENDING").containsEntry("attempts", 0);
        assertThat(notifications()).isEqualTo(1);
        // It fires again at the new time, with a second notification.
        setClock(later.plusSeconds(5));
        doNothing().when(delivery).send(anyString(), any());
        processor.runOnce();
        assertThat(row(id)).containsEntry("status", "SENT");
        assertThat(notifications()).isEqualTo(2);
    }

    @Test
    void aTaskDeletedBeforeTheClaimSendsNothing() {
        long id = reminder("gone", true, FIRE);
        long task = jdbc.queryForObject("select task_id from reminders where id = ?", Long.class, id);
        jdbc.update("delete from tasks where id = ?", task);
        processor.runOnce();
        verify(delivery, never()).send(anyString(), any());
        assertThat(notifications()).isZero();
    }

    @Test
    void anUnexpectedErrorOnOneReminderDoesNotStopTheOthers() {
        long first = reminder("first", true, FIRE);
        long second = reminder("second", true, FIRE);
        String firstEmail = jdbc.queryForObject("select u.email from users u join reminders r on r.user_id = u.id"
                + " where r.id = ?", String.class, first);
        doAnswer(inv -> {
            throw new IllegalStateException("boom");
        }).when(delivery).send(org.mockito.ArgumentMatchers.eq(firstEmail), any());
        doNothing().when(delivery).send(org.mockito.ArgumentMatchers.argThat(to -> !to.equals(firstEmail)), any());

        processor.runOnce();

        assertThat(row(second)).containsEntry("status", "SENT");
        assertThat(row(first)).containsEntry("status", "PENDING").containsEntry("attempts", 1);
        assertThat(notifications()).isEqualTo(2);
    }

    @Test
    void theTimerIsOffWhenRemindersAreDisabled() {
        // app.reminders.enabled=false (test application.properties): the processor exists, the timer does not.
        assertThat(context.getBeanNamesForType(com.taskapp.reminder.ReminderScheduler.class)).isEmpty();
        assertThat(context.getBeanNamesForType(ReminderProcessor.class)).hasSize(1);
    }

    // ---- helpers ----

    private int notifications() {
        return jdbc.queryForObject("select count(*) from notifications", Integer.class);
    }

    private java.util.Map<String, Object> row(long id) {
        return jdbc.queryForMap("select status, attempts from reminders where id = ?", id);
    }

    private static LocalDateTime db(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    /** A verified user with a task due at {@code fire} (AT_DUE, UTC) and its reminder; returns the reminder id. */
    private long reminder(String name, boolean verified, Instant fire) {
        String email = name + "-" + System.nanoTime() + "@example.com";
        jdbc.update("insert into users (email, password_hash, display_name, email_verified_at, time_zone)"
                + " values (?, 'h', ?, ?, 'UTC')", email, name + " " + System.nanoTime(),
                verified ? db(T0.minusSeconds(3600)) : null);
        long user = jdbc.queryForObject("select id from users where email = ?", Long.class, email);
        com.taskapp.task.TestLists.personal(jdbc, user);
        long list = jdbc.queryForObject("select id from lists where owner_id = ?", Long.class, user);
        jdbc.update("insert into tasks (owner_id, list_id, title, due_date, due_time) values (?, ?, 'Buy milk',"
                + " '2026-10-21', '09:00:00')", user, list);
        long task = jdbc.queryForObject("select max(id) from tasks where owner_id = ?", Long.class, user);
        jdbc.update("insert into reminders (task_id, user_id, reminder_offset, fire_at, next_attempt_at)"
                + " values (?, ?, 'AT_DUE', ?, ?)", task, user, db(fire), db(fire));
        return jdbc.queryForObject("select id from reminders where task_id = ?", Long.class, task);
    }
}
