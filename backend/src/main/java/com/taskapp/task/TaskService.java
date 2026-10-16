package com.taskapp.task;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import com.taskapp.task.dto.CreateTaskRequest;
import com.taskapp.task.dto.ReplaceTaskRequest;
import com.taskapp.task.dto.TaskResponse;

/**
 * All operations take the owner id from the session; another user's task is reported as not found. Status and its
 * {@code done} mirror are always written together ({@link Task#changeStatus}).
 */
@Service
public class TaskService {

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
     * {@code rawTag} is the first {@code ?tag=} value: null/blank = no tag filter; invalid or not one of the owner's
     * tags = no tasks.
     */
    @Transactional(readOnly = true)
    public List<TaskResponse> list(long ownerId, TaskFilter filter, String rawTag) {
        List<Task> tasks;
        if (TagNames.isBlank(rawTag)) {
            tasks = switch (filter) {
                case ALL -> taskRepository.findByOwnerIdOrderByDoneAscCreatedAtDescIdDesc(ownerId);
                case ACTIVE -> taskRepository.findByOwnerIdAndDoneOrderByCreatedAtDescIdDesc(ownerId, false);
                case IN_PROGRESS -> taskRepository.findByOwnerIdAndStatusOrderByCreatedAtDescIdDesc(ownerId,
                        TaskStatus.IN_PROGRESS);
                case DONE -> taskRepository.findByOwnerIdAndDoneOrderByCreatedAtDescIdDesc(ownerId, true);
            };
        } else {
            Optional<Long> tagId = tagService.findTagId(ownerId, rawTag);
            if (tagId.isEmpty()) {
                return List.of();
            }
            tasks = taskRepository.findByOwnerIdAndTagId(ownerId, tagId.get(),
                    filter.statuses().stream().map(Enum::name).toList());
        }
        if (tasks.isEmpty()) {
            return List.of();
        }
        Map<Long, List<String>> tags = tagService.namesByTask(ownerId);
        return tasks.stream().map(task -> TaskResponse.from(task, tags.getOrDefault(task.getId(), List.of()))).toList();
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
        taskRepository.saveAndFlush(task);
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
        taskRepository.saveAndFlush(task);
        return withTags(ownerId, task);
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
