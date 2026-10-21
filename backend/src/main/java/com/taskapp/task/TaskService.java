package com.taskapp.task;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;

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
import com.taskapp.task.validation.RequiresDueDate;
import com.taskapp.task.validation.ValidReminderValue;
import com.taskapp.task.dto.CreateTaskRequest;
import com.taskapp.task.dto.RepeatInput;
import com.taskapp.task.dto.SetTaskDoneRequest;
import com.taskapp.task.dto.ReplaceTaskRequest;
import com.taskapp.task.dto.TaskPage;
import com.taskapp.task.dto.TaskResponse;
import com.taskapp.task.dto.TaskResult;
import com.taskapp.task.dto.UndoCompleteResult;

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
            if (after != null && query.sort() == TaskSort.MANUAL) {
                // The manual cursor holds only the anchor's id: its position and creation time are read now. An anchor
                // that is trashed, deleted, moved or not in this list makes the cursor invalid.
                TaskCursor issued = after;
                after = taskRepository.findLiveInList(issued.id(), listId).map(issued::resolved).orElse(null);
            }
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
            // "My order" (0.18.0): a new task goes to the top of the list.
            task.positionedAt(Task.above(taskRepository.minLivePosition(list.getId())));
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
        return update(userId, id, request.listId(), request.tags() != null || request.listId() != null,
                task -> request);
    }

    /**
     * {@code PATCH} with any subset of the fields (contract 0.16.0). A body with only {@code done} / {@code status}
     * takes the lighter {@link #setStatus} path (unchanged since 0.15.0). Anything else is merged with the task's
     * current values into the request {@code PUT} would take, inside the lock, and then goes through exactly the
     * same code as {@code PUT}: same rules, same side effects (move, tags, reminder, recurrence).
     */
    public TaskResult patch(long userId, long id, SetTaskDoneRequest patch) {
        if (patch.onlyStatus()) {
            return setStatus(userId, id, patch.status(), patch.done());
        }
        return update(userId, id, patch.listId(), patch.tags() != null || patch.listId() != null,
                task -> merge(task, patch));
    }

    /**
     * The {@code PUT} request equal to {@code patch} applied to {@code task}: fields not sent keep the task's value
     * ({@code dueTime} and the null-means-unchanged fields are already "unchanged" in a {@link ReplaceTaskRequest}).
     * A time, reminder offset or rule needs a due date after the change: else 400 {@code REQUIRES_DUE_DATE}.
     */
    static ReplaceTaskRequest merge(Task task, SetTaskDoneRequest patch) {
        LocalDate dueDate = patch.dueDate() == null ? task.getDueDate() : patch.dueDate().value();
        if (dueDate == null) {
            MapBindingResult errors = new MapBindingResult(new HashMap<>(), "setTaskDoneRequest");
            if (patch.dueTime() != null && patch.dueTime().value() != null) {
                errors.rejectValue("dueTime", "RequiresDueDate", RequiresDueDate.TIME_MESSAGE);
            }
            if (ReminderOffset.parse(patch.reminder()).isPresent()) {
                errors.rejectValue("reminder", "RequiresDueDate", RequiresDueDate.REMINDER_MESSAGE);
            }
            if (patch.repeat() != null && patch.repeat().isRule()) {
                errors.rejectValue("repeat", "RequiresDueDate", RequiresDueDate.REPEAT_MESSAGE);
            }
            if (errors.hasErrors()) {
                throw new InvalidFieldsException(errors);
            }
        }
        return new ReplaceTaskRequest(patch.title() == null ? task.getTitle() : patch.title(),
                patch.description() == null ? task.getDescription() : patch.description().value(), dueDate,
                patch.dueTime(), patch.priority(), patch.status(), patch.done(), patch.tags(), patch.listId(),
                patch.reminder(), patch.repeat());
    }

    /**
     * The write behind {@code PUT} and {@code PATCH}: {@code request} is built from the locked task. Locked as in the
     * class comment: the list FOR SHARE when {@code exclusive} is false (no tags, no move), FOR UPDATE (both lists on a
     * move) when true; restarted if the task moved meanwhile, and run again if it lost a deadlock.
     */
    private TaskResult update(long userId, long id, Long requestedListId, boolean exclusive,
            Function<Task, ReplaceTaskRequest> build) {
        return writeLocked(userId, id, requestedListId, exclusive, false, (task, lists) ->
                toResult(userId, applyUpdate(userId, task, lists, build.apply(task), exclusive, false)));
    }

    /**
     * What one write did, before any response is built: the saved task, its tag names ({@code null} = not read yet,
     * the bulk calls read them for all tasks in one query) and the copy of a completed repeating task (or null).
     */
    record Applied(Task task, List<String> tags, Task next) {
    }

    /**
     * The body of {@code PUT} / {@code PATCH}, run on the locked {@code task} (also by the bulk calls, task by task, in
     * one transaction): applies {@code request}, saves, re-arms reminders, replaces the tags or moves the task, then
     * creates the next task of a completed repeating one. {@code batch}: do not read the tag names of a task whose
     * tags did not change (the caller reads them for all tasks at once).
     */
    Applied applyUpdate(long userId, Task task, Located lists, ReplaceTaskRequest request, boolean exclusive,
            boolean batch) {
        Spawn spawn = applyFields(task, request);
        saveOrNotFound(task);
        reminders.afterTaskSaved(task, userId, request.reminder());
        if (!exclusive) {
            return applied(userId, task, batch ? null : tagService.namesOf(task.getListId(), task.getId()), spawn);
        }
        TaskList source = lists.source();
        TaskList target = lists.target();
        if (target == null) {
            List<String> tags = request.tags() == null
                    ? (batch ? null : tagService.namesOf(source.getId(), task.getId()))
                    : tagService.replaceTags(source.getOwnerId(), source.getId(), task.getId(), request.tags());
            return applied(userId, task, tags, spawn);
        }
        // "My order" (0.18.0): a moved task goes to the top of the target list (read before it joins the list).
        long top = Task.above(taskRepository.minLivePosition(target.getId()));
        if (taskRepository.moveToList(task.getId(), source.getId(), target.getId()) == 0) {
            // Not reachable: the source list's lock keeps the task in it.
            throw new TaskNotFoundException();
        }
        taskRepository.setPosition(task.getId(), top);
        task.movedTo(target.getId());
        task.positionedAt(top);
        return applied(userId, task, tagService.moveTags(target.getOwnerId(), source.getId(), target.getId(),
                task.getId(), request.tags()), spawn);
    }

    /** The rule (and its anchor) a completed task handed over; the next task is created from it. */
    private record Spawn(RepeatRule rule, LocalDate anchor) {
    }

    /**
     * The write's outcome and, when {@code spawn} is set, the next task: created now, after every other change of this
     * call (tags, move), so it has the post-update values and lives in the task's final list.
     */
    private Applied applied(long userId, Task task, List<String> tags, Spawn spawn) {
        Task next = spawn == null ? null
                : recurrence.createNext(userId, task, spawn.rule(), spawn.anchor(), now());
        return new Applied(task, tags, next);
    }

    /** One write's response (single-task calls). */
    private TaskResult toResult(long userId, Applied applied) {
        TaskResponse response = respond(userId, applied.task(), applied.tags());
        Task next = applied.next();
        return next == null ? TaskResult.of(response)
                : new TaskResult(response, respond(userId, next, tagService.namesOf(next.getListId(), next.getId())));
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
        return writeLocked(userId, id, null, false, false, (task, lists) ->
                toResult(userId, applyStatus(userId, task, status, done, false)));
    }

    /** The body of the status-only {@code PATCH}, run on the locked task (also by the bulk calls). */
    Applied applyStatus(long userId, Task task, TaskStatus status, Boolean done, boolean batch) {
        boolean wasDone = task.getStatus() == TaskStatus.DONE;
        task.changeStatus(TaskStatus.resolve(task.getStatus(), status, done), now());
        Spawn spawn = spawnIfCompleted(task, wasDone);
        saveOrNotFound(task);
        return applied(userId, task, batch ? null : tagService.namesOf(task.getListId(), task.getId()), spawn);
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
     * {@code DELETE /api/tasks/{id}} (0.16.0): moves the task to the trash. Nothing else changes: tags (still in use),
     * reminders, {@code updated_at}. The list is locked FOR SHARE like a field-only write.
     */
    public void delete(long userId, long id) {
        writeLocked(userId, id, null, false, false, (task, lists) -> {
            if (taskRepository.trash(task.getId(), userId, now()) == 0) {
                throw new TaskNotFoundException();
            }
            return Boolean.TRUE;
        });
    }

    /**
     * {@code POST /api/trash/{id}/restore}: back into its list as it was ({@code updated_at} untouched). Reminders are
     * re-armed like on a reopen: one whose fire time is still ahead is active again, an older one never fires.
     */
    public TaskResponse restore(long userId, long id) {
        return writeLocked(userId, id, null, false, true, (task, lists) -> {
            if (taskRepository.restore(task.getId()) == 0) {
                throw new TaskNotFoundException();
            }
            Task restored = taskRepository.findById(task.getId()).orElseThrow(TaskNotFoundException::new);
            reminders.afterRestore(restored);
            return respond(userId, restored, tagService.namesOf(restored.getListId(), restored.getId()));
        });
    }

    /**
     * {@code DELETE /api/trash/{id}}: deletes a trashed task for good. Its task_tags rows go with it (ON DELETE
     * CASCADE); tags of its list that no remaining task (trashed or not) uses are deleted too. Locked like a tag write.
     */
    public void deleteForever(long userId, long id) {
        writeLocked(userId, id, null, true, true, (task, lists) -> {
            removeForGood(task);
            return Boolean.TRUE;
        });
    }

    private void removeForGood(Task task) {
        List<Long> tagIds = tagService.tagIdsOf(task.getId());
        taskRepository.delete(task);
        taskRepository.flush();
        tagService.deleteUnused(task.getListId(), tagIds);
    }

    /**
     * The purge of one task trashed before {@code cutoff} (no user: the list owner's and the list's rows are locked in
     * the usual order, the task row last). False when it is gone, restored or trashed again since.
     */
    boolean purge(long taskId, long listId, Instant cutoff) {
        Optional<Long> owner = listService.ownerOf(listId);
        if (owner.isEmpty()) {
            return false;
        }
        return Boolean.TRUE.equals(DeadlockRetry.run(() -> transaction.execute(status -> {
            if (!listService.shareUsers(owner.get(), List.of()) || !listService.lockList(listId)) {
                return false;
            }
            Optional<Task> task = taskRepository.findTrashedByIdAndListId(taskId, listId)
                    .filter(t -> t.getDeletedAt().isBefore(cutoff));
            task.ifPresent(this::removeForGood);
            return task.isPresent();
        })));
    }

    /** Puts a (locked) task of {@code listId} above every other task of that list in "My order". */
    void putOnTop(Task task, long listId) {
        long top = Task.above(taskRepository.minLivePosition(listId));
        taskRepository.setPosition(task.getId(), top);
        task.positionedAt(top);
    }

    /**
     * {@code PUT /api/tasks/{id}/position} (contract 0.18.0): puts the task right after {@code afterId} (null: at the
     * top) inside its group (done / not done) of its list, in one transaction with the list row locked FOR UPDATE
     * (like a move: no other write of the list runs meanwhile, so two reorders queue up and the last wins). Checks, in
     * order, on the locked task: {@code afterId} must be a live task of one of the caller's lists (404), not the task
     * itself ({@code SELF}), in the same list ({@code OTHER_LIST}) and group ({@code OTHER_GROUP}). {@code updated_at}
     * is not touched.
     */
    public void reposition(long userId, long id, Long afterId) {
        writeLocked(userId, id, null, true, false, (task, lists) -> {
            if (afterId != null) {
                Task after = taskRepository.findAccessible(afterId, userId).orElseThrow(TaskNotFoundException::new);
                if (after.getId().equals(task.getId())) {
                    throw afterIdError("Self", "A task cannot be placed after itself");
                }
                if (!after.getListId().equals(task.getListId())) {
                    throw afterIdError("OtherList", "The task is in another list");
                }
                if (after.isDone() != task.isDone()) {
                    throw afterIdError("OtherGroup", "The task is in the other group (done or not done)");
                }
            }
            place(task, afterId);
            return Boolean.TRUE;
        });
    }

    private static InvalidFieldsException afterIdError(String constraint, String message) {
        MapBindingResult errors = new MapBindingResult(new HashMap<>(), "taskPositionRequest");
        errors.rejectValue("afterId", constraint, message);
        return new InvalidFieldsException(errors);
    }

    /**
     * The order logic of {@link #reposition}, on the locked task. The group is read in "My order". If a task of it
     * has no position, the whole group is numbered first (1024, 2048, ... in that order, rows locked in id order).
     * Then the task takes the middle of the gap between {@code afterId} and the task that follows it (not counting the
     * task itself); with no whole number in between (a gap under 2, e.g. a tie) the group is renumbered with the task
     * in its new place.
     */
    private void place(Task task, Long afterId) {
        long listId = task.getListId();
        boolean done = task.isDone();
        long self = task.getId();
        List<TaskSearchRepository.GroupRow> rows = taskRepository.manualGroup(listId, done);
        if (rows.stream().anyMatch(row -> row.position() == null)) {
            taskRepository.lockGroup(listId, done);
            taskRepository.assignPositions(rows.stream().map(TaskSearchRepository.GroupRow::id).toList(),
                    Task.POSITION_STEP);
            List<TaskSearchRepository.GroupRow> numbered = new ArrayList<>();
            for (int i = 0; i < rows.size(); i++) {
                numbered.add(new TaskSearchRepository.GroupRow(rows.get(i).id(), (i + 1) * Task.POSITION_STEP));
            }
            rows = numbered;
        }
        int at = indexOf(rows, self);
        if (at < 0) {
            // Not reachable: the task is locked, live and in this group.
            throw new TaskNotFoundException();
        }
        List<TaskSearchRepository.GroupRow> others = new ArrayList<>(rows);
        others.remove(at);
        int slot = 0;
        if (afterId != null) {
            int after = indexOf(others, afterId);
            if (after < 0) {
                throw new TaskNotFoundException();
            }
            slot = after + 1;
        }
        if (slot == at) {
            return;
        }
        long position;
        if (slot == 0) {
            position = others.isEmpty() ? 0 : others.get(0).position() - Task.POSITION_STEP;
        } else if (slot == others.size()) {
            position = others.get(slot - 1).position() + Task.POSITION_STEP;
        } else {
            long below = others.get(slot - 1).position();
            long above = others.get(slot).position();
            if (above - below < 2) {
                List<Long> ordered = new ArrayList<>(others.stream().map(TaskSearchRepository.GroupRow::id).toList());
                ordered.add(slot, self);
                taskRepository.lockGroup(listId, done);
                taskRepository.assignPositions(ordered, Task.POSITION_STEP);
                return;
            }
            position = below + (above - below) / 2;
        }
        taskRepository.setPosition(self, position);
    }

    private static int indexOf(List<TaskSearchRepository.GroupRow> rows, long id) {
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).id() == id) {
                return i;
            }
        }
        return -1;
    }

    /**
     * {@code POST /api/tasks/{id}/undo-complete} (0.16.0, AC11): reopens {@code id} (status {@code TODO} unless
     * {@code status} says {@code IN_PROGRESS}) and decides about the copy {@code nextTaskId}, atomically with the list
     * locked FOR UPDATE. An untouched copy (same list, live, a rule, {@code TODO}, {@code updated_at} = {@code
     * created_at}) is deleted and its rule moves back to the task; any other copy that exists is kept; none is GONE.
     */
    public UndoCompleteResult undoComplete(long userId, long id, long nextTaskId, TaskStatus status) {
        return writeLocked(userId, id, null, true, false, (task, lists) -> {
            UndoApplied done = applyUndoComplete(userId, task, nextTaskId, status, false);
            return new UndoCompleteResult(respond(userId, task, done.applied().tags()), done.outcome());
        });
    }

    /** What an undo-complete did to one task, before the response is built. */
    record UndoApplied(Applied applied, UndoCompleteResult.Outcome outcome) {
    }

    /**
     * The body of {@code undo-complete}, run on the locked task (also by the bulk call). {@code nextTaskId} null (bulk
     * only): a plain reopen, there is no copy to look at, the outcome is {@code NONE}.
     */
    UndoApplied applyUndoComplete(long userId, Task task, Long nextTaskId, TaskStatus status, boolean batch) {
        UndoCompleteResult.Outcome outcome = UndoCompleteResult.Outcome.NONE;
        if (nextTaskId != null) {
            outcome = UndoCompleteResult.Outcome.GONE;
            if (nextTaskId == task.getId().longValue()) {
                outcome = UndoCompleteResult.Outcome.KEPT;
            } else {
                Optional<Task> copy = taskRepository.findByIdAndListId(nextTaskId, task.getListId());
                if (copy.isPresent()) {
                    Task next = copy.get();
                    boolean untouched = next.getRepeat() != null && next.getStatus() == TaskStatus.TODO
                            && next.getCreatedAt().equals(next.getUpdatedAt());
                    if (untouched) {
                        task.setRepeat(next.getRepeat(), next.getRepeatAnchor());
                        removeForGood(next);
                        outcome = UndoCompleteResult.Outcome.DELETED;
                    } else {
                        outcome = UndoCompleteResult.Outcome.KEPT;
                    }
                } else if (taskRepository.findTrashedByIdAndListId(nextTaskId, task.getListId()).isPresent()
                        || taskRepository.findAccessible(nextTaskId, userId).isPresent()
                        || taskRepository.findTrashedAccessible(nextTaskId, userId).isPresent()) {
                    outcome = UndoCompleteResult.Outcome.KEPT;
                }
            }
        }
        task.changeStatus(status == null ? TaskStatus.TODO : status, now());
        saveOrNotFound(task);
        reminders.afterTaskSaved(task, userId, null);
        return new UndoApplied(new Applied(task, batch ? null : tagService.namesOf(task.getListId(), task.getId()),
                null), outcome);
    }

    /**
     * Runs {@code work} on the task with its list locked: FOR UPDATE when {@code exclusive} (tag writes, moves,
     * deletes; then also the move target, if {@code requestedListId} is another of the user's lists, which
     * {@code work} gets as {@link Located#target()}), else FOR SHARE (field-only writes, which never wait for each
     * other). {@code trashed}: the task must be in the trash (restore, delete forever), else it must not be.
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
    private <T> T writeLocked(long userId, long taskId, Long requestedListId, boolean exclusive, boolean trashed,
            BiFunction<Task, Located, T> work) {
        for (int attempt = 1;; attempt++) {
            Located located = transaction.execute(status -> locate(userId, taskId, requestedListId, trashed));
            Optional<T> result = DeadlockRetry.run(() -> transaction.execute(status -> {
                Optional<T> done = lockedOnce(userId, taskId, located, exclusive, trashed, work);
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

    private Located locate(long userId, long taskId, Long requestedListId, boolean trashed) {
        long sourceId = (trashed ? taskRepository.findTrashedAccessible(taskId, userId).orElseThrow(TaskNotFoundException::new)
                : find(userId, taskId)).getListId();
        // Its list deleted in between: the task went with it (404 NOT_FOUND, not LIST_NOT_FOUND).
        TaskList source = listService.findMember(userId, sourceId).orElseThrow(TaskNotFoundException::new);
        TaskList target = requestedListId == null || requestedListId == sourceId ? null
                : listService.requireMember(userId, requestedListId);
        return new Located(source, target);
    }

    private <T> Optional<T> lockedOnce(long userId, long taskId, Located located, boolean exclusive, boolean trashed,
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
        Optional<Task> task = !member ? Optional.empty()
                : trashed ? taskRepository.findTrashedByIdAndListId(taskId, source)
                        : taskRepository.findByIdAndListId(taskId, source);
        if (task.isEmpty()) {
            return Optional.empty();
        }
        if (!targetThere || (target != null && !listService.shareMember(target.getId(), userId))) {
            throw new ListNotFoundException();
        }
        return Optional.of(work.apply(task.get(), located));
    }

    /** The list {@link #locate} found the task in, and the move target (null = no move). */
    record Located(TaskList source, TaskList target) {
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

    /**
     * Responses for these tasks with a fixed number of queries (bulk calls): the tag names of tasks whose {@code known}
     * entry is absent in one query per list, the adders' display names in one, the caller's reminders in one. In the
     * order of {@code tasks}.
     */
    List<TaskResponse> responses(long userId, List<Task> tasks, Map<Long, List<String>> known) {
        Map<Long, List<Task>> missing = new java.util.LinkedHashMap<>();
        for (Task task : tasks) {
            if (!known.containsKey(task.getId())) {
                missing.computeIfAbsent(task.getListId(), id -> new java.util.ArrayList<>()).add(task);
            }
        }
        Map<Long, List<String>> tags = new HashMap<>(known);
        missing.forEach((listId, group) -> tags.putAll(
                tagService.namesByTask(listId, group.stream().map(Task::getId).toList())));
        List<Long> ids = tasks.stream().map(Task::getId).toList();
        Map<Long, String> names = displayNames(tasks.stream().map(Task::getOwnerId).toList());
        Map<Long, ReminderService.Mine> mine = reminders.mine(userId, ids);
        return tasks.stream().map(task -> TaskResponse.from(task, tags.getOrDefault(task.getId(), List.of()),
                names.get(task.getOwnerId()), mine.get(task.getId()))).toList();
    }

    /** {@link TaskResult}s of these writes in order, with the responses built by {@link #responses}. */
    List<TaskResult> results(long userId, List<Applied> applied) {
        List<Task> all = new ArrayList<>();
        Map<Long, List<String>> known = new HashMap<>();
        for (Applied a : applied) {
            all.add(a.task());
            if (a.tags() != null) {
                known.put(a.task().getId(), a.tags());
            }
            if (a.next() != null) {
                all.add(a.next());
            }
        }
        Map<Long, TaskResponse> byId = new HashMap<>();
        List<TaskResponse> built = responses(userId, all, known);
        for (int i = 0; i < all.size(); i++) {
            byId.put(all.get(i).getId(), built.get(i));
        }
        return applied.stream().map(a -> new TaskResult(byId.get(a.task().getId()),
                a.next() == null ? null : byId.get(a.next().getId()))).toList();
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
