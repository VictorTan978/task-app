package com.taskapp.task.dto;

import com.taskapp.task.TaskList;

/** Schema {@code TaskList} (contract 0.12.0). Never exposes the owner. */
public record TaskListResponse(long id, String name, boolean personal) {

    public static TaskListResponse from(TaskList list) {
        return new TaskListResponse(list.getId(), list.getName(), list.isPersonal());
    }
}
