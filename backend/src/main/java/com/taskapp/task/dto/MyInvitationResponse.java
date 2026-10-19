package com.taskapp.task.dto;

import java.time.Instant;

import com.taskapp.task.MembershipRepository.MyInvitationRow;

/** Schema {@code MyInvitation} (0.13.0): only the owner's display name and the list's name, no emails. */
public record MyInvitationResponse(long id, ListRef list, InvitedBy invitedBy, Instant invitedAt, Instant expiresAt) {

    public record ListRef(long id, String name) {
    }

    public record InvitedBy(String displayName) {
    }

    public static MyInvitationResponse from(MyInvitationRow row) {
        return new MyInvitationResponse(row.id(), new ListRef(row.listId(), row.listName()),
                new InvitedBy(row.ownerDisplayName()), row.invitedAt(), row.expiresAt());
    }
}
