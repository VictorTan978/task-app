package com.taskapp.task;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
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
 * Lists (contract 0.12.0, specs/sharing-team-lists.md T3a). The user id always comes from the session; a list that is
 * not the user's is reported as {@link ListNotFoundException} (404), never 403. Nothing is shared yet: "the user's
 * list" means "owned by the user".
 * <p>
 * Locks follow the order users row, then lists rows (ascending id), then task / tag / link rows (see
 * {@link ListRepository}).
 */
@Service
public class ListService {

    /** Lists a user can own, "My tasks" included. */
    public static final int MAX_LISTS = 50;

    /** A plain decimal integer that fits a long (18 digits always do). Anything else in {@code ?list=} is a 404. */
    private static final Pattern PLAIN_ID = Pattern.compile("[0-9]{1,18}");

    private final ListRepository lists;
    private final TransactionOperations transaction;
    private final Clock clock;

    @Autowired
    public ListService(ListRepository lists, PlatformTransactionManager transactions) {
        this(lists, new TransactionTemplate(transactions), Clock.systemUTC());
    }

    ListService(ListRepository lists, TransactionOperations transaction, Clock clock) {
        this.lists = lists;
        this.transaction = transaction;
        this.clock = clock;
    }

    /** {@code GET /api/lists}: every list of the user, "My tasks" first, then by name, then id. */
    @Transactional(readOnly = true)
    public List<TaskListResponse> list(long userId) {
        return lists.findAllOwned(userId).stream().map(TaskListResponse::from).toList();
    }

    /** {@code GET /api/lists/{id}}: the list with its number of tasks (every status). */
    @Transactional(readOnly = true)
    public TaskListDetailsResponse get(long userId, long listId) {
        TaskList list = requireOwned(userId, listId);
        return TaskListDetailsResponse.from(list, lists.countTasks(list.getId()));
    }

    /**
     * {@code POST /api/lists}. The user's row is locked FOR UPDATE before counting, so two creates racing at the limit
     * can never make 51 lists: the second one counts after the first has committed. Run again if it lost a deadlock.
     */
    public TaskListResponse create(long userId, CreateListRequest request) {
        return DeadlockRetry.run(() -> transaction.execute(status -> {
            if (lists.lockUser(userId).isEmpty()) {
                throw new UnauthenticatedException();
            }
            if (lists.countByOwnerId(userId) >= MAX_LISTS) {
                throw new ListLimitException();
            }
            return TaskListResponse.from(lists.saveAndFlush(new TaskList(userId, request.name(), false, now())));
        }));
    }

    /**
     * {@code PATCH /api/lists/{id}}: users row FOR SHARE, then one UPDATE scoped by owner; 0 rows (not the user's, or
     * deleted) is a 404. The users row comes first like every other list write: an account delete (X on that row,
     * then the cascade into {@code lists}) otherwise deadlocked with the UPDATE on {@code ix_lists_owner_name}
     * (found by the AC13 stress test). Run again if it lost a deadlock anyway.
     */
    public TaskListResponse rename(long userId, long listId, RenameListRequest request) {
        return DeadlockRetry.run(() -> transaction.execute(status -> {
            if (lists.shareUser(userId).isEmpty() || lists.rename(userId, listId, request.name()) == 0) {
                throw new ListNotFoundException();
            }
            return TaskListResponse.from(lists.findById(listId).orElseThrow(ListNotFoundException::new));
        }));
    }

    /**
     * {@code DELETE /api/lists/{id}}: users row FOR SHARE, then the list row FOR UPDATE (so writes in progress on its
     * tasks or tags finish first, and later ones find it gone), then the owner and "My tasks" checks, then the DELETE;
     * the database deletes its tasks, their links and its tags. Run again if it lost a deadlock.
     */
    public void delete(long userId, long listId) {
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
     * Creates the user's "My tasks" in the caller's transaction: both sign-up paths call it right after inserting the
     * user, so an account never exists without it. The database refuses a second one (uk_lists_one_personal_per_owner).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public TaskList createPersonal(long userId) {
        return lists.saveAndFlush(new TaskList(userId, TaskList.PERSONAL_NAME, true, now()));
    }

    /**
     * The list a {@code ?list=} value means: missing or empty = "My tasks"; otherwise it must be a plain decimal
     * integer that is the id of one of the user's lists. Anything else (another user's list, unknown, 0, negative,
     * not a number, blank) is a 404.
     */
    @Transactional(readOnly = true)
    public long resolve(long userId, String raw) {
        if (raw == null || raw.isEmpty()) {
            return lists.findByOwnerIdAndPersonalTrue(userId).orElseThrow(ListNotFoundException::new).getId();
        }
        if (!PLAIN_ID.matcher(raw).matches()) {
            throw new ListNotFoundException();
        }
        return requireOwned(userId, Long.parseLong(raw)).getId();
    }

    /** The list if it is the user's, else {@link ListNotFoundException}. Plain read. */
    @Transactional(readOnly = true)
    public TaskList requireOwned(long userId, long listId) {
        return findOwned(userId, listId).orElseThrow(ListNotFoundException::new);
    }

    /** The list if it is the user's. Plain read. */
    @Transactional(readOnly = true)
    public Optional<TaskList> findOwned(long userId, long listId) {
        return lists.findByIdAndOwnerId(listId, userId);
    }

    /** The user's "My tasks". Plain read. */
    @Transactional(readOnly = true)
    public TaskList personal(long userId) {
        return lists.findByOwnerIdAndPersonalTrue(userId).orElseThrow(ListNotFoundException::new);
    }

    // ---- locks for the task writes (TaskService), in the caller's transaction ----

    /** S lock on the user's row (see {@link ListRepository#shareUser}). False when the account is gone. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean shareUser(long userId) {
        return lists.shareUser(userId).isPresent();
    }

    /** X lock on a list row the caller knows is the user's. False when it is gone. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean lockList(long listId) {
        return lists.lockList(listId).isPresent();
    }

    /** S lock on a list row the caller knows is the user's. False when it is gone. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean shareList(long listId) {
        return lists.shareList(listId).isPresent();
    }

    /** Microseconds match MySQL DATETIME(6). */
    private Instant now() {
        return Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
    }
}
