package com.taskapp.task;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import com.taskapp.auth.UnauthenticatedException;
import com.taskapp.task.dto.CreateListRequest;
import com.taskapp.task.dto.RenameListRequest;
import com.taskapp.task.dto.TaskListDetailsResponse;
import com.taskapp.task.dto.TaskListResponse;

/**
 * Lists (contract 0.12.0; shared lists 0.13.0, specs/sharing-team-lists.md). The user id always comes from the
 * session. A list the user is not a member of is reported as {@link ListNotFoundException} (404), never 403; a member
 * trying an owner-only action gets {@link OwnerOnlyException} (403). Owners are members of their own lists (a
 * {@code list_members} row inserted with the list).
 * <p>
 * Locks follow the order: {@code users} rows (the caller's, plus the owners' of the lists touched, ascending id), then
 * {@code lists} rows (ascending id), then the caller's membership row, then task / tag / link / member / invitation
 * rows (see {@link ListRepository}).
 */
@Service
public class ListService {

    /** Lists a user can own, "My tasks" included. Lists shared with them don't count. */
    public static final int MAX_LISTS = 50;

    /** Members of one list, the owner included. */
    public static final int MAX_MEMBERS = 20;

    /** A plain decimal integer that fits a long (18 digits always do). Anything else in {@code ?list=} is a 404. */
    private static final Pattern PLAIN_ID = Pattern.compile("[0-9]{1,18}");

    private final ListRepository lists;
    private final MembershipRepository members;
    private final TransactionOperations transaction;
    private final Clock clock;

    @Autowired
    public ListService(ListRepository lists, MembershipRepository members, PlatformTransactionManager transactions) {
        this(lists, members, new TransactionTemplate(transactions), Clock.systemUTC());
    }

    ListService(ListRepository lists, MembershipRepository members, TransactionOperations transaction, Clock clock) {
        this.lists = lists;
        this.members = members;
        this.transaction = transaction;
        this.clock = clock;
    }

    /** {@code GET /api/lists}: every list the user is a member of, "My tasks" first, then by name, then id. */
    @Transactional(readOnly = true)
    public List<TaskListResponse> list(long userId) {
        List<TaskList> mine = lists.findAllForMember(userId);
        Map<Long, Integer> counts = members.countMembers(mine.stream().map(TaskList::getId).toList());
        return mine.stream()
                .map(list -> TaskListResponse.from(list, ListRole.of(list, userId),
                        counts.getOrDefault(list.getId(), 1)))
                .toList();
    }

    /** {@code GET /api/lists/{id}}: the list with its number of tasks (every status). Any member. */
    @Transactional(readOnly = true)
    public TaskListDetailsResponse get(long userId, long listId) {
        TaskList list = requireMember(userId, listId);
        return TaskListDetailsResponse.from(list, ListRole.of(list, userId), members.countMembers(list.getId()),
                lists.countTasks(list.getId()));
    }

    /** The list as {@code GET /api/lists} shows it to {@code userId}. Plain reads. */
    @Transactional(readOnly = true)
    public TaskListResponse response(long userId, long listId) {
        TaskList list = lists.findById(listId).orElseThrow(ListNotFoundException::new);
        return TaskListResponse.from(list, ListRole.of(list, userId), members.countMembers(listId));
    }

    /**
     * {@code POST /api/lists}. The user's row is locked FOR UPDATE before counting, so two creates racing at the limit
     * can never make 51 lists: the second one counts after the first has committed. The owner's membership row is
     * inserted with the list. Run again if it lost a deadlock.
     */
    public TaskListResponse create(long userId, CreateListRequest request) {
        return DeadlockRetry.run(() -> transaction.execute(status -> {
            if (lists.lockUser(userId).isEmpty()) {
                throw new UnauthenticatedException();
            }
            if (lists.countByOwnerId(userId) >= MAX_LISTS) {
                throw new ListLimitException();
            }
            Instant now = now();
            TaskList list = lists.saveAndFlush(new TaskList(userId, request.name(), false, now));
            members.insertMember(list.getId(), userId, now);
            return TaskListResponse.from(list, ListRole.OWNER, 1);
        }));
    }

    /**
     * {@code PATCH /api/lists/{id}}, owner only: first a plain check (404 not a member, 403 a member), then users row
     * FOR SHARE and one UPDATE scoped by owner; 0 rows (deleted meanwhile) is a 404. The users row comes first like
     * every other list write: an account delete (X on that row, then the cascade into {@code lists}) otherwise
     * deadlocked with the UPDATE on {@code ix_lists_owner_name} (found by the AC13 stress test). Run again if it lost
     * a deadlock anyway.
     */
    public TaskListResponse rename(long userId, long listId, RenameListRequest request) {
        requireOwner(userId, listId);
        return DeadlockRetry.run(() -> transaction.execute(status -> {
            if (lists.shareUser(userId).isEmpty() || lists.rename(userId, listId, request.name()) == 0) {
                throw new ListNotFoundException();
            }
            TaskList list = lists.findById(listId).orElseThrow(ListNotFoundException::new);
            return TaskListResponse.from(list, ListRole.OWNER, members.countMembers(listId));
        }));
    }

    /**
     * {@code DELETE /api/lists/{id}}, owner only: a plain check (404 not a member, 403 a member), then users row FOR
     * SHARE, then the list row FOR UPDATE (so writes in progress on its tasks, tags, members or invitations finish
     * first, and later ones find it gone), then the "My tasks" check, then the DELETE; the database deletes its tasks,
     * their links, its tags, its members and its invitations. Run again if it lost a deadlock.
     */
    public void delete(long userId, long listId) {
        requireOwner(userId, listId);
        DeadlockRetry.run(() -> transaction.execute(status -> {
            if (lists.shareUser(userId).isEmpty()) {
                throw new ListNotFoundException();
            }
            TaskList list = lists.findByIdAndOwnerId(listId, userId).orElseThrow(ListNotFoundException::new);
            if (lists.lockList(list.getId()).isEmpty()) {
                throw new ListNotFoundException();
            }
            if (list.isPersonal()) {
                throw new PersonalListException();
            }
            lists.deleteNow(list.getId());
            return null;
        }));
    }

    /**
     * Creates the user's "My tasks" and their membership in the caller's transaction: both sign-up paths call it right
     * after inserting the user, so an account never exists without it. The database refuses a second one
     * (uk_lists_one_personal_per_owner).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public TaskList createPersonal(long userId) {
        Instant now = now();
        TaskList list = lists.saveAndFlush(new TaskList(userId, TaskList.PERSONAL_NAME, true, now));
        members.insertMember(list.getId(), userId, now);
        return list;
    }

    /**
     * The list a {@code ?list=} value means: missing or empty = "My tasks"; otherwise it must be a plain decimal
     * integer that is the id of a list the user is a member of. Anything else (a list they are not a member of,
     * unknown, 0, negative, not a number, blank) is a 404.
     */
    @Transactional(readOnly = true)
    public long resolve(long userId, String raw) {
        if (raw == null || raw.isEmpty()) {
            return lists.findByOwnerIdAndPersonalTrue(userId).orElseThrow(ListNotFoundException::new).getId();
        }
        if (!PLAIN_ID.matcher(raw).matches()) {
            throw new ListNotFoundException();
        }
        return requireMember(userId, Long.parseLong(raw)).getId();
    }

    /** The list if the user is a member of it (owner or member), else {@link ListNotFoundException}. Plain read. */
    @Transactional(readOnly = true)
    public TaskList requireMember(long userId, long listId) {
        return findMember(userId, listId).orElseThrow(ListNotFoundException::new);
    }

    /** The list if the user is a member of it (owner or member). Plain read. */
    @Transactional(readOnly = true)
    public Optional<TaskList> findMember(long userId, long listId) {
        return lists.findAccessible(listId, userId);
    }

    /**
     * The list if the user owns it. A member who is not the owner: {@link OwnerOnlyException} (403); anyone else:
     * {@link ListNotFoundException} (404). Plain read.
     */
    @Transactional(readOnly = true)
    public TaskList requireOwner(long userId, long listId) {
        TaskList list = requireMember(userId, listId);
        if (list.getOwnerId() != userId) {
            throw new OwnerOnlyException();
        }
        return list;
    }

    /** The id of the list's owner, if the list exists. Plain read (the purge, which has no user). */
    @Transactional(readOnly = true)
    public Optional<Long> ownerOf(long listId) {
        return lists.findById(listId).map(TaskList::getOwnerId);
    }

    /** The user's "My tasks". Plain read. */
    @Transactional(readOnly = true)
    public TaskList personal(long userId) {
        return lists.findByOwnerIdAndPersonalTrue(userId).orElseThrow(ListNotFoundException::new);
    }

    // ---- locks for the writes of TaskService and the sharing services, in the caller's transaction ----

    /** S lock on the user's row (see {@link ListRepository#shareUser}). False when the account is gone. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean shareUser(long userId) {
        return lists.shareUser(userId).isPresent();
    }

    /**
     * S locks on the {@code users} rows of the caller and of these list owners, in ascending id (the first step of the
     * lock order). The owners' rows are needed because a tag insert's foreign-key check takes an S lock on its list
     * owner's row: taken later, it would deadlock with that owner's account deletion (X on the row, then the list).
     * False when the caller's account is gone; a gone owner means its lists are gone, which the list lock reports.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean shareUsers(long callerId, Collection<Long> ownerIds) {
        TreeSet<Long> ids = new TreeSet<>(ownerIds);
        ids.add(callerId);
        boolean caller = false;
        for (long id : ids) {
            boolean there = lists.shareUser(id).isPresent();
            if (id == callerId) {
                caller = there;
            }
        }
        return caller;
    }

    /** X lock on a list row. False when it is gone. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean lockList(long listId) {
        return lists.lockList(listId).isPresent();
    }

    /** S lock on a list row. False when it is gone. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean shareList(long listId) {
        return lists.shareList(listId).isPresent();
    }

    /**
     * S lock on the user's membership row of the list, taken after the list's row lock: the membership re-check of
     * every write (a member removed, or who left, meanwhile gets false, i.e. 404). Exact primary key.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean shareMember(long listId, long userId) {
        return members.shareMember(listId, userId).isPresent();
    }

    /**
     * Account deletion (self or admin), in the caller's transaction, which already holds the user's row FOR UPDATE and
     * has read nothing before taking it (or runs READ COMMITTED): locks FOR UPDATE, ascending, every list the deletion
     * touches (lists the user is a member of or owns, lists holding tasks they added, lists with an invitation to
     * their email), so the cascade never meets a write in progress there; then deletes the invitations to their email
     * by primary key (AC30: no foreign key covers them). The caller then deletes the user.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockForAccountDeletion(long userId, String email) {
        for (long listId : members.listsTouchedBy(userId, email)) {
            lists.lockList(listId);
        }
        for (long invitationId : members.invitationIdsFor(email)) {
            members.deleteInvitation(invitationId);
        }
    }

    /** Microseconds match MySQL DATETIME(6). */
    private Instant now() {
        return Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
    }
}
