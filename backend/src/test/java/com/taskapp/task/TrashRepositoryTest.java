package com.taskapp.task;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.taskapp.auth.User;

/**
 * V14 and every query that must skip (or find only) trashed tasks, MySQL 8.4 with the real migrations (contract
 * 0.16.0): task lookups, the list query and its count, the list's task count, moves, the trash reads, and the lock set
 * of an account deletion.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TrashQueries.class, ListService.class, MembershipRepository.class})
class TrashRepositoryTest {

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
            .withUrlParam("connectionTimeZone", "UTC")
            .withUrlParam("forceConnectionTimeZoneToSession", "true");

    private static final Instant T0 = Instant.parse("2026-10-01T00:00:00Z");

    @Autowired
    TaskRepository tasks;

    @Autowired
    TrashQueries queries;

    @Autowired
    ListRepository lists;

    @Autowired
    MembershipRepository members;

    @Autowired
    TestEntityManager em;

    @Autowired
    JdbcTemplate jdbc;

    long ana;
    long bob;
    long zoe;
    long home;
    long work;
    long zoes;

    @BeforeEach
    void data() {
        ana = em.persistAndFlush(new User("ana@example.com", "$2a$10$" + "x".repeat(53), "Ana")).getId();
        bob = em.persistAndFlush(new User("bob@example.com", "$2a$10$" + "x".repeat(53), "Bob")).getId();
        zoe = em.persistAndFlush(new User("zoe@example.com", "$2a$10$" + "x".repeat(53), "Zoe")).getId();
        home = TestLists.personal(jdbc, ana);
        work = TestLists.other(jdbc, ana, "Work");
        zoes = TestLists.personal(jdbc, zoe);
        TestLists.member(jdbc, work, bob);
    }

    private long task(long owner, long list, String title) {
        Task task = em.persistAndFlush(new Task(owner, list, title, "about " + title, null, T0));
        return task.getId();
    }

    private void trash(long id, long by, Instant at) {
        jdbc.update("update tasks set deleted_at = ?, deleted_by = ? where id = ?", Timestamp.from(at), by, id);
        em.clear();
    }

    // ---- the repository writes ----

    @Test
    void trashAndRestoreSetAndClearBothColumnsWithoutTouchingUpdatedAt() {
        long id = task(ana, home, "a");
        Instant updatedBefore = jdbc.queryForObject("select updated_at from tasks where id = ?", Timestamp.class, id)
                .toInstant();

        assertThat(tasks.trash(id, bob, T0.plusSeconds(5))).isEqualTo(1);
        assertThat(tasks.trash(id, ana, T0.plusSeconds(9))).as("already trashed").isZero();
        Task trashed = tasks.findById(id).orElseThrow();
        assertThat(trashed.getDeletedAt()).isEqualTo(T0.plusSeconds(5));
        assertThat(trashed.getDeletedBy()).isEqualTo(bob);
        assertThat(trashed.isTrashed()).isTrue();

        assertThat(tasks.restore(id)).isEqualTo(1);
        assertThat(tasks.restore(id)).as("not trashed").isZero();
        Task back = tasks.findById(id).orElseThrow();
        assertThat(back.getDeletedAt()).isNull();
        assertThat(back.getDeletedBy()).isNull();
        assertThat(back.getUpdatedAt()).isEqualTo(updatedBefore);
    }

    @Test
    void savingATaskNeverWritesTheTrashColumns() {
        long id = task(ana, home, "a");
        trash(id, bob, T0.plusSeconds(5));
        em.clear();
        Task loaded = tasks.findById(id).orElseThrow();
        loaded.changePriority(Priority.HIGH, T0.plusSeconds(6));
        tasks.saveAndFlush(loaded);

        assertThat(jdbc.queryForObject("select count(*) from tasks where id = ? and deleted_by = ? and deleted_at is not null",
                Long.class, id, bob)).isEqualTo(1);
    }

    // ---- lookups ----

    @Test
    void liveLookupsSkipTrashedTasksAndTrashLookupsFindOnlyThose() {
        long live = task(ana, home, "live");
        long gone = task(ana, work, "gone");
        trash(gone, ana, T0.plusSeconds(1));

        assertThat(tasks.findAccessible(live, ana)).isPresent();
        assertThat(tasks.findAccessible(gone, ana)).isEmpty();
        assertThat(tasks.findAccessible(gone, bob)).isEmpty();
        assertThat(tasks.findByIdAndListId(live, home)).isPresent();
        assertThat(tasks.findByIdAndListId(gone, work)).isEmpty();

        assertThat(tasks.findTrashedAccessible(gone, ana)).isPresent();
        assertThat(tasks.findTrashedAccessible(gone, bob)).as("a member sees the shared trash").isPresent();
        assertThat(tasks.findTrashedAccessible(gone, zoe)).as("not a member: 404").isEmpty();
        assertThat(tasks.findTrashedAccessible(live, ana)).isEmpty();
        assertThat(tasks.findTrashedByIdAndListId(gone, work)).isPresent();
        assertThat(tasks.findTrashedByIdAndListId(gone, home)).isEmpty();
        assertThat(tasks.findTrashedByIdAndListId(live, home)).isEmpty();
    }

    @Test
    void aMoveNeverTouchesATrashedTask() {
        long gone = task(ana, home, "gone");
        trash(gone, ana, T0.plusSeconds(1));

        assertThat(tasks.moveToList(gone, home, work)).isZero();
        assertThat(jdbc.queryForObject("select list_id from tasks where id = ?", Long.class, gone)).isEqualTo(home);
    }

    // ---- the list query, its count, tags, search ----

    @Test
    void theListQueryItsCountTheTagFilterAndSearchSkipTrashedTasks() {
        long live = task(ana, home, "milk live");
        long gone = task(ana, home, "milk gone");
        jdbc.update("insert into tags (owner_id, list_id, name) values (?, ?, 'Shop')", ana, home);
        long tag = jdbc.queryForObject("select max(id) from tags", Long.class);
        jdbc.update("insert into task_tags (task_id, tag_id) values (?, ?), (?, ?)", live, tag, gone, tag);
        trash(gone, ana, T0.plusSeconds(1));

        for (TaskFilter filter : TaskFilter.values()) {
            for (TaskSort sort : TaskSort.values()) {
                var criteria = new TaskSearchCriteria(home, filter, sort, null, null);
                assertThat(tasks.findPage(criteria, null, 100)).extracting(Task::getId)
                        .as(filter + " " + sort).doesNotContain(gone);
            }
        }
        var all = new TaskSearchCriteria(home, TaskFilter.ALL, TaskSort.NEWEST, null, null);
        assertThat(tasks.countMatching(all)).isEqualTo(1);
        var byTag = new TaskSearchCriteria(home, TaskFilter.ALL, TaskSort.NEWEST, tag, null);
        assertThat(tasks.findPage(byTag, null, 100)).extracting(Task::getId).containsExactly(live);
        assertThat(tasks.countMatching(byTag)).isEqualTo(1);
        var searched = new TaskSearchCriteria(home, TaskFilter.ALL, TaskSort.NEWEST, null, "milk");
        assertThat(tasks.findPage(searched, null, 100)).extracting(Task::getId).containsExactly(live);
        assertThat(tasks.countMatching(searched)).isEqualTo(1);
        // The trashed task still uses its tag.
        assertThat(jdbc.queryForObject("select count(*) from task_tags where tag_id = ?", Long.class, tag)).isEqualTo(2);
    }

    @Test
    void theListsTaskCountSkipsTrashedTasks() {
        task(ana, home, "a");
        long gone = task(ana, home, "b");
        trash(gone, ana, T0);

        assertThat(lists.countTasks(home)).isEqualTo(1);
    }

    // ---- the trash reads ----

    @Test
    void myTrashHoldsTheTrashedTasksOfEveryListIAmAMemberOfNewestDeletedFirst() {
        long a = task(ana, home, "a");
        long b = task(ana, work, "b");
        long c = task(bob, work, "c");
        long d = task(zoe, zoes, "d");
        long e = task(ana, home, "live");
        trash(a, ana, T0.plusSeconds(10));
        trash(b, bob, T0.plusSeconds(30));
        trash(c, ana, T0.plusSeconds(30));
        trash(d, zoe, T0.plusSeconds(20));

        assertThat(queries.findPage(ana, null, null, 50)).extracting(Task::getId).containsExactly(c, b, a);
        assertThat(queries.count(ana, null)).isEqualTo(3);
        assertThat(queries.findPage(bob, null, null, 50)).extracting(Task::getId).containsExactly(c, b);
        assertThat(queries.findPage(ana, work, null, 50)).extracting(Task::getId).containsExactly(c, b);
        assertThat(queries.count(ana, home)).isEqualTo(1);
        assertThat(queries.findPage(zoe, null, null, 50)).extracting(Task::getId).containsExactly(d);
        assertThat(queries.findPage(ana, zoes, null, 50)).as("not my list").isEmpty();
        assertThat(queries.findPage(ana, null, null, 50)).extracting(Task::getId).doesNotContain(e, d);
    }

    @Test
    void theTrashIsPagedByAKeysetOnDeletedAtThenId() {
        long[] ids = new long[7];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = task(ana, home, "t" + i);
            trash(ids[i], ana, T0.plusSeconds(i / 2));
        }
        List<Long> seen = new java.util.ArrayList<>();
        TrashCursor after = null;
        while (true) {
            List<Task> page = queries.findPage(ana, null, after, 3);
            page.forEach(t -> seen.add(t.getId()));
            if (page.size() < 3) {
                break;
            }
            after = TrashCursor.after(page.get(page.size() - 1));
        }
        assertThat(seen).containsExactly(ids[6], ids[5], ids[4], ids[3], ids[2], ids[1], ids[0]);
    }

    @Test
    void idsForEmptyAndExpiredOnesForThePurge() {
        long a = task(ana, home, "a");
        long b = task(ana, work, "b");
        long c = task(zoe, zoes, "c");
        task(ana, home, "live");
        trash(a, ana, T0);
        trash(b, bob, T0.plusSeconds(100));
        trash(c, zoe, T0.minusSeconds(100));

        assertThat(queries.findIds(ana, null, 0, 10)).containsExactly(a, b);
        assertThat(queries.findIds(ana, null, a, 10)).containsExactly(b);
        assertThat(queries.findIds(ana, work, 0, 10)).containsExactly(b);
        assertThat(queries.findIds(ana, null, 0, 1)).containsExactly(a);

        assertThat(queries.findExpired(T0.plusSeconds(50), 10)).extracting(TrashQueries.Expired::id)
                .containsExactly(c, a);
        assertThat(queries.findExpired(T0.plusSeconds(50), 10).get(0).listId()).isEqualTo(zoes);
        assertThat(queries.findExpired(T0.minusSeconds(100), 10)).as("strictly before").isEmpty();
        assertThat(queries.findExpired(T0.plusSeconds(500), 2)).hasSize(2);
    }

    // ---- accounts and lists ----

    @Test
    void anAccountDeletionLocksTheListsHoldingTasksTheUserTrashedToo() {
        // Bob is a member of "work" only by trashing: remove his membership but keep the trashed task he deleted.
        long t = task(ana, home, "ana's task in home");
        trash(t, bob, T0);
        jdbc.update("delete from list_members where user_id = ?", bob);

        assertThat(members.listsTouchedBy(bob, "bob@example.com")).containsExactly(home);
    }

    @Test
    void deletingTheDeleterKeepsTheTrashedTaskWithANullDeleter() {
        long t = task(ana, work, "t");
        trash(t, bob, T0);

        jdbc.update("delete from users where id = ?", bob);

        assertThat(jdbc.queryForObject("select count(*) from tasks where id = ? and deleted_at is not null and deleted_by is null",
                Long.class, t)).isEqualTo(1);
        assertThat(queries.findPage(ana, null, null, 10)).extracting(Task::getDeletedBy).containsExactly((Long) null);
    }

    @Test
    void deletingAListDeletesItsTrashedTasksAndTheirTags() {
        long t = task(ana, work, "t");
        jdbc.update("insert into tags (owner_id, list_id, name) values (?, ?, 'X')", ana, work);
        long tag = jdbc.queryForObject("select max(id) from tags", Long.class);
        jdbc.update("insert into task_tags (task_id, tag_id) values (?, ?)", t, tag);
        trash(t, ana, T0);

        lists.deleteNow(work);

        assertThat(jdbc.queryForObject("select count(*) from tasks where id = ?", Long.class, t)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from task_tags where task_id = ?", Long.class, t)).isZero();
    }

    // ---- V14 ----

    @Test
    void v14AddsTheColumnsAndTheThreeIndexes() {
        List<String> columns = jdbc.queryForList("select column_name from information_schema.columns"
                + " where table_schema = database() and table_name = 'tasks' and column_name like 'deleted_%'"
                + " order by column_name", String.class);
        assertThat(columns).containsExactly("deleted_at", "deleted_by");
        List<String> indexes = jdbc.queryForList("select distinct index_name from information_schema.statistics"
                + " where table_schema = database() and table_name = 'tasks' and index_name like 'ix_tasks_%deleted%'"
                + " order by index_name", String.class);
        assertThat(indexes).containsExactly("ix_tasks_deleted", "ix_tasks_deleted_by", "ix_tasks_list_deleted");
        assertThat(jdbc.queryForObject("select delete_rule from information_schema.referential_constraints"
                + " where constraint_schema = database() and constraint_name = 'fk_tasks_deleted_by'", String.class))
                .isEqualTo("SET NULL");
    }
}
