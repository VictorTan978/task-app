package com.taskapp.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.taskapp.auth.User;

/** Runs against MySQL 8.4 with the real Flyway migrations (V2/V9 schema, constraints and cascade). */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class TaskRepositoryTest {

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
            .withUrlParam("connectionTimeZone", "UTC")
            .withUrlParam("forceConnectionTimeZoneToSession", "true");

    private static final Instant T0 = Instant.parse("2026-10-01T00:00:00Z");

    @Autowired
    TaskRepository repository;

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

    private Task task(long owner, String title, boolean done, Instant createdAt) {
        Task task = new Task(owner, title, null, null, createdAt);
        if (done) {
            task.changeStatus(TaskStatus.DONE, createdAt);
        }
        return em.persistAndFlush(task);
    }

    private Task task(long owner, String title, TaskStatus status, Instant createdAt) {
        Task task = new Task(owner, title, null, null, createdAt);
        task.changeStatus(status, createdAt);
        return em.persistAndFlush(task);
    }

    private long tag(long owner, String name, Task... tasks) {
        long id = em.persistAndFlush(new Tag(owner, name)).getId();
        for (Task task : tasks) {
            jdbc.update("insert into task_tags (task_id, tag_id) values (?, ?)", task.getId(), id);
        }
        return id;
    }

    private static List<String> titles(List<Task> tasks) {
        return tasks.stream().map(Task::getTitle).toList();
    }

    @Test
    void listSortsNotDoneFirstThenNewestFirstWithIdTieBreak() {
        task(alice, "old active", false, T0);
        task(alice, "old done", true, T0.plusSeconds(10));
        task(alice, "tie 1", false, T0.plusSeconds(20));
        task(alice, "tie 2", false, T0.plusSeconds(20));
        task(alice, "new done", true, T0.plusSeconds(30));
        task(alice, "new active", false, T0.plusSeconds(40));
        task(bob, "bob's", false, T0.plusSeconds(50));
        em.clear();

        assertThat(titles(repository.findByOwnerIdOrderByDoneAscCreatedAtDescIdDesc(alice)))
                .containsExactly("new active", "tie 2", "tie 1", "old active", "new done", "old done");
        assertThat(titles(repository.findByOwnerIdAndDoneOrderByCreatedAtDescIdDesc(alice, false)))
                .containsExactly("new active", "tie 2", "tie 1", "old active");
        assertThat(titles(repository.findByOwnerIdAndDoneOrderByCreatedAtDescIdDesc(alice, true)))
                .containsExactly("new done", "old done");
        assertThat(titles(repository.findByOwnerIdOrderByDoneAscCreatedAtDescIdDesc(bob))).containsExactly("bob's");
    }

    @Test
    void findByIdIsScopedToOwner() {
        long id = task(bob, "bob's", false, T0).getId();

        assertThat(repository.findByIdAndOwnerId(id, bob)).isPresent();
        assertThat(repository.findByIdAndOwnerId(id, alice)).isEmpty();
        assertThat(repository.findByIdAndOwnerId(id + 1000, bob)).isEmpty();
    }

    @Test
    void roundTripsAllFieldsIncludingMicroseconds() {
        Instant created = Instant.parse("2026-10-08T09:15:02.123456Z");
        Task saved = em.persistAndFlush(new Task(alice, "Buy milk", "2%", LocalDate.parse("2024-02-29"), created));
        em.clear();

        Task loaded = repository.findByIdAndOwnerId(saved.getId(), alice).orElseThrow();
        assertThat(loaded.getTitle()).isEqualTo("Buy milk");
        assertThat(loaded.getDescription()).isEqualTo("2%");
        assertThat(loaded.isDone()).isFalse();
        assertThat(loaded.getDueDate()).isEqualTo(LocalDate.parse("2024-02-29"));
        assertThat(loaded.getCreatedAt()).isEqualTo(created);
        assertThat(loaded.getUpdatedAt()).isEqualTo(created);
    }

    @Test
    void deletingUserCascadesToTasks() {
        task(alice, "a", false, T0);
        task(bob, "b", false, T0);

        jdbc.update("delete from users where id = ?", alice);

        assertThat(jdbc.queryForObject("select count(*) from tasks where owner_id = ?", Integer.class, alice)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from tasks where owner_id = ?", Integer.class, bob)).isEqualTo(1);
    }

    // ---- V9: priority, status, tags ----

    @Test
    void roundTripsPriorityAndStatusWithTheDoneMirror() {
        Task task = new Task(alice, "x", null, null, T0);
        task.changePriority(Priority.HIGH, T0);
        task.changeStatus(TaskStatus.IN_PROGRESS, T0);
        long id = em.persistAndFlush(task).getId();
        em.clear();

        Task loaded = repository.findByIdAndOwnerId(id, alice).orElseThrow();
        assertThat(loaded.getPriority()).isEqualTo(Priority.HIGH);
        assertThat(loaded.getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
        assertThat(loaded.isDone()).isFalse();
        assertThat(jdbc.queryForMap("select priority, status, done from tasks where id = ?", id))
                .containsEntry("priority", "HIGH").containsEntry("status", "IN_PROGRESS").containsEntry("done", false);

        loaded.changeStatus(TaskStatus.DONE, T0);
        em.flush();
        assertThat(jdbc.queryForMap("select status, done from tasks where id = ?", id))
                .containsEntry("status", "DONE").containsEntry("done", true);
    }

    @Test
    void newTasksDefaultToNoneAndTodo() {
        long id = task(alice, "x", false, T0).getId();
        assertThat(jdbc.queryForMap("select priority, status, done from tasks where id = ?", id))
                .containsEntry("priority", "NONE").containsEntry("status", "TODO").containsEntry("done", false);

        // Columns default too (rows not written by the app).
        jdbc.update("insert into tasks (owner_id, title) values (?, 'raw')", alice);
        assertThat(jdbc.queryForMap("select priority, status from tasks where title = 'raw'"))
                .containsEntry("priority", "NONE").containsEntry("status", "TODO");
    }

    @Test
    void inProgressQueryKeepsTheListOrder() {
        task(alice, "old", TaskStatus.IN_PROGRESS, T0);
        task(alice, "todo", TaskStatus.TODO, T0.plusSeconds(10));
        task(alice, "tie 1", TaskStatus.IN_PROGRESS, T0.plusSeconds(20));
        task(alice, "tie 2", TaskStatus.IN_PROGRESS, T0.plusSeconds(20));
        task(alice, "done", TaskStatus.DONE, T0.plusSeconds(30));
        task(bob, "bob's", TaskStatus.IN_PROGRESS, T0.plusSeconds(40));
        em.clear();

        assertThat(titles(repository.findByOwnerIdAndStatusOrderByCreatedAtDescIdDesc(alice, TaskStatus.IN_PROGRESS)))
                .containsExactly("tie 2", "tie 1", "old");
        // Active (done = false) mixes TODO and IN_PROGRESS by createdAt only.
        assertThat(titles(repository.findByOwnerIdAndDoneOrderByCreatedAtDescIdDesc(alice, false)))
                .containsExactly("tie 2", "tie 1", "todo", "old");
    }

    @Test
    void tagQueryFiltersByTagStatusesAndOwnerInListOrder() {
        Task oldDone = task(alice, "old done", TaskStatus.DONE, T0);
        Task todo = task(alice, "todo", TaskStatus.TODO, T0.plusSeconds(10));
        Task progress = task(alice, "progress", TaskStatus.IN_PROGRESS, T0.plusSeconds(20));
        Task newDone = task(alice, "new done", TaskStatus.DONE, T0.plusSeconds(30));
        Task untagged = task(alice, "untagged", TaskStatus.TODO, T0.plusSeconds(40));
        Task bobs = task(bob, "bob's", TaskStatus.TODO, T0.plusSeconds(50));
        long work = tag(alice, "Work", oldDone, todo, progress, newDone);
        tag(alice, "Home", untagged, todo);
        long bobsWork = tag(bob, "Work", bobs);
        em.clear();

        List<String> all = List.of("TODO", "IN_PROGRESS", "DONE");
        assertThat(titles(repository.findByOwnerIdAndTagId(alice, work, all)))
                .containsExactly("progress", "todo", "new done", "old done");
        assertThat(titles(repository.findByOwnerIdAndTagId(alice, work, List.of("TODO", "IN_PROGRESS"))))
                .containsExactly("progress", "todo");
        assertThat(titles(repository.findByOwnerIdAndTagId(alice, work, List.of("IN_PROGRESS"))))
                .containsExactly("progress");
        assertThat(titles(repository.findByOwnerIdAndTagId(alice, work, List.of("DONE"))))
                .containsExactly("new done", "old done");
        // Scoped by owner even with someone else's tag id.
        assertThat(repository.findByOwnerIdAndTagId(alice, bobsWork, all)).isEmpty();
        assertThat(titles(repository.findByOwnerIdAndTagId(bob, bobsWork, all))).containsExactly("bob's");
    }

    @ParameterizedTest
    @ValueSource(strings = {"HIGH ", "high", "High", " HIGH", "URGENT", "", "HIGH\t"})
    void priorityCheckRejectsAnythingButTheExactNames(String value) {
        long id = task(alice, "x", false, T0).getId();
        assertThatThrownBy(() -> jdbc.update("update tasks set priority = ? where id = ?", value, id))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_tasks_priority");
    }

    @ParameterizedTest
    @ValueSource(strings = {"TODO ", "todo", "In_Progress", "IN PROGRESS", "", "DONE\n"})
    void statusCheckRejectsAnythingButTheExactNames(String value) {
        long id = task(alice, "x", false, T0).getId();
        assertThatThrownBy(() -> jdbc.update("update tasks set status = ? where id = ?", value, id))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_tasks_status");
    }

    @Test
    void statusAndDoneCanNeverDisagree() {
        long todo = task(alice, "todo", false, T0).getId();
        long done = task(alice, "done", true, T0).getId();

        for (String sql : List.of("update tasks set status = 'DONE' where id = " + todo,
                "update tasks set done = true where id = " + todo,
                "update tasks set status = 'IN_PROGRESS', done = true where id = " + todo,
                "update tasks set done = false where id = " + done,
                "update tasks set status = 'TODO' where id = " + done)) {
            assertThatThrownBy(() -> jdbc.update(sql)).as(sql)
                    .isInstanceOf(DataAccessException.class)
                    .hasMessageContaining("ck_tasks_status_matches_done");
        }
        jdbc.update("update tasks set status = 'IN_PROGRESS' where id = ?", todo);
        jdbc.update("update tasks set status = 'TODO', done = false where id = ?", done);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Work ", " Work", "a  b", "a,b", ",", "a\tb", "a\nb", "\u007f", "\u0085", "x\u0000"})
    void tagNameChecksRejectSpacingCommasAndControlCharacters(String name) {
        assertThatThrownBy(() -> jdbc.update("insert into tags (owner_id, name) values (?, ?)", alice, name))
                .as("[%s]", name)
                .isInstanceOf(DataAccessException.class)
                .hasMessageMatching("(?s).*ck_tags_name_(spaces|chars).*");
    }

    @Test
    void tagNameMustBe1To30Characters() {
        assertThatThrownBy(() -> jdbc.update("insert into tags (owner_id, name) values (?, '')", alice))
                .hasMessageContaining("ck_tags_name_length");
        assertThatThrownBy(() -> jdbc.update("insert into tags (owner_id, name) values (?, ?)", alice, "x".repeat(31)))
                .isInstanceOf(DataAccessException.class);
        jdbc.update("insert into tags (owner_id, name) values (?, ?)", alice, "x".repeat(30));
        jdbc.update("insert into tags (owner_id, name) values (?, ?)", alice, "a b é 😀");
    }

    @Test
    void tagNameIsUniquePerOwnerIgnoringCaseAndAccents() {
        jdbc.update("insert into tags (owner_id, name) values (?, 'Work')", alice);
        for (String variant : List.of("Work", "work", "WORK", "wörk", "WÖRK")) {
            assertThatThrownBy(() -> jdbc.update("insert into tags (owner_id, name) values (?, ?)", alice, variant))
                    .as(variant)
                    .isInstanceOf(DataAccessException.class)
                    .hasMessageContaining("uk_tags_owner_name");
        }
        jdbc.update("insert into tags (owner_id, name) values (?, 'work')", bob);
        jdbc.update("insert into tags (owner_id, name) values (?, 'Works')", alice);
    }

    @Test
    void deletingTasksTagsAndUsersCascades() {
        Task a1 = task(alice, "a1", false, T0);
        Task a2 = task(alice, "a2", false, T0);
        Task b1 = task(bob, "b1", false, T0);
        long work = tag(alice, "Work", a1, a2);
        long home = tag(alice, "Home", a2);
        tag(bob, "Work", b1);

        jdbc.update("delete from tasks where id = ?", a1.getId());
        assertThat(jdbc.queryForObject("select count(*) from task_tags where task_id = ?", Integer.class, a1.getId()))
                .isZero();
        assertThat(jdbc.queryForObject("select count(*) from tags where id = ?", Integer.class, work)).isEqualTo(1);

        jdbc.update("delete from tags where id = ?", home);
        assertThat(jdbc.queryForObject("select count(*) from task_tags where tag_id = ?", Integer.class, home)).isZero();

        jdbc.update("delete from users where id = ?", alice);
        assertThat(jdbc.queryForObject("select count(*) from tags where owner_id = ?", Integer.class, alice)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from task_tags", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from tags where owner_id = ?", Integer.class, bob)).isEqualTo(1);
    }
}
