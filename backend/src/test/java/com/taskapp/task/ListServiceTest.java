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

    private static final long MEMBER = 9L;

    private final ListRepository lists = mock(ListRepository.class);
    private final MembershipRepository members = mock(MembershipRepository.class);
    private ListService service;

    @BeforeEach
    void setUp() {
        service = new ListService(lists, members, TransactionOperations.withoutTransaction(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        when(lists.saveAndFlush(any(TaskList.class))).thenAnswer(inv -> {
            TaskList list = inv.getArgument(0);
            ReflectionTestUtils.setField(list, "id", 8L);
            return list;
        });
        when(lists.lockUser(OWNER)).thenReturn(Optional.of(OWNER));
        when(lists.shareUser(OWNER)).thenReturn(Optional.of(OWNER));
        when(lists.lockList(anyLong())).thenAnswer(inv -> Optional.of(inv.getArgument(0)));
        // The owner's "My tasks" (3) and "Home" (8); MEMBER is a member of 8 too.
        when(lists.findAccessible(3L, OWNER)).thenReturn(Optional.of(list(3, "My tasks", true)));
        when(lists.findAccessible(8L, OWNER)).thenReturn(Optional.of(list(8, "Home", false)));
        when(lists.findAccessible(8L, MEMBER)).thenReturn(Optional.of(list(8, "Home", false)));
        when(members.countMembers(anyLong())).thenReturn(1);
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

        assertThat(created).isEqualTo(new TaskListResponse(8, "Home", false, ListRole.OWNER, 1));
        InOrder order = inOrder(lists, members);
        order.verify(lists).lockUser(OWNER);
        order.verify(lists).countByOwnerId(OWNER);
        order.verify(lists).saveAndFlush(org.mockito.ArgumentMatchers.argThat(l -> l.getOwnerId() == OWNER
                && !l.isPersonal() && l.getName().equals("Home")
                && l.getCreatedAt().equals(Instant.parse("2026-10-17T09:15:02.123456Z"))));
        order.verify(members).insertMember(8L, OWNER, Instant.parse("2026-10-17T09:15:02.123456Z"));
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
        when(members.countMembers(8L)).thenReturn(3);

        assertThat(service.rename(OWNER, 8L, new RenameListRequest("Groceries")))
                .isEqualTo(new TaskListResponse(8, "Groceries", false, ListRole.OWNER, 3));

        // Passed the check, then deleted meanwhile: the scoped UPDATE matches 0 rows.
        when(lists.findAccessible(9L, OWNER)).thenReturn(Optional.of(list(9, "Gone", false)));
        when(lists.rename(OWNER, 9L, "x")).thenReturn(0);
        assertThatThrownBy(() -> service.rename(OWNER, 9L, new RenameListRequest("x")))
                .isInstanceOf(ListNotFoundException.class);
        verify(lists, never()).findById(9L);
    }

    @Test
    void renameSharesTheUserRowFirstAndAGoneAccountIsNotFound() {
        when(lists.rename(OWNER, 8L, "x")).thenReturn(1);
        when(lists.findById(8L)).thenReturn(Optional.of(list(8, "x", false)));
        service.rename(OWNER, 8L, new RenameListRequest("x"));
        InOrder order = inOrder(lists);
        order.verify(lists).shareUser(OWNER);
        order.verify(lists).rename(OWNER, 8L, "x");

        when(lists.shareUser(OWNER)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.rename(OWNER, 8L, new RenameListRequest("y")))
                .isInstanceOf(ListNotFoundException.class);
        verify(lists, never()).rename(OWNER, 8L, "y");
    }

    @Test
    void renameRetriesADeadlock() {
        when(lists.rename(OWNER, 8L, "x")).thenThrow(
                new CannotAcquireLockException("x", new SQLException("x", "40001", 1213))).thenReturn(1);
        when(lists.findById(8L)).thenReturn(Optional.of(list(8, "x", false)));

        assertThat(service.rename(OWNER, 8L, new RenameListRequest("x")).name()).isEqualTo("x");
        verify(lists, times(2)).shareUser(OWNER);
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

    /** Another user's list (their "My tasks" included) is 404, never 409: the membership check comes first. */
    @Test
    void deletingAListNotMineOrGoneIs404() {
        assertThatThrownBy(() -> service.delete(OWNER, 4L)).isInstanceOf(ListNotFoundException.class);
        verify(lists, never()).shareUser(anyLong());

        when(lists.findByIdAndOwnerId(8L, OWNER)).thenReturn(Optional.of(list(8, "Home", false)));
        when(lists.lockList(8L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.delete(OWNER, 8L)).isInstanceOf(ListNotFoundException.class);

        verify(lists, never()).deleteNow(anyLong());
    }

    @Test
    void getAddsTheTaskCount() {
        when(lists.findByIdAndOwnerId(8L, OWNER)).thenReturn(Optional.of(list(8, "Home", false)));
        when(lists.countTasks(8L)).thenReturn(12L);

        when(members.countMembers(8L)).thenReturn(3);
        assertThat(service.get(OWNER, 8L))
                .isEqualTo(new TaskListDetailsResponse(8, "Home", false, ListRole.OWNER, 3, 12));
        assertThat(service.get(MEMBER, 8L))
                .isEqualTo(new TaskListDetailsResponse(8, "Home", false, ListRole.MEMBER, 3, 12));
        assertThatThrownBy(() -> service.get(OWNER, 9L)).isInstanceOf(ListNotFoundException.class);
    }

    @Test
    void listMapsTheRepositoryOrder() {
        TaskList shared = new TaskList(MEMBER, "Team", false, NOW);
        ReflectionTestUtils.setField(shared, "id", 21L);
        when(lists.findAllForMember(OWNER)).thenReturn(List.of(list(3, "My tasks", true), shared, list(8, "Home", false)));
        when(members.countMembers(List.of(3L, 21L, 8L))).thenReturn(java.util.Map.of(3L, 1, 21L, 5, 8L, 2));

        assertThat(service.list(OWNER)).containsExactly(new TaskListResponse(3, "My tasks", true, ListRole.OWNER, 1),
                new TaskListResponse(21, "Team", false, ListRole.MEMBER, 5),
                new TaskListResponse(8, "Home", false, ListRole.OWNER, 2));
    }

    @Test
    void createPersonalMakesMyTasksWithTheOwnersMembership() {
        TaskList created = service.createPersonal(OWNER);

        assertThat(created.getName()).isEqualTo("My tasks");
        assertThat(created.isPersonal()).isTrue();
        assertThat(created.getOwnerId()).isEqualTo(OWNER);
        verify(members).insertMember(8L, OWNER, Instant.parse("2026-10-17T09:15:02.123456Z"));
    }

    // ---- 0.13.0: owner-only actions and locks ----

    @Test
    void renameAndDeleteByAMemberAre403AndTouchNothing() {
        assertThatThrownBy(() -> service.rename(MEMBER, 8L, new RenameListRequest("x")))
                .isInstanceOf(OwnerOnlyException.class).hasMessage("Only the list owner can do this");
        assertThatThrownBy(() -> service.delete(MEMBER, 8L)).isInstanceOf(OwnerOnlyException.class);
        verify(lists, never()).shareUser(anyLong());
        verify(lists, never()).rename(anyLong(), anyLong(), anyString());
        verify(lists, never()).deleteNow(anyLong());
    }

    @Test
    void requireOwnerIs404ForANonMemberAnd403ForAMember() {
        assertThat(service.requireOwner(OWNER, 8L).getId()).isEqualTo(8L);
        assertThatThrownBy(() -> service.requireOwner(MEMBER, 8L)).isInstanceOf(OwnerOnlyException.class);
        assertThatThrownBy(() -> service.requireOwner(MEMBER, 3L)).isInstanceOf(ListNotFoundException.class);
    }

    @Test
    void shareUsersLocksCallerAndOwnersOnceEachInAscendingId() {
        when(lists.shareUser(anyLong())).thenAnswer(inv -> Optional.of(inv.getArgument(0)));

        assertThat(service.shareUsers(9L, List.of(12L, 4L, 9L, 12L))).isTrue();

        InOrder order = inOrder(lists);
        order.verify(lists).shareUser(4L);
        order.verify(lists).shareUser(9L);
        order.verify(lists).shareUser(12L);
        verify(lists, times(3)).shareUser(anyLong());
    }

    @Test
    void shareUsersIsFalseOnlyWhenTheCallerIsGone() {
        when(lists.shareUser(4L)).thenReturn(Optional.empty());
        when(lists.shareUser(9L)).thenReturn(Optional.of(9L));
        assertThat(service.shareUsers(9L, List.of(4L))).isTrue();

        when(lists.shareUser(9L)).thenReturn(Optional.empty());
        assertThat(service.shareUsers(9L, List.of(4L))).isFalse();
    }

    @Test
    void accountDeletionLocksEveryTouchedListAscendingThenDeletesInvitationsByKey() {
        when(members.listsTouchedBy(OWNER, "a@x.test")).thenReturn(List.of(3L, 8L, 21L));
        when(members.invitationIdsFor("a@x.test")).thenReturn(List.of(31L, 40L));

        service.lockForAccountDeletion(OWNER, "a@x.test");

        InOrder order = inOrder(lists, members);
        order.verify(lists).lockList(3L);
        order.verify(lists).lockList(8L);
        order.verify(lists).lockList(21L);
        order.verify(members).deleteInvitation(31L);
        order.verify(members).deleteInvitation(40L);
    }

    @Test
    void resolveMissingOrEmptyIsMyTasks() {
        when(lists.findByOwnerIdAndPersonalTrue(OWNER)).thenReturn(Optional.of(list(3, "My tasks", true)));

        assertThat(service.resolve(OWNER, null)).isEqualTo(3L);
        assertThat(service.resolve(OWNER, "")).isEqualTo(3L);
    }

    @Test
    void resolveAPlainIdOfMine() {
        assertThat(service.resolve(OWNER, "8")).isEqualTo(8L);
        assertThat(service.resolve(MEMBER, "8")).isEqualTo(8L);
        assertThat(service.resolve(OWNER, "0008")).isEqualTo(8L);
    }

    @ParameterizedTest
    @ValueSource(strings = {" ", "abc", "1.5", " 8", "8 ", "+8", "-8", "0", "8a", "0x8", "１", "99999999999999999999",
            "9", "1e3"})
    void resolveAnythingElseIs404WithoutTouchingOtherListsForNonNumbers(String raw) {
        when(lists.findAccessible(anyLong(), org.mockito.ArgumentMatchers.eq(OWNER))).thenReturn(Optional.empty());

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
