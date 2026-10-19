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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.taskapp.security.AuthenticatedUser;
import com.taskapp.task.dto.CreateListRequest;
import com.taskapp.task.dto.RenameListRequest;
import com.taskapp.task.dto.TaskListDetailsResponse;
import com.taskapp.task.dto.TaskListResponse;

import jakarta.validation.Valid;

/**
 * The signed-in user's lists (contract 0.12.0; 0.13.0: owned and shared, rename and delete owner only). Anonymous
 * callers never reach this: the security chain answers 401.
 * The path id is bound before the body, so a non-integer id is reported before anything in the body.
 */
@RestController
@RequestMapping("/api/lists")
public class ListController {

    private final ListService listService;

    public ListController(ListService listService) {
        this.listService = listService;
    }

    @GetMapping
    public List<TaskListResponse> list(@AuthenticationPrincipal AuthenticatedUser user) {
        return listService.list(user.id());
    }

    @PostMapping
    public ResponseEntity<TaskListResponse> create(@AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody CreateListRequest body) {
        TaskListResponse list = listService.create(user.id(), body);
        return ResponseEntity.created(URI.create("/api/lists/" + list.id())).body(list);
    }

    @GetMapping("/{id}")
    public TaskListDetailsResponse get(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable("id") long id) {
        return listService.get(user.id(), id);
    }

    @PatchMapping("/{id}")
    public TaskListResponse rename(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable("id") long id,
            @Valid @RequestBody RenameListRequest body) {
        return listService.rename(user.id(), id, body);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable("id") long id) {
        listService.delete(user.id(), id);
    }
}
