package com.taskapp.task;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.validation.MapBindingResult;

import com.taskapp.auth.InvalidFieldsException;
import com.taskapp.reminder.ReminderOffset;
import com.taskapp.reminder.ReminderService;
import com.taskapp.task.validation.ValidReminderValue;
import com.taskapp.task.dto.CreateTaskRequest;
import com.taskapp.task.dto.RepeatInput;
import com.taskapp.task.dto.ReplaceTaskRequest;
import com.taskapp.task.dto.TaskPage;
import com.taskapp.task.dto.TaskResponse;
import com.taskapp.task.dto.TaskResult;

/**
 * All operations take the user id from the session. A task is the user's when they are a member of its list (0.13.0;
 * owner or member, both may do everything with tasks); any other task is reported as not found, any other list as
 * {@link ListNotFoundException}. New tasks record the caller as the one who added them ({@code owner_id},
 * {@code addedBy}). Status and its {@code done} mirror are always written together ({@link Task#changeStatus}).
 * <p>
 * Concurrency (specs/sharing-team-lists.md AC13, AC33). Lock order everywhere: {@code users} rows FOR SHARE (the
 * caller's and the owners' of the lists touched, ascending id), then {@code lists} rows in ascending id, then the
 * caller's membership row(s) FOR SHARE, then task / tag / link rows. Every task write first locates the task's list
 * with a short read, then in one transaction takes those locks and reads the task again by id and list: if it left
 * that list in between, or the caller is no longer a member of it, the transaction is dropped and everything starts
 * again (at most {@value #MAX_LOCATE_ATTEMPTS} times, then 404). Writes that change tags or the list (PUT with
 * {@code tags} or a move, DELETE) lock the list row(s) FOR UPDATE; PATCH and PUT without tags or a move lock it FOR
 * SHARE, so they never wait for each other but a delete of the list or the account waits for them (without that lock
 * the delete's cascade and the task UPDATE deadlocked on the {@code list_id} index: found by the AC13 stress test).
 * <p>
 * The task row itself is read FOR UPDATE in that transaction ({@link TaskRepository#findByIdAndListId}), last in the lock
 * order. Completing a repeating task (contract 0.15.0) decides on that committed status: of two simultaneous
 * completions the second finds the task done, creates no copy and (rule already moved) writes no rule back.
 */
@Service
public class TaskService {

    /** Constraint name the error handler maps to {@code INVALID_CURSOR}. */
    public static final String INVALID_CURSOR_CONSTRAINT = "InvalidCursor";

    /** Fixed page size of {@code GET /api/tasks}. */
    static final int PAGE_SIZE = 50;

    /** How often a locked write starts again because the task left its list in between. */
    static final int MAX_LOCATE_ATTEMPTS = 3;

    static final String CURSOR_MESSAGE = "The list changed, reload from the first page";
    static final String SEARCH_TOO_LONG_MESSAGE = "Search must be at most 100 characters";

    private final TaskRepository taskRepository;
    private final TagService tagService;
    private final ListService listService;
    private final ReminderService reminders;
    private final RecurrenceService recurrence;
    private final TransactionOperations transaction;
    private final Clock clock;

    @Autowired
    public TaskService(TaskRepository taskRepository, TagService tagService, ListService listService,
            ReminderService reminders, RecurrenceService recurrence, PlatformTransactionManager transactions,
            Clock clock) {
        this(taskRepository, tagService, listService, reminders, recurrence, new TransactionTemplate(transactions),
                clock);
    }

    TaskService(TaskRepository taskRepository, TagService tagService, ListService listService,
            ReminderService reminders, RecurrenceService recurrence, TransactionOperations transaction,
            Clock clock) {
        this.taskRepository = taskRepository;
        this.tagService = tagService;
        this.listService = listService;
        this.reminders = reminders;
        this.recurrence = recurrence;
        this.transaction = transaction;
        this.clock = clock;
    }

    /**
     * One page of a list's tasks (contract 0.12.0). The list ({@code ?list=}, default "My tasks") is checked first:
     * 404 when it is not the user's. Then 400 ({@link InvalidFieldsException}) for a search over 100 characters or a
     * cursor that can't be read or was issued for another list/filter/sort/tag/search. A tag that is invalid or not
     * one of the list's gives an empty page. Reads 51 rows to know whether a next page exists; the total is counted
     * in the same snapshot (skipped when the first page already holds everything). Tags of the page's tasks: one
     * query.
     */
    @Transactional(readOnly = true)
    public TaskPage list(long userId, TaskListQuery query) {
        long listId = listService.resolve(userId, query.list());
        MapBindingResult errors = new MapBindingResult(new HashMap<>(), "taskListQuery");
        TaskCursor after = null;
        byte[] fingerprint = query.fingerprint(listId);
        if (query.hasCursor()) {
            after = TaskCursor.decode(query.cursor(), query.sort(), fingerprint).orElse(null);
            if (after == null) {
                errors.rejectValue("cursor", INVALID_CURSOR_CONSTRAINT, CURSOR_MESSAGE);
            }
        }
        if (query.q().length() > TaskListQuery.MAX_SEARCH_LENGTH) {
            errors.rejectValue("q", "Size", SEARCH_TOO_LONG_MESSAGE);
        }
        if (errors.hasErrors()) {
            throw new InvalidFieldsException(errors);
        }
        Long tagId = null;
        if (!TagNames.isBlank(query.tag())) {
            Optional<Long> found = tagService.findTagId(listId, query.tag());
            if (found.isEmpty()) {
                return TaskPage.empty();
            }
            tagId = found.get();
        }
        TaskSearchCriteria criteria = new TaskSearchCriteria(listId, query.filter(), query.sort(), tagId,
                query.search());
        List<Task> rows = taskRepository.findPage(criteria, after, PAGE_SIZE + 1);
        boolean more = rows.size() > PAGE_SIZE;
        List<Task> tasks = more ? rows.subList(0, PAGE_SIZE) : rows;
        long total = after == null && !more ? tasks.size() : taskRepository.countMatching(criteria);
        String nextCursor = more
                ? TaskCursor.after(tasks.get(tasks.size() - 1), query.sort()).encode(query.sort(), fingerprint)
                : null;
        Map<Long, List<String>> tags = tagService.namesByTask(listId, tasks.stream().map(Task::getId).toList());
        Map<Long, String> names = displayNames(tasks.stream().map(Task::getOwnerId).toList());
        Map<Long, ReminderService.Mine> mine = reminders.mine(userId, tasks.stream().map(Task::getId).toList());
        List<TaskResponse> items = tasks.stream()
                .map(task -> TaskResponse.from(task, tags.getOrDefault(task.getId(), List.of()),
                        names.get(task.getOwnerId()), mine.get(task.getId())))
                .toList();
        return new TaskPage(items, total, nextCursor);
    }

    @Transactional(readOnly = true)
    public TaskResponse get(long userId, long id) {
        return withTags(userId, find(userId, id));
    }

    /**
     * Request fields are already stripped/normalized by {@link CreateTaskRequest}. New tasks have no tags and are
     * added by the caller. The task goes into {@code listId} (null = "My tasks"; a list the user is not a member of
     * = 404). The users rows (caller and list owner) and then the list row are locked FOR SHARE and the membership
     * re-checked before the insert, so a delete of the list or a removal of the caller in progress finishes first
     * and the insert then reports 404 instead of failing on the foreign key.
     */
    public TaskResponse create(long userId, CreateTaskRequest request) {
        return DeadlockRetry.run(() -> transaction.execute(status -> {
            TaskList list = request.listId() == null ? listService.personal(userId)
                    : listService.requireMember(userId, request.listId());
            if (!listService.shareUsers(userId, List.of(list.getOwnerId())) || !listService.shareList(list.getId())
                    || !listService.shareMember(list.getId(), userId)) {
                throw new ListNotFoundException();
            }
            Task task = new Task(userId, list.getId(), request.title(), request.description(), request.dueDate(),
                    now());
            return respond(userId, taskRepository.saveAndFlush(task), List.of());
        }));
    }

    /**
     * Missing/null priority, status/done and tags leave those unchanged; {@code tags} replaces when sent; a
     * {@code listId} of another of the user's lists moves the task there (0.12.0). Locked as in the class comment:
     * the list FOR SHARE without tags or listId, FOR UPDATE (both lists on a move) with either; restarted if the
     * task moved meanwhile, and run again if it lost a deadlock ({@link DeadlockRetry}).
     */
    public TaskResult replace(long userId, long id, ReplaceTaskRequest request) {
        if (request.tags() == null && request.listId() == null) {
            return writeLocked(userId, id, null, false, (task, lists) -> {
                Spawn spawn = applyFields(task, request);
                saveOrNotFound(task);
                reminders.afterTaskSaved(task, userId, request.reminder());
                return result(userId, task, tagService.namesOf(task.getListId(), task.getId()), spawn);
            });
        }
        return writeLocked(userId, id, request.listId(), true, (task, lists) -> {
            Spawn spawn = applyFields(task, request);
            saveOrNotFound(task);
            reminders.afterTaskSaved(task, userId, request.reminder());
            TaskList source = lists.source();
            TaskList target = lists.target();
            if (target == null) {
                List<String> tags = request.tags() == null ? tagService.namesOf(source.getId(), task.getId())
                        : tagService.replaceTags(source.getOwnerId(), source.getId(), task.getId(), request.tags());
                return result(userId, task, tags, spawn);
            }
            if (taskRepository.moveToList(task.getId(), source.getId(), target.getId()) == 0) {
                // Not reachable: the source list's lock keeps the task in it.
                throw new TaskNotFoundException();
            }
            task.movedTo(target.getId());
            return result(userId, task, tagService.moveTags(target.getOwnerId(), source.getId(), target.getId(),
                    task.getId(), request.tags()), spawn);
        });
    }

    /** The rule (and its anchor) a completed task handed over; the next task is created from it. */
    private record Spawn(RepeatRule rule, LocalDate anchor) {
    }

    /**
     * The saved task's response and, when {@code spawn} is set, the next task: created now, after every other change
     * of this call (tags, move), so it has the post-update values and lives in the task's final list.
     */
    private TaskResult result(long userId, Task task, List<String> tags, Spawn spawn) {
        TaskResponse response = respond(userId, task, tags);
        if (spawn == null) {
            return TaskResult.of(response);
        }
        Task next = recurrence.createNext(userId, task, spawn.rule(), spawn.anchor(), now());
        return new TaskResult(response, respond(userId, next, tagService.namesOf(next.getListId(), next.getId())));
    }

    /**
     * Completing a repeating task (not done before, done after, rule still set): the rule moves to the next task, so
     * the completed one is cleared here, before it is saved (one UPDATE). Returns what the next task needs, else null.
     */
    private static Spawn spawnIfCompleted(Task task, boolean wasDone) {
        RepeatRule rule = task.getRepeat();
        if (wasDone || task.getStatus() != TaskStatus.DONE || rule == null) {
            return null;
        }
        Spawn spawn = new Spawn(rule, task.getRepeatAnchor());
        task.clearRepeat();
        return spawn;
    }

    /**
     * {@code repeat}: not sent = the rule stays, {@code NEVER} removes it, a rule sets it. The anchor restarts at the
     * due date whenever the rule or the due date changes (re-sent unchanged values keep it, so a monthly Jan 31
     * rule does not drift after an unrelated edit). A cleared due date has already cleared the rule.
     */
    private static void applyRepeat(Task task, RepeatInput input, LocalDate oldDue, RepeatRule oldRule) {
        LocalDate due = task.getDueDate();
        if (due == null) {
            return;
        }
        RepeatRule rule = oldRule;
        if (input != null) {
            if (input.isNever()) {
                task.clearRepeat();
                return;
            }
            rule = input.rule().orElse(oldRule);
        }
        if (rule != null && (!rule.equals(oldRule) || !due.equals(oldDue))) {
            task.setRepeat(rule, due);
        }
    }

    /**
     * The due time is replaced like the date, except that a time that is not sent stays (0.14.0); clearing the date
     * drops it. A reminder offset must fit the resulting task (timed or date-only), else 400 {@code reminder} /
     * {@code INVALID_REMINDER} and nothing is saved.
     */
    private Spawn applyFields(Task task, ReplaceTaskRequest request) {
        Instant now = now();
        LocalDate oldDue = task.getDueDate();
        RepeatRule oldRule = task.getRepeat();
        boolean wasDone = task.getStatus() == TaskStatus.DONE;
        LocalTime dueTime = request.dueDate() == null ? null
                : request.dueTime() == null ? task.getDueTime() : request.dueTime().value();
        ReminderOffset.parse(request.reminder()).ifPresent(offset -> {
            if (request.dueDate() != null && !offset.fits(dueTime != null)) {
                MapBindingResult errors = new MapBindingResult(new HashMap<>(), "replaceTaskRequest");
                errors.rejectValue("reminder", "InvalidReminder", ValidReminderValue.MESSAGE);
                throw new InvalidFieldsException(errors);
            }
        });
        task.replace(request.title(), request.description(), request.dueDate(), dueTime, now);
        applyRepeat(task, request.repeat(), oldDue, oldRule);
        if (request.priority() != null) {
            task.changePriority(request.priority(), now);
        }
        task.changeStatus(TaskStatus.resolve(task.getStatus(), request.status(), request.done()), now);
        return spawnIfCompleted(task, wasDone);
    }

    /**
     * PATCH: {@code status} and/or {@code done} (validated: at least one, and they agree). Takes the list row FOR
     * SHARE like a PUT without tags (see the class comment).
     */
    public TaskResult setStatus(long userId, long id, TaskStatus status, Boolean done) {
        return writeLocked(userId, id, null, false, (task, lists) -> {
            boolean wasDone = task.getStatus() == TaskStatus.DONE;
            task.changeStatus(TaskStatus.resolve(task.getStatus(), status, done), now());
            Spawn spawn = spawnIfCompleted(task, wasDone);
            saveOrNotFound(task);
            return result(userId, task, tagService.namesOf(task.getListId(), task.getId()), spawn);
        });
    }

    /**
     * {@code task} was read a moment ago with {@link #find}, which found it; there is no {@code @Version} column, so
     * the only way the flush now matches zero rows is a concurrent delete (of the task, its list or the account) that
     * has since committed. Reported the same way as never having found it, i.e. 404, instead of the generic 500
     * Hibernate's optimistic-lock failure would otherwise become.
     */
    private void saveOrNotFound(Task task) {
        try {
            taskRepository.saveAndFlush(task);
        } catch (ObjectOptimisticLockingFailureException e) {
            throw new TaskNotFoundException();
        }
    }

    /**
     * The task's task_tags rows go with it (ON DELETE CASCADE); tags of its list it was the last user of are deleted
     * too. Locked like a tag write (see the class comment).
     */
    public void delete(long userId, long id) {
        writeLocked(userId, id, null, true, (task, lists) -> {
            List<Long> tagIds = tagService.tagIdsOf(task.getId());
            taskRepository.delete(task);
            taskRepository.flush();
            tagService.deleteUnused(task.getListId(), tagIds);
            return Boolean.TRUE;
        });
    }

    /**
     * Runs {@code work} on the task with its list locked: FOR UPDATE when {@code exclusive} (tag writes, moves,
     * deletes; then also the move target, if {@code requestedListId} is another of the user's lists, which
     * {@code work} gets as {@link Located#target()}), else FOR SHARE (field-only writes, which never wait for each
     * other).
     * <ol>
     * <li>A short read: the task must be in a list the user is a member of (else 404 {@code NOT_FOUND}); a requested
     * list other than the task's must be one they are a member of (else 404 {@code LIST_NOT_FOUND}).</li>
     * <li>One transaction: users rows FOR SHARE (caller and list owners, ascending; caller gone: 404), the list rows,
     * lowest id first, the caller's membership of the source list FOR SHARE; the task read again by id in its list.
     * If the source list, the membership or the task in it is gone, the transaction does nothing and the whole thing
     * starts again from 1 (so a task deleted meanwhile, or a removal of the caller, is a 404 and a task moved
     * meanwhile is handled in its new list); after {@value #MAX_LOCATE_ATTEMPTS} attempts: 404. A target list gone,
     * or no longer theirs, is {@code LIST_NOT_FOUND}.</li>
     * </ol>
     */
    private <T> T writeLocked(long userId, long taskId, Long requestedListId, boolean exclusive,
            BiFunction<Task, Located, T> work) {
        for (int attempt = 1;; attempt++) {
            Located located = transaction.execute(status -> locate(userId, taskId, requestedListId));
            Optional<T> result = DeadlockRetry.run(() -> transaction.execute(status -> {
                Optional<T> done = lockedOnce(userId, taskId, located, exclusive, work);
                if (done.isEmpty()) {
                    status.setRollbackOnly();
                }
                return done;
            }));
            if (result.isPresent()) {
                return result.get();
            }
            if (attempt >= MAX_LOCATE_ATTEMPTS) {
                throw new TaskNotFoundException();
            }
        }
    }

    private Located locate(long userId, long taskId, Long requestedListId) {
        long sourceId = find(userId, taskId).getListId();
        // Its list deleted in between: the task went with it (404 NOT_FOUND, not LIST_NOT_FOUND).
        TaskList source = listService.findMember(userId, sourceId).orElseThrow(TaskNotFoundException::new);
        TaskList target = requestedListId == null || requestedListId == sourceId ? null
                : listService.requireMember(userId, requestedListId);
        return new Located(source, target);
    }

    private <T> Optional<T> lockedOnce(long userId, long taskId, Located located, boolean exclusive,
            BiFunction<Task, Located, T> work) {
        long source = located.source().getId();
        TaskList target = located.target();
        List<Long> owners = target == null ? List.of(located.source().getOwnerId())
                : List.of(located.source().getOwnerId(), target.getOwnerId());
        if (!listService.shareUsers(userId, owners)) {
            throw new TaskNotFoundException();
        }
        boolean sourceThere;
        boolean targetThere = true;
        if (!exclusive) {
            sourceThere = listService.shareList(source);
        } else if (target == null) {
            sourceThere = listService.lockList(source);
        } else if (source < target.getId()) {
            sourceThere = listService.lockList(source);
            targetThere = listService.lockList(target.getId());
        } else {
            targetThere = listService.lockList(target.getId());
            sourceThere = listService.lockList(source);
        }
        boolean member = sourceThere && listService.shareMember(source, userId);
        Optional<Task> task = member ? taskRepository.findByIdAndListId(taskId, source) : Optional.empty();
        if (task.isEmpty()) {
            return Optional.empty();
        }
        if (!targetThere || (target != null && !listService.shareMember(target.getId(), userId))) {
            throw new ListNotFoundException();
        }
        return Optional.of(work.apply(task.get(), located));
    }

    /** The list {@link #locate} found the task in, and the move target (null = no move). */
    private record Located(TaskList source, TaskList target) {
    }

    private TaskResponse withTags(long userId, Task task) {
        return respond(userId, task, tagService.namesOf(task.getListId(), task.getId()));
    }

    /**
     * The response with {@code addedBy} looked up (one primary-key read; none when the adder's account is gone) and
     * the caller's own reminder on the task (one indexed read).
     */
    private TaskResponse respond(long userId, Task task, List<String> tags) {
        Long adder = task.getOwnerId();
        String name = adder == null ? null : displayNames(List.of(adder)).get(adder);
        return TaskResponse.from(task, tags, name, reminders.mine(userId, List.of(task.getId())).get(task.getId()));
    }

    /** Display name per user id, one query; null ids (deleted adders) and deleted accounts are absent. */
    private Map<Long, String> displayNames(List<Long> userIds) {
        Map<Long, String> names = new HashMap<>();
        List<Long> ids = userIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return names;
        }
        for (TaskRepository.UserName row : taskRepository.findDisplayNames(ids)) {
            names.put(row.getId(), row.getDisplayName());
        }
        return names;
    }

    private Task find(long userId, long id) {
        return taskRepository.findAccessible(id, userId).orElseThrow(TaskNotFoundException::new);
    }

    /** Microseconds match MySQL DATETIME(6), so the response equals what a later GET returns. */
    private Instant now() {
        return Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
    }
}
