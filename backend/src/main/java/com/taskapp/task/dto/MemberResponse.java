package com.taskapp.task.dto;

import java.time.Instant;

import com.taskapp.task.ListRole;
import com.taskapp.task.MembershipRepository.MemberRow;

/** Schema {@code Member} (0.13.0). */
public record MemberResponse(long userId, String displayName, String email, ListRole role, Instant joinedAt) {

    public static MemberResponse from(MemberRow row) {
        return new MemberResponse(row.userId(), row.displayName(), row.email(),
                row.owner() ? ListRole.OWNER : ListRole.MEMBER, row.joinedAt());
    }
}
