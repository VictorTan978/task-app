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
 * Performance constraint of specs/undo-trash-dnd-bulk.md: "the trash filter must use an index, so task-list queries stay as
 * fast as today". 100,000 tasks in 20 lists, 5,000 of them trashed. The live-list query keeps its V10 index (the
 * {@code deleted_at IS NULL} filter is a row condition), the trash listing and the purge use the V14 indexes. No Spring
 * context. The plans are printed so a change shows up in the build log.
 */
@Testcontainers
class V14QueryPlanTest {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
            .withUrlParam("connectionTimeZone", "UTC")
            .withUrlParam("forceConnectionTimeZoneToSession", "true");

    private record Plan(String keys, String text) {
    }

    private static Plan explain(Statement st, String sql) throws Exception {
        List<String> keys = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        try (ResultSet rs = st.executeQuery("EXPLAIN " + sql)) {
            while (rs.next()) {
                keys.add(rs.getString("table") + ":" + rs.getString("key"));
                text.append(rs.getString("table")).append(" type=").append(rs.getString("type")).append(" key=")
                        .append(rs.getString("key")).append(" rows=").append(rs.getString("rows")).append(" extra=")
                        .append(rs.getString("Extra")).append('\n');
            }
        }
        return new Plan(String.join(";", keys), text.toString());
    }

    private static long millis(Statement st, String sql) throws Exception {
        long start = System.nanoTime();
        try (ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                rs.getObject(1);
            }
        }
        return (System.nanoTime() - start) / 1_000_000;
    }

    @Test
    void liveListTrashListingAndPurgeUseTheirIndexes() throws Exception {
        Flyway.configure().dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                .locations("classpath:db/migration").load().migrate();
        try (Connection c = DriverManager.getConnection(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
                Statement st = c.createStatement()) {
            st.executeUpdate("insert into users (id, email, password_hash, display_name) values (1, 'a@example.com',"
                    + " 'h', 'A')");
            st.executeUpdate("insert into lists (id, owner_id, name, personal) with recursive l(n) as (select 1 union all"
                    + " select n + 1 from l where n < 20) select n, 1, concat('L', n), n = 1 from l");
            st.executeUpdate("insert into list_members (list_id, user_id) select id, 1 from lists");
            st.executeUpdate("set session cte_max_recursion_depth = 200000");
            st.executeUpdate("insert into tasks (id, owner_id, list_id, title, created_at, updated_at, deleted_at,"
                    + " deleted_by) with recursive seq(n) as (select 1 union all select n + 1 from seq where n < 100000)"
                    + " select n, 1, 1 + n % 20, concat('t', n), date_add('2026-01-01', interval n second),"
                    + " date_add('2026-01-01', interval n second),"
                    + " if(n % 20 = 0, date_add('2026-09-01', interval n second), null), if(n % 20 = 0, 1, null)"
                    + " from seq");
            st.execute("analyze table tasks, lists, list_members");

            String live = "select t.* from tasks t where t.list_id = 3 and t.deleted_at is null and t.done = false"
                    + " order by t.done, t.created_at desc, t.id desc limit 51";
            String liveAll = "select t.* from tasks t where t.list_id = 3 and t.deleted_at is null"
                    + " order by t.done, t.created_at desc, t.id desc limit 51";
            String liveCount = "select count(*) from tasks t where t.list_id = 3 and t.deleted_at is null";
            String trashAll = "select t.* from tasks t join list_members m on m.list_id = t.list_id and m.user_id = 1"
                    + " where t.deleted_at is not null order by t.deleted_at desc, t.id desc limit 51";
            String trashList = "select t.* from tasks t join list_members m on m.list_id = t.list_id and m.user_id = 1"
                    + " where t.deleted_at is not null and t.list_id = 20 order by t.deleted_at desc, t.id desc limit 51";
            String purge = "select t.id, t.list_id from tasks t where t.deleted_at < '2026-09-02'"
                    + " order by t.deleted_at, t.id limit 100";

            Plan livePlan = explain(st, live);
            Plan liveAllPlan = explain(st, liveAll);
            Plan countPlan = explain(st, liveCount);
            Plan trashAllPlan = explain(st, trashAll);
            Plan trashListPlan = explain(st, trashList);
            Plan purgePlan = explain(st, purge);
            System.out.println("V14 PLAN live (active):\n" + livePlan.text());
            System.out.println("V14 PLAN live (all):\n" + liveAllPlan.text());
            System.out.println("V14 PLAN live count:\n" + countPlan.text());
            System.out.println("V14 PLAN trash (all my lists):\n" + trashAllPlan.text());
            System.out.println("V14 PLAN trash (one list):\n" + trashListPlan.text());
            System.out.println("V14 PLAN purge:\n" + purgePlan.text());

            // The live list keeps its V10 sort index (no filesort), so it is as fast as before V14.
            assertThat(livePlan.keys()).isEqualTo("t:ix_tasks_list_done_created");
            assertThat(livePlan.text()).doesNotContain("filesort");
            assertThat(liveAllPlan.keys()).isEqualTo("t:ix_tasks_list_done_created");
            assertThat(liveAllPlan.text()).doesNotContain("filesort");
            assertThat(countPlan.keys()).as(countPlan.text()).contains("t:ix_tasks_list_");
            // The trash of one list and the purge are index range scans on the V14 indexes.
            assertThat(trashListPlan.keys()).as(trashListPlan.text()).contains("t:ix_tasks_list_deleted");
            assertThat(purgePlan.keys()).isEqualTo("t:ix_tasks_deleted");
            assertThat(purgePlan.text()).doesNotContain("filesort");
            // The trash of all my lists reads only trashed rows through an index, not the whole table.
            assertThat(trashAllPlan.text()).doesNotContain("type=ALL");

            long liveMs = millis(st, live);
            long trashMs = millis(st, trashAll);
            long purgeMs = millis(st, purge);
            System.out.println("V14 TIMES ms: live=" + liveMs + " trash=" + trashMs + " purge=" + purgeMs);
            assertThat(liveMs).isLessThan(200);
            assertThat(trashMs).isLessThan(500);
            assertThat(purgeMs).isLessThan(200);
        }
    }
}
