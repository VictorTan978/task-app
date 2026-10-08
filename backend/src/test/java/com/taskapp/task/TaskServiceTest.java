package com.taskapp.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.taskapp.task.dto.CreateTaskRequest;
import com.taskapp.task.dto.ReplaceTaskRequest;
import com.taskapp.task.dto.TaskResponse;

class TaskServiceTest {

    private static final long OWNER = 7L;
    private static final Instant CREATED = Instant.parse("2026-10-01T18:00:00Z");
    /** Nanosecond precision on purpose: the service must truncate to what PostgreSQL stores. */
    private static final Instant NOW = Instant.parse("2026-10-08T09:15:02.123456789Z");
    private static final Instant NOW_MICROS = Instant.parse("2026-10-08T09:15:02.123456Z");

    private final TaskRepository repository = mock(TaskRepository.class);
    private TaskService service;

    @BeforeEach
    void setUp() {
        service = new TaskService(repository, Clock.fixed(NOW, ZoneOffset.UTC));
        when(repository.saveAndFlush(any(Task.class))).thenAnswer(inv -> {
            Task task = inv.getArgument(0);
            if (task.getId() == null) {
                ReflectionTestUtils.setField(task, "id", 12L);
            }
            return task;
        });
    }

    private static Task existing(long id) {
        Task task = new Task(OWNER, "Buy milk", "2%", LocalDate.parse("2026-10-10"), CREATED);
        ReflectionTestUtils.setField(task, "id", id);
        return task;
    }

    @Test
    void createIsNotDoneAndHasEqualTimestamps() {
        TaskResponse created = service.create(OWNER, new CreateTaskRequest("Buy milk", null, LocalDate.parse("2026-10-10")));

        assertThat(created).isEqualTo(new TaskResponse(12L, "Buy milk", null, false, LocalDate.parse("2026-10-10"),
                NOW_MICROS, NOW_MICROS));
        verify(repository).saveAndFlush(any(Task.class));
    }

    @Test
    void createStoresTheSessionOwner() {
        service.create(OWNER, new CreateTaskRequest("x", null, null));

        verify(repository).saveAndFlush(org.mockito.ArgumentMatchers.argThat(t -> t.getOwnerId() == OWNER));
    }

    @Test
    void listUsesTheQueryForEachFilter() {
        Task a = existing(1L);
        when(repository.findByOwnerIdOrderByDoneAscCreatedAtDescIdDesc(OWNER)).thenReturn(List.of(a));
        when(repository.findByOwnerIdAndDoneOrderByCreatedAtDescIdDesc(OWNER, false)).thenReturn(List.of());
        when(repository.findByOwnerIdAndDoneOrderByCreatedAtDescIdDesc(OWNER, true)).thenReturn(List.of(a, a));

        assertThat(service.list(OWNER, TaskFilter.ALL)).hasSize(1);
        assertThat(service.list(OWNER, TaskFilter.ACTIVE)).isEmpty();
        assertThat(service.list(OWNER, TaskFilter.DONE)).hasSize(2);
    }

    @Test
    void replaceOverwritesEveryEditableFieldAndClearsNulls() {
        Task task = existing(12L);
        when(repository.findByIdAndOwnerId(12L, OWNER)).thenReturn(Optional.of(task));

        TaskResponse updated = service.replace(OWNER, 12L, new ReplaceTaskRequest("Buy oat milk", null, null, true));

        assertThat(updated).isEqualTo(new TaskResponse(12L, "Buy oat milk", null, true, null, CREATED, NOW_MICROS));
    }

    @Test
    void setDoneSetsValueAndAlwaysBumpsUpdatedAt() {
        Task task = existing(12L);
        when(repository.findByIdAndOwnerId(12L, OWNER)).thenReturn(Optional.of(task));

        assertThat(service.setDone(OWNER, 12L, true).done()).isTrue();
        TaskResponse again = service.setDone(OWNER, 12L, true);
        assertThat(again.done()).isTrue();
        assertThat(again.updatedAt()).isEqualTo(NOW_MICROS);
        assertThat(again.title()).isEqualTo("Buy milk");
        assertThat(again.description()).isEqualTo("2%");
        assertThat(service.setDone(OWNER, 12L, false).done()).isFalse();
    }

    @Test
    void deleteRemovesOwnedTask() {
        Task task = existing(12L);
        when(repository.findByIdAndOwnerId(12L, OWNER)).thenReturn(Optional.of(task));

        service.delete(OWNER, 12L);

        verify(repository).delete(task);
    }

    @Test
    void missingOrForeignTaskThrowsNotFoundAndChangesNothing() {
        when(repository.findByIdAndOwnerId(42L, OWNER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(OWNER, 42L)).isInstanceOf(TaskNotFoundException.class);
        assertThatThrownBy(() -> service.replace(OWNER, 42L, new ReplaceTaskRequest("x", null, null, false)))
                .isInstanceOf(TaskNotFoundException.class);
        assertThatThrownBy(() -> service.setDone(OWNER, 42L, true)).isInstanceOf(TaskNotFoundException.class);
        assertThatThrownBy(() -> service.delete(OWNER, 42L)).isInstanceOf(TaskNotFoundException.class);

        verify(repository, never()).saveAndFlush(any());
        verify(repository, never()).delete(any());
    }
}
