package com.taskapp.task.dto;

import com.taskapp.task.ListRole;
import com.taskapp.task.TaskList;

/** Schema {@code TaskListDetails} (contract 0.12.0): {@code TaskList} plus the number of tasks, every status. */
public record TaskListDetailsResponse(long id, String name, boolean personal, ListRole role, int memberCount,
        long taskCount) {

    public static TaskListDetailsResponse from(TaskList list, ListRole role, int memberCount, long taskCount) {
        return new TaskListDetailsResponse(list.getId(), list.getName(), list.isPersonal(), role, memberCount,
                taskCount);
    }
}
