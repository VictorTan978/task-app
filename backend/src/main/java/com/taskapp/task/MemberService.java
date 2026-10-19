package com.taskapp.task;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import com.taskapp.task.dto.MemberResponse;

/**
 * Members of a list (contract 0.13.0, specs/sharing-team-lists.md AC25, AC26, AC28). A list the caller is not a member
 * of is a 404, never 403. Writes first check with a plain read (404 / 403 / 409), then run one transaction that takes
 * the locks in the usual order (users rows, the list row FOR UPDATE, then the rows) and decides from plain reads made
 * after the locks, writing by primary key only (see {@link MembershipRepository}). Removing or leaving takes the list
 * FOR UPDATE, so writes of the member in progress on that list finish first, and later ones fail the membership
 * re-check (404).
 */
@Service
public class MemberService {

    private final ListService listService;
    private final MembershipRepository members;
    private final TransactionOperations transaction;

    @Autowired
    public MemberService(ListService listService, MembershipRepository members,
            PlatformTransactionManager transactions) {
        this(listService, members, new TransactionTemplate(transactions));
    }

    MemberService(ListService listService, MembershipRepository members, TransactionOperations transaction) {
        this.listService = listService;
        this.members = members;
        this.transaction = transaction;
    }

    /** {@code GET /api/lists/{id}/members}: any member; the owner first, then by display name, then id. */
    @Transactional(readOnly = true)
    public List<MemberResponse> members(long userId, long listId) {
        listService.requireMember(userId, listId);
        return members.members(listId).stream().map(MemberResponse::from).toList();
    }

    /**
     * {@code DELETE /api/lists/{id}/members/{userId}}, owner only. Order: 404 not a member > 403 a member >
     * 409 the target is the owner > 404 MEMBER_NOT_FOUND > done. Their tasks stay.
     */
    public void remove(long userId, long listId, long memberId) {
        TaskList list = listService.requireOwner(userId, listId);
        if (memberId == list.getOwnerId()) {
            throw new OwnerCannotLeaveException();
        }
        DeadlockRetry.run(() -> transaction.execute(status -> {
            lockForChange(userId, list);
            if (!members.isMember(listId, memberId)) {
                throw new MemberNotFoundException();
            }
            members.deleteMember(listId, memberId);
            return null;
        }));
    }

    /** {@code POST /api/lists/{id}/leave}: a member (not the owner) leaves. Their tasks stay. */
    public void leave(long userId, long listId) {
        TaskList list = listService.requireMember(userId, listId);
        if (list.getOwnerId() == userId) {
            throw new OwnerCannotLeaveException();
        }
        DeadlockRetry.run(() -> transaction.execute(status -> {
            lockForChange(userId, list);
            members.deleteMember(listId, userId);
            return null;
        }));
    }

    /**
     * Users rows (caller, owner) FOR SHARE, the list FOR UPDATE, then the caller's membership FOR SHARE: 404 when the
     * list or the caller's membership is gone meanwhile.
     */
    private void lockForChange(long userId, TaskList list) {
        if (!listService.shareUsers(userId, List.of(list.getOwnerId())) || !listService.lockList(list.getId())
                || !listService.shareMember(list.getId(), userId)) {
            throw new ListNotFoundException();
        }
    }
}
