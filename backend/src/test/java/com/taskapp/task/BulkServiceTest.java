package com.taskapp.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.support.TransactionOperations;

import com.taskapp.auth.InvalidFieldsException;
import com.taskapp.reminder.ReminderService;
import com.taskapp.task.dto.BulkAction;

/** Lookup, lock order and retry of {@link BulkService}; the writes are covered end to end in BulkIntegrationTest. */
class BulkServiceTest {

    private static final long USER = 7L;

    private final TaskRepository repository = mock(TaskRepository.class);
    private final ListService lists = mock(ListService.class);
    private final TaskService tasks = mock(TaskService.class);
    private final BulkService service = new BulkService(repository, mock(TagService.class), lists,
            mock(ReminderService.class), tasks, TransactionOperations.withoutTransaction(),
            Clock.fixed(Instant.parse("2026-10-20T08:00:00Z"), ZoneOffset.UTC));

    private static TaskRepository.IdList row(long id, long list) {
        return new TaskRepository.IdList() {
            @Override
            public Long getId() {
                return id;
            }

            @Override
            public Long getListId() {
                return list;
            }
        };
    }

    private static TaskList list(long id, long owner) {
        TaskList list = mock(TaskList.class);
        when(list.getId()).thenReturn(id);
        when(list.getOwnerId()).thenReturn(owner);
        return list;
    }

    private static final BulkRequests.Parsed COMPLETE = new BulkRequests.Parsed(BulkAction.COMPLETE, null, null, null,
            null);

    @Test
    void everyUnavailableIdIsReportedAscendingBeforeAnyLock() {
        when(repository.findAccessibleIds(anyCollection(), anyLong())).thenReturn(List.of(row(5, 1)));

        assertThatThrownBy(() -> service.bulk(USER, List.of(9L, 5L, 2L), COMPLETE))
                .isInstanceOfSatisfying(BulkTasksNotFoundException.class,
                        e -> assertThat(e.failedIds()).containsExactly(2L, 9L));
        verifyNoInteractions(tasks);
        verify(lists, times(0)).lockList(anyLong());
    }

    @Test
    void tasksOfTwoListsAreMixedListsAndNothingIsLocked() {
        when(repository.findAccessibleIds(anyCollection(), anyLong())).thenReturn(List.of(row(1, 10), row(2, 11)));

        assertThatThrownBy(() -> service.bulk(USER, List.of(1L, 2L), COMPLETE))
                .isInstanceOf(InvalidFieldsException.class);
        verify(lists, times(0)).shareUsers(anyLong(), any());
        verify(repository, times(0)).lockLive(any(), any());
    }

    @Test
    void locksUsersThenListsAscendingThenMembershipsThenTheTaskRowsInOneStatement() {
        TaskList source = list(10, 3);
        TaskList target = list(4, 8);
        when(repository.findAccessibleIds(anyCollection(), anyLong())).thenReturn(List.of(row(1, 10)));
        when(lists.requireMember(USER, 4)).thenReturn(target);
        when(lists.findMember(USER, 10)).thenReturn(Optional.of(source));
        when(lists.shareUsers(anyLong(), any())).thenReturn(true);
        when(lists.lockList(anyLong())).thenReturn(true);
        when(lists.shareMember(anyLong(), anyLong())).thenReturn(true);
        Task task = mock(Task.class);
        when(task.getId()).thenReturn(1L);
        when(task.getListId()).thenReturn(10L);
        when(repository.lockLive(any(), any())).thenReturn(List.of(task));
        when(tasks.results(anyLong(), any())).thenReturn(List.of());

        service.bulk(USER, List.of(1L), new BulkRequests.Parsed(BulkAction.MOVE, null, null, null, 4L));

        InOrder order = inOrder(lists, repository);
        order.verify(lists).shareUsers(USER, java.util.Set.of(3L, 8L));
        order.verify(lists).lockList(4);
        order.verify(lists).lockList(10);
        order.verify(lists).shareMember(4, USER);
        order.verify(lists).shareMember(10, USER);
        order.verify(repository).lockLive(any(), any());
    }

    @Test
    void aTaskThatVanishesBetweenTheReadAndTheLocksRestartsThreeTimesThenIsNotFound() {
        TaskList source = list(10, 3);
        when(repository.findAccessibleIds(anyCollection(), anyLong())).thenReturn(List.of(row(1, 10)));
        when(lists.findMember(USER, 10)).thenReturn(Optional.of(source));
        when(lists.shareUsers(anyLong(), any())).thenReturn(true);
        when(lists.shareList(anyLong())).thenReturn(true);
        when(lists.shareMember(anyLong(), anyLong())).thenReturn(true);
        when(repository.lockLive(any(), any())).thenReturn(List.of());

        assertThatThrownBy(() -> service.bulk(USER, List.of(1L), COMPLETE))
                .isInstanceOfSatisfying(BulkTasksNotFoundException.class,
                        e -> assertThat(e.failedIds()).containsExactly(1L));
        verify(repository, times(TaskService.MAX_LOCATE_ATTEMPTS)).lockLive(any(), any());
        verifyNoInteractions(tasks);
    }
}
