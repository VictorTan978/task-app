package com.taskapp.task.dto;

import com.taskapp.task.TaskList;

/** Schema {@code TaskListDetails} (contract 0.12.0): {@code TaskList} plus the number of tasks, every status. */
public record TaskListDetailsResponse(long id, String name, boolean personal, long taskCount) {

    public static TaskListDetailsResponse from(TaskList list, long taskCount) {
        return new TaskListDetailsResponse(list.getId(), list.getName(), list.isPersonal(), taskCount);
    }
}
