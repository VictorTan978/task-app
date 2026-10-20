package com.taskapp.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * V12 (specs/due-times-reminders.md T4a) on a database that already has users and tasks: existing rows keep their
 * meaning (no zone, reminder emails on, no due time), and the new constraints and cascades work. No Spring context:
 * Flyway is driven directly.
 */
@Testcontainers
class V12MigrationTest {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
            .withUrlParam("connectionTimeZone", "UTC")
            .withUrlParam("forceConnectionTimeZoneToSession", "true");

    private Flyway flyway(String target) {
        return Flyway.configure()
                .dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }

    private static List<String> rows(Statement st, String sql) throws Exception {
        List<String> out = new ArrayList<>();
        try (ResultSet rs = st.executeQuery(sql)) {
            int columns = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                StringBuilder row = new StringBuilder();
                for (int i = 1; i <= columns; i++) {
                    row.append(i == 1 ? "" : "|").append(rs.getString(i));
                }
                out.add(row.toString());
            }
        }
        return out;
    }

    private static void rejected(Statement st, String sql) {
        assertThatThrownBy(() -> st.executeUpdate(sql)).as(sql).isInstanceOf(SQLException.class);
    }

    @Test
    void existingRowsKeepTheirMeaningAndTheNewConstraintsHold() throws Exception {
        flyway("11").migrate();
        try (Connection c = DriverManager.getConnection(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
                Statement st = c.createStatement()) {
            st.executeUpdate("insert into users (id, email, password_hash, display_name) values"
                    + " (1, 'a@example.com', 'h', 'A'), (2, 'b@example.com', 'h', 'B')");
            st.executeUpdate("insert into lists (id, owner_id, name, personal) values (10, 1, 'My tasks', true)");
            st.executeUpdate("insert into list_members (list_id, user_id) values (10, 1)");
            st.executeUpdate("insert into tasks (id, owner_id, list_id, title, due_date) values"
                    + " (1, 1, 10, 'dated', '2026-10-21'), (2, 1, 10, 'undated', null)");

            flyway("12").migrate();

            assertThat(rows(st, "select version from flyway_schema_history where success = 1"
                    + " order by installed_rank desc limit 1")).containsExactly("12");
            assertThat(rows(st, "select id, time_zone, reminder_emails from users order by id"))
                    .containsExactly("1|null|1", "2|null|1");
            assertThat(rows(st, "select id, due_date, due_time from tasks order by id"))
                    .containsExactly("1|2026-10-21|null", "2|null|null");

            // users.time_zone: bounded length. due_time needs a due date.
            st.executeUpdate("update users set time_zone = 'Europe/Bucharest' where id = 1");
            rejected(st, "update users set time_zone = '' where id = 2");
            rejected(st, "update users set time_zone = '" + "x".repeat(65) + "' where id = 2");
            st.executeUpdate("update tasks set due_time = '09:30:00' where id = 1");
            rejected(st, "update tasks set due_time = '09:30:00' where id = 2");

            // reminders: one per user per task, exact status / offset values.
            st.executeUpdate("insert into reminders (task_id, user_id, reminder_offset, fire_at, next_attempt_at)"
                    + " values (1, 1, 'ONE_HOUR', '2026-10-21 05:30:00', '2026-10-21 05:30:00')");
            assertThat(rows(st, "select status, attempts, notified_at from reminders")).containsExactly("PENDING|0|null");
            rejected(st, "insert into reminders (task_id, user_id, reminder_offset, fire_at, next_attempt_at)"
                    + " values (1, 1, 'AT_DUE', '2026-10-21 06:30:00', '2026-10-21 06:30:00')");
            rejected(st, "insert into reminders (task_id, user_id, reminder_offset, fire_at, next_attempt_at)"
                    + " values (1, 2, 'one_hour', '2026-10-21 05:30:00', '2026-10-21 05:30:00')");
            rejected(st, "insert into reminders (task_id, user_id, reminder_offset, fire_at, next_attempt_at)"
                    + " values (1, 2, 'ONE_HOUR ', '2026-10-21 05:30:00', '2026-10-21 05:30:00')");
            rejected(st, "insert into reminders (task_id, user_id, reminder_offset, fire_at, status, next_attempt_at)"
                    + " values (1, 2, 'ONE_HOUR', '2026-10-21 05:30:00', 'pending', '2026-10-21 05:30:00')");
            rejected(st, "insert into reminders (task_id, user_id, reminder_offset, fire_at, next_attempt_at)"
                    + " values (99, 2, 'ONE_HOUR', '2026-10-21 05:30:00', '2026-10-21 05:30:00')");

            // notifications: the task goes NULL when it is deleted, everything goes with the user.
            st.executeUpdate("insert into notifications (user_id, task_id, title, due_date) values"
                    + " (1, 1, 'dated', '2026-10-21')");
            assertThat(rows(st, "select type, late, read_at from notifications")).containsExactly("REMINDER|0|null");
            rejected(st, "insert into notifications (user_id, task_id, type, title, due_date) values"
                    + " (1, 1, 'OTHER', 'x', '2026-10-21')");
            rejected(st, "insert into notifications (user_id, title, due_date) values (1, '', '2026-10-21')");

            st.executeUpdate("delete from tasks where id = 1");
            assertThat(rows(st, "select count(*) from reminders")).containsExactly("0");
            assertThat(rows(st, "select task_id from notifications")).containsExactly("null");

            st.executeUpdate("delete from users where id = 1");
            assertThat(rows(st, "select count(*) from notifications")).containsExactly("0");
        }
    }
}
