package com.taskapp.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionOperations;

import com.taskapp.auth.EmailJobs;
import com.taskapp.common.error.ErrorCode;
import com.taskapp.security.ratelimit.InviteRateLimiter;
import com.taskapp.security.ratelimit.RateLimitedException;
import com.taskapp.task.MembershipRepository.InvitationRow;
import com.taskapp.task.MembershipRepository.MyInvitationTarget;
import com.taskapp.task.dto.CreateInvitationRequest;
import com.taskapp.task.dto.ListInvitationResponse;
import com.taskapp.task.dto.TaskListResponse;

/** Invitations (contract 0.13.0): check order, locks, limits, AC18 response, accept / decline / cancel. */
class InvitationServiceTest {

    private static final long OWNER = 7L;
    private static final long INVITEE = 9L;
    private static final long HOME = 8L;
    private static final String EMAIL = "carol@example.com";
    private static final Instant NOW = Instant.parse("2026-10-19T10:00:00.123456789Z");
    private static final Instant NOW_MICROS = Instant.parse("2026-10-19T10:00:00.123456Z");
    private static final Instant EXPIRES = NOW_MICROS.plus(Duration.ofDays(14));

    private final ListService listService = mock(ListService.class);
    private final ListRepository lists = mock(ListRepository.class);
    private final MembershipRepository members = mock(MembershipRepository.class);
    private final InviteRateLimiter rateLimiter = mock(InviteRateLimiter.class);
    private final EmailJobs emailJobs = mock(EmailJobs.class);
    private InvitationService service;

    private static TaskList list(long id, boolean personal) {
        TaskList list = new TaskList(OWNER, personal ? "My tasks" : "Home", personal, NOW);
        ReflectionTestUtils.setField(list, "id", id);
        return list;
    }

    @BeforeEach
    void setUp() {
        service = new InvitationService(listService, lists, members, rateLimiter, emailJobs,
                TransactionOperations.withoutTransaction(), Clock.fixed(NOW, ZoneOffset.UTC));
        when(listService.requireOwner(OWNER, HOME)).thenReturn(list(HOME, false));
        when(listService.shareUsers(anyLong(), any())).thenReturn(true);
        when(listService.lockList(anyLong())).thenReturn(true);
        when(listService.shareMember(anyLong(), anyLong())).thenReturn(true);
        when(lists.findById(HOME)).thenReturn(Optional.of(list(HOME, false)));
        when(members.displayName(OWNER)).thenReturn(Optional.of("Ana"));
        when(members.insertInvitation(HOME, EMAIL, NOW_MICROS, EXPIRES)).thenReturn(31L);
        when(members.findInvitationById(31L))
                .thenReturn(Optional.of(new InvitationRow(31L, HOME, EMAIL, NOW_MICROS, EXPIRES)));
        when(members.countMembers(HOME)).thenReturn(1);
    }

    private ListInvitationResponse invite() {
        return service.invite(OWNER, "ana@example.com", HOME, new CreateInvitationRequest(" Carol@Example.com "));
    }

    // ---- invite ----

    @Test
    void inviteChecksThenCountsThenLocksThenInsertsAndQueuesTheEmailAfterwards() {
        ListInvitationResponse created = invite();

        assertThat(created).isEqualTo(new ListInvitationResponse(31L, EMAIL, NOW_MICROS, EXPIRES));
        InOrder order = inOrder(listService, rateLimiter, members, emailJobs);
        order.verify(listService).requireOwner(OWNER, HOME);
        order.verify(rateLimiter).acquireInvite(OWNER, "ana@example.com");
        order.verify(listService).shareUsers(OWNER, List.of(OWNER));
        order.verify(listService).lockList(HOME);
        order.verify(listService).shareMember(HOME, OWNER);
        order.verify(members).isMemberEmail(HOME, EMAIL);
        order.verify(members).insertInvitation(HOME, EMAIL, NOW_MICROS, EXPIRES);
        order.verify(emailJobs).sendListInvitation(EMAIL, "Ana", "Home", Duration.ofDays(14));
    }

    @Test
    void myTasksCantBeSharedAndNothingIsCounted() {
        when(listService.requireOwner(OWNER, 3L)).thenReturn(list(3L, true));

        assertThatThrownBy(() -> service.invite(OWNER, "a", 3L, new CreateInvitationRequest(EMAIL)))
                .isInstanceOf(PersonalListException.class).hasMessage("Your personal list can't be shared");
        verifyNoInteractions(rateLimiter, members, emailJobs);
    }

    @Test
    void aMemberOrANonMemberIsRefusedBeforeTheRateLimit() {
        when(listService.requireOwner(INVITEE, HOME)).thenThrow(new OwnerOnlyException());
        when(listService.requireOwner(INVITEE, 99L)).thenThrow(new ListNotFoundException());

        assertThatThrownBy(() -> service.invite(INVITEE, "b", HOME, new CreateInvitationRequest(EMAIL)))
                .isInstanceOf(OwnerOnlyException.class);
        assertThatThrownBy(() -> service.invite(INVITEE, "b", 99L, new CreateInvitationRequest(EMAIL)))
                .isInstanceOf(ListNotFoundException.class);
        verifyNoInteractions(rateLimiter, members, emailJobs);
    }

    @Test
    void overTheRateLimitNothingIsLockedOrCreated() {
        doThrow(new RateLimitedException(Duration.ofMinutes(5), RateLimitedException.Limit.INVITE_USER, "a"))
                .when(rateLimiter).acquireInvite(anyLong(), anyString());

        assertThatThrownBy(this::invite).isInstanceOf(RateLimitedException.class);
        verify(listService, never()).lockList(anyLong());
        verifyNoInteractions(members, emailJobs);
    }

    @Test
    void refusalsComeInTheContractOrderAndSendNothing() {
        when(members.isMemberEmail(HOME, EMAIL)).thenReturn(true);
        when(members.findInvitation(HOME, EMAIL))
                .thenReturn(Optional.of(new InvitationRow(30L, HOME, EMAIL, NOW_MICROS, EXPIRES)));
        when(members.countMembers(HOME)).thenReturn(20);
        when(members.countPending(eq(HOME), any())).thenReturn(20);
        assertConflict(ErrorCode.ALREADY_MEMBER, "This person is already a member of the list");

        when(members.isMemberEmail(HOME, EMAIL)).thenReturn(false);
        assertConflict(ErrorCode.ALREADY_INVITED, "This email address already has a pending invitation");

        when(members.findInvitation(HOME, EMAIL)).thenReturn(Optional.empty());
        assertConflict(ErrorCode.MEMBER_LIMIT, "A list can have at most 20 members");

        when(members.countMembers(HOME)).thenReturn(19);
        assertConflict(ErrorCode.INVITATION_LIMIT, "A list can have at most 20 pending invitations");

        when(members.countPending(eq(HOME), any())).thenReturn(19);
        invite();
        verify(members).insertInvitation(HOME, EMAIL, NOW_MICROS, EXPIRES);
    }

    private void assertConflict(String code, String message) {
        assertThatThrownBy(this::invite).isInstanceOf(SharingConflictException.class).hasMessage(message)
                .extracting(e -> ((SharingConflictException) e).code()).isEqualTo(code);
        verify(members, never()).insertInvitation(anyLong(), anyString(), any(), any());
        verifyNoInteractions(emailJobs);
    }

    @Test
    void anExpiredInvitationIsRefreshedInPlace() {
        Instant old = NOW_MICROS.minus(Duration.ofDays(20));
        when(members.findInvitation(HOME, EMAIL))
                .thenReturn(Optional.of(new InvitationRow(30L, HOME, EMAIL, old, old.plus(Duration.ofDays(14)))));
        when(members.findInvitationById(30L))
                .thenReturn(Optional.of(new InvitationRow(30L, HOME, EMAIL, NOW_MICROS, EXPIRES)));

        assertThat(invite()).isEqualTo(new ListInvitationResponse(30L, EMAIL, NOW_MICROS, EXPIRES));
        verify(members).refreshInvitation(30L, NOW_MICROS, EXPIRES);
        verify(members, never()).insertInvitation(anyLong(), anyString(), any(), any());
    }

    /** An invitation expiring exactly now is expired (pending means expires_at > now). */
    @Test
    void anInvitationExpiringNowIsNoLongerPending() {
        when(members.findInvitation(HOME, EMAIL))
                .thenReturn(Optional.of(new InvitationRow(30L, HOME, EMAIL, NOW_MICROS.minus(Duration.ofDays(14)),
                        NOW_MICROS)));
        when(members.findInvitationById(30L))
                .thenReturn(Optional.of(new InvitationRow(30L, HOME, EMAIL, NOW_MICROS, EXPIRES)));

        invite();
        verify(members).refreshInvitation(30L, NOW_MICROS, EXPIRES);
    }

    @Test
    void theListDeletedBeforeTheLockIs404AndSendsNothing() {
        when(listService.lockList(HOME)).thenReturn(false);

        assertThatThrownBy(this::invite).isInstanceOf(ListNotFoundException.class);
        verifyNoInteractions(emailJobs);
    }

    // ---- cancel ----

    @Test
    void cancelDeletesTheListsInvitationByKeyAndRefusesAnotherListsOne() {
        when(members.findInvitationById(31L))
                .thenReturn(Optional.of(new InvitationRow(31L, HOME, EMAIL, NOW_MICROS, EXPIRES)));
        when(members.findInvitationById(40L))
                .thenReturn(Optional.of(new InvitationRow(40L, 77L, EMAIL, NOW_MICROS, EXPIRES)));

        service.cancel(OWNER, HOME, 31L);
        verify(members).deleteInvitation(31L);

        assertThatThrownBy(() -> service.cancel(OWNER, HOME, 40L)).isInstanceOf(InvitationNotFoundException.class);
        assertThatThrownBy(() -> service.cancel(OWNER, HOME, 41L)).isInstanceOf(InvitationNotFoundException.class);
        verify(members, never()).deleteInvitation(40L);
    }

    // ---- accept / decline ----

    private void invited() {
        when(members.findMine(eq(31L), eq(INVITEE), any())).thenReturn(Optional.of(new MyInvitationTarget(31L, HOME,
                OWNER)));
        when(listService.response(INVITEE, HOME))
                .thenReturn(new TaskListResponse(HOME, "Home", false, ListRole.MEMBER, 2));
    }

    @Test
    void acceptLocksInviteeAndOwnerThenTheListThenRereadsAndJoins() {
        invited();

        assertThat(service.accept(INVITEE, 31L))
                .isEqualTo(new TaskListResponse(HOME, "Home", false, ListRole.MEMBER, 2));
        InOrder order = inOrder(listService, members);
        order.verify(members).findMine(31L, INVITEE, NOW_MICROS);
        order.verify(listService).shareUsers(INVITEE, List.of(OWNER));
        order.verify(listService).lockList(HOME);
        order.verify(members).findMine(31L, INVITEE, NOW_MICROS);
        order.verify(members).countMembers(HOME);
        order.verify(members).insertMember(HOME, INVITEE, NOW_MICROS);
        order.verify(members).deleteInvitation(31L);
    }

    @Test
    void acceptWhenAlreadyAMemberOnlyDeletesTheInvitation() {
        invited();
        when(members.isMember(HOME, INVITEE)).thenReturn(true);

        service.accept(INVITEE, 31L);

        verify(members, never()).insertMember(anyLong(), anyLong(), any());
        verify(members).deleteInvitation(31L);
    }

    @Test
    void acceptAtTwentyMembersIs409AndKeepsTheInvitation() {
        invited();
        when(members.countMembers(HOME)).thenReturn(20);

        assertThatThrownBy(() -> service.accept(INVITEE, 31L)).isInstanceOf(SharingConflictException.class)
                .hasMessage("A list can have at most 20 members");
        verify(members, never()).insertMember(anyLong(), anyLong(), any());
        verify(members, never()).deleteInvitation(anyLong());
    }

    /** Not mine, unverified, expired, cancelled, declined: findMine finds nothing. All the same 404. */
    @Test
    void acceptAndDeclineOfAnythingButMyPendingInvitationAre404() {
        assertThatThrownBy(() -> service.accept(INVITEE, 31L)).isInstanceOf(InvitationNotFoundException.class)
                .hasMessage("This invitation is no longer valid");
        assertThatThrownBy(() -> service.decline(INVITEE, 31L)).isInstanceOf(InvitationNotFoundException.class);
        verify(listService, never()).lockList(anyLong());
    }

    /** Cancelled, expired or its list deleted between the first read and the lock. */
    @Test
    void acceptIs404WhenTheInvitationOrListWentAwayBeforeTheLock() {
        when(members.findMine(eq(31L), eq(INVITEE), any()))
                .thenReturn(Optional.of(new MyInvitationTarget(31L, HOME, OWNER)))
                .thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.accept(INVITEE, 31L)).isInstanceOf(InvitationNotFoundException.class);

        invited();
        when(listService.lockList(HOME)).thenReturn(false);
        assertThatThrownBy(() -> service.accept(INVITEE, 31L)).isInstanceOf(InvitationNotFoundException.class);
        verify(members, never()).insertMember(anyLong(), anyLong(), any());
    }

    @Test
    void declineDeletesTheInvitationUnderTheListLock() {
        invited();

        service.decline(INVITEE, 31L);

        InOrder order = inOrder(listService, members);
        order.verify(listService).lockList(HOME);
        order.verify(members).deleteInvitation(31L);
        verify(members, never()).insertMember(anyLong(), anyLong(), any());
    }
}
