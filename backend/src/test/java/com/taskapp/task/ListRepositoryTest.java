package com.taskapp.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.taskapp.auth.User;
import com.taskapp.common.error.GlobalExceptionHandlerAccess;
import com.taskapp.task.dto.TaskListResponse;

/** V10 ({@code lists}) and {@link ListRepository} / {@link ListService} queries against MySQL 8.4. */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ListService.class, MembershipRepository.class})
class ListRepositoryTest {

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
            .withUrlParam("connectionTimeZone", "UTC")
            .withUrlParam("forceConnectionTimeZoneToSession", "true");

    @Autowired
    ListRepository lists;

    @Autowired
    ListService listService;

    @Autowired
    TestEntityManager em;

    @Autowired
    JdbcTemplate jdbc;

    long alice;
    long bob;

    @BeforeEach
    void users() {
        alice = em.persistAndFlush(new User("alice@example.com", "$2a$10$" + "x".repeat(53), "Alice")).getId();
        bob = em.persistAndFlush(new User("bob@example.com", "$2a$10$" + "x".repeat(53), "Bob")).getId();
    }

    @Test
    void atMostOnePersonalListPerUser() {
        listService.createPersonal(alice);
        listService.createPersonal(bob);

        // Written directly: a failed flush would leave the test's Hibernate session unusable.
        assertThatThrownBy(() -> TestLists.personal(jdbc, alice))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uk_lists_one_personal_per_owner");
        // Non-personal lists never collide (the index value is NULL for them), duplicate names included.
        TestLists.other(jdbc, alice, "Home");
        TestLists.other(jdbc, alice, "Home");
        assertThat(lists.countByOwnerId(alice)).isEqualTo(3);
    }

    @Test
    void nameIs1To100Characters() {
        assertThatThrownBy(() -> TestLists.other(jdbc, alice, "")).hasMessageContaining("ck_lists_name_length");
        assertThatThrownBy(() -> TestLists.other(jdbc, alice, "x".repeat(101))).isInstanceOf(DataAccessException.class);
        TestLists.other(jdbc, alice, "😀".repeat(100));
    }

    @Test
    void listIsMyTasksFirstThenByNameIgnoringCaseAndAccentsThenId() {
        long work2 = TestLists.other(jdbc, alice, "work");
        long work1 = TestLists.other(jdbc, alice, "Wörk");
        long apples = TestLists.other(jdbc, alice, "Äpfel");
        long zoo = TestLists.other(jdbc, alice, "zoo");
        long personal = TestLists.personal(jdbc, alice);
        jdbc.update("update lists set name = 'Zzz' where id = ?", personal);
        TestLists.personal(jdbc, bob);

        assertThat(listService.list(alice)).extracting(TaskListResponse::id)
                .containsExactly(personal, apples, Math.min(work1, work2), Math.max(work1, work2), zoo);
    }

    @Test
    void renameIsScopedByOwner() {
        long home = TestLists.other(jdbc, alice, "Home");

        assertThat(lists.rename(bob, home, "Stolen")).isZero();
        assertThat(lists.rename(alice, home + 1000, "x")).isZero();
        assertThat(lists.rename(alice, home, "Groceries")).isEqualTo(1);
        assertThat(lists.rename(alice, home, "Groceries")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select name from lists where id = ?", String.class, home))
                .isEqualTo("Groceries");
    }

    @Test
    void ownedLookupsAreScopedByOwner() {
        long home = TestLists.other(jdbc, alice, "Home");

        assertThat(lists.findByIdAndOwnerId(home, alice)).isPresent();
        assertThat(lists.findByIdAndOwnerId(home, bob)).isEmpty();
        assertThatThrownBy(() -> listService.requireMember(bob, home)).isInstanceOf(ListNotFoundException.class);
        assertThatThrownBy(() -> listService.resolve(bob, Long.toString(home)))
                .isInstanceOf(ListNotFoundException.class);
    }

    @Test
    void countTasksCountsEveryStatus() {
        long home = TestLists.other(jdbc, alice, "Home");
        for (String status : List.of("TODO", "IN_PROGRESS", "DONE")) {
            jdbc.update("insert into tasks (owner_id, list_id, title, status, done) values (?, ?, 't', ?, ?)", alice,
                    home, status, status.equals("DONE"));
        }

        assertThat(lists.countTasks(home)).isEqualTo(3);
    }

    @Test
    void lockQueriesFindExistingRowsOnly() {
        long home = TestLists.other(jdbc, alice, "Home");

        assertThat(lists.lockList(home)).contains(home);
        assertThat(lists.shareList(home)).contains(home);
        assertThat(lists.lockList(home + 1000)).isEmpty();
        assertThat(lists.shareUser(alice)).contains(alice);
        assertThat(lists.lockUser(alice)).contains(alice);
        assertThat(lists.lockUser(alice + 1000)).isEmpty();
    }

    /** AC4: deleting a list deletes its tasks, their links and its tags, nothing else. */
    @Test
    void deletingAListCascadesToItsTasksTagsAndLinksOnly() {
        long personal = TestLists.personal(jdbc, alice);
        long home = TestLists.other(jdbc, alice, "Home");
        long kept = task(alice, personal);
        long gone = task(alice, home);
        link(gone, tag(alice, home, "Work"));
        link(kept, tag(alice, personal, "Work"));

        lists.deleteNow(home);

        assertThat(count("select count(*) from tasks where list_id = " + home)).isZero();
        assertThat(count("select count(*) from tags where list_id = " + home)).isZero();
        assertThat(count("select count(*) from task_tags where task_id = " + gone)).isZero();
        assertThat(count("select count(*) from tasks where id = " + kept)).isEqualTo(1);
        assertThat(count("select count(*) from task_tags where task_id = " + kept)).isEqualTo(1);
    }

    /** AC12: deleting the account deletes all its lists, tasks and tags; another user's stay. */
    @Test
    void deletingTheUserCascadesToListsTasksAndTags() {
        long a1 = TestLists.personal(jdbc, alice);
        long a2 = TestLists.other(jdbc, alice, "Home");
        long b1 = TestLists.personal(jdbc, bob);
        link(task(alice, a1), tag(alice, a1, "x"));
        link(task(alice, a2), tag(alice, a2, "x"));
        link(task(bob, b1), tag(bob, b1, "x"));

        jdbc.update("delete from users where id = ?", alice);

        assertThat(count("select count(*) from lists where owner_id = " + alice)).isZero();
        assertThat(count("select count(*) from tasks where list_id in (" + a1 + "," + a2 + ")")).isZero();
        assertThat(count("select count(*) from tags where list_id in (" + a1 + "," + a2 + ")")).isZero();
        assertThat(count("select count(*) from lists")).isEqualTo(1);
        assertThat(count("select count(*) from tasks")).isEqualTo(1);
        assertThat(count("select count(*) from tags")).isEqualTo(1);
        assertThat(count("select count(*) from task_tags")).isEqualTo(1);
    }

    @Test
    void aTaskOrTagNeedsAnExistingList() {
        assertThatThrownBy(() -> task(alice, 999_999))
                .hasMessageContaining("fk_tasks_list");
        assertThatThrownBy(() -> tag(alice, 999_999, "x"))
                .hasMessageContaining("fk_tags_list");
        assertThat(GlobalExceptionHandlerAccess.isListForeignKey(catchFk())).isTrue();
    }

    private Throwable catchFk() {
        try {
            task(alice, 999_999);
            return null;
        } catch (DataAccessException e) {
            return e;
        }
    }

    private long task(long owner, long list) {
        jdbc.update("insert into tasks (owner_id, list_id, title) values (?, ?, 't')", owner, list);
        return jdbc.queryForObject("select max(id) from tasks", Long.class);
    }

    private long tag(long owner, long list, String name) {
        jdbc.update("insert into tags (owner_id, list_id, name) values (?, ?, ?)", owner, list, name);
        return jdbc.queryForObject("select max(id) from tags", Long.class);
    }

    private void link(long task, long tag) {
        jdbc.update("insert into task_tags (task_id, tag_id) values (?, ?)", task, tag);
    }

    private int count(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }
}
