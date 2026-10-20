package com.taskapp.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Performance constraint of specs/due-times-reminders.md: the scheduler's scan uses the claim index and stays under
 * 50 ms with 100,000 pending reminders; the unread count, the notification list and the retention delete use their
 * indexes. No Spring context.
 */
@Testcontainers
class V12QueryPlanTest {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
            .withUrlParam("connectionTimeZone", "UTC")
            .withUrlParam("forceConnectionTimeZoneToSession", "true");

    private static String explain(Statement st, String sql) throws Exception {
        StringBuilder out = new StringBuilder();
        try (ResultSet rs = st.executeQuery("EXPLAIN " + sql)) {
            while (rs.next()) {
                out.append(rs.getString("table")).append(':').append(rs.getString("key")).append(';');
            }
        }
        return out.toString();
    }

    @Test
    void theClaimScanUsesItsIndexAndStaysFastAt100000PendingReminders() throws Exception {
        Flyway.configure().dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                .locations("classpath:db/migration").load().migrate();
        try (Connection c = DriverManager.getConnection(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
                Statement st = c.createStatement()) {
            st.executeUpdate("insert into users (id, email, password_hash, display_name) values (1, 'a@example.com',"
                    + " 'h', 'A')");
            st.executeUpdate("insert into lists (id, owner_id, name, personal) values (10, 1, 'My tasks', true)");
            st.executeUpdate("set session cte_max_recursion_depth = 200000");
            st.executeUpdate("insert into tasks (id, owner_id, list_id, title, due_date, due_time) with recursive seq(n)"
                    + " as (select 1 union all select n + 1 from seq where n < 100000)"
                    + " select n, 1, 10, concat('t', n), date_add('2026-11-01', interval n % 365 day),"
                    + " sec_to_time((n % 1440) * 60) from seq");
            // 100,000 pending reminders, 100 of them due now, the rest in the future (two users would be the same
            // plan; one is enough because the scan does not filter by user).
            st.executeUpdate("insert into users (id, email, password_hash, display_name)"
                    + " values (2, 'b@example.com', 'h', 'B')");
            st.executeUpdate("insert into reminders (task_id, user_id, reminder_offset, fire_at, next_attempt_at)"
                    + " select id, 1, 'AT_DUE', date_add('2026-10-21 09:00:00', interval id second),"
                    + " date_add('2026-10-21 09:00:00', interval id second) from tasks");
            st.execute("analyze table reminders, tasks");

            String due = "select id, user_id, task_id from reminders where status = 'PENDING'"
                    + " and next_attempt_at <= '2026-10-21 09:01:40' order by next_attempt_at, id limit 100";
            assertThat(explain(st, due)).isEqualTo("reminders:ix_reminders_claim;");
            long best = Long.MAX_VALUE;
            for (int i = 0; i < 5; i++) {
                long start = System.nanoTime();
                int rows = 0;
                try (ResultSet rs = st.executeQuery(due)) {
                    while (rs.next()) {
                        rows++;
                    }
                }
                best = Math.min(best, (System.nanoTime() - start) / 1_000_000);
                assertThat(rows).isEqualTo(100);
            }
            assertThat(best).as("best of 5 scans, ms").isLessThan(50);

            // Nothing due: the same index answers without reading the rest.
            assertThat(explain(st, due.replace("2026-10-21 09:01:40", "2026-10-21 08:00:00")))
                    .isEqualTo("reminders:ix_reminders_claim;");

            // Notifications: list, unread count, retention.
            assertThat(explain(st, "select id from notifications where user_id = 1 order by created_at desc, id desc"
                    + " limit 21")).isEqualTo("notifications:ix_notifications_user_created;");
            assertThat(explain(st, "select count(*) from notifications where user_id = 1 and read_at is null"))
                    .isEqualTo("notifications:ix_notifications_user_unread;");
            assertThat(explain(st, "delete from notifications where created_at < '2026-07-01' limit 1000"))
                    .contains("ix_notifications_created");
            // My reminders of the tasks on a page.
            assertThat(explain(st, "select task_id, reminder_offset, fire_at from reminders where user_id = 1"
                    + " and task_id in (1, 2, 3)")).contains("reminders:");

            // The due sort of a list with 100,000 tasks: no index serves it (before and after T4a it is a sort of
            // the list's rows); the first page of an unfiltered list still has to be fast.
            String page = "select * from tasks t where t.list_id = 10 order by t.done, (t.due_date is null),"
                    + " t.due_date, (t.due_time is null), t.due_time, t.created_at desc, t.id desc limit 51";
            long pageBest = Long.MAX_VALUE;
            for (int i = 0; i < 3; i++) {
                long start = System.nanoTime();
                try (ResultSet rs = st.executeQuery(page)) {
                    while (rs.next()) {
                        // read all 51 rows
                    }
                }
                pageBest = Math.min(pageBest, (System.nanoTime() - start) / 1_000_000);
            }
            System.out.println("DUE sort plan: " + explain(st, page) + " first page of 100,000 tasks: " + pageBest + " ms");
            assertThat(pageBest).as("first page of the due sort at 100,000 tasks, ms").isLessThan(300);
        }
    }
}
