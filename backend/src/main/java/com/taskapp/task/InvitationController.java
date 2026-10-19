package com.taskapp.task;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.taskapp.security.AuthenticatedUser;
import com.taskapp.task.dto.InvitationCountResponse;
import com.taskapp.task.dto.MyInvitationResponse;
import com.taskapp.task.dto.TaskListResponse;

/** The signed-in user's own pending invitations (contract 0.13.0). */
@RestController
@RequestMapping("/api/invitations")
public class InvitationController {

    private final InvitationService invitationService;

    public InvitationController(InvitationService invitationService) {
        this.invitationService = invitationService;
    }

    @GetMapping
    public List<MyInvitationResponse> mine(@AuthenticationPrincipal AuthenticatedUser user) {
        return invitationService.mine(user.id());
    }

    @GetMapping("/count")
    public InvitationCountResponse count(@AuthenticationPrincipal AuthenticatedUser user) {
        return invitationService.count(user.id());
    }

    @PostMapping("/{id}/accept")
    public TaskListResponse accept(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable("id") long id) {
        return invitationService.accept(user.id(), id);
    }

    @PostMapping("/{id}/decline")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void decline(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable("id") long id) {
        invitationService.decline(user.id(), id);
    }
}
