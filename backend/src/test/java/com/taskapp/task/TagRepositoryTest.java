package com.taskapp.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
 * utf8mb4_0900_ai_ci collation, so they can only be tested on the database. Tags belong to a list (V10).
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TagService.class, ListService.class, MembershipRepository.class})
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
    /** Alice's "My tasks", Alice's "Work" list, Bob's "My tasks". */
    long aliceList;
    long aliceWork;
    long bobList;

    @BeforeEach
    void users() {
        alice = em.persistAndFlush(new User("alice@example.com", "$2a$10$" + "x".repeat(53), "Alice")).getId();
        bob = em.persistAndFlush(new User("bob@example.com", "$2a$10$" + "x".repeat(53), "Bob")).getId();
        aliceList = TestLists.personal(jdbc, alice);
        aliceWork = TestLists.other(jdbc, alice, "Work");
        bobList = TestLists.personal(jdbc, bob);
    }

    private long task(long owner, long list) {
        return em.persistAndFlush(new Task(owner, list, "t", null, null, T0)).getId();
    }

    private long task(long list) {
        return task(list == bobList ? bob : alice, list);
    }

    private List<String> replace(long list, long taskId, List<String> names) {
        return tagService.replaceTags(list == bobList ? bob : alice, list, taskId, names);
    }

    private List<String> tagNames(long list) {
        return tagRepository.findByListIdOrderByNameAsc(list).stream().map(Tag::getName).toList();
    }

    private int links(long taskId) {
        return jdbc.queryForObject("select count(*) from task_tags where task_id = ?", Integer.class, taskId);
    }

    @Test
    void replaceCreatesTagsAndReturnsThemSortedIgnoringCaseAndAccents() {
        long t = task(aliceList);

        List<String> names = replace(aliceList, t, List.of("zebra", " Äpfel ", "apple", "Banana"));

        // Binary order would be Banana, apple, zebra, Äpfel.
        assertThat(names).containsExactly("Äpfel", "apple", "Banana", "zebra");
        assertThat(tagService.namesOf(aliceList, t)).isEqualTo(names);
        assertThat(tagNames(aliceList)).isEqualTo(names);
    }

    @Test
    void listWithoutParameterIsMyTasksAndOtherwiseTheGivenListOnly() {
        replace(aliceList, task(aliceList), List.of("Home"));
        replace(aliceWork, task(aliceWork), List.of("Office"));

        assertThat(tagService.list(alice, null)).containsExactly(new TagResponse("Home"));
        assertThat(tagService.list(alice, "")).containsExactly(new TagResponse("Home"));
        assertThat(tagService.list(alice, Long.toString(aliceWork))).containsExactly(new TagResponse("Office"));
        for (String other : List.of(Long.toString(bobList), "999999", "0", "-1", " 1", "abc", "1.5", " ")) {
            assertThatThrownBy(() -> tagService.list(alice, other)).as(other)
                    .isInstanceOf(ListNotFoundException.class);
        }
    }

    @Test
    void newTagsGetTheListsOwnerAndList() {
        long t = task(aliceWork);
        replace(aliceWork, t, List.of("Work"));

        assertThat(jdbc.queryForMap("select owner_id, list_id from tags where name = 'Work'"))
                .containsEntry("owner_id", alice).containsEntry("list_id", aliceWork);
    }

    @Test
    void variantsInOneRequestBecomeOneTagWithTheFirstSpelling() {
        long t = task(aliceList);

        assertThat(replace(aliceList, t, List.of("wörk", "Work", "WORK", " work "))).containsExactly("wörk");
        assertThat(links(t)).isEqualTo(1);
        assertThat(tagRepository.countByListId(aliceList)).isEqualTo(1);
    }

    @Test
    void existingTagIsReusedWithItsStoredSpelling() {
        long t1 = task(aliceList);
        long t2 = task(aliceList);
        replace(aliceList, t1, List.of("Work"));
        long workId = tagRepository.findByListIdAndName(aliceList, "Work").orElseThrow().getId();

        assertThat(replace(aliceList, t2, List.of("wörk", "HOME"))).containsExactly("HOME", "Work");
        assertThat(replace(aliceList, t2, List.of("home", "WORK"))).containsExactly("HOME", "Work");
        assertThat(tagRepository.findByListIdAndName(aliceList, "WÖRK").orElseThrow().getId()).isEqualTo(workId);
        assertThat(tagNames(aliceList)).containsExactly("HOME", "Work");
    }

    @Test
    void namesTheDatabaseConsidersEqualAreDedupedByTagId() {
        long t = task(aliceList);
        // Java's approximation keeps these apart; utf8mb4_0900_ai_ci makes them equal (ß = ss, ligature ﬀ = ff).
        List<String> names = replace(aliceList, t, List.of("straße", "STRASSE", "ﬀ", "FF"));

        assertThat(names).hasSize(2).contains("straße", "ﬀ");
        assertThat(links(t)).isEqualTo(2);
        assertThat(tagRepository.countByListId(aliceList)).isEqualTo(2);
    }

    @Test
    void replaceRemovesOldLinksAndDeletesUnusedTagsOnly() {
        long t1 = task(aliceList);
        long t2 = task(aliceList);
        replace(aliceList, t1, List.of("Old", "Shared"));
        replace(aliceList, t2, List.of("Shared"));

        assertThat(replace(aliceList, t1, List.of("New"))).containsExactly("New");
        assertThat(tagNames(aliceList)).containsExactly("New", "Shared");

        assertThat(replace(aliceList, t1, List.of())).isEmpty();
        assertThat(links(t1)).isZero();
        assertThat(tagNames(aliceList)).containsExactly("Shared");
    }

    @Test
    void reAddingAnUnusedTagCreatesItWithTheNewSpelling() {
        long t = task(aliceList);
        replace(aliceList, t, List.of("Old"));
        replace(aliceList, t, List.of());

        assertThat(replace(aliceList, t, List.of("OLD"))).containsExactly("OLD");
    }

    /** AC8: the same name in two lists is two tags; one list's tag is never reused, listed or deleted by another. */
    @Test
    void anotherListsTagsAreNeverReusedListedOrDeleted() {
        long bobs = task(bobList);
        replace(bobList, bobs, List.of("Work"));
        long bobsWork = tagRepository.findByListIdAndName(bobList, "Work").orElseThrow().getId();
        long home = task(aliceWork);
        replace(aliceWork, home, List.of("Work"));
        long workListsWork = tagRepository.findByListIdAndName(aliceWork, "Work").orElseThrow().getId();
        long a = task(aliceList);

        assertThat(replace(aliceList, a, List.of("work"))).containsExactly("work");
        long mine = tagRepository.findByListIdAndName(aliceList, "Work").orElseThrow().getId();
        assertThat(mine).isNotIn(bobsWork, workListsWork);
        assertThat(tagService.findTagId(aliceList, "Work")).contains(mine);
        replace(aliceList, a, List.of());

        assertThat(tagNames(aliceList)).isEmpty();
        assertThat(tagNames(aliceWork)).containsExactly("Work");
        assertThat(tagNames(bobList)).containsExactly("Work");
        assertThat(tagService.namesOf(aliceList, bobs)).isEmpty();
        assertThat(tagService.namesByTask(aliceList, List.of(a, bobs, home))).isEmpty();
    }

    @Test
    void findTagIdNormalisesAndMatchesIgnoringCaseAndAccents() {
        long t = task(aliceList);
        replace(aliceList, t, List.of("Deep Work"));
        long id = tagRepository.findByListIdAndName(aliceList, "Deep Work").orElseThrow().getId();

        for (String raw : List.of("Deep Work", "deep work", " DÉEP   wörk ")) {
            assertThat(tagService.findTagId(aliceList, raw)).as(raw).contains(id);
        }
        for (String raw : List.of("Deep", "Deep  Work,", "Deep\tWork", "x".repeat(31), "Deep Works")) {
            assertThat(tagService.findTagId(aliceList, raw)).as(raw).isEmpty();
        }
        assertThat(tagService.findTagId(aliceWork, "Deep Work")).isEmpty();
        assertThat(tagService.findTagId(bobList, "Deep Work")).isEmpty();
    }

    @Test
    void namesByTaskGroupsTheGivenTasksOfTheListInOneQuery() {
        long t1 = task(aliceList);
        long t2 = task(aliceList);
        long t3 = task(aliceList);
        long t4 = task(aliceList);
        long bobs = task(bobList);
        replace(aliceList, t1, List.of("b", "A"));
        replace(aliceList, t2, List.of("c"));
        replace(aliceList, t4, List.of("not asked for"));
        replace(bobList, bobs, List.of("bob"));

        Map<Long, List<String>> byTask = tagService.namesByTask(aliceList, List.of(t1, t2, t3, bobs));

        assertThat(tagService.namesByTask(aliceList, List.of())).isEmpty();
        assertThat(byTask).containsOnlyKeys(t1, t2);
        assertThat(byTask.get(t1)).containsExactly("A", "b");
        assertThat(byTask.get(t2)).containsExactly("c");
    }

    @Test
    void deleteUnusedOnlyDeletesTheGivenTagsOfTheListThatNoTaskUses() {
        long a = task(aliceList);
        replace(aliceList, a, List.of("Keep"));
        long keep = tagRepository.findByListIdAndName(aliceList, "Keep").orElseThrow().getId();
        long orphan = tagId(alice, aliceList, "Orphan");
        long otherOrphan = tagId(alice, aliceList, "Not listed");
        long bobs = tagId(bob, bobList, "Bob orphan");

        tagService.deleteUnused(aliceList, List.of(keep, orphan, bobs));

        assertThat(tagNames(aliceList)).containsExactly("Keep", "Not listed");
        assertThat(tagRepository.existsById(otherOrphan)).isTrue();
        assertThat(tagNames(bobList)).containsExactly("Bob orphan");
    }

    @Test
    void replaceWritesOnlyTheLinksThatChange() {
        long t = task(aliceList);
        replace(aliceList, t, List.of("A", "B"));
        long a = tagRepository.findByListIdAndName(aliceList, "A").orElseThrow().getId();
        long b = tagRepository.findByListIdAndName(aliceList, "B").orElseThrow().getId();

        assertThat(replace(aliceList, t, List.of("b", "C"))).containsExactly("B", "C");

        assertThat(tagService.tagIdsOf(t)).contains(b).doesNotContain(a).hasSize(2);
        assertThat(tagRepository.existsById(a)).isFalse();
        assertThat(replace(aliceList, t, List.of("B", "C"))).containsExactly("B", "C");
    }

    private long tagId(long owner, long list, String name) {
        jdbc.update("insert into tags (owner_id, list_id, name) values (?, ?, ?)", owner, list, name);
        return jdbc.queryForObject("select id from tags where list_id = ? and name = ?", Long.class, list, name);
    }

    private void fillTo(long list, int count) {
        long owner = list == bobList ? bob : alice;
        long holder = task(owner, list);
        for (int i = 0; i < count; i++) {
            jdbc.update("insert into tags (owner_id, list_id, name) values (?, ?, ?)", owner, list, "filler " + i);
        }
        jdbc.update("insert into task_tags (task_id, tag_id) select ?, id from tags where list_id = ?", holder, list);
    }

    @Test
    void the200thTagIsAllowed() {
        fillTo(aliceList, 199);
        long t = task(aliceList);

        assertThat(replace(aliceList, t, List.of("number 200", "filler 0"))).hasSize(2);
        assertThat(tagRepository.countByListId(aliceList)).isEqualTo(200);
    }

    @Test
    void the201stTagIsRejected() {
        fillTo(aliceList, 200);
        long t = task(aliceList);

        assertThatThrownBy(() -> replace(aliceList, t, List.of("one too many")))
                .isInstanceOf(TagLimitExceededException.class)
                .hasMessage("A list can have at most 200 tags");
    }

    @Test
    void atTheLimitReusingOrSwappingATagIsAllowed() {
        fillTo(aliceList, 199);
        long t = task(aliceList);
        replace(aliceList, t, List.of("Only here"));
        assertThat(tagRepository.countByListId(aliceList)).isEqualTo(200);

        // Reusing existing tags never hits the cap.
        assertThat(replace(aliceList, t, List.of("only HERE", "filler 1", "filler 2"))).hasSize(3);
        // Swapping: "Only here" becomes unused and is deleted in the same change, so the count stays 200.
        assertThat(replace(aliceList, t, List.of("Brand new"))).containsExactly("Brand new");
        assertThat(tagRepository.countByListId(aliceList)).isEqualTo(200);
        assertThat(tagRepository.findByListIdAndName(aliceList, "Only here")).isEmpty();
    }

    /** AC8: the limit is per list, so neither another user's nor another of my lists' tags count. */
    @Test
    void otherListsTagsDoNotCountTowardsTheLimit() {
        fillTo(bobList, 200);
        fillTo(aliceWork, 200);
        long t = task(aliceList);

        assertThat(replace(aliceList, t, List.of("mine"))).containsExactly("mine");
    }

    // ---- moveTags (AC10, AC11): the task row already points at the target ----

    private long moved(long taskId, long target) {
        jdbc.update("update tasks set list_id = ? where id = ?", target, taskId);
        return taskId;
    }

    @Test
    void moveKeepsTheCurrentNamesReusingTheTargetsSpellingAndCreatingTheRest() {
        long t = task(aliceList);
        long stays = task(aliceList);
        replace(aliceList, t, List.of("Work", "Only here", "Shared"));
        replace(aliceList, stays, List.of("Shared"));
        replace(aliceWork, task(aliceWork), List.of("WÖRK"));
        long targetWork = tagRepository.findByListIdAndName(aliceWork, "Work").orElseThrow().getId();

        List<String> names = tagService.moveTags(alice, aliceList, aliceWork, moved(t, aliceWork), null);

        assertThat(names).containsExactly("Only here", "Shared", "WÖRK");
        assertThat(tagRepository.findByListIdAndName(aliceWork, "Work").orElseThrow().getId()).isEqualTo(targetWork);
        assertThat(tagNames(aliceWork)).containsExactly("Only here", "Shared", "WÖRK");
        // The source keeps "Shared" (still used) and loses the now-unused ones.
        assertThat(tagNames(aliceList)).containsExactly("Shared");
        assertThat(links(t)).isEqualTo(3);
        assertThat(jdbc.queryForObject("""
                select count(*) from task_tags tt join tags g on g.id = tt.tag_id
                where tt.task_id = ? and g.list_id <> ?""", Integer.class, t, aliceWork)).isZero();
    }

    @Test
    void moveWithTagsSentUsesThoseNames() {
        long t = task(aliceList);
        replace(aliceList, t, List.of("Old"));

        assertThat(tagService.moveTags(alice, aliceList, aliceWork, moved(t, aliceWork), List.of(" new ", "NEW", "x")))
                .containsExactly("new", "x");
        assertThat(tagNames(aliceList)).isEmpty();
        assertThat(tagService.moveTags(alice, aliceWork, aliceList, moved(t, aliceList), List.of())).isEmpty();
        assertThat(tagNames(aliceWork)).isEmpty();
        assertThat(links(t)).isZero();
    }

    @Test
    void moveOver200TagsInTheTargetIsRejected() {
        fillTo(aliceWork, 199);
        long t = task(aliceList);
        replace(aliceList, t, List.of("a", "b", "filler 3"));

        assertThatThrownBy(() -> tagService.moveTags(alice, aliceList, aliceWork, moved(t, aliceWork), null))
                .isInstanceOf(TagLimitExceededException.class);
    }

    @Test
    void moveAtExactly200TagsInTheTargetIsAllowed() {
        fillTo(aliceWork, 199);
        long t = task(aliceList);
        replace(aliceList, t, List.of("a", "filler 3"));

        assertThat(tagService.moveTags(alice, aliceList, aliceWork, moved(t, aliceWork), null))
                .containsExactly("a", "filler 3");
        assertThat(tagRepository.countByListId(aliceWork)).isEqualTo(200);
        assertThat(tagRepository.findByListIdAndName(aliceList, "a")).isEqualTo(Optional.empty());
    }
}
