package com.taskapp.task;

import java.net.URI;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.taskapp.security.AuthenticatedUser;
import com.taskapp.task.dto.CreateTaskRequest;
import com.taskapp.task.dto.ReplaceTaskRequest;
import com.taskapp.task.dto.SetTaskDoneRequest;
import com.taskapp.task.dto.TaskResponse;

import jakarta.validation.Valid;

/** Anonymous callers never reach this: the security chain answers 401. */
@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private final TaskService taskService;

    public TaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    @GetMapping
    public List<TaskResponse> list(@AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(name = "filter", required = false) String filter) {
        return taskService.list(user.id(), TaskFilter.parse(filter));
    }

    @PostMapping
    public ResponseEntity<TaskResponse> create(@AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody CreateTaskRequest body) {
        TaskResponse task = taskService.create(user.id(), body);
        return ResponseEntity.created(URI.create("/api/tasks/" + task.id())).body(task);
    }

    @GetMapping("/{id}")
    public TaskResponse get(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable("id") long id) {
        return taskService.get(user.id(), id);
    }

    @PutMapping("/{id}")
    public TaskResponse replace(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable("id") long id,
            @Valid @RequestBody ReplaceTaskRequest body) {
        return taskService.replace(user.id(), id, body);
    }

    @PatchMapping("/{id}")
    public TaskResponse setDone(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable("id") long id,
            @Valid @RequestBody SetTaskDoneRequest body) {
        return taskService.setDone(user.id(), id, body.done());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable("id") long id) {
        taskService.delete(user.id(), id);
    }
}
