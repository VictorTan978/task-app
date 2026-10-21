package com.taskapp.task;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.taskapp.security.AuthenticatedUser;
import com.taskapp.task.dto.EmptyTrashResult;
import com.taskapp.task.dto.RestoreTasksRequest;
import com.taskapp.task.dto.RestoreTasksResult;
import com.taskapp.task.dto.TaskResponse;
import com.taskapp.task.dto.TrashPage;

import jakarta.servlet.http.HttpServletRequest;

/**
 * The trash (contract 0.16.0). {@code list} and {@code cursor} are read with {@code getParameter} (the first value),
 * like {@link TaskController}. Anonymous callers never reach this: the security chain answers 401.
 */
@RestController
@RequestMapping("/api/trash")
public class TrashController {

    private final TrashService trash;
    private final TaskService tasks;
    private final BulkService bulk;

    public TrashController(TrashService trash, TaskService tasks, BulkService bulk) {
        this.trash = trash;
        this.tasks = tasks;
        this.bulk = bulk;
    }

    @GetMapping
    public TrashPage list(@AuthenticationPrincipal AuthenticatedUser user, HttpServletRequest request) {
        return trash.list(user.id(), request.getParameter("list"), request.getParameter("cursor"));
    }

    /** {@code POST /api/trash/restore} (0.17.0): restores 1-200 tasks, all or nothing. */
    @PostMapping("/restore")
    public RestoreTasksResult restoreMany(@AuthenticationPrincipal AuthenticatedUser user,
            @RequestBody RestoreTasksRequest body) {
        BindingResult errors = BulkRequests.errors();
        BulkRequests.ids(errors, "ids", body.ids());
        BulkRequests.throwIfAny(errors);
        return bulk.restore(user.id(), body.ids());
    }

    @PostMapping("/{id}/restore")
    public TaskResponse restore(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable("id") long id) {
        return tasks.restore(user.id(), id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteForever(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable("id") long id) {
        tasks.deleteForever(user.id(), id);
    }

    @PostMapping("/empty")
    public EmptyTrashResult empty(@AuthenticationPrincipal AuthenticatedUser user, HttpServletRequest request) {
        return trash.empty(user.id(), request.getParameter("list"));
    }
}
