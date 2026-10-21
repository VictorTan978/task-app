package com.taskapp.task;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.validation.MapBindingResult;

import com.taskapp.auth.InvalidFieldsException;
import com.taskapp.reminder.ReminderService;
import com.taskapp.task.TaskService.Applied;
import com.taskapp.task.TaskService.Located;
import com.taskapp.task.TaskService.UndoApplied;
import com.taskapp.task.dto.BulkAction;
import com.taskapp.task.dto.BulkPatchItem;
import com.taskapp.task.dto.BulkResult;
import com.taskapp.task.dto.BulkUndoCompleteItem;
import com.taskapp.task.dto.BulkUndoCompleteResult;
import com.taskapp.task.dto.ReplaceTaskRequest;
import com.taskapp.task.dto.RestoreTasksResult;
import com.taskapp.task.dto.SetTaskDoneRequest;
import com.taskapp.task.dto.TaskResponse;
import com.taskapp.task.dto.UndoCompleteResult;

/**
 * Bulk writes (contract 0.17.0): 1-200 tasks in one transaction, all or nothing. The writes themselves are the
 * single-task routines of {@link TaskService} ({@code applyStatus}, {@code applyUpdate}, {@code applyUndoComplete}),
 * run task by task on the locked rows, so a bulk change has exactly the rules and side effects of the single one;
 * responses are built for all tasks at once.
 * <p>
 * Locking, the order of {@link TaskService}'s class comment applied to many tasks. (1) A plain read finds the lists of
 * all ids ({@code failedIds}, mixed lists, move targets are decided here, before any lock). (2) One transaction: the
 * {@code users} rows FOR SHARE (caller and every list owner, ascending), every list involved (sources and move
 * targets) FOR UPDATE, or FOR SHARE when the call changes no tags and moves nothing, in ascending id, the caller's
 * membership rows FOR SHARE in the same order, then all task rows in ONE statement ordered by id FOR UPDATE. If a
 * list, a membership or a task vanished or moved in between, the transaction is dropped and starts again from (1) (at
 * most {@value TaskService#MAX_LOCATE_ATTEMPTS} attempts). Two bulk calls, or a bulk and a single write, therefore
 * always take their locks in the same order and cannot deadlock; {@link DeadlockRetry} stays the safety net.
 */
@Service
public class BulkService {

    private final TaskRepository taskRepository;
    private final TagService tagService;
    private final ListService listService;
    private final ReminderService reminders;
    private final TaskService tasks;
    private final TransactionOperations transaction;
    private final Clock clock;

    @Autowired
    public BulkService(TaskRepository taskRepository, TagService tagService, ListService listService,
            ReminderService reminders, TaskService tasks, PlatformTransactionManager transactions, Clock clock) {
        this(taskRepository, tagService, listService, reminders, tasks, new TransactionTemplate(transactions), clock);
    }

    BulkService(TaskRepository taskRepository, TagService tagService, ListService listService,
            ReminderService reminders, TaskService tasks, TransactionOperations transaction, Clock clock) {
        this.taskRepository = taskRepository;
        this.tagService = tagService;
        this.listService = listService;
        this.reminders = reminders;
        this.tasks = tasks;
        this.transaction = transaction;
        this.clock = clock;
    }

    // ---- POST /api/tasks/bulk ----

    public BulkResult bulk(long userId, List<Long> ids, BulkRequests.Parsed parsed) {
        BulkAction action = parsed.action();
        boolean exclusive = action == BulkAction.ADD_TAG || action == BulkAction.REMOVE_TAG
                || action == BulkAction.MOVE;
        Set<Long> targets = parsed.listId() == null ? Set.of() : Set.of(parsed.listId());
        return locked(userId, ids, false, "ids", targets, exclusive, (rows, plan) -> {
            if (action == BulkAction.DELETE) {
                taskRepository.trashAll(ids, userId, now());
                return new BulkResult(ids.size(), List.of());
            }
            Map<Long, Task> byId = byId(rows);
            long listId = rows.get(0).getListId();
            Map<Long, List<String>> names = action == BulkAction.ADD_TAG || action == BulkAction.REMOVE_TAG
                    ? tagService.namesByTask(listId, ids) : Map.of();
            if (action == BulkAction.ADD_TAG) {
                String key = TagNames.key(parsed.tag());
                List<Long> tooMany = ids.stream().filter(id -> {
                    List<String> current = names.getOrDefault(id, List.of());
                    return !has(current, key) && current.size() >= TagNames.MAX_PER_TASK;
                }).toList();
                if (!tooMany.isEmpty()) {
                    throw new BulkTooManyTagsException(tooMany);
                }
            }
            List<Applied> applied = new ArrayList<>();
            for (long id : ids) {
                applied.add(applyAction(userId, byId.get(id), parsed, names.getOrDefault(id, List.of()), plan));
            }
            return new BulkResult(ids.size(), tasks.results(userId, applied));
        });
    }

    private Applied applyAction(long userId, Task task, BulkRequests.Parsed parsed, List<String> names, Plan plan) {
        Located here = new Located(plan.sources().get(task.getListId()), null);
        switch (parsed.action()) {
            case COMPLETE -> {
                return task.getStatus() == TaskStatus.DONE ? unchanged(task)
                        : tasks.applyStatus(userId, task, TaskStatus.DONE, null, true);
            }
            case REOPEN -> {
                return task.getStatus() != TaskStatus.DONE ? unchanged(task)
                        : tasks.applyStatus(userId, task, null, false, true);
            }
            case SET_STATUS -> {
                return task.getStatus() == parsed.status() ? unchanged(task)
                        : tasks.applyStatus(userId, task, parsed.status(), null, true);
            }
            case SET_PRIORITY -> {
                return task.getPriority() == parsed.priority() ? unchanged(task)
                        : tasks.applyUpdate(userId, task, here, TaskService.merge(task,
                                patch(parsed.priority(), null, null)), false, true);
            }
            case ADD_TAG -> {
                if (has(names, TagNames.key(parsed.tag()))) {
                    return unchanged(task);
                }
                List<String> tags = new ArrayList<>(names);
                tags.add(parsed.tag());
                return tasks.applyUpdate(userId, task, here, TaskService.merge(task, patch(null, tags, null)), true,
                        true);
            }
            case REMOVE_TAG -> {
                String key = TagNames.key(parsed.tag());
                if (!has(names, key)) {
                    return unchanged(task);
                }
                List<String> tags = names.stream().filter(n -> !TagNames.key(n).equals(key)).toList();
                return tasks.applyUpdate(userId, task, here, TaskService.merge(task, patch(null, tags, null)), true,
                        true);
            }
            case MOVE -> {
                if (task.getListId().equals(parsed.listId())) {
                    return unchanged(task);
                }
                Located move = new Located(plan.sources().get(task.getListId()),
                        plan.targets().get(parsed.listId()));
                return tasks.applyUpdate(userId, task, move, TaskService.merge(task,
                        patch(null, null, parsed.listId())), true, true);
            }
            default -> throw new IllegalStateException("not applied per task: " + parsed.action());
        }
    }

    private static SetTaskDoneRequest patch(Priority priority, List<String> tags, Long listId) {
        return new SetTaskDoneRequest(null, null, null, null, null, null, priority, tags, listId, null, null);
    }

    private static boolean has(List<String> names, String key) {
        return names.stream().anyMatch(n -> TagNames.key(n).equals(key));
    }

    private static Applied unchanged(Task task) {
        return new Applied(task, null, null);
    }

    // ---- POST /api/tasks/bulk-patch ----

    public BulkResult bulkPatch(long userId, List<BulkPatchItem> items) {
        List<Long> ids = items.stream().map(BulkPatchItem::id).toList();
        Set<Long> targets = items.stream().map(i -> i.patch().listId()).filter(java.util.Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        boolean exclusive = items.stream().anyMatch(i -> exclusive(i.patch()));
        return locked(userId, ids, false, "items", targets, exclusive, (rows, plan) -> {
            Map<Long, Task> byId = byId(rows);
            List<Applied> applied = new ArrayList<>();
            for (BulkPatchItem item : items) {
                Task task = byId.get(item.id());
                SetTaskDoneRequest patch = item.patch();
                if (patch.onlyStatus()) {
                    applied.add(tasks.applyStatus(userId, task, patch.status(), patch.done(), true));
                    continue;
                }
                TaskList target = patch.listId() == null || patch.listId().equals(task.getListId()) ? null
                        : plan.targets().get(patch.listId());
                Located lists = new Located(plan.sources().get(task.getListId()), target);
                applied.add(tasks.applyUpdate(userId, task, lists, TaskService.merge(task, patch), exclusive(patch),
                        true));
            }
            return new BulkResult(ids.size(), tasks.results(userId, applied));
        });
    }

    private static boolean exclusive(SetTaskDoneRequest patch) {
        return !patch.onlyStatus() && (patch.tags() != null || patch.listId() != null);
    }

    // ---- POST /api/tasks/bulk-undo-complete ----

    public BulkUndoCompleteResult bulkUndoComplete(long userId, List<BulkUndoCompleteItem> items) {
        List<Long> ids = items.stream().map(BulkUndoCompleteItem::id).toList();
        return locked(userId, ids, false, null, Set.of(), true, (rows, plan) -> {
            Map<Long, Task> byId = byId(rows);
            List<UndoApplied> done = new ArrayList<>();
            for (BulkUndoCompleteItem item : items) {
                done.add(tasks.applyUndoComplete(userId, byId.get(item.id()), item.nextTaskId(), item.status(), true));
            }
            List<TaskResponse> responses = tasks.responses(userId,
                    done.stream().map(d -> d.applied().task()).toList(), Map.of());
            List<UndoCompleteResult> results = new ArrayList<>();
            for (int i = 0; i < done.size(); i++) {
                results.add(new UndoCompleteResult(responses.get(i), done.get(i).outcome()));
            }
            return new BulkUndoCompleteResult(results);
        });
    }

    // ---- POST /api/trash/restore ----

    public RestoreTasksResult restore(long userId, List<Long> ids) {
        return locked(userId, ids, true, null, Set.of(), false, (rows, plan) -> {
            if (taskRepository.restoreAll(ids) != ids.size()) {
                // Not reachable: the rows are locked and were read as trashed.
                throw new BulkTasksNotFoundException(ids);
            }
            Map<Long, Task> restored = byId(taskRepository.findAllById(ids));
            List<Task> ordered = ids.stream().map(restored::get).toList();
            ordered.forEach(reminders::afterRestore);
            return new RestoreTasksResult(tasks.responses(userId, ordered, Map.of()));
        });
    }

    // ---- locking ----

    /** The lists found by the first read: each task's list, and the lists as the caller sees them. */
    private record Plan(Map<Long, TaskList> sources, Map<Long, TaskList> targets) {
    }

    /**
     * Runs {@code work} on the locked task rows (ascending id) as described in the class comment. {@code mixedField}
     * non-null: all tasks must be in one list, else 400 {@code MIXED_LISTS} on that field. {@code trashed}: the tasks
     * must be in the trash (restore), else not. {@code targetIds}: lists tasks move to, which must be the caller's.
     */
    private <T> T locked(long userId, List<Long> ids, boolean trashed, String mixedField, Collection<Long> targetIds,
            boolean exclusive, BiFunction<List<Task>, Plan, T> work) {
        for (int attempt = 1;; attempt++) {
            Plan plan = transaction.execute(status -> locate(userId, ids, trashed, mixedField, targetIds));
            Optional<T> result = DeadlockRetry.run(() -> transaction.execute(status -> {
                Optional<T> done = lockedOnce(userId, ids, plan, trashed, exclusive, work);
                if (done.isEmpty()) {
                    status.setRollbackOnly();
                }
                return done;
            }));
            if (result.isPresent()) {
                return result.get();
            }
            if (attempt >= TaskService.MAX_LOCATE_ATTEMPTS) {
                throw new BulkTasksNotFoundException(ids);
            }
        }
    }

    private Plan locate(long userId, List<Long> ids, boolean trashed, String mixedField, Collection<Long> targetIds) {
        List<TaskRepository.IdList> rows = trashed ? taskRepository.findTrashedAccessibleIds(ids, userId)
                : taskRepository.findAccessibleIds(ids, userId);
        Map<Long, Long> listOf = new HashMap<>();
        rows.forEach(row -> listOf.put(row.getId(), row.getListId()));
        List<Long> missing = ids.stream().filter(id -> !listOf.containsKey(id)).toList();
        if (!missing.isEmpty()) {
            throw new BulkTasksNotFoundException(missing);
        }
        Map<Long, TaskList> targets = new HashMap<>();
        for (long target : targetIds) {
            targets.put(target, listService.requireMember(userId, target));
        }
        Set<Long> listIds = new TreeSet<>(listOf.values());
        if (mixedField != null && listIds.size() > 1) {
            MapBindingResult errors = new MapBindingResult(new HashMap<>(), "bulkRequest");
            errors.rejectValue(mixedField, "MixedLists", "The tasks are not all in the same list");
            throw new InvalidFieldsException(errors);
        }
        Map<Long, TaskList> sources = new HashMap<>();
        for (long listId : listIds) {
            Optional<TaskList> list = listService.findMember(userId, listId);
            if (list.isEmpty()) {
                throw new BulkTasksNotFoundException(
                        listOf.entrySet().stream().filter(e -> e.getValue() == listId).map(Map.Entry::getKey).toList());
            }
            sources.put(listId, list.get());
        }
        return new Plan(sources, targets);
    }

    private <T> Optional<T> lockedOnce(long userId, List<Long> ids, Plan plan, boolean trashed, boolean exclusive,
            BiFunction<List<Task>, Plan, T> work) {
        Set<Long> owners = new TreeSet<>();
        plan.sources().values().forEach(l -> owners.add(l.getOwnerId()));
        plan.targets().values().forEach(l -> owners.add(l.getOwnerId()));
        if (!listService.shareUsers(userId, owners)) {
            throw new BulkTasksNotFoundException(ids);
        }
        Set<Long> lists = new TreeSet<>(plan.sources().keySet());
        lists.addAll(plan.targets().keySet());
        for (long listId : lists) {
            boolean there = exclusive ? listService.lockList(listId) : listService.shareList(listId);
            if (!there) {
                if (!plan.sources().containsKey(listId)) {
                    throw new ListNotFoundException();
                }
                return Optional.empty();
            }
        }
        for (long listId : lists) {
            if (!listService.shareMember(listId, userId)) {
                if (!plan.sources().containsKey(listId)) {
                    throw new ListNotFoundException();
                }
                return Optional.empty();
            }
        }
        Set<Long> sourceIds = plan.sources().keySet();
        List<Task> rows = trashed ? taskRepository.lockTrashed(ids, sourceIds) : taskRepository.lockLive(ids, sourceIds);
        if (rows.size() != ids.size()) {
            return Optional.empty();
        }
        return Optional.of(work.apply(rows, plan));
    }

    private static Map<Long, Task> byId(Collection<Task> rows) {
        Map<Long, Task> byId = new HashMap<>();
        rows.forEach(task -> byId.put(task.getId(), task));
        return byId;
    }

    private Instant now() {
        return Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
    }
}
