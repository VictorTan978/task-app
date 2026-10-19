package com.taskapp.task.dto;

import java.time.Instant;

import com.taskapp.task.MembershipRepository.InvitationRow;

/**
 * Schema {@code ListInvitation} (0.13.0): built only from the stored row, so it is the same whether or not an account
 * has the address (AC18).
 */
public record ListInvitationResponse(long id, String email, Instant invitedAt, Instant expiresAt) {

    public static ListInvitationResponse from(InvitationRow row) {
        return new ListInvitationResponse(row.id(), row.email(), row.invitedAt(), row.expiresAt());
    }
}
