package com.taskapp.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
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
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionOperations;

import com.taskapp.auth.UnauthenticatedException;
import com.taskapp.task.dto.CreateListRequest;
import com.taskapp.task.dto.RenameListRequest;
import com.taskapp.task.dto.TaskListDetailsResponse;
import com.taskapp.task.dto.TaskListResponse;

class ListServiceTest {

    private static final long OWNER = 7L;
    private static final Instant NOW = Instant.parse("2026-10-17T09:15:02.123456789Z");

    private final ListRepository lists = mock(ListRepository.class);
    private ListService service;

    @BeforeEach
    void setUp() {
        service = new ListService(lists, TransactionOperations.withoutTransaction(), Clock.fixed(NOW, ZoneOffset.UTC));
        when(lists.saveAndFlush(any(TaskList.class))).thenAnswer(inv -> {
            TaskList list = inv.getArgument(0);
            ReflectionTestUtils.setField(list, "id", 8L);
            return list;
        });
        when(lists.lockUser(OWNER)).thenReturn(Optional.of(OWNER));
        when(lists.shareUser(OWNER)).thenReturn(Optional.of(OWNER));
        when(lists.lockList(anyLong())).thenAnswer(inv -> Optional.of(inv.getArgument(0)));
    }

    private static TaskList list(long id, String name, boolean personal) {
        TaskList list = new TaskList(OWNER, name, personal, NOW);
        ReflectionTestUtils.setField(list, "id", id);
        return list;
    }

    @Test
    void createLocksTheUserThenCountsThenInserts() {
        when(lists.countByOwnerId(OWNER)).thenReturn(49L);

        TaskListResponse created = service.create(OWNER, new CreateListRequest("Home"));

        assertThat(created).isEqualTo(new TaskListResponse(8, "Home", false));
        InOrder order = inOrder(lists);
        order.verify(lists).lockUser(OWNER);
        order.verify(lists).countByOwnerId(OWNER);
        order.verify(lists).saveAndFlush(org.mockito.ArgumentMatchers.argThat(l -> l.getOwnerId() == OWNER
                && !l.isPersonal() && l.getName().equals("Home")
                && l.getCreatedAt().equals(Instant.parse("2026-10-17T09:15:02.123456Z"))));
    }

    @Test
    void the51stListIsRefusedAndNothingIsInserted() {
        when(lists.countByOwnerId(OWNER)).thenReturn(50L);

        assertThatThrownBy(() -> service.create(OWNER, new CreateListRequest("One more")))
                .isInstanceOf(ListLimitException.class)
                .hasMessage("You can have at most 50 lists");
        verify(lists, never()).saveAndFlush(any());
    }

    @Test
    void createForADeletedAccountIsUnauthenticated() {
        when(lists.lockUser(OWNER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(OWNER, new CreateListRequest("x")))
                .isInstanceOf(UnauthenticatedException.class);
        verify(lists, never()).saveAndFlush(any());
    }

    @Test
    void createRetriesADeadlock() {
        when(lists.countByOwnerId(OWNER)).thenThrow(
                new CannotAcquireLockException("x", new SQLException("x", "40001", 1213))).thenReturn(1L);

        service.create(OWNER, new CreateListRequest("x"));

        verify(lists, times(2)).lockUser(OWNER);
    }

    @Test
    void renameIsOneScopedUpdateAndZeroRowsIsNotFound() {
        when(lists.rename(OWNER, 8L, "Groceries")).thenReturn(1);
        when(lists.findById(8L)).thenReturn(Optional.of(list(8, "Groceries", false)));

        assertThat(service.rename(OWNER, 8L, new RenameListRequest("Groceries")))
                .isEqualTo(new TaskListResponse(8, "Groceries", false));

        when(lists.rename(OWNER, 9L, "x")).thenReturn(0);
        assertThatThrownBy(() -> service.rename(OWNER, 9L, new RenameListRequest("x")))
                .isInstanceOf(ListNotFoundException.class);
        verify(lists, never()).findById(9L);
    }

    @Test
    void deleteLocksUserThenListThenChecksThenDeletes() {
        when(lists.findByIdAndOwnerId(8L, OWNER)).thenReturn(Optional.of(list(8, "Home", false)));

        service.delete(OWNER, 8L);

        InOrder order = inOrder(lists);
        order.verify(lists).shareUser(OWNER);
        order.verify(lists).findByIdAndOwnerId(8L, OWNER);
        order.verify(lists).lockList(8L);
        order.verify(lists).deleteNow(8L);
    }

    @Test
    void deletingMyTasksIs409AndDeletesNothing() {
        when(lists.findByIdAndOwnerId(3L, OWNER)).thenReturn(Optional.of(list(3, "Renamed", true)));

        assertThatThrownBy(() -> service.delete(OWNER, 3L)).isInstanceOf(PersonalListException.class);
        verify(lists, never()).deleteNow(anyLong());
    }

    /** Another user's list (their "My tasks" included) is 404, never 409: the owner check comes first. */
    @Test
    void deletingAListNotMineOrGoneIs404() {
        when(lists.findByIdAndOwnerId(4L, OWNER)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.delete(OWNER, 4L)).isInstanceOf(ListNotFoundException.class);

        when(lists.findByIdAndOwnerId(8L, OWNER)).thenReturn(Optional.of(list(8, "Home", false)));
        when(lists.lockList(8L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.delete(OWNER, 8L)).isInstanceOf(ListNotFoundException.class);

        verify(lists, never()).deleteNow(anyLong());
    }

    @Test
    void getAddsTheTaskCount() {
        when(lists.findByIdAndOwnerId(8L, OWNER)).thenReturn(Optional.of(list(8, "Home", false)));
        when(lists.countTasks(8L)).thenReturn(12L);

        assertThat(service.get(OWNER, 8L)).isEqualTo(new TaskListDetailsResponse(8, "Home", false, 12));
        assertThatThrownBy(() -> service.get(OWNER, 9L)).isInstanceOf(ListNotFoundException.class);
    }

    @Test
    void listMapsTheRepositoryOrder() {
        when(lists.findAllOwned(OWNER)).thenReturn(List.of(list(3, "My tasks", true), list(8, "Home", false)));

        assertThat(service.list(OWNER)).containsExactly(new TaskListResponse(3, "My tasks", true),
                new TaskListResponse(8, "Home", false));
    }

    @Test
    void createPersonalMakesMyTasks() {
        TaskList created = service.createPersonal(OWNER);

        assertThat(created.getName()).isEqualTo("My tasks");
        assertThat(created.isPersonal()).isTrue();
        assertThat(created.getOwnerId()).isEqualTo(OWNER);
    }

    @Test
    void resolveMissingOrEmptyIsMyTasks() {
        when(lists.findByOwnerIdAndPersonalTrue(OWNER)).thenReturn(Optional.of(list(3, "My tasks", true)));

        assertThat(service.resolve(OWNER, null)).isEqualTo(3L);
        assertThat(service.resolve(OWNER, "")).isEqualTo(3L);
    }

    @Test
    void resolveAPlainIdOfMine() {
        when(lists.findByIdAndOwnerId(8L, OWNER)).thenReturn(Optional.of(list(8, "Home", false)));

        assertThat(service.resolve(OWNER, "8")).isEqualTo(8L);
        assertThat(service.resolve(OWNER, "0008")).isEqualTo(8L);
    }

    @ParameterizedTest
    @ValueSource(strings = {" ", "abc", "1.5", " 8", "8 ", "+8", "-8", "0", "8a", "0x8", "１", "99999999999999999999",
            "9", "1e3"})
    void resolveAnythingElseIs404WithoutTouchingOtherListsForNonNumbers(String raw) {
        when(lists.findByIdAndOwnerId(anyLong(), org.mockito.ArgumentMatchers.eq(OWNER))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolve(OWNER, raw)).isInstanceOf(ListNotFoundException.class);
        verify(lists, never()).findByOwnerIdAndPersonalTrue(anyLong());
    }

    @Test
    void nonNumericListNeverReachesTheDatabase() {
        assertThatThrownBy(() -> service.resolve(OWNER, "abc")).isInstanceOf(ListNotFoundException.class);
        verifyNoInteractions(lists);
        verify(lists, never()).rename(anyLong(), anyLong(), anyString());
    }
}
