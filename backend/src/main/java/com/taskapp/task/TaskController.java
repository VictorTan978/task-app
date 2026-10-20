package com.taskapp.task;

import java.net.URI;

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
import com.taskapp.task.dto.TaskPage;
import com.taskapp.task.dto.TaskResponse;
import com.taskapp.task.dto.TaskResult;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

/** Anonymous callers never reach this: the security chain answers 401. */
@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private final TaskService taskService;

    public TaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    /**
     * {@code list}, {@code tag}, {@code q}, {@code sort} and {@code cursor} are read with {@code getParameter} (the
     * first value): binding them to a String would join repeated values with commas. {@code filter} keeps its 0.10.0
     * binding (repeated values join into an unknown value, i.e. {@code all}).
     */
    @GetMapping
    public TaskPage list(@AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(name = "filter", required = false) String filter, HttpServletRequest request) {
        return taskService.list(user.id(), TaskListQuery.of(request.getParameter("list"), filter,
                request.getParameter("sort"), request.getParameter("tag"), request.getParameter("q"),
                request.getParameter("cursor")));
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
    public TaskResult replace(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable("id") long id,
            @Valid @RequestBody ReplaceTaskRequest body) {
        return taskService.replace(user.id(), id, body);
    }

    @PatchMapping("/{id}")
    public TaskResult setDone(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable("id") long id,
            @Valid @RequestBody SetTaskDoneRequest body) {
        return taskService.setStatus(user.id(), id, body.status(), body.done());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable("id") long id) {
        taskService.delete(user.id(), id);
    }
}
