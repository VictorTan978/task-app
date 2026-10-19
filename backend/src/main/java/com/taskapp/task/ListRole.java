package com.taskapp.task;

/**
 * Schema {@code ListRole} (0.13.0). Not stored: the owner is {@code lists.owner_id} (never changes), every other row of
 * {@code list_members} is a member.
 */
public enum ListRole {
    OWNER,
    MEMBER;

    public static ListRole of(TaskList list, long userId) {
        return list.getOwnerId() == userId ? OWNER : MEMBER;
    }
}
