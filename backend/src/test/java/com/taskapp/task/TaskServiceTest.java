package com.taskapp.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionOperations;

import com.taskapp.auth.InvalidFieldsException;
import com.taskapp.task.dto.CreateTaskRequest;
import com.taskapp.task.dto.ReplaceTaskRequest;
import com.taskapp.task.dto.TaskPage;
import com.taskapp.task.dto.TaskResponse;

class TaskServiceTest {

    private static final long OWNER = 7L;
    private static final Instant CREATED = Instant.parse("2026-10-01T18:00:00Z");
    /** Nanosecond precision on purpose: the service must truncate to what MySQL DATETIME(6) stores. */
    private static final Instant NOW = Instant.parse("2026-10-08T09:15:02.123456789Z");
    private static final Instant NOW_MICROS = Instant.parse("2026-10-08T09:15:02.123456Z");

    private final TaskRepository repository = mock(TaskRepository.class);
    private final TagService tagService = mock(TagService.class);
    private TaskService service;

    @BeforeEach
    void setUp() {
        service = new TaskService(repository, tagService, TransactionOperations.withoutTransaction(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        when(repository.saveAndFlush(any(Task.class))).thenAnswer(inv -> {
            Task task = inv.getArgument(0);
            if (task.getId() == null) {
                ReflectionTestUtils.setField(task, "id", 12L);
            }
            return task;
        });
        when(tagService.namesOf(anyLong(), anyLong())).thenReturn(List.of());
    }

    private static Task existing(long id) {
        Task task = new Task(OWNER, "Buy milk", "2%", LocalDate.parse("2026-10-10"), CREATED);
        ReflectionTestUtils.setField(task, "id", id);
        return task;
    }

    private Task stored(long id, TaskStatus status, Priority priority) {
        Task task = existing(id);
        task.changeStatus(status, CREATED);
        task.changePriority(priority, CREATED);
        when(repository.findByIdAndOwnerId(id, OWNER)).thenReturn(Optional.of(task));
        return task;
    }

    private static ReplaceTaskRequest put(Priority priority, TaskStatus status, Boolean done, List<String> tags) {
        return new ReplaceTaskRequest("Buy milk", "2%", LocalDate.parse("2026-10-10"), priority, status, done, tags);
    }

    @Test
    void createIsNoneTodoWithoutTagsAndHasEqualTimestamps() {
        TaskResponse created = service.create(OWNER, new CreateTaskRequest("Buy milk", null, LocalDate.parse("2026-10-10")));

        assertThat(created).isEqualTo(new TaskResponse(12L, "Buy milk", null, Priority.NONE, TaskStatus.TODO, List.of(),
                false, LocalDate.parse("2026-10-10"), NOW_MICROS, NOW_MICROS));
        verify(repository).saveAndFlush(any(Task.class));
        verifyNoInteractions(tagService);
    }

    @Test
    void createStoresTheSessionOwner() {
        service.create(OWNER, new CreateTaskRequest("x", null, null));

        verify(repository).saveAndFlush(org.mockito.ArgumentMatchers.argThat(t -> t.getOwnerId() == OWNER));
    }

    private static List<Task> tasks(int count) {
        List<Task> tasks = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            tasks.add(existing(i));
        }
        return tasks;
    }

    private static TaskListQuery query(TaskFilter filter, TaskSort sort, String tag, String q, String cursor) {
        return TaskListQuery.of(filter.name().toLowerCase(), sort.name().toLowerCase(), tag, q, cursor);
    }

    private static TaskSearchCriteria criteria(TaskFilter filter, TaskSort sort, Long tagId, String search) {
        return new TaskSearchCriteria(OWNER, filter, sort, tagId, search);
    }

    @Test
    void firstPageWithEverythingSkipsTheCountAndLoadsTagsOnce() {
        Task a = existing(1L);
        Task b = existing(2L);
        when(repository.findPage(criteria(TaskFilter.DONE, TaskSort.DUE, null, "milk"), null, 51))
                .thenReturn(List.of(a, b));
        when(tagService.namesByTask(OWNER, List.of(1L, 2L))).thenReturn(Map.of(1L, List.of("Home", "Work")));

        TaskPage page = service.list(OWNER, query(TaskFilter.DONE, TaskSort.DUE, " ", "  milk ", ""));

        assertThat(page.items()).extracting(TaskResponse::tags).containsExactly(List.of("Home", "Work"), List.of());
        assertThat(page.total()).isEqualTo(2);
        assertThat(page.nextCursor()).isNull();
        verify(repository, never()).countMatching(any());
        verify(tagService, times(1)).namesByTask(anyLong(), any());
        verify(tagService, never()).namesOf(anyLong(), anyLong());
        verify(tagService, never()).findTagId(anyLong(), any());
    }

    @Test
    void fullPageReturns50WithACursorAndTheCount() {
        List<Task> rows = tasks(51);
        TaskSearchCriteria criteria = criteria(TaskFilter.ALL, TaskSort.TITLE, null, null);
        when(repository.findPage(criteria, null, 51)).thenReturn(rows);
        when(repository.countMatching(criteria)).thenReturn(120L);
        when(tagService.namesByTask(anyLong(), any())).thenReturn(Map.of());

        TaskListQuery first = query(TaskFilter.ALL, TaskSort.TITLE, null, null, null);
        TaskPage page = service.list(OWNER, first);

        assertThat(page.items()).hasSize(50).extracting(TaskResponse::id).startsWith(1L).endsWith(50L);
        assertThat(page.total()).isEqualTo(120);
        assertThat(page.nextCursor()).matches("[A-Za-z0-9_-]+");
        assertThat(TaskCursor.decode(page.nextCursor(), TaskSort.TITLE, first.fingerprint()))
                .contains(TaskCursor.after(rows.get(49), TaskSort.TITLE));

        // The next page starts after that task and is always counted.
        TaskCursor after = TaskCursor.after(rows.get(49), TaskSort.TITLE);
        when(repository.findPage(criteria, after, 51)).thenReturn(List.of(existing(51)));
        TaskPage next = service.list(OWNER, query(TaskFilter.ALL, TaskSort.TITLE, null, null, page.nextCursor()));
        assertThat(next.items()).extracting(TaskResponse::id).containsExactly(51L);
        assertThat(next.total()).isEqualTo(120);
        assertThat(next.nextCursor()).isNull();
    }

    @Test
    void tagIsResolvedToTheOwnersTagId() {
        when(tagService.findTagId(OWNER, " work ")).thenReturn(Optional.of(55L));
        when(repository.findPage(any(), any(), anyInt())).thenReturn(List.of());

        TaskPage page = service.list(OWNER, query(TaskFilter.ACTIVE, TaskSort.NEWEST, " work ", null, null));

        assertThat(page).isEqualTo(TaskPage.empty());
        verify(repository).findPage(criteria(TaskFilter.ACTIVE, TaskSort.NEWEST, 55L, null), null, 51);
    }

    @Test
    void unknownOrInvalidTagIsAnEmptyPage() {
        when(tagService.findTagId(anyLong(), any())).thenReturn(Optional.empty());

        assertThat(service.list(OWNER, query(TaskFilter.ALL, TaskSort.NEWEST, "nope", null, null)))
                .isEqualTo(TaskPage.empty());
        assertThat(service.list(OWNER, query(TaskFilter.ALL, TaskSort.NEWEST, "a,b", null, null)))
                .isEqualTo(TaskPage.empty());
        verify(repository, never()).findPage(any(), any(), anyInt());
        verify(repository, never()).countMatching(any());
    }

    @Test
    void searchOver100CharactersAfterTrimmingIs400() {
        String hundred = "é".repeat(100);
        when(repository.findPage(any(), any(), anyInt())).thenReturn(List.of());
        service.list(OWNER, query(TaskFilter.ALL, TaskSort.NEWEST, null, "  " + hundred + "  ", null));

        assertThatThrownBy(() -> service.list(OWNER, query(TaskFilter.ALL, TaskSort.NEWEST, null, hundred + "x", null)))
                .isInstanceOfSatisfying(InvalidFieldsException.class, e -> {
                    assertThat(e.bindingResult().getFieldErrors()).hasSize(1);
                    assertThat(e.bindingResult().getFieldError("q").getCode()).isEqualTo("Size");
                });
        // UTF-16 code units, like JavaScript's length: 50 emoji = 100.
        service.list(OWNER, query(TaskFilter.ALL, TaskSort.NEWEST, null, "😀".repeat(50), null));
        assertThatThrownBy(() -> service.list(OWNER, query(TaskFilter.ALL, TaskSort.NEWEST, null, "😀".repeat(51), null)))
                .isInstanceOf(InvalidFieldsException.class);
    }

    @Test
    void cursorOfAnotherQueryOrGarbageIs400AndNothingIsRead() {
        Task last = existing(9L);
        TaskListQuery issuedFor = query(TaskFilter.ACTIVE, TaskSort.DUE, "Work", "milk", null);
        String cursor = TaskCursor.after(last, TaskSort.DUE).encode(TaskSort.DUE, issuedFor.fingerprint());
        when(tagService.findTagId(anyLong(), any())).thenReturn(Optional.of(55L));
        when(repository.findPage(any(), any(), anyInt())).thenReturn(List.of());

        // Same list after normalising: accepted.
        service.list(OWNER, query(TaskFilter.ACTIVE, TaskSort.DUE, " WÖRK ", " milk ", cursor));

        List<TaskListQuery> others = List.of(
                query(TaskFilter.ALL, TaskSort.DUE, "Work", "milk", cursor),
                query(TaskFilter.ACTIVE, TaskSort.NEWEST, "Work", "milk", cursor),
                query(TaskFilter.ACTIVE, TaskSort.DUE, "Home", "milk", cursor),
                query(TaskFilter.ACTIVE, TaskSort.DUE, null, "milk", cursor),
                query(TaskFilter.ACTIVE, TaskSort.DUE, "Work", "milky", cursor),
                query(TaskFilter.ACTIVE, TaskSort.DUE, "Work", "milk", cursor + "A"),
                query(TaskFilter.ACTIVE, TaskSort.DUE, "Work", "milk", "garbage"),
                query(TaskFilter.ACTIVE, TaskSort.DUE, "Work", "milk", "x".repeat(1025)));
        for (TaskListQuery other : others) {
            assertThatThrownBy(() -> service.list(OWNER, other)).as(other.toString())
                    .isInstanceOfSatisfying(InvalidFieldsException.class, e ->
                            assertThat(e.bindingResult().getFieldError("cursor").getCode())
                                    .isEqualTo(TaskService.INVALID_CURSOR_CONSTRAINT));
        }
        verify(repository, times(1)).findPage(any(), any(), anyInt());
    }

    @Test
    void badCursorAndLongSearchAreReportedTogether() {
        assertThatThrownBy(() -> service.list(OWNER, query(TaskFilter.ALL, TaskSort.NEWEST, null, "x".repeat(101), "@")))
                .isInstanceOfSatisfying(InvalidFieldsException.class, e ->
                        assertThat(e.bindingResult().getFieldErrors()).extracting(f -> f.getField())
                                .containsExactlyInAnyOrder("cursor", "q"));
        verifyNoInteractions(repository, tagService);
    }

    @Test
    void getIncludesTheTasksTags() {
        stored(12L, TaskStatus.IN_PROGRESS, Priority.LOW);
        when(tagService.namesOf(OWNER, 12L)).thenReturn(List.of("Errands"));

        TaskResponse task = service.get(OWNER, 12L);

        assertThat(task.tags()).containsExactly("Errands");
        assertThat(task.priority()).isEqualTo(Priority.LOW);
        assertThat(task.status()).isEqualTo(TaskStatus.IN_PROGRESS);
        assertThat(task.done()).isFalse();
    }

    @Test
    void replaceOverwritesTextFieldsAndClearsNulls() {
        stored(12L, TaskStatus.TODO, Priority.NONE);

        TaskResponse updated = service.replace(OWNER, 12L,
                new ReplaceTaskRequest("Buy oat milk", null, null, null, null, true, null));

        assertThat(updated).isEqualTo(new TaskResponse(12L, "Buy oat milk", null, Priority.NONE, TaskStatus.DONE,
                List.of(), true, null, CREATED, NOW_MICROS));
    }

    @Test
    void replaceWithoutNewFieldsLeavesPriorityStatusAndTagsUnchanged() {
        stored(12L, TaskStatus.IN_PROGRESS, Priority.HIGH);
        when(tagService.namesOf(OWNER, 12L)).thenReturn(List.of("Home"));

        TaskResponse updated = service.replace(OWNER, 12L, put(null, null, null, null));

        assertThat(updated.priority()).isEqualTo(Priority.HIGH);
        assertThat(updated.status()).isEqualTo(TaskStatus.IN_PROGRESS);
        assertThat(updated.done()).isFalse();
        assertThat(updated.tags()).containsExactly("Home");
        assertThat(updated.updatedAt()).isEqualTo(NOW_MICROS);
        verify(tagService, never()).lockOwner(anyLong());
        verify(tagService, never()).replaceTags(anyLong(), anyLong(), any());
    }

    @Test
    void replaceSetsPriority() {
        Task task = stored(12L, TaskStatus.TODO, Priority.HIGH);

        assertThat(service.replace(OWNER, 12L, put(Priority.NONE, null, null, null)).priority()).isEqualTo(Priority.NONE);
        assertThat(task.getPriority()).isEqualTo(Priority.NONE);
        assertThat(service.replace(OWNER, 12L, put(Priority.MEDIUM, null, null, null)).priority())
                .isEqualTo(Priority.MEDIUM);
    }

    /** Every row of the contract's PUT table (both sent and disagreeing is rejected by validation). */
    @Test
    void replaceStatusAndDoneMatrix() {
        record Row(TaskStatus from, TaskStatus status, Boolean done, TaskStatus expected) {
        }
        List<Row> rows = List.of(
                new Row(TaskStatus.TODO, null, null, TaskStatus.TODO),
                new Row(TaskStatus.IN_PROGRESS, null, null, TaskStatus.IN_PROGRESS),
                new Row(TaskStatus.DONE, null, null, TaskStatus.DONE),
                new Row(TaskStatus.TODO, TaskStatus.IN_PROGRESS, null, TaskStatus.IN_PROGRESS),
                new Row(TaskStatus.DONE, TaskStatus.IN_PROGRESS, null, TaskStatus.IN_PROGRESS),
                new Row(TaskStatus.DONE, TaskStatus.TODO, null, TaskStatus.TODO),
                new Row(TaskStatus.IN_PROGRESS, TaskStatus.DONE, null, TaskStatus.DONE),
                new Row(TaskStatus.TODO, null, true, TaskStatus.DONE),
                new Row(TaskStatus.IN_PROGRESS, null, true, TaskStatus.DONE),
                new Row(TaskStatus.DONE, null, true, TaskStatus.DONE),
                new Row(TaskStatus.DONE, null, false, TaskStatus.TODO),
                new Row(TaskStatus.TODO, null, false, TaskStatus.TODO),
                new Row(TaskStatus.IN_PROGRESS, null, false, TaskStatus.IN_PROGRESS),
                new Row(TaskStatus.TODO, TaskStatus.DONE, true, TaskStatus.DONE),
                new Row(TaskStatus.DONE, TaskStatus.IN_PROGRESS, false, TaskStatus.IN_PROGRESS),
                new Row(TaskStatus.IN_PROGRESS, TaskStatus.TODO, false, TaskStatus.TODO));
        for (Row row : rows) {
            Task task = stored(12L, row.from(), Priority.NONE);
            TaskResponse put = service.replace(OWNER, 12L, put(null, row.status(), row.done(), null));
            assertThat(put.status()).as("PUT %s", row).isEqualTo(row.expected());
            assertThat(put.done()).as("PUT %s", row).isEqualTo(row.expected() == TaskStatus.DONE);
            assertThat(task.isDone()).isEqualTo(task.getStatus() == TaskStatus.DONE);

            stored(12L, row.from(), Priority.NONE);
            TaskResponse patch = service.setStatus(OWNER, 12L, row.status(), row.done());
            if (row.status() != null || row.done() != null) {
                assertThat(patch.status()).as("PATCH %s", row).isEqualTo(row.expected());
                assertThat(patch.done()).as("PATCH %s", row).isEqualTo(row.expected() == TaskStatus.DONE);
                assertThat(patch.updatedAt()).isEqualTo(NOW_MICROS);
            }
        }
    }

    @Test
    void replaceWithTagsLocksTheOwnerFirstAndReturnsTheNewTags() {
        stored(12L, TaskStatus.TODO, Priority.NONE);
        when(tagService.replaceTags(OWNER, 12L, List.of(" Home ", "work"))).thenReturn(List.of("Home", "Work"));

        TaskResponse updated = service.replace(OWNER, 12L, put(null, null, null, List.of(" Home ", "work")));

        assertThat(updated.tags()).containsExactly("Home", "Work");
        InOrder order = inOrder(tagService, repository);
        order.verify(tagService).lockOwner(OWNER);
        order.verify(repository).findByIdAndOwnerId(12L, OWNER);
        order.verify(repository).saveAndFlush(any(Task.class));
        order.verify(tagService).replaceTags(OWNER, 12L, List.of(" Home ", "work"));
    }

    @Test
    void replaceWithEmptyTagsClearsThem() {
        stored(12L, TaskStatus.TODO, Priority.NONE);
        when(tagService.replaceTags(OWNER, 12L, List.of())).thenReturn(List.of());

        assertThat(service.replace(OWNER, 12L, put(null, null, null, List.of())).tags()).isEmpty();
        verify(tagService).replaceTags(OWNER, 12L, List.of());
    }

    @Test
    void setStatusNeverTouchesTagsOrPriority() {
        stored(12L, TaskStatus.TODO, Priority.HIGH);
        when(tagService.namesOf(OWNER, 12L)).thenReturn(List.of("Work"));

        TaskResponse patched = service.setStatus(OWNER, 12L, TaskStatus.IN_PROGRESS, null);

        assertThat(patched.priority()).isEqualTo(Priority.HIGH);
        assertThat(patched.tags()).containsExactly("Work");
        assertThat(patched.title()).isEqualTo("Buy milk");
        assertThat(patched.description()).isEqualTo("2%");
        verify(tagService, never()).lockOwner(anyLong());
        verify(tagService, never()).replaceTags(anyLong(), anyLong(), any());
    }

    @Test
    void deleteLocksRemovesTheTaskAndThenUnusedTags() {
        Task task = stored(12L, TaskStatus.TODO, Priority.NONE);
        when(tagService.tagIdsOf(12L)).thenReturn(List.of(5L, 7L));

        service.delete(OWNER, 12L);

        InOrder order = inOrder(tagService, repository);
        order.verify(tagService).lockOwner(OWNER);
        order.verify(tagService).tagIdsOf(12L);
        order.verify(repository).delete(task);
        order.verify(repository).flush();
        order.verify(tagService).deleteUnused(OWNER, List.of(5L, 7L));
    }

    @Test
    void missingOrForeignTaskThrowsNotFoundAndChangesNothing() {
        when(repository.findByIdAndOwnerId(42L, OWNER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(OWNER, 42L)).isInstanceOf(TaskNotFoundException.class);
        assertThatThrownBy(() -> service.replace(OWNER, 42L, put(Priority.HIGH, null, false, List.of("x"))))
                .isInstanceOf(TaskNotFoundException.class);
        assertThatThrownBy(() -> service.setStatus(OWNER, 42L, null, true)).isInstanceOf(TaskNotFoundException.class);
        assertThatThrownBy(() -> service.delete(OWNER, 42L)).isInstanceOf(TaskNotFoundException.class);

        verify(repository, never()).saveAndFlush(any());
        verify(repository, never()).delete(any());
        verify(tagService, never()).replaceTags(anyLong(), anyLong(), any());
        verify(tagService, never()).deleteUnused(anyLong(), any());
    }

    private static CannotAcquireLockException lockFailure(String sqlState, int errorCode) {
        return new CannotAcquireLockException("lock", new SQLException("lock", sqlState, errorCode));
    }

    @Test
    void replaceRunsTheWholeTransactionAgainAfterLosingADeadlock() {
        Task task = stored(12L, TaskStatus.TODO, Priority.NONE);
        when(repository.findByIdAndOwnerId(12L, OWNER)).thenThrow(lockFailure("40001", 1213))
                .thenReturn(Optional.of(task));
        when(tagService.replaceTags(OWNER, 12L, List.of("Work"))).thenReturn(List.of("Work"));

        assertThat(service.replace(OWNER, 12L, put(null, null, null, List.of("Work"))).tags()).containsExactly("Work");

        verify(tagService, times(2)).lockOwner(OWNER);
        verify(tagService, times(1)).replaceTags(OWNER, 12L, List.of("Work"));
    }

    @Test
    void deleteRetriesADeadlockAtMostThreeTimesInAll() {
        when(repository.findByIdAndOwnerId(12L, OWNER)).thenThrow(lockFailure("40001", 1213));

        assertThatThrownBy(() -> service.delete(OWNER, 12L)).isInstanceOf(CannotAcquireLockException.class);

        verify(repository, times(DeadlockRetry.MAX_ATTEMPTS)).findByIdAndOwnerId(12L, OWNER);
    }

    @Test
    void aLockWaitTimeoutIsNotRetried() {
        when(repository.findByIdAndOwnerId(12L, OWNER)).thenThrow(lockFailure("HY000", 1205));

        assertThatThrownBy(() -> service.replace(OWNER, 12L, put(null, null, null, List.of("Work"))))
                .isInstanceOf(CannotAcquireLockException.class);
        assertThatThrownBy(() -> service.delete(OWNER, 12L)).isInstanceOf(CannotAcquireLockException.class);

        verify(repository, times(2)).findByIdAndOwnerId(12L, OWNER);
    }
}
