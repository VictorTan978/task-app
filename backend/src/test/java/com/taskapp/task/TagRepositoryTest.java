package com.taskapp.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.taskapp.auth.User;
import com.taskapp.task.dto.TagResponse;

/**
 * Tag queries and {@link TagService} against MySQL 8.4 with the real migrations: matching and sorting come from the
 * utf8mb4_0900_ai_ci collation, so they can only be tested on the database.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TagService.class)
class TagRepositoryTest {

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
            .withUrlParam("connectionTimeZone", "UTC")
            .withUrlParam("forceConnectionTimeZoneToSession", "true");

    private static final Instant T0 = Instant.parse("2026-10-01T00:00:00Z");

    @Autowired
    TagService tagService;

    @Autowired
    TagRepository tagRepository;

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

    private long task(long owner) {
        return em.persistAndFlush(new Task(owner, "t", null, null, T0)).getId();
    }

    private List<String> tagNames(long owner) {
        return tagService.list(owner).stream().map(TagResponse::name).toList();
    }

    private int links(long taskId) {
        return jdbc.queryForObject("select count(*) from task_tags where task_id = ?", Integer.class, taskId);
    }

    @Test
    void replaceCreatesTagsAndReturnsThemSortedIgnoringCaseAndAccents() {
        long t = task(alice);

        List<String> names = tagService.replaceTags(alice, t, List.of("zebra", " Äpfel ", "apple", "Banana"));

        // Binary order would be Banana, apple, zebra, Äpfel.
        assertThat(names).containsExactly("Äpfel", "apple", "Banana", "zebra");
        assertThat(tagService.namesOf(alice, t)).isEqualTo(names);
        assertThat(tagNames(alice)).isEqualTo(names);
    }

    @Test
    void variantsInOneRequestBecomeOneTagWithTheFirstSpelling() {
        long t = task(alice);

        assertThat(tagService.replaceTags(alice, t, List.of("wörk", "Work", "WORK", " work "))).containsExactly("wörk");
        assertThat(links(t)).isEqualTo(1);
        assertThat(tagRepository.countByOwnerId(alice)).isEqualTo(1);
    }

    @Test
    void existingTagIsReusedWithItsStoredSpelling() {
        long t1 = task(alice);
        long t2 = task(alice);
        tagService.replaceTags(alice, t1, List.of("Work"));
        long workId = tagRepository.findByOwnerIdAndName(alice, "Work").orElseThrow().getId();

        assertThat(tagService.replaceTags(alice, t2, List.of("wörk", "HOME"))).containsExactly("HOME", "Work");
        assertThat(tagService.replaceTags(alice, t2, List.of("home", "WORK"))).containsExactly("HOME", "Work");
        assertThat(tagRepository.findByOwnerIdAndName(alice, "WÖRK").orElseThrow().getId()).isEqualTo(workId);
        assertThat(tagNames(alice)).containsExactly("HOME", "Work");
    }

    @Test
    void namesTheDatabaseConsidersEqualAreDedupedByTagId() {
        long t = task(alice);
        // Java's approximation keeps these apart; utf8mb4_0900_ai_ci makes them equal (ß = ss, ligature ﬀ = ff).
        List<String> names = tagService.replaceTags(alice, t, List.of("straße", "STRASSE", "ﬀ", "FF"));

        assertThat(names).hasSize(2).contains("straße", "ﬀ");
        assertThat(links(t)).isEqualTo(2);
        assertThat(tagRepository.countByOwnerId(alice)).isEqualTo(2);
    }

    @Test
    void replaceRemovesOldLinksAndDeletesUnusedTagsOnly() {
        long t1 = task(alice);
        long t2 = task(alice);
        tagService.replaceTags(alice, t1, List.of("Old", "Shared"));
        tagService.replaceTags(alice, t2, List.of("Shared"));

        assertThat(tagService.replaceTags(alice, t1, List.of("New"))).containsExactly("New");
        assertThat(tagNames(alice)).containsExactly("New", "Shared");

        assertThat(tagService.replaceTags(alice, t1, List.of())).isEmpty();
        assertThat(links(t1)).isZero();
        assertThat(tagNames(alice)).containsExactly("Shared");
    }

    @Test
    void reAddingAnUnusedTagCreatesItWithTheNewSpelling() {
        long t = task(alice);
        tagService.replaceTags(alice, t, List.of("Old"));
        tagService.replaceTags(alice, t, List.of());

        assertThat(tagService.replaceTags(alice, t, List.of("OLD"))).containsExactly("OLD");
    }

    @Test
    void anotherUsersTagsAreNeverReusedListedOrDeleted() {
        long bobs = task(bob);
        tagService.replaceTags(bob, bobs, List.of("Work"));
        long bobsWork = tagRepository.findByOwnerIdAndName(bob, "Work").orElseThrow().getId();
        long a = task(alice);

        assertThat(tagService.replaceTags(alice, a, List.of("work"))).containsExactly("work");
        assertThat(tagRepository.findByOwnerIdAndName(alice, "Work").orElseThrow().getId()).isNotEqualTo(bobsWork);
        assertThat(tagService.findTagId(alice, "Work")).isNotEqualTo(java.util.Optional.of(bobsWork));
        tagService.replaceTags(alice, a, List.of());

        assertThat(tagNames(alice)).isEmpty();
        assertThat(tagNames(bob)).containsExactly("Work");
        assertThat(tagService.namesOf(alice, bobs)).isEmpty();
        assertThat(tagService.namesByTask(alice)).isEmpty();
    }

    @Test
    void findTagIdNormalisesAndMatchesIgnoringCaseAndAccents() {
        long t = task(alice);
        tagService.replaceTags(alice, t, List.of("Deep Work"));
        long id = tagRepository.findByOwnerIdAndName(alice, "Deep Work").orElseThrow().getId();

        for (String raw : List.of("Deep Work", "deep work", " DÉEP   wörk ")) {
            assertThat(tagService.findTagId(alice, raw)).as(raw).contains(id);
        }
        for (String raw : List.of("Deep", "Deep  Work,", "Deep\tWork", "x".repeat(31), "Deep Works")) {
            assertThat(tagService.findTagId(alice, raw)).as(raw).isEmpty();
        }
        assertThat(tagService.findTagId(bob, "Deep Work")).isEmpty();
    }

    @Test
    void namesByTaskGroupsAllOfTheOwnersTasksInOneQuery() {
        long t1 = task(alice);
        long t2 = task(alice);
        long t3 = task(alice);
        tagService.replaceTags(alice, t1, List.of("b", "A"));
        tagService.replaceTags(alice, t2, List.of("c"));
        tagService.replaceTags(bob, task(bob), List.of("bob"));

        Map<Long, List<String>> byTask = tagService.namesByTask(alice);

        assertThat(byTask).containsOnlyKeys(t1, t2);
        assertThat(byTask.get(t1)).containsExactly("A", "b");
        assertThat(byTask.get(t2)).containsExactly("c");
        assertThat(byTask).doesNotContainKey(t3);
    }

    @Test
    void deleteUnusedOnlyDeletesTheGivenTagsThatNoTaskUses() {
        long a = task(alice);
        tagService.replaceTags(alice, a, List.of("Keep"));
        long keep = tagRepository.findByOwnerIdAndName(alice, "Keep").orElseThrow().getId();
        long orphan = tagId(alice, "Orphan");
        long otherOrphan = tagId(alice, "Not listed");
        long bobs = tagId(bob, "Bob orphan");

        tagService.deleteUnused(alice, List.of(keep, orphan, bobs));

        assertThat(tagNames(alice)).containsExactly("Keep", "Not listed");
        assertThat(tagRepository.existsById(otherOrphan)).isTrue();
        assertThat(tagNames(bob)).containsExactly("Bob orphan");
    }

    @Test
    void replaceWritesOnlyTheLinksThatChange() {
        long t = task(alice);
        tagService.replaceTags(alice, t, List.of("A", "B"));
        long a = tagRepository.findByOwnerIdAndName(alice, "A").orElseThrow().getId();
        long b = tagRepository.findByOwnerIdAndName(alice, "B").orElseThrow().getId();

        assertThat(tagService.replaceTags(alice, t, List.of("b", "C"))).containsExactly("B", "C");

        assertThat(tagService.tagIdsOf(t)).contains(b).doesNotContain(a).hasSize(2);
        assertThat(tagRepository.existsById(a)).isFalse();
        assertThat(tagService.replaceTags(alice, t, List.of("B", "C"))).containsExactly("B", "C");
    }

    private long tagId(long owner, String name) {
        jdbc.update("insert into tags (owner_id, name) values (?, ?)", owner, name);
        return jdbc.queryForObject("select id from tags where owner_id = ? and name = ?", Long.class, owner, name);
    }

    private void fillTo(long owner, int count) {
        long holder = task(owner);
        for (int i = 0; i < count; i++) {
            jdbc.update("insert into tags (owner_id, name) values (?, ?)", owner, "filler " + i);
        }
        jdbc.update("insert into task_tags (task_id, tag_id) select ?, id from tags where owner_id = ?", holder, owner);
    }

    @Test
    void the200thTagIsAllowed() {
        fillTo(alice, 199);
        long t = task(alice);

        assertThat(tagService.replaceTags(alice, t, List.of("number 200", "filler 0"))).hasSize(2);
        assertThat(tagRepository.countByOwnerId(alice)).isEqualTo(200);
    }

    @Test
    void the201stTagIsRejected() {
        fillTo(alice, 200);
        long t = task(alice);

        assertThatThrownBy(() -> tagService.replaceTags(alice, t, List.of("one too many")))
                .isInstanceOf(TagLimitExceededException.class)
                .hasMessage("You can have at most 200 tags");
    }

    @Test
    void atTheLimitReusingOrSwappingATagIsAllowed() {
        fillTo(alice, 199);
        long t = task(alice);
        tagService.replaceTags(alice, t, List.of("Only here"));
        assertThat(tagRepository.countByOwnerId(alice)).isEqualTo(200);

        // Reusing existing tags never hits the cap.
        assertThat(tagService.replaceTags(alice, t, List.of("only HERE", "filler 1", "filler 2"))).hasSize(3);
        // Swapping: "Only here" becomes unused and is deleted in the same change, so the count stays 200.
        assertThat(tagService.replaceTags(alice, t, List.of("Brand new"))).containsExactly("Brand new");
        assertThat(tagRepository.countByOwnerId(alice)).isEqualTo(200);
        assertThat(tagRepository.findByOwnerIdAndName(alice, "Only here")).isEmpty();
    }

    @Test
    void otherUsersTagsDoNotCountTowardsTheLimit() {
        fillTo(bob, 200);
        long t = task(alice);

        assertThat(tagService.replaceTags(alice, t, List.of("mine"))).containsExactly("mine");
    }
}
