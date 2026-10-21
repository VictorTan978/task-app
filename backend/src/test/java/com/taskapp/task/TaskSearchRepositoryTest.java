package com.taskapp.task;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.taskapp.auth.User;

/** The list query of {@code GET /api/tasks} (contract 0.11.0) against MySQL 8.4 with the real migrations. */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class TaskSearchRepositoryTest {

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
            .withUrlParam("connectionTimeZone", "UTC")
            .withUrlParam("forceConnectionTimeZoneToSession", "true");

    private static final Instant T0 = Instant.parse("2026-10-01T00:00:00Z");
    private static final LocalDate D0 = LocalDate.parse("2026-11-01");

    @Autowired
    TaskRepository repository;

    @Autowired
    TestEntityManager em;

    @Autowired
    JdbcTemplate jdbc;

    /** List ids (V10: the query is scoped by list): Alice's and Bob's "My tasks". */
    long alice;
    long bob;
    long aliceUser;
    long bobUser;

    @BeforeEach
    void users() {
        aliceUser = em.persistAndFlush(new User("alice@example.com", "$2a$10$" + "x".repeat(53), "Alice")).getId();
        bobUser = em.persistAndFlush(new User("bob@example.com", "$2a$10$" + "x".repeat(53), "Bob")).getId();
        alice = TestLists.personal(jdbc, aliceUser);
        bob = TestLists.personal(jdbc, bobUser);
    }

    private long ownerOf(long list) {
        return jdbc.queryForObject("select owner_id from lists where id = ?", Long.class, list);
    }

    private Task task(long list, String title, String description, LocalDate due, Priority priority,
            TaskStatus status, Instant createdAt) {
        Task task = new Task(ownerOf(list), list, title, description, due, createdAt);
        task.changePriority(priority, createdAt);
        task.changeStatus(status, createdAt);
        return em.persistAndFlush(task);
    }

    private Task task(String title, Instant createdAt) {
        return task(alice, title, null, null, Priority.NONE, TaskStatus.TODO, createdAt);
    }

    private static Instant at(int seconds) {
        return T0.plusSeconds(seconds);
    }

    private List<String> titles(TaskFilter filter, TaskSort sort) {
        return titles(new TaskSearchCriteria(alice, filter, sort, null, null));
    }

    private List<String> titles(TaskSearchCriteria criteria) {
        return repository.findPage(criteria, null, 1000).stream().map(Task::getTitle).toList();
    }

    private List<String> search(String text) {
        return titles(new TaskSearchCriteria(alice, TaskFilter.ALL, TaskSort.NEWEST, null, text));
    }

    // ---- order ----

    @Test
    void newestAndOldestAreExactReversesWithIdTieBreak() {
        task("a", at(0));
        task("b tie 1", at(10));
        task("b tie 2", at(10));
        task("c", at(20));
        task(bob, "bob's", null, null, Priority.NONE, TaskStatus.TODO, at(5));

        assertThat(titles(TaskFilter.ALL, TaskSort.NEWEST)).containsExactly("c", "b tie 2", "b tie 1", "a");
        assertThat(titles(TaskFilter.ALL, TaskSort.OLDEST)).containsExactly("a", "b tie 1", "b tie 2", "c");
    }

    @Test
    void dueIsSoonestFirstWithoutDateLastTiesNewestFirst() {
        task(alice, "no date old", null, null, Priority.NONE, TaskStatus.TODO, at(0));
        task(alice, "later", null, D0.plusDays(5), Priority.NONE, TaskStatus.TODO, at(1));
        task(alice, "soon old", null, D0, Priority.NONE, TaskStatus.TODO, at(2));
        task(alice, "soon new", null, D0, Priority.NONE, TaskStatus.TODO, at(3));
        task(alice, "no date new", null, null, Priority.NONE, TaskStatus.TODO, at(4));
        task(alice, "past", null, D0.minusYears(3), Priority.NONE, TaskStatus.TODO, at(5));

        assertThat(titles(TaskFilter.ALL, TaskSort.DUE))
                .containsExactly("past", "soon new", "soon old", "later", "no date new", "no date old");
    }

    @Test
    void priorityIsHighMediumLowNoneTiesNewestFirst() {
        task(alice, "none", null, null, Priority.NONE, TaskStatus.TODO, at(0));
        task(alice, "low", null, null, Priority.LOW, TaskStatus.TODO, at(1));
        task(alice, "high old", null, null, Priority.HIGH, TaskStatus.TODO, at(2));
        task(alice, "medium", null, null, Priority.MEDIUM, TaskStatus.TODO, at(3));
        task(alice, "high new", null, null, Priority.HIGH, TaskStatus.TODO, at(4));

        assertThat(titles(TaskFilter.ALL, TaskSort.PRIORITY))
                .containsExactly("high new", "high old", "medium", "low", "none");
    }

    @Test
    void titleIgnoresCaseAndAccentsAndTiesAreNewestFirst() {
        task("banana", at(0));
        task("Café", at(1));
        task("cafe", at(2));
        task("CAFÉ", at(3));
        task("apple pie", at(4));
        task("Apple", at(5));
        task("éclair", at(6));
        task("Zebra", at(7));
        task("cafeteria", at(8));

        assertThat(titles(TaskFilter.ALL, TaskSort.TITLE)).containsExactly(
                "Apple", "apple pie", "banana", "CAFÉ", "cafe", "Café", "cafeteria", "éclair", "Zebra");
    }

    @ParameterizedTest
    @EnumSource(TaskSort.class)
    void allPutsTheNotDoneGroupFirstForEverySort(TaskSort sort) {
        task(alice, "done A", null, D0, Priority.HIGH, TaskStatus.DONE, at(30));
        task(alice, "todo Z", null, null, Priority.NONE, TaskStatus.TODO, at(0));
        task(alice, "progress M", null, D0.plusDays(1), Priority.LOW, TaskStatus.IN_PROGRESS, at(10));
        task(alice, "done B", null, null, Priority.NONE, TaskStatus.DONE, at(20));

        List<String> all = titles(TaskFilter.ALL, sort);
        assertThat(all.subList(0, 2)).containsExactlyInAnyOrder("todo Z", "progress M");
        assertThat(all.subList(2, 4)).containsExactlyInAnyOrder("done A", "done B");
        // Each group in the sort's own order, the same as the single-group filters.
        assertThat(all.subList(0, 2)).isEqualTo(titles(TaskFilter.ACTIVE, sort));
        assertThat(all.subList(2, 4)).isEqualTo(titles(TaskFilter.DONE, sort));
        assertThat(titles(TaskFilter.IN_PROGRESS, sort)).containsExactly("progress M");
    }

    // ---- search ----

    @Test
    void searchMatchesTitleOrDescriptionIgnoringCaseAndAccents() {
        task(alice, "Buy milk", null, null, Priority.NONE, TaskStatus.TODO, at(0));
        task(alice, "Call Bob", null, null, Priority.NONE, TaskStatus.TODO, at(1));
        task(alice, "Groceries", "eggs, MILK and bread", null, Priority.NONE, TaskStatus.TODO, at(2));
        task(alice, "Café meeting", null, null, Priority.NONE, TaskStatus.TODO, at(3));
        task(bob, "Bob's milk", null, null, Priority.NONE, TaskStatus.TODO, at(4));

        assertThat(search("MILK")).containsExactly("Groceries", "Buy milk");
        assertThat(search("cafe")).containsExactly("Café meeting");
        assertThat(search("CAFÉ MEET")).containsExactly("Café meeting");
        assertThat(search("bob")).containsExactly("Call Bob");
        assertThat(search("nothing")).isEmpty();
    }

    @Test
    void wildcardsAndEscapeCharactersAreLiteral() {
        task("50% off", at(0));
        task("5000 off", at(1));
        task("a_b", at(2));
        task("axb", at(3));
        task("back\\slash", at(4));
        task("backslash", at(5));
        task("wow!", at(6));
        task("wow", at(7));
        task("it's \"quoted\"", at(8));

        assertThat(search("50%")).containsExactly("50% off");
        assertThat(search("%")).containsExactly("50% off");
        assertThat(search("a_b")).containsExactly("a_b");
        assertThat(search("_")).containsExactly("a_b");
        assertThat(search("k\\s")).containsExactly("back\\slash");
        assertThat(search("\\")).containsExactly("back\\slash");
        assertThat(search("w!")).containsExactly("wow!");
        assertThat(search("!")).containsExactly("wow!");
        assertThat(search("'s \"q")).containsExactly("it's \"quoted\"");
    }

    @Test
    void filterTagSearchAndSortCombineAndCountMatches() {
        Task a = task(alice, "Milk A", null, null, Priority.LOW, TaskStatus.TODO, at(0));
        Task b = task(alice, "milk b", null, null, Priority.HIGH, TaskStatus.IN_PROGRESS, at(1));
        Task c = task(alice, "Milk c", null, null, Priority.HIGH, TaskStatus.DONE, at(2));
        Task d = task(alice, "bread", "with milk", null, Priority.MEDIUM, TaskStatus.TODO, at(3));
        task(alice, "milk untagged", null, null, Priority.HIGH, TaskStatus.TODO, at(4));
        Task bobs = task(bob, "milk", null, null, Priority.HIGH, TaskStatus.TODO, at(5));
        long work = em.persistAndFlush(new Tag(aliceUser, alice, "Work")).getId();
        long bobsWork = em.persistAndFlush(new Tag(bobUser, bob, "Work")).getId();
        for (Task t : List.of(a, b, c, d)) {
            jdbc.update("insert into task_tags (task_id, tag_id) values (?, ?)", t.getId(), work);
        }
        jdbc.update("insert into task_tags (task_id, tag_id) values (?, ?)", bobs.getId(), bobsWork);

        TaskSearchCriteria all = new TaskSearchCriteria(alice, TaskFilter.ALL, TaskSort.PRIORITY, work, "MILK");
        assertThat(titles(all)).containsExactly("milk b", "bread", "Milk A", "Milk c");
        assertThat(repository.countMatching(all)).isEqualTo(4);
        TaskSearchCriteria active = new TaskSearchCriteria(alice, TaskFilter.ACTIVE, TaskSort.TITLE, work, "milk");
        assertThat(titles(active)).containsExactly("bread", "Milk A", "milk b");
        assertThat(repository.countMatching(active)).isEqualTo(3);
        TaskSearchCriteria progress = new TaskSearchCriteria(alice, TaskFilter.IN_PROGRESS, TaskSort.DUE, work, "k");
        assertThat(titles(progress)).containsExactly("milk b");
        TaskSearchCriteria done = new TaskSearchCriteria(alice, TaskFilter.DONE, TaskSort.OLDEST, null, "milk");
        assertThat(titles(done)).containsExactly("Milk c");
        assertThat(repository.countMatching(new TaskSearchCriteria(alice, TaskFilter.ALL, TaskSort.NEWEST, null, null)))
                .isEqualTo(5);
        // Another user's tag id never reaches their tasks.
        TaskSearchCriteria foreignTag = new TaskSearchCriteria(alice, TaskFilter.ALL, TaskSort.NEWEST, bobsWork, null);
        assertThat(titles(foreignTag)).isEmpty();
        assertThat(repository.countMatching(foreignTag)).isZero();
    }

    // ---- keyset paging ----

    /**
     * 120 tasks with many ties on every key (createdAt, title ignoring case/accents, priority, due date incl. none)
     * and both groups.
     */
    /** V10: another list of the same user is just as separate as another user's list. */
    @Test
    void onlyTheGivenListsTasksAreReturnedEvenForTheSameOwner() {
        long work = TestLists.other(jdbc, aliceUser, "Work");
        task("mine", at(0));
        task(work, "other list", null, null, Priority.NONE, TaskStatus.TODO, at(1));

        assertThat(titles(TaskFilter.ALL, TaskSort.NEWEST)).containsExactly("mine");
        assertThat(titles(new TaskSearchCriteria(work, TaskFilter.ALL, TaskSort.NEWEST, null, null)))
                .containsExactly("other list");
        assertThat(repository.countMatching(new TaskSearchCriteria(work, TaskFilter.ALL, TaskSort.NEWEST, null, null)))
                .isEqualTo(1);
    }

    private void manyTasks(long owner) {
        String[] titles = {"apple", "Apple", "ápple", "banana", "Banana", "cherry"};
        Priority[] priorities = Priority.values();
        TaskStatus[] statuses = TaskStatus.values();
        for (int i = 0; i < 120; i++) {
            LocalDate due = i % 4 == 0 ? null : D0.plusDays(i % 5);
            task(owner, titles[i % titles.length], null, due, priorities[i % priorities.length],
                    statuses[i % statuses.length], at(i / 7));
        }
    }

    /** Pages of 50 the way the service reads them (51 rows, cursor = 50th). */
    private List<Long> pageThrough(TaskSearchCriteria criteria) {
        List<Long> ids = new ArrayList<>();
        TaskCursor after = null;
        for (int page = 0; page < 10; page++) {
            List<Task> rows = repository.findPage(criteria, after, 51);
            List<Task> tasks = rows.size() > 50 ? rows.subList(0, 50) : rows;
            tasks.forEach(t -> ids.add(t.getId()));
            if (rows.size() <= 50) {
                return ids;
            }
            after = TaskCursor.after(tasks.get(49), criteria.sort());
        }
        throw new AssertionError("Paging never ended");
    }

    private List<Long> unpaged(TaskSearchCriteria criteria) {
        return repository.findPage(criteria, null, 1000).stream().map(Task::getId).toList();
    }

    @ParameterizedTest
    @EnumSource(TaskSort.class)
    void pagesNeverRepeatOrSkipATaskForEverySortAndFilter(TaskSort sort) {
        manyTasks(alice);
        manyTasks(bob);
        for (TaskFilter filter : TaskFilter.values()) {
            TaskSearchCriteria criteria = new TaskSearchCriteria(alice, filter, sort, null, null);
            List<Long> paged = pageThrough(criteria);
            List<Long> whole = unpaged(criteria);
            assertThat(paged).as(filter + " " + sort).isEqualTo(whole).doesNotHaveDuplicates();
            assertThat((long) paged.size()).isEqualTo(repository.countMatching(criteria));
        }
        TaskSearchCriteria all = new TaskSearchCriteria(alice, TaskFilter.ALL, sort, null, null);
        assertThat(pageThrough(all)).hasSize(120);
        Set<Long> bobs = new HashSet<>(jdbc.queryForList("select id from tasks where list_id = ?", Long.class, bob));
        assertThat(pageThrough(all)).doesNotContainAnyElementsOf(bobs);
    }

    @ParameterizedTest
    @EnumSource(TaskSort.class)
    void nextPageStaysExactWhenTheBoundaryTaskIsEditedOrDeleted(TaskSort sort) {
        manyTasks(alice);
        TaskSearchCriteria criteria = new TaskSearchCriteria(alice, TaskFilter.ALL, sort, null, null);
        List<Long> before = unpaged(criteria);
        List<Task> first = repository.findPage(criteria, null, 51).subList(0, 50);
        Task boundary = first.get(49);
        TaskCursor cursor = TaskCursor.after(boundary, sort);

        // Edited so it moves elsewhere in every order, then deleted: the cursor still holds its old position.
        jdbc.update("update tasks set title = 'zzz moved', priority = 'NONE', due_date = null, status = 'TODO',"
                + " done = false where id = ?", boundary.getId());
        em.clear();
        List<Long> afterEdit = repository.findPage(criteria, cursor, 1000).stream().map(Task::getId)
                .filter(id -> !id.equals(boundary.getId())).toList();
        assertThat(afterEdit).isEqualTo(before.subList(50, before.size()));

        jdbc.update("delete from tasks where id = ?", boundary.getId());
        List<Long> afterDelete = repository.findPage(criteria, cursor, 1000).stream().map(Task::getId).toList();
        assertThat(afterDelete).isEqualTo(before.subList(50, before.size()));
    }

    @Test
    void pagingWorksWithSearchAndTag() {
        manyTasks(alice);
        long work = em.persistAndFlush(new Tag(aliceUser, alice, "Work")).getId();
        jdbc.update("insert into task_tags (task_id, tag_id) select id, ? from tasks where list_id = ? and id % 3 <> 0",
                work, alice);
        for (TaskSort sort : TaskSort.values()) {
            TaskSearchCriteria criteria = new TaskSearchCriteria(alice, TaskFilter.ALL, sort, work, "AN");
            List<Long> paged = pageThrough(criteria);
            assertThat(paged).as(sort.name()).isEqualTo(unpaged(criteria)).isNotEmpty().doesNotHaveDuplicates();
            assertThat((long) paged.size()).isEqualTo(repository.countMatching(criteria));
        }
    }

    // ---- 0.14.0: the due time ----

    private Task timed(String title, LocalDate due, java.time.LocalTime time, int createdAt) {
        Task task = new Task(ownerOf(alice), alice, title, null, due, at(createdAt));
        task.replace(title, null, due, time, at(createdAt));
        return em.persistAndFlush(task);
    }

    @Test
    void insideADateTimedTasksComeFirstByTimeThenDateOnlyTasksTiesNewestFirst() {
        timed("date-only old", D0, null, 0);
        timed("10:00", D0, java.time.LocalTime.of(10, 0), 1);
        timed("09:00 old", D0, java.time.LocalTime.of(9, 0), 2);
        timed("09:00 new", D0, java.time.LocalTime.of(9, 0), 3);
        timed("date-only new", D0, null, 4);
        timed("previous day 23:59", D0.minusDays(1), java.time.LocalTime.of(23, 59), 5);
        timed("next day date-only", D0.plusDays(1), null, 6);
        timed("next day 00:00", D0.plusDays(1), java.time.LocalTime.MIDNIGHT, 7);
        timed("no date", null, null, 8);

        assertThat(titles(TaskFilter.ALL, TaskSort.DUE)).containsExactly("previous day 23:59", "09:00 new",
                "09:00 old", "10:00", "date-only new", "date-only old", "next day 00:00", "next day date-only",
                "no date");
    }

    @Test
    void theWallClockTimeIsStoredAndReadBackUnchanged() {
        Task saved = timed("t", D0, java.time.LocalTime.of(9, 30), 0);
        em.clear();
        assertThat(repository.findById(saved.getId()).orElseThrow().getDueTime()).isEqualTo(java.time.LocalTime.of(9, 30));
        // The column itself holds 09:30, whatever the JVM's zone is (Hibernate's own TIME mapping shifted it).
        assertThat(jdbc.queryForObject("select cast(due_time as char) from tasks where id = ?", String.class,
                saved.getId())).isEqualTo("09:30:00");
    }

    @Test
    void thereIsNoGapOrRepeatWhenACursorSitsOnAnyTaskOfTheDueOrder() {
        // Every kind of position: a timed task, a date-only task and a no-date task in the same group, with ties.
        timed("a 09:00", D0, java.time.LocalTime.of(9, 0), 0);
        timed("b 09:00", D0, java.time.LocalTime.of(9, 0), 1);
        timed("c 12:00", D0, java.time.LocalTime.of(12, 0), 2);
        timed("d date-only", D0, null, 3);
        timed("e date-only", D0, null, 4);
        timed("f next day", D0.plusDays(1), java.time.LocalTime.of(8, 0), 5);
        timed("g next day date-only", D0.plusDays(1), null, 6);
        timed("h no date", null, null, 7);
        timed("i no date", null, null, 8);
        timed("j done", D0, java.time.LocalTime.of(7, 0), 9);
        jdbc.update("update tasks set status = 'DONE', done = true where title = 'j done'");
        em.clear();

        TaskSearchCriteria criteria = new TaskSearchCriteria(alice, TaskFilter.ALL, TaskSort.DUE, null, null);
        List<Task> all = repository.findPage(criteria, null, 1000);
        assertThat(all).hasSize(10);
        for (int i = 0; i < all.size(); i++) {
            List<Long> rest = repository.findPage(criteria, TaskCursor.after(all.get(i), TaskSort.DUE), 1000).stream()
                    .map(Task::getId).toList();
            assertThat(rest).as("after %s", all.get(i).getTitle())
                    .isEqualTo(all.subList(i + 1, all.size()).stream().map(Task::getId).toList());
        }
    }

    // ---- "My order" (sort=manual, contract 0.18.0) ----

    private Task positioned(String title, int createdSeconds, Long position, TaskStatus status) {
        Task task = task(alice, title, null, null, Priority.NONE, status, at(createdSeconds));
        if (position != null) {
            jdbc.update("update tasks set position = ? where id = ?", position, task.getId());
        }
        // Written behind the entity's back (its position column is not updatable): read fresh rows from now on.
        em.clear();
        return task;
    }

    private Task positioned(String title, int createdSeconds, Long position) {
        return positioned(title, createdSeconds, position, TaskStatus.TODO);
    }

    @Test
    void manualIsPositionAscendingThenUnpositionedNewestFirst() {
        positioned("none old", 0, null);
        positioned("p3", 1, 3072L);
        positioned("none new", 9, null);
        positioned("p1", 2, 1024L);
        positioned("negative", 3, -1024L);
        positioned("p2", 4, 2048L);

        assertThat(titles(TaskFilter.ALL, TaskSort.MANUAL))
                .containsExactly("negative", "p1", "p2", "p3", "none new", "none old");
    }

    @Test
    void manualTiesOfAPositionAreNewestFirstThenHighestId() {
        positioned("old", 0, 1024L);
        positioned("tie a", 5, 1024L);
        positioned("tie b", 5, 1024L);
        positioned("new", 9, 1024L);
        positioned("first", 9, 512L);

        assertThat(titles(TaskFilter.ALL, TaskSort.MANUAL)).containsExactly("first", "new", "tie b", "tie a", "old");
    }

    @Test
    void manualKeepsTheDoneGroupLastAndOrdersEachGroupOnItsOwn() {
        positioned("done p1", 0, 1024L, TaskStatus.DONE);
        positioned("todo p5", 1, 5120L);
        positioned("done none", 2, null, TaskStatus.DONE);
        positioned("progress p2", 3, 2048L, TaskStatus.IN_PROGRESS);
        positioned("done p0", 4, 0L, TaskStatus.DONE);
        positioned("todo none", 5, null);

        assertThat(titles(TaskFilter.ALL, TaskSort.MANUAL))
                .containsExactly("progress p2", "todo p5", "todo none", "done p0", "done p1", "done none");
        assertThat(titles(TaskFilter.ACTIVE, TaskSort.MANUAL)).containsExactly("progress p2", "todo p5", "todo none");
        assertThat(titles(TaskFilter.DONE, TaskSort.MANUAL)).containsExactly("done p0", "done p1", "done none");
        assertThat(titles(TaskFilter.IN_PROGRESS, TaskSort.MANUAL)).containsExactly("progress p2");
    }

    @Test
    void manualSkipsTrashedTasksAndOtherListsAndWorksWithTagAndSearch() {
        Task gone = positioned("trashed", 0, 1L);
        jdbc.update("update tasks set deleted_at = ? where id = ?", java.sql.Timestamp.from(at(50)), gone.getId());
        positioned("milk", 1, 2L);
        positioned("bread", 2, 1L);
        task(bob, "bob's", null, null, Priority.NONE, TaskStatus.TODO, at(3));

        assertThat(titles(TaskFilter.ALL, TaskSort.MANUAL)).containsExactly("bread", "milk");
        assertThat(titles(new TaskSearchCriteria(alice, TaskFilter.ALL, TaskSort.MANUAL, null, "mil")))
                .containsExactly("milk");
        assertThat(repository.countMatching(new TaskSearchCriteria(alice, TaskFilter.ALL, TaskSort.MANUAL, null, null)))
                .isEqualTo(2);
    }

    /** Pages of {@code size} chained the way the service does it (manual: the anchor row is read again each time). */
    private List<String> pages(TaskFilter filter, int size) {
        TaskSearchCriteria criteria = new TaskSearchCriteria(alice, filter, TaskSort.MANUAL, null, null);
        List<String> all = new ArrayList<>();
        TaskCursor after = null;
        while (true) {
            List<Task> page = repository.findPage(criteria, after, size);
            page.forEach(t -> all.add(t.getTitle()));
            if (page.size() < size) {
                return all;
            }
            Task last = page.get(page.size() - 1);
            em.clear();
            Task anchor = repository.findLiveInList(last.getId(), alice).orElseThrow();
            after = TaskCursor.manual(last.isDone(), last.getId()).resolved(anchor);
        }
    }

    @ParameterizedTest
    @EnumSource(value = TaskFilter.class, names = {"ALL", "ACTIVE", "DONE"})
    void manualKeysetPagesEqualTheWholeOrderAcrossPositionedTiedAndUnpositionedRows(TaskFilter filter) {
        for (int i = 0; i < 7; i++) {
            positioned("p" + i, i % 3, (long) (i / 2) * 1024, i % 4 == 3 ? TaskStatus.DONE : TaskStatus.TODO);
            positioned("n" + i, i % 3, null, i % 4 == 1 ? TaskStatus.DONE : TaskStatus.TODO);
        }
        List<String> whole = titles(filter, TaskSort.MANUAL);

        for (int size : new int[] {1, 2, 3, 5}) {
            assertThat(pages(filter, size)).as("page size " + size).containsExactlyElementsOf(whole);
        }
    }

    @Test
    void manualCursorFollowsTheAnchorWhenItIsMovedOrRenumberedBetweenPages() {
        Task a = positioned("a", 0, 1024L);
        Task b = positioned("b", 1, 2048L);
        positioned("c", 2, 3072L);
        positioned("d", 3, 4096L);
        TaskSearchCriteria criteria = new TaskSearchCriteria(alice, TaskFilter.ALL, TaskSort.MANUAL, null, null);
        assertThat(repository.findPage(criteria, null, 2)).extracting(Task::getTitle).containsExactly("a", "b");

        // A drop right after the last loaded row (b): "x" lands between b and c. The cursor is the anchor b.
        Task x = positioned("x", 4, 2560L);
        TaskCursor after = TaskCursor.manual(false, b.getId())
                .resolved(repository.findLiveInList(b.getId(), alice).orElseThrow());
        assertThat(repository.findPage(criteria, after, 10)).extracting(Task::getTitle).containsExactly("x", "c", "d");

        // The group is renumbered meanwhile (position = id * 100, so the order is a, b, c, d, x): the same anchor id
        // still points at b and what follows it is c, d, x.
        jdbc.update("update tasks set position = id * 100 where list_id = ?", alice);
        em.clear();
        after = TaskCursor.manual(false, b.getId()).resolved(repository.findLiveInList(b.getId(), alice).orElseThrow());
        assertThat(repository.findPage(criteria, after, 10)).extracting(Task::getId)
                .containsExactly(a.getId() + 2, a.getId() + 3, x.getId());
    }

    @Test
    void manualCursorKeepsTheIssuedGroupWhenTheAnchorWasTickedBetweenPages() {
        positioned("a", 0, 1024L);
        Task b = positioned("b", 1, 2048L);
        positioned("c", 2, 3072L);
        positioned("d", 3, 4096L);
        positioned("e done", 4, 512L, TaskStatus.DONE);
        TaskSearchCriteria criteria = new TaskSearchCriteria(alice, TaskFilter.ALL, TaskSort.MANUAL, null, null);
        TaskCursor issued = TaskCursor.manual(false, b.getId());

        // b is ticked after its page was loaded: it is now in the done group, with its old position (2048).
        jdbc.update("update tasks set status = 'DONE', done = true where id = ?", b.getId());
        em.clear();
        TaskCursor after = issued.resolved(repository.findLiveInList(b.getId(), alice).orElseThrow());

        // Rest of the not-done group by the anchor's position, then the whole done group (b shows there again).
        assertThat(repository.findPage(criteria, after, 10)).extracting(Task::getTitle)
                .containsExactly("c", "d", "e done", "b");
    }

    @Test
    void anchorLookupOnlyFindsLiveTasksOfTheList() {
        Task live = positioned("live", 0, 1L);
        Task trashed = positioned("trashed", 1, 2L);
        jdbc.update("update tasks set deleted_at = ? where id = ?", java.sql.Timestamp.from(at(50)), trashed.getId());
        Task others = task(bob, "bob's", null, null, Priority.NONE, TaskStatus.TODO, at(2));

        assertThat(repository.findLiveInList(live.getId(), alice)).isPresent();
        assertThat(repository.findLiveInList(live.getId(), bob)).isEmpty();
        assertThat(repository.findLiveInList(trashed.getId(), alice)).isEmpty();
        assertThat(repository.findLiveInList(others.getId(), alice)).isEmpty();
        assertThat(repository.findLiveInList(999_999L, alice)).isEmpty();
    }

    @Test
    void lowestLivePositionIgnoresTrashedTasksOtherListsAndNulls() {
        assertThat(repository.minLivePosition(alice)).isEmpty();
        positioned("none", 0, null);
        assertThat(repository.minLivePosition(alice)).isEmpty();
        positioned("p", 1, 500L);
        Task low = positioned("low", 2, -7000L);
        assertThat(repository.minLivePosition(alice)).contains(-7000L);
        jdbc.update("update tasks set deleted_at = ? where id = ?", java.sql.Timestamp.from(at(50)), low.getId());
        assertThat(repository.minLivePosition(alice)).contains(500L);
        assertThat(repository.minLivePosition(bob)).isEmpty();
    }

    @Test
    void groupHelpersReadTheOrderAndNumberTheRows() {
        Task none = positioned("none", 5, null);
        Task p2 = positioned("p2", 0, 2048L);
        Task p1 = positioned("p1", 1, 1024L);
        Task done = positioned("done", 2, 7L, TaskStatus.DONE);

        assertThat(repository.manualGroup(alice, false)).extracting(TaskSearchRepository.GroupRow::id)
                .containsExactly(p1.getId(), p2.getId(), none.getId());
        assertThat(repository.manualGroup(alice, true)).extracting(TaskSearchRepository.GroupRow::id)
                .containsExactly(done.getId());
        assertThat(repository.manualGroup(alice, false).get(2).position()).isNull();

        repository.lockGroup(alice, false);
        repository.assignPositions(List.of(none.getId(), p1.getId(), p2.getId()), 1024);
        em.clear();
        assertThat(repository.manualGroup(alice, false)).extracting(TaskSearchRepository.GroupRow::id,
                TaskSearchRepository.GroupRow::position).containsExactly(
                        org.assertj.core.groups.Tuple.tuple(none.getId(), 1024L),
                        org.assertj.core.groups.Tuple.tuple(p1.getId(), 2048L),
                        org.assertj.core.groups.Tuple.tuple(p2.getId(), 3072L));
        // The other group and updated_at are untouched.
        assertThat(repository.manualGroup(alice, true).get(0).position()).isEqualTo(7L);
        assertThat(repository.findById(none.getId()).orElseThrow().getUpdatedAt()).isEqualTo(at(5));
    }

    @Test
    void assignPositionsHandlesMoreRowsThanOneStatementTakes() {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 450; i++) {
            ids.add(positioned("t" + i, i, null).getId());
        }
        repository.assignPositions(ids, 1024);
        em.clear();

        List<TaskSearchRepository.GroupRow> rows = repository.manualGroup(alice, false);
        assertThat(rows).hasSize(450);
        for (int i = 0; i < rows.size(); i++) {
            assertThat(rows.get(i).id()).isEqualTo(ids.get(i));
            assertThat(rows.get(i).position()).isEqualTo((i + 1) * 1024L);
        }
    }

    @Test
    void setPositionWritesOnlyThePositionAndAnEntitySaveNeverWritesItBack() {
        Task t = positioned("t", 0, null);
        assertThat(repository.setPosition(t.getId(), -2048L)).isOne();
        // A stale managed entity (position still null in memory) saved later must not undo the write.
        t.changePriority(Priority.HIGH, at(60));
        repository.saveAndFlush(t);
        em.clear();

        assertThat(jdbc.queryForObject("select position from tasks where id = ?", Long.class, t.getId()))
                .isEqualTo(-2048L);
        assertThat(repository.findById(t.getId()).orElseThrow().getPriority()).isEqualTo(Priority.HIGH);
    }
}
