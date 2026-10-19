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
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
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
}
