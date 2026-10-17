package com.taskapp.task;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.validation.MapBindingResult;

import com.taskapp.auth.InvalidFieldsException;
import com.taskapp.task.dto.CreateTaskRequest;
import com.taskapp.task.dto.ReplaceTaskRequest;
import com.taskapp.task.dto.TaskPage;
import com.taskapp.task.dto.TaskResponse;

/**
 * All operations take the owner id from the session; another user's task is reported as not found. Status and its
 * {@code done} mirror are always written together ({@link Task#changeStatus}).
 */
@Service
public class TaskService {

    /** Constraint name the error handler maps to {@code INVALID_CURSOR}. */
    public static final String INVALID_CURSOR_CONSTRAINT = "InvalidCursor";

    /** Fixed page size of {@code GET /api/tasks}. */
    static final int PAGE_SIZE = 50;

    static final String CURSOR_MESSAGE = "The list changed, reload from the first page";
    static final String SEARCH_TOO_LONG_MESSAGE = "Search must be at most 100 characters";

    private final TaskRepository taskRepository;
    private final TagService tagService;
    private final TransactionOperations transaction;
    private final Clock clock;

    @Autowired
    public TaskService(TaskRepository taskRepository, TagService tagService, PlatformTransactionManager transactions) {
        this(taskRepository, tagService, new TransactionTemplate(transactions), Clock.systemUTC());
    }

    TaskService(TaskRepository taskRepository, TagService tagService, TransactionOperations transaction, Clock clock) {
        this.taskRepository = taskRepository;
        this.tagService = tagService;
        this.transaction = transaction;
        this.clock = clock;
    }

    /**
     * One page of the owner's tasks (contract 0.11.0). 400 ({@link InvalidFieldsException}) for a search over 100
     * characters or a cursor that can't be read or was issued for another filter/sort/tag/search. A tag that is
     * invalid or not one of the owner's gives an empty page. Reads 51 rows to know whether a next page exists; the
     * total is counted in the same snapshot (skipped when the first page already holds everything). Tags of the
     * page's tasks: one query.
     */
    @Transactional(readOnly = true)
    public TaskPage list(long ownerId, TaskListQuery query) {
        MapBindingResult errors = new MapBindingResult(new HashMap<>(), "taskListQuery");
        TaskCursor after = null;
        byte[] fingerprint = query.fingerprint();
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
            Optional<Long> found = tagService.findTagId(ownerId, query.tag());
            if (found.isEmpty()) {
                return TaskPage.empty();
            }
            tagId = found.get();
        }
        TaskSearchCriteria criteria = new TaskSearchCriteria(ownerId, query.filter(), query.sort(), tagId,
                query.search());
        List<Task> rows = taskRepository.findPage(criteria, after, PAGE_SIZE + 1);
        boolean more = rows.size() > PAGE_SIZE;
        List<Task> tasks = more ? rows.subList(0, PAGE_SIZE) : rows;
        long total = after == null && !more ? tasks.size() : taskRepository.countMatching(criteria);
        String nextCursor = more
                ? TaskCursor.after(tasks.get(tasks.size() - 1), query.sort()).encode(query.sort(), fingerprint)
                : null;
        Map<Long, List<String>> tags = tagService.namesByTask(ownerId, tasks.stream().map(Task::getId).toList());
        List<TaskResponse> items = tasks.stream()
                .map(task -> TaskResponse.from(task, tags.getOrDefault(task.getId(), List.of())))
                .toList();
        return new TaskPage(items, total, nextCursor);
    }

    @Transactional(readOnly = true)
    public TaskResponse get(long ownerId, long id) {
        return withTags(ownerId, find(ownerId, id));
    }

    /** Request fields are already stripped/normalized by {@link CreateTaskRequest}. New tasks have no tags. */
    @Transactional
    public TaskResponse create(long ownerId, CreateTaskRequest request) {
        Task task = new Task(ownerId, request.title(), request.description(), request.dueDate(), now());
        return TaskResponse.from(taskRepository.saveAndFlush(task), List.of());
    }

    /**
     * Missing/null priority, status/done and tags leave those unchanged; {@code tags} replaces when sent. One
     * transaction, run again if it lost a deadlock ({@link DeadlockRetry}).
     */
    public TaskResponse replace(long ownerId, long id, ReplaceTaskRequest request) {
        return DeadlockRetry.run(() -> transaction.execute(status -> replaceOnce(ownerId, id, request)));
    }

    private TaskResponse replaceOnce(long ownerId, long id, ReplaceTaskRequest request) {
        if (request.tags() != null) {
            tagService.lockOwner(ownerId);
        }
        Task task = find(ownerId, id);
        Instant now = now();
        task.replace(request.title(), request.description(), request.dueDate(), now);
        if (request.priority() != null) {
            task.changePriority(request.priority(), now);
        }
        task.changeStatus(TaskStatus.resolve(task.getStatus(), request.status(), request.done()), now);
        saveOrNotFound(task);
        if (request.tags() != null) {
            return TaskResponse.from(task, tagService.replaceTags(ownerId, task.getId(), request.tags()));
        }
        return withTags(ownerId, task);
    }

    /** PATCH: {@code status} and/or {@code done} (validated: at least one, and they agree). */
    @Transactional
    public TaskResponse setStatus(long ownerId, long id, TaskStatus status, Boolean done) {
        Task task = find(ownerId, id);
        task.changeStatus(TaskStatus.resolve(task.getStatus(), status, done), now());
        saveOrNotFound(task);
        return withTags(ownerId, task);
    }

    /**
     * {@code task} was read a moment ago with {@link #find}, which found it; there is no {@code @Version} column, so
     * the only way the flush now matches zero rows is a concurrent delete that has since committed. Reported the
     * same way as never having found it, i.e. 404, instead of the generic 500 Hibernate's optimistic-lock failure
     * would otherwise become.
     */
    private void saveOrNotFound(Task task) {
        try {
            taskRepository.saveAndFlush(task);
        } catch (ObjectOptimisticLockingFailureException e) {
            throw new TaskNotFoundException();
        }
    }

    /**
     * The task's task_tags rows go with it (ON DELETE CASCADE); tags it was the last user of are deleted too. Locks:
     * the owner lock, then X record locks on the task row and its link rows; the FK cascade and the tag deletes add
     * gap-only locks, which never wait, after which nothing else is written. One transaction, run again if it lost
     * a deadlock ({@link DeadlockRetry}).
     */
    public void delete(long ownerId, long id) {
        DeadlockRetry.run(() -> transaction.execute(status -> {
            deleteOnce(ownerId, id);
            return null;
        }));
    }

    private void deleteOnce(long ownerId, long id) {
        tagService.lockOwner(ownerId);
        Task task = find(ownerId, id);
        List<Long> tagIds = tagService.tagIdsOf(task.getId());
        taskRepository.delete(task);
        taskRepository.flush();
        tagService.deleteUnused(ownerId, tagIds);
    }

    private TaskResponse withTags(long ownerId, Task task) {
        return TaskResponse.from(task, tagService.namesOf(ownerId, task.getId()));
    }

    private Task find(long ownerId, long id) {
        return taskRepository.findByIdAndOwnerId(id, ownerId).orElseThrow(TaskNotFoundException::new);
    }

    /** Microseconds match MySQL DATETIME(6), so the response equals what a later GET returns. */
    private Instant now() {
        return Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
    }
}
