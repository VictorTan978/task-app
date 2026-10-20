package com.taskapp.task.dto;

import com.fasterxml.jackson.annotation.JsonUnwrapped;

/** Schema {@code TaskResult} (0.15.0): the task plus {@code nextTask}, the copy this call created (else null). */
public record TaskResult(@JsonUnwrapped TaskResponse task, TaskResponse nextTask) {

    public static TaskResult of(TaskResponse task) {
        return new TaskResult(task, null);
    }
}
