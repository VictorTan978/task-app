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
 * V13 (specs/due-times-reminders.md T4b) on a database that already has tasks: existing rows get no rule, and the
 * CHECKs accept exactly the allowed rule shapes. No Spring context: Flyway is driven directly.
 */
@Testcontainers
class V13MigrationTest {

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
    void existingTasksHaveNoRuleAndTheChecksAcceptOnlyValidShapes() throws Exception {
        flyway("12").migrate();
        try (Connection c = DriverManager.getConnection(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
                Statement st = c.createStatement()) {
            st.executeUpdate("insert into users (id, email, password_hash, display_name) values (1, 'a@example.com', 'h', 'A')");
            st.executeUpdate("insert into lists (id, owner_id, name, personal) values (10, 1, 'My tasks', true)");
            st.executeUpdate("insert into list_members (list_id, user_id) values (10, 1)");
            st.executeUpdate("insert into tasks (id, owner_id, list_id, title, due_date) values"
                    + " (1, 1, 10, 'dated', '2026-10-21'), (2, 1, 10, 'undated', null)");

            flyway("13").migrate();

            assertThat(rows(st, "select version from flyway_schema_history where success = 1"
                    + " order by installed_rank desc limit 1")).containsExactly("13");
            assertThat(rows(st, "select id, repeat_frequency, repeat_weekdays, repeat_anchor from tasks order by id"))
                    .containsExactly("1|null|null|null", "2|null|null|null");

            st.executeUpdate("update tasks set repeat_frequency = 'DAILY', repeat_anchor = '2026-10-21' where id = 1");
            st.executeUpdate("update tasks set repeat_frequency = 'WEEKLY', repeat_weekdays = 127,"
                    + " repeat_anchor = '2026-10-21' where id = 1");
            st.executeUpdate("update tasks set repeat_frequency = 'MONTHLY', repeat_weekdays = null where id = 1");
            // Case-sensitive frequency, unknown frequency.
            rejected(st, "update tasks set repeat_frequency = 'daily' where id = 1");
            rejected(st, "update tasks set repeat_frequency = 'HOURLY' where id = 1");
            // Weekly needs 1..127 weekdays; the others must have none.
            rejected(st, "update tasks set repeat_frequency = 'WEEKLY', repeat_weekdays = null where id = 1");
            rejected(st, "update tasks set repeat_frequency = 'WEEKLY', repeat_weekdays = 0 where id = 1");
            rejected(st, "update tasks set repeat_frequency = 'WEEKLY', repeat_weekdays = 128 where id = 1");
            rejected(st, "update tasks set repeat_frequency = 'DAILY', repeat_weekdays = 1 where id = 1");
            // All or nothing, and a rule needs the anchor and a due date.
            rejected(st, "update tasks set repeat_anchor = null where id = 1");
            rejected(st, "update tasks set repeat_frequency = null where id = 1");
            rejected(st, "update tasks set due_date = null where id = 1");
            rejected(st, "update tasks set repeat_frequency = 'DAILY', repeat_anchor = '2026-10-21' where id = 2");
            // Clearing the due date together with the rule is the only way.
            st.executeUpdate("update tasks set due_date = null, due_time = null, repeat_frequency = null,"
                    + " repeat_weekdays = null, repeat_anchor = null where id = 1");
            assertThat(rows(st, "select repeat_frequency, repeat_weekdays, repeat_anchor from tasks where id = 1"))
                    .containsExactly("null|null|null");
        }
    }
}
