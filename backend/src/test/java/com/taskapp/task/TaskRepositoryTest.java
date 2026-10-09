package com.taskapp.task;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

/** Runs against MySQL 8.4 with the real Flyway migrations (V2 schema, constraints and cascade). */
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
            task.setDone(true, createdAt);
        }
        return em.persistAndFlush(task);
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
}
