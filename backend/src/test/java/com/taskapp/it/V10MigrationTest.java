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
 * AC1 / AC9 (specs/sharing-team-lists.md): V10 on a database that already has users, tasks and tags. Migrates to V9,
 * adds pre-feature rows, then applies V10 and checks the backfill. No Spring context: Flyway is driven directly.
 */
@Testcontainers
class V10MigrationTest {

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
    void everyUserGetsMyTasksHoldingAllTheirTasksAndTags() throws Exception {
        flyway("9").migrate();
        try (Connection c = DriverManager.getConnection(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
                Statement st = c.createStatement()) {
            st.executeUpdate("insert into users (id, email, password_hash, display_name, created_at) values"
                    + " (1, 'a@example.com', 'h', 'A', '2026-01-01 10:00:00.123456'),"
                    + " (2, 'b@example.com', 'h', 'B', '2026-02-01 10:00:00'),"
                    + " (3, 'c@example.com', null, 'C', '2026-03-01 10:00:00')");
            st.executeUpdate("insert into tasks (id, owner_id, title, status, done) values"
                    + " (1, 1, 'a1', 'TODO', false), (2, 1, 'a2', 'DONE', true), (3, 2, 'b1', 'IN_PROGRESS', false)");
            // The same tag name for two users (allowed per user in V9).
            st.executeUpdate("insert into tags (id, owner_id, name) values (1, 1, 'Work'), (2, 1, 'Home'), (3, 2, 'work')");
            st.executeUpdate("insert into task_tags (task_id, tag_id) values (1, 1), (2, 1), (2, 2), (3, 3)");
            List<String> tasksBefore = rows(st, "select id, owner_id, title, status, created_at, updated_at from tasks order by id");
            List<String> tagsBefore = rows(st, "select id, owner_id, name from tags order by id");
            List<String> linksBefore = rows(st, "select task_id, tag_id from task_tags order by task_id, tag_id");

            flyway("10").migrate();

            assertThat(rows(st, "select version from flyway_schema_history where success = 1"
                    + " order by installed_rank desc limit 1")).containsExactly("10");
            // AC1: exactly one personal "My tasks" per user, created when the user was.
            assertThat(rows(st, "select owner_id, name, personal, created_at from lists order by owner_id"))
                    .containsExactly("1|My tasks|1|2026-01-01 10:00:00.123456", "2|My tasks|1|2026-02-01 10:00:00",
                            "3|My tasks|1|2026-03-01 10:00:00");
            // AC9: every task and tag is on its owner's "My tasks"; nothing else changed.
            assertThat(rows(st, "select count(*) from tasks t join lists l on l.id = t.list_id"
                    + " where l.owner_id <> t.owner_id or not l.personal")).containsExactly("0");
            assertThat(rows(st, "select count(*) from tags g join lists l on l.id = g.list_id"
                    + " where l.owner_id <> g.owner_id or not l.personal")).containsExactly("0");
            assertThat(rows(st, "select id, owner_id, title, status, created_at, updated_at from tasks order by id"))
                    .isEqualTo(tasksBefore);
            assertThat(rows(st, "select id, owner_id, name from tags order by id")).isEqualTo(tagsBefore);
            assertThat(rows(st, "select task_id, tag_id from task_tags order by task_id, tag_id")).isEqualTo(linksBefore);

            // Constraints after V10.
            assertThat(rows(st, "select is_nullable from information_schema.columns where table_schema = database()"
                    + " and table_name in ('tasks', 'tags') and column_name = 'list_id'")).containsExactly("NO", "NO");
            assertThat(rows(st, "select distinct index_name from information_schema.statistics"
                    + " where table_schema = database() and table_name = 'tasks' order by index_name"))
                    .contains("ix_tasks_list_done_created", "ix_tasks_list_status_created");
            assertThat(rows(st, "select distinct index_name from information_schema.statistics"
                    + " where table_schema = database() and table_name = 'tags' order by index_name"))
                    .contains("uk_tags_list_name", "ix_tags_owner").doesNotContain("uk_tags_owner_name");
            long myTasks1 = Long.parseLong(rows(st, "select id from lists where owner_id = 1").get(0));
            assertThatThrownBy(() -> st.executeUpdate("insert into lists (owner_id, name, personal) values (1, 'x', true)"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("uk_lists_one_personal_per_owner");
            assertThatThrownBy(() -> st.executeUpdate(
                    "insert into tags (owner_id, list_id, name) values (1, " + myTasks1 + ", 'WORK')"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("uk_tags_list_name");
        }
    }
}
