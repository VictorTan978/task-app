package com.taskapp.task;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import com.taskapp.auth.EmailJobs;
import com.taskapp.security.ratelimit.InviteRateLimiter;
import com.taskapp.task.MembershipRepository.InvitationRow;
import com.taskapp.task.MembershipRepository.MyInvitationTarget;
import com.taskapp.task.dto.CreateInvitationRequest;
import com.taskapp.task.dto.InvitationCountResponse;
import com.taskapp.task.dto.ListInvitationResponse;
import com.taskapp.task.dto.MyInvitationResponse;
import com.taskapp.task.dto.TaskListResponse;

/**
 * Invitations (contract 0.13.0, specs/sharing-team-lists.md AC18-AC22). An invitation is stored by email address, the
 * same way whether or not an account has it; the request never looks an account up by that address (AC18), and the
 * email is queued after the commit ({@link EmailJobs#sendListInvitation}), which looks the account up in the
 * background. The invitee is matched by email (ignoring case and accents) whenever they read, accept or decline, and
 * only while their own email is verified.
 * <p>
 * Every write: a plain check first (404 / 403 / 409 in the contract's order), then one transaction that takes the
 * locks in the usual order (users rows FOR SHARE, the list row FOR UPDATE, the caller's membership where they need
 * one), then decides from plain reads made after the locks and writes by primary key only. Everything that changes a
 * list's members or invitations holds that list's row FOR UPDATE, so the limits are counted exactly, an accept and a
 * cancel (or an expiry) of the same invitation are ordered, and two accepts can't both take the 20th seat.
 */
@Service
public class InvitationService {

    /** Pending invitations of one list. */
    public static final int MAX_PENDING = 20;

    /** How long an invitation can be accepted. */
    public static final Duration LIFETIME = Duration.ofDays(14);

    private final ListService listService;
    private final ListRepository lists;
    private final MembershipRepository members;
    private final InviteRateLimiter rateLimiter;
    private final EmailJobs emailJobs;
    private final TransactionOperations transaction;
    private final Clock clock;

    /** {@code clock}: the application clock (expiry; tests move it). */
    @Autowired
    public InvitationService(ListService listService, ListRepository lists, MembershipRepository members,
            InviteRateLimiter rateLimiter, EmailJobs emailJobs, Clock clock, PlatformTransactionManager transactions) {
        this(listService, lists, members, rateLimiter, emailJobs, new TransactionTemplate(transactions), clock);
    }

    InvitationService(ListService listService, ListRepository lists, MembershipRepository members,
            InviteRateLimiter rateLimiter, EmailJobs emailJobs, TransactionOperations transaction, Clock clock) {
        this.listService = listService;
        this.lists = lists;
        this.members = members;
        this.rateLimiter = rateLimiter;
        this.emailJobs = emailJobs;
        this.transaction = transaction;
        this.clock = clock;
    }

    // ---- the owner's side ----

    /** {@code GET /api/lists/{id}/invitations}, owner only: the pending invitations, newest first. */
    @Transactional(readOnly = true)
    public List<ListInvitationResponse> pending(long userId, long listId) {
        listService.requireOwner(userId, listId);
        return members.pending(listId, now()).stream().map(ListInvitationResponse::from).toList();
    }

    /**
     * {@code POST /api/lists/{id}/invitations}, owner only. Order: 404 > 403 > 409 PERSONAL_LIST > 429 (counted from
     * here on) > 409 ALREADY_MEMBER > ALREADY_INVITED > MEMBER_LIMIT > INVITATION_LIMIT > created. An expired
     * invitation for the address is refreshed (same id). The response is the stored row; the email is queued after
     * the commit. {@code userEmail} is the caller's, for the rate-limit log only.
     */
    public ListInvitationResponse invite(long userId, String userEmail, long listId, CreateInvitationRequest request) {
        TaskList list = listService.requireOwner(userId, listId);
        if (list.isPersonal()) {
            throw PersonalListException.share();
        }
        rateLimiter.acquireInvite(userId, userEmail);
        String email = request.email();
        Created created = DeadlockRetry.run(() -> transaction.execute(status -> {
            lockOwnersList(userId, listId);
            Instant now = now();
            if (members.isMemberEmail(listId, email)) {
                throw SharingConflictException.alreadyMember();
            }
            Optional<InvitationRow> existing = members.findInvitation(listId, email);
            if (existing.isPresent() && existing.get().pendingAt(now)) {
                throw SharingConflictException.alreadyInvited();
            }
            if (members.countMembers(listId) >= ListService.MAX_MEMBERS) {
                throw SharingConflictException.memberLimit();
            }
            if (members.countPending(listId, now) >= MAX_PENDING) {
                throw SharingConflictException.invitationLimit();
            }
            Instant expires = now.plus(LIFETIME);
            long id;
            if (existing.isPresent()) {
                id = existing.get().id();
                members.refreshInvitation(id, now, expires);
            } else {
                id = members.insertInvitation(listId, email, now, expires);
            }
            InvitationRow row = members.findInvitationById(id).orElseThrow(ListNotFoundException::new);
            String listName = lists.findById(listId).map(TaskList::getName).orElseThrow(ListNotFoundException::new);
            String ownerName = members.displayName(userId).orElseThrow(ListNotFoundException::new);
            return new Created(row, ownerName, listName);
        }));
        emailJobs.sendListInvitation(created.row().email(), created.ownerName(), created.listName(), LIFETIME);
        return ListInvitationResponse.from(created.row());
    }

    /** {@code DELETE /api/lists/{id}/invitations/{invitationId}}, owner only; an expired one can be cancelled too. */
    public void cancel(long userId, long listId, long invitationId) {
        listService.requireOwner(userId, listId);
        DeadlockRetry.run(() -> transaction.execute(status -> {
            lockOwnersList(userId, listId);
            InvitationRow row = members.findInvitationById(invitationId)
                    .filter(found -> found.listId() == listId)
                    .orElseThrow(InvitationNotFoundException::new);
            members.deleteInvitation(row.id());
            return null;
        }));
    }

    // ---- the invitee's side ----

    /** {@code GET /api/invitations}: pending invitations to my verified email, newest first ([] if unverified). */
    @Transactional(readOnly = true)
    public List<MyInvitationResponse> mine(long userId) {
        return members.mine(userId, now()).stream().map(MyInvitationResponse::from).toList();
    }

    /** {@code GET /api/invitations/count}. */
    @Transactional(readOnly = true)
    public InvitationCountResponse count(long userId) {
        return new InvitationCountResponse(members.countMine(userId, now()));
    }

    /**
     * {@code POST /api/invitations/{id}/accept}: becomes a member (unless already one) and deletes the invitation.
     * 404 "no longer valid" for anything but a pending invitation to my verified email on an existing list; 409
     * MEMBER_LIMIT (invitation kept) when the list has 20 members, counted under the list's lock.
     */
    public TaskListResponse accept(long userId, long invitationId) {
        MyInvitationTarget target = findMine(userId, invitationId);
        return DeadlockRetry.run(() -> transaction.execute(status -> {
            long listId = lockInviteesList(userId, target);
            Instant now = now();
            if (!members.isMember(listId, userId)) {
                if (members.countMembers(listId) >= ListService.MAX_MEMBERS) {
                    throw SharingConflictException.memberLimit();
                }
                members.insertMember(listId, userId, now);
            }
            members.deleteInvitation(invitationId);
            return listService.response(userId, listId);
        }));
    }

    /** {@code POST /api/invitations/{id}/decline}: deletes the invitation. Same 404 cases as accept. */
    public void decline(long userId, long invitationId) {
        MyInvitationTarget target = findMine(userId, invitationId);
        DeadlockRetry.run(() -> transaction.execute(status -> {
            lockInviteesList(userId, target);
            members.deleteInvitation(invitationId);
            return null;
        }));
    }

    // ---- locks ----

    /** Users row FOR SHARE, the list FOR UPDATE, the owner's membership FOR SHARE; 404 when the list is gone. */
    private void lockOwnersList(long userId, long listId) {
        if (!listService.shareUsers(userId, List.of(userId)) || !listService.lockList(listId)
                || !listService.shareMember(listId, userId)) {
            throw new ListNotFoundException();
        }
    }

    /**
     * Users rows (invitee, owner) FOR SHARE, the list FOR UPDATE, then the invitation read again (first plain read of
     * the transaction, so it sees everything committed before the lock): still pending, still to my verified email.
     * Returns the list id. Anything gone: 404 "no longer valid".
     */
    private long lockInviteesList(long userId, MyInvitationTarget target) {
        if (!listService.shareUsers(userId, List.of(target.ownerId())) || !listService.lockList(target.listId())) {
            throw new InvitationNotFoundException();
        }
        MyInvitationTarget again = members.findMine(target.id(), userId, now())
                .orElseThrow(InvitationNotFoundException::new);
        return again.listId();
    }

    private MyInvitationTarget findMine(long userId, long invitationId) {
        return members.findMine(invitationId, userId, now()).orElseThrow(InvitationNotFoundException::new);
    }

    /** Microseconds match MySQL DATETIME(6). */
    private Instant now() {
        return Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
    }

    private record Created(InvitationRow row, String ownerName, String listName) {
    }
}
