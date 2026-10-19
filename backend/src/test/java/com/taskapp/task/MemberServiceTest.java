package com.taskapp.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionOperations;

import com.taskapp.task.MembershipRepository.MemberRow;
import com.taskapp.task.dto.MemberResponse;

/** Members (contract 0.13.0, AC25, AC26, AC28): 404 vs 403 vs 409 order, locks, primary-key writes. */
class MemberServiceTest {

    private static final long OWNER = 7L;
    private static final long MEMBER = 9L;
    private static final long HOME = 8L;
    private static final Instant JOINED = Instant.parse("2026-10-19T10:00:00Z");

    private final ListService listService = mock(ListService.class);
    private final MembershipRepository members = mock(MembershipRepository.class);
    private MemberService service;

    @BeforeEach
    void setUp() {
        service = new MemberService(listService, members, TransactionOperations.withoutTransaction());
        TaskList home = new TaskList(OWNER, "Home", false, JOINED);
        ReflectionTestUtils.setField(home, "id", HOME);
        when(listService.requireOwner(OWNER, HOME)).thenReturn(home);
        when(listService.requireOwner(MEMBER, HOME)).thenThrow(new OwnerOnlyException());
        when(listService.requireMember(OWNER, HOME)).thenReturn(home);
        when(listService.requireMember(MEMBER, HOME)).thenReturn(home);
        when(listService.requireMember(OWNER, 99L)).thenThrow(new ListNotFoundException());
        when(listService.requireOwner(OWNER, 99L)).thenThrow(new ListNotFoundException());
        when(listService.shareUsers(anyLong(), any())).thenReturn(true);
        when(listService.lockList(anyLong())).thenReturn(true);
        when(listService.shareMember(anyLong(), anyLong())).thenReturn(true);
        when(members.isMember(HOME, MEMBER)).thenReturn(true);
    }

    @Test
    void membersMapsRolesInTheRepositoryOrder() {
        when(members.members(HOME)).thenReturn(List.of(new MemberRow(OWNER, "Ana", "ana@x.test", true, JOINED),
                new MemberRow(MEMBER, "Bob", "bob@x.test", false, JOINED)));

        assertThat(service.members(MEMBER, HOME)).containsExactly(
                new MemberResponse(OWNER, "Ana", "ana@x.test", ListRole.OWNER, JOINED),
                new MemberResponse(MEMBER, "Bob", "bob@x.test", ListRole.MEMBER, JOINED));
        assertThatThrownBy(() -> service.members(OWNER, 99L)).isInstanceOf(ListNotFoundException.class);
    }

    @Test
    void removeLocksThenChecksThenDeletesByKey() {
        service.remove(OWNER, HOME, MEMBER);

        InOrder order = inOrder(listService, members);
        order.verify(listService).shareUsers(OWNER, List.of(OWNER));
        order.verify(listService).lockList(HOME);
        order.verify(listService).shareMember(HOME, OWNER);
        order.verify(members).isMember(HOME, MEMBER);
        order.verify(members).deleteMember(HOME, MEMBER);
    }

    @Test
    void removeOrderIs404Then403Then409ThenMemberNotFound() {
        assertThatThrownBy(() -> service.remove(OWNER, 99L, MEMBER)).isInstanceOf(ListNotFoundException.class);
        assertThatThrownBy(() -> service.remove(MEMBER, HOME, MEMBER)).isInstanceOf(OwnerOnlyException.class);
        assertThatThrownBy(() -> service.remove(OWNER, HOME, OWNER)).isInstanceOf(OwnerCannotLeaveException.class)
                .hasMessage("The owner can't leave the list");
        verifyNoInteractions(members);

        assertThatThrownBy(() -> service.remove(OWNER, HOME, 42L)).isInstanceOf(MemberNotFoundException.class)
                .hasMessage("Member not found");
        verify(members, never()).deleteMember(anyLong(), anyLong());
    }

    @Test
    void leaveDeletesTheCallersRowAndTheOwnerCantLeave() {
        service.leave(MEMBER, HOME);

        InOrder order = inOrder(listService, members);
        order.verify(listService).shareUsers(MEMBER, List.of(OWNER));
        order.verify(listService).lockList(HOME);
        order.verify(listService).shareMember(HOME, MEMBER);
        order.verify(members).deleteMember(HOME, MEMBER);

        assertThatThrownBy(() -> service.leave(OWNER, HOME)).isInstanceOf(OwnerCannotLeaveException.class);
        verify(members, never()).deleteMember(HOME, OWNER);
    }

    /** Removed, left or the list deleted between the check and the lock: 404, nothing deleted. */
    @Test
    void leaveAfterLosingTheMembershipMeanwhileIs404() {
        when(listService.shareMember(HOME, MEMBER)).thenReturn(false);
        assertThatThrownBy(() -> service.leave(MEMBER, HOME)).isInstanceOf(ListNotFoundException.class);

        when(listService.lockList(HOME)).thenReturn(false);
        assertThatThrownBy(() -> service.remove(OWNER, HOME, MEMBER)).isInstanceOf(ListNotFoundException.class);
        verify(members, never()).deleteMember(anyLong(), anyLong());
    }
}
