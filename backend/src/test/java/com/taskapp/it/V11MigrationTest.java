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
 * V11 (specs/sharing-team-lists.md T3b) on a database that already has users, lists, tasks and tags: migrates to V10,
 * adds rows, then applies V11 and checks the backfill (every list's owner becomes its only member) and the new
 * constraints. No Spring context: Flyway is driven directly.
 */
@Testcontainers
class V11MigrationTest {

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
    void everyListsOwnerBecomesItsOnlyMemberAndTasksSurviveTheirAddersDeletion() throws Exception {
        flyway("10").migrate();
        try (Connection c = DriverManager.getConnection(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
                Statement st = c.createStatement()) {
            st.executeUpdate("insert into users (id, email, password_hash, display_name) values"
                    + " (1, 'a@example.com', 'h', 'A'), (2, 'b@example.com', 'h', 'B')");
            st.executeUpdate("insert into lists (id, owner_id, name, personal, created_at) values"
                    + " (10, 1, 'My tasks', true, '2026-01-01 10:00:00.123456'),"
                    + " (11, 1, 'Home', false, '2026-01-02 10:00:00'),"
                    + " (20, 2, 'My tasks', true, '2026-02-01 10:00:00')");
            st.executeUpdate("insert into tasks (id, owner_id, list_id, title) values (1, 1, 10, 'a1'),"
                    + " (2, 1, 11, 'a2'),"
                    + " (3, 2, 20, 'b1')");
            st.executeUpdate("insert into tags (id, owner_id, list_id, name) values (1, 1, 11, 'Work')");
            st.executeUpdate("insert into task_tags (task_id, tag_id) values (2, 1)");
            List<String> tasksBefore = rows(st, "select * from tasks order by id");

            flyway("11").migrate();

            assertThat(rows(st, "select version from flyway_schema_history where success = 1"
                    + " order by installed_rank desc limit 1")).containsExactly("11");
            assertThat(rows(st, "select list_id, user_id, joined_at from list_members order by list_id"))
                    .containsExactly("10|1|2026-01-01 10:00:00.123456", "11|1|2026-01-02 10:00:00",
                            "20|2|2026-02-01 10:00:00");
            assertThat(rows(st, "select count(*) from list_invitations")).containsExactly("0");
            assertThat(rows(st, "select * from tasks order by id")).isEqualTo(tasksBefore);
            assertThat(rows(st, "select is_nullable from information_schema.columns where table_schema = database()"
                    + " and table_name = 'tasks' and column_name = 'owner_id'")).containsExactly("YES");
            assertThat(rows(st, "select constraint_name, delete_rule from information_schema.referential_constraints"
                    + " where constraint_schema = database() and table_name in ('tasks', 'list_members',"
                    + " 'list_invitations') order by constraint_name")).containsExactly(
                            "fk_list_invitations_list|CASCADE", "fk_list_members_list|CASCADE",
                            "fk_list_members_user|CASCADE", "fk_tasks_list|CASCADE", "fk_tasks_owner|SET NULL");

            // B joins A's "Home" and adds a task there; deleting B keeps it, with owner_id NULL, and keeps A's tag.
            st.executeUpdate("insert into list_members (list_id, user_id) values (11, 2)");
            st.executeUpdate("insert into tasks (id, owner_id, list_id, title) values (4, 2, 11, 'by b')");
            st.executeUpdate("insert into task_tags (task_id, tag_id) values (4, 1)");
            st.executeUpdate("delete from users where id = 2");
            assertThat(rows(st, "select id, owner_id, list_id from tasks order by id"))
                    .containsExactly("1|1|10", "2|1|11", "4|null|11");
            assertThat(rows(st, "select id from tags")).containsExactly("1");
            assertThat(rows(st, "select list_id, user_id from list_members order by list_id"))
                    .containsExactly("10|1", "11|1");
            assertThatThrownBy(() -> st.executeUpdate("insert into list_members (list_id, user_id) values (11, 1)"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("PRIMARY");
        }
    }
}
