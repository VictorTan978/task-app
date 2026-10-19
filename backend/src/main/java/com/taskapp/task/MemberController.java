package com.taskapp.task;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.taskapp.security.AuthenticatedUser;
import com.taskapp.task.dto.CreateInvitationRequest;
import com.taskapp.task.dto.ListInvitationResponse;
import com.taskapp.task.dto.MemberResponse;

import jakarta.validation.Valid;

/**
 * Members and invitations of one list (contract 0.13.0). Path ids are bound before the body, so a non-integer id is
 * reported before anything in the body. Invite answers 201 without a Location header (no endpoint reads one
 * invitation).
 */
@RestController
@RequestMapping("/api/lists/{id}")
public class MemberController {

    private final MemberService memberService;
    private final InvitationService invitationService;

    public MemberController(MemberService memberService, InvitationService invitationService) {
        this.memberService = memberService;
        this.invitationService = invitationService;
    }

    @GetMapping("/members")
    public List<MemberResponse> members(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable("id") long id) {
        return memberService.members(user.id(), id);
    }

    @DeleteMapping("/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable("id") long id,
            @PathVariable("userId") long userId) {
        memberService.remove(user.id(), id, userId);
    }

    @PostMapping("/leave")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void leave(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable("id") long id) {
        memberService.leave(user.id(), id);
    }

    @GetMapping("/invitations")
    public List<ListInvitationResponse> invitations(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable("id") long id) {
        return invitationService.pending(user.id(), id);
    }

    @PostMapping("/invitations")
    @ResponseStatus(HttpStatus.CREATED)
    public ListInvitationResponse invite(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable("id") long id,
            @Valid @RequestBody CreateInvitationRequest body) {
        return invitationService.invite(user.id(), user.email(), id, body);
    }

    @DeleteMapping("/invitations/{invitationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable("id") long id,
            @PathVariable("invitationId") long invitationId) {
        invitationService.cancel(user.id(), id, invitationId);
    }
}
