package com.taskapp.task;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.taskapp.task.dto.CreateTaskRequest;
import com.taskapp.task.dto.ReplaceTaskRequest;
import com.taskapp.task.dto.TaskResponse;

/** All operations take the owner id from the session; another user's task is reported as not found. */
@Service
public class TaskService {

    private final TaskRepository taskRepository;
    private final Clock clock;

    @Autowired
    public TaskService(TaskRepository taskRepository) {
        this(taskRepository, Clock.systemUTC());
    }

    TaskService(TaskRepository taskRepository, Clock clock) {
        this.taskRepository = taskRepository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<TaskResponse> list(long ownerId, TaskFilter filter) {
        List<Task> tasks = switch (filter) {
            case ALL -> taskRepository.findByOwnerIdOrderByDoneAscCreatedAtDescIdDesc(ownerId);
            case ACTIVE -> taskRepository.findByOwnerIdAndDoneOrderByCreatedAtDescIdDesc(ownerId, false);
            case DONE -> taskRepository.findByOwnerIdAndDoneOrderByCreatedAtDescIdDesc(ownerId, true);
        };
        return tasks.stream().map(TaskResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public TaskResponse get(long ownerId, long id) {
        return TaskResponse.from(find(ownerId, id));
    }

    /** Request fields are already stripped/normalized by {@link CreateTaskRequest}. */
    @Transactional
    public TaskResponse create(long ownerId, CreateTaskRequest request) {
        Task task = new Task(ownerId, request.title(), request.description(), request.dueDate(), now());
        return TaskResponse.from(taskRepository.saveAndFlush(task));
    }

    @Transactional
    public TaskResponse replace(long ownerId, long id, ReplaceTaskRequest request) {
        Task task = find(ownerId, id);
        task.replace(request.title(), request.description(), request.dueDate(), request.done(), now());
        return TaskResponse.from(taskRepository.saveAndFlush(task));
    }

    @Transactional
    public TaskResponse setDone(long ownerId, long id, boolean done) {
        Task task = find(ownerId, id);
        task.setDone(done, now());
        return TaskResponse.from(taskRepository.saveAndFlush(task));
    }

    @Transactional
    public void delete(long ownerId, long id) {
        taskRepository.delete(find(ownerId, id));
    }

    private Task find(long ownerId, long id) {
        return taskRepository.findByIdAndOwnerId(id, ownerId).orElseThrow(TaskNotFoundException::new);
    }

    /** Microseconds match PostgreSQL TIMESTAMPTZ, so the response equals what a later GET returns. */
    private Instant now() {
        return Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
    }
}
