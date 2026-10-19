package com.taskapp.task.dto;

import com.taskapp.task.ListRole;
import com.taskapp.task.TaskList;

/**
 * Schema {@code TaskList} (contract 0.12.0; {@code role} and {@code memberCount} 0.13.0). Never exposes the owner's
 * id.
 */
public record TaskListResponse(long id, String name, boolean personal, ListRole role, int memberCount) {

    public static TaskListResponse from(TaskList list, ListRole role, int memberCount) {
        return new TaskListResponse(list.getId(), list.getName(), list.isPersonal(), role, memberCount);
    }
}
