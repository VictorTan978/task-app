package com.taskapp.task;

import java.net.URI;
import java.util.HashMap;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.BindingResult;
import org.springframework.validation.MapBindingResult;
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

import com.taskapp.auth.InvalidFieldsException;
import com.taskapp.security.AuthenticatedUser;
import com.taskapp.task.dto.BulkActionRequest;
import com.taskapp.task.dto.BulkPatchItem;
import com.taskapp.task.dto.BulkPatchRequest;
import com.taskapp.task.dto.BulkResult;
import com.taskapp.task.dto.BulkUndoCompleteItem;
import com.taskapp.task.dto.BulkUndoCompleteRequest;
import com.taskapp.task.dto.BulkUndoCompleteResult;
import com.taskapp.task.dto.CreateTaskRequest;
import com.taskapp.task.dto.ReplaceTaskRequest;
import com.taskapp.task.dto.SetTaskDoneRequest;
import com.taskapp.task.dto.TaskPage;
import com.taskapp.task.dto.TaskResponse;
import com.taskapp.task.dto.TaskResult;
import com.taskapp.task.dto.UndoCompleteRequest;
import com.taskapp.task.dto.UndoCompleteResult;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

/** Anonymous callers never reach this: the security chain answers 401. */
@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private final TaskService taskService;
    private final BulkService bulkService;

    public TaskController(TaskService taskService, BulkService bulkService) {
        this.taskService = taskService;
        this.bulkService = bulkService;
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

    /** {@code POST /api/tasks/bulk} (0.17.0): one action on 1-200 tasks of one list. */
    @PostMapping("/bulk")
    public BulkResult bulk(@AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody BulkActionRequest body) {
        BindingResult errors = BulkRequests.errors();
        BulkRequests.ids(errors, "ids", body.ids());
        BulkRequests.Parsed parsed = BulkRequests.action(errors, body);
        BulkRequests.throwIfAny(errors);
        return bulkService.bulk(user.id(), body.ids(), parsed);
    }

    /** {@code POST /api/tasks/bulk-patch} (0.17.0): a PATCH body per task, 1-200 tasks of one list. */
    @PostMapping("/bulk-patch")
    public BulkResult bulkPatch(@AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody BulkPatchRequest body) {
        BindingResult errors = BulkRequests.errors();
        BulkRequests.ids(errors, "items", body.items().stream().map(BulkPatchItem::id).toList());
        for (int i = 0; i < body.items().size(); i++) {
            BulkRequests.patchDescription(errors, i, body.items().get(i).patch());
        }
        BulkRequests.throwIfAny(errors);
        return bulkService.bulkPatch(user.id(), body.items());
    }

    /** {@code POST /api/tasks/bulk-undo-complete} (0.17.0): {@code undo-complete} for 1-200 tasks. */
    @PostMapping("/bulk-undo-complete")
    public BulkUndoCompleteResult bulkUndoComplete(@AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody BulkUndoCompleteRequest body) {
        BindingResult errors = BulkRequests.errors();
        BulkRequests.ids(errors, "items", body.items().stream().map(BulkUndoCompleteItem::id).toList());
        for (int i = 0; i < body.items().size(); i++) {
            BulkRequests.undoItem(errors, i, body.items().get(i));
        }
        BulkRequests.throwIfAny(errors);
        return bulkService.bulkUndoComplete(user.id(), body.items());
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
        checkDescriptionLength(body);
        return taskService.patch(user.id(), id, body);
    }

    /**
     * {@code POST /api/tasks/{id}/undo-complete} (0.16.0). {@code status} {@code DONE} is 400 {@code status} /
     * {@code OUT_OF_RANGE} (a reopen cannot end in DONE).
     */
    @PostMapping("/{id}/undo-complete")
    public UndoCompleteResult undoComplete(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable("id") long id, @Valid @RequestBody UndoCompleteRequest body) {
        if (body.status() == TaskStatus.DONE) {
            MapBindingResult errors = new MapBindingResult(new HashMap<>(), "undoCompleteRequest");
            errors.rejectValue("status", "Min", "Status must be TODO or IN_PROGRESS");
            throw new InvalidFieldsException(errors);
        }
        return taskService.undoComplete(user.id(), id, body.nextTaskId(), body.status());
    }

    /** The description's length after stripping (a {@link TextUpdate} cannot carry a bean validation constraint). */
    private static void checkDescriptionLength(SetTaskDoneRequest body) {
        if (body.description() != null && body.description().value() != null
                && body.description().value().length() > 2000) {
            MapBindingResult errors = new MapBindingResult(new HashMap<>(), "setTaskDoneRequest");
            errors.rejectValue("description", "Size", "Description must be 2,000 characters or fewer");
            throw new InvalidFieldsException(errors);
        }
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable("id") long id) {
        taskService.delete(user.id(), id);
    }
}
