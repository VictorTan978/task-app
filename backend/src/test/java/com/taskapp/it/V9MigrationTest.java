package com.taskapp.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * AC15 (specs/tasks-priority-status-tags.md): V9 on a database that already has tasks. Migrates to V8, adds
 * pre-feature rows, then applies V9 and checks the backfill. No Spring context: Flyway is driven directly.
 */
@Testcontainers
class V9MigrationTest {

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

    @Test
    void existingTasksGetPriorityNoneNoTagsAndStatusFromDone() throws Exception {
        flyway("8").migrate();
        try (Connection c = DriverManager.getConnection(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
                Statement st = c.createStatement()) {
            st.executeUpdate("insert into users (id, email, password_hash, display_name)"
                    + " values (1, 'a@example.com', 'h', 'A'), (2, 'b@example.com', 'h', 'B')");
            st.executeUpdate("insert into tasks (id, owner_id, title, done, created_at, updated_at) values"
                    + " (1, 1, 'open', false, '2026-01-01 10:00:00.123456', '2026-01-02 10:00:00.654321'),"
                    + " (2, 1, 'finished', true, '2026-01-01 11:00:00', '2026-01-03 11:00:00'),"
                    + " (3, 2, 'other user done', true, '2026-01-01 12:00:00', '2026-01-04 12:00:00')");
            List<String> before = rows(st, "select id, owner_id, title, done, created_at, updated_at from tasks order by id");

            // V9 only: later migrations change these tables again (V10MigrationTest covers V10).
            flyway("9").migrate();

            assertThat(rows(st, "select version from flyway_schema_history where success = 1 order by installed_rank desc limit 1")).containsExactly("9");
            assertThat(rows(st, "select id, priority, status, done from tasks order by id"))
                    .containsExactly("1|NONE|TODO|0", "2|NONE|DONE|1", "3|NONE|DONE|1");
            // Nothing else changed, updated_at included.
            assertThat(rows(st, "select id, owner_id, title, done, created_at, updated_at from tasks order by id"))
                    .isEqualTo(before);
            assertThat(rows(st, "select count(*) from tags")).containsExactly("0");
            assertThat(rows(st, "select count(*) from task_tags")).containsExactly("0");
            // The V2 index stays (All/Active/Done), the new one serves ?filter=in_progress.
            assertThat(rows(st, "select distinct index_name from information_schema.statistics"
                    + " where table_schema = database() and table_name = 'tasks' order by index_name"))
                    .contains("ix_tasks_owner_done_created", "ix_tasks_owner_status_created");
            assertThat(rows(st, "select constraint_name from information_schema.check_constraints"
                    + " where constraint_schema = database() and constraint_name like 'ck_t%' order by constraint_name"))
                    .contains("ck_tasks_priority", "ck_tasks_status", "ck_tasks_status_matches_done",
                            "ck_tags_name_chars", "ck_tags_name_length", "ck_tags_name_spaces");
        }
    }
}
