package com.taskapp.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import com.taskapp.task.dto.CreateTaskRequest;
import com.taskapp.task.dto.ReplaceTaskRequest;
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
        service = new TaskService(repository, tagService, Clock.fixed(NOW, ZoneOffset.UTC));
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

    @Test
    void listUsesTheQueryForEachFilterAndLoadsTagsOnce() {
        Task a = existing(1L);
        Task b = existing(2L);
        when(repository.findByOwnerIdOrderByDoneAscCreatedAtDescIdDesc(OWNER)).thenReturn(List.of(a, b));
        when(repository.findByOwnerIdAndDoneOrderByCreatedAtDescIdDesc(OWNER, false)).thenReturn(List.of());
        when(repository.findByOwnerIdAndDoneOrderByCreatedAtDescIdDesc(OWNER, true)).thenReturn(List.of(a));
        when(repository.findByOwnerIdAndStatusOrderByCreatedAtDescIdDesc(OWNER, TaskStatus.IN_PROGRESS))
                .thenReturn(List.of(b));
        when(tagService.namesByTask(OWNER)).thenReturn(Map.of(1L, List.of("Home", "Work")));

        List<TaskResponse> all = service.list(OWNER, TaskFilter.ALL, null);
        assertThat(all).extracting(TaskResponse::tags).containsExactly(List.of("Home", "Work"), List.of());
        assertThat(service.list(OWNER, TaskFilter.ACTIVE, null)).isEmpty();
        assertThat(service.list(OWNER, TaskFilter.DONE, " ")).hasSize(1);
        assertThat(service.list(OWNER, TaskFilter.IN_PROGRESS, "")).extracting(TaskResponse::id).containsExactly(2L);

        // One tag query per non-empty list, never one per task; none for an empty list.
        verify(tagService, times(3)).namesByTask(OWNER);
        verify(tagService, never()).namesOf(anyLong(), anyLong());
        verify(tagService, never()).findTagId(anyLong(), any());
    }

    @Test
    void listWithTagFiltersByTheOwnersTagAndTheFilterStatuses() {
        Task a = existing(1L);
        when(tagService.findTagId(OWNER, " work ")).thenReturn(Optional.of(55L));
        when(repository.findByOwnerIdAndTagId(OWNER, 55L, List.of("TODO", "IN_PROGRESS"))).thenReturn(List.of(a));
        when(repository.findByOwnerIdAndTagId(OWNER, 55L, List.of("TODO", "IN_PROGRESS", "DONE"))).thenReturn(List.of(a));
        when(repository.findByOwnerIdAndTagId(OWNER, 55L, List.of("IN_PROGRESS"))).thenReturn(List.of());
        when(repository.findByOwnerIdAndTagId(OWNER, 55L, List.of("DONE"))).thenReturn(List.of());
        when(tagService.namesByTask(OWNER)).thenReturn(Map.of(1L, List.of("Work")));

        assertThat(service.list(OWNER, TaskFilter.ACTIVE, " work ")).extracting(TaskResponse::tags)
                .containsExactly(List.of("Work"));
        assertThat(service.list(OWNER, TaskFilter.ALL, " work ")).hasSize(1);
        assertThat(service.list(OWNER, TaskFilter.IN_PROGRESS, " work ")).isEmpty();
        assertThat(service.list(OWNER, TaskFilter.DONE, " work ")).isEmpty();
        verify(repository, never()).findByOwnerIdOrderByDoneAscCreatedAtDescIdDesc(anyLong());
    }

    @Test
    void listWithUnknownOrInvalidTagIsEmpty() {
        when(tagService.findTagId(anyLong(), any())).thenReturn(Optional.empty());

        assertThat(service.list(OWNER, TaskFilter.ALL, "nope")).isEmpty();
        assertThat(service.list(OWNER, TaskFilter.ALL, "a,b")).isEmpty();

        verify(repository, never()).findByOwnerIdAndTagId(anyLong(), anyLong(), any());
        verify(repository, never()).findByOwnerIdOrderByDoneAscCreatedAtDescIdDesc(anyLong());
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

        service.delete(OWNER, 12L);

        InOrder order = inOrder(tagService, repository);
        order.verify(tagService).lockOwner(OWNER);
        order.verify(repository).delete(task);
        order.verify(repository).flush();
        order.verify(tagService).deleteUnused(OWNER);
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
        verify(tagService, never()).deleteUnused(anyLong());
    }
}
