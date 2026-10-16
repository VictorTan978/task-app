package com.taskapp.task.dto;

import com.taskapp.task.TaskStatus;
import com.taskapp.task.validation.DoneOrStatusRequired;
import com.taskapp.task.validation.StatusAndDone;
import com.taskapp.task.validation.StatusMatchesDone;

/** Sets (does not toggle) the status and/or its done mirror; at least one is required. */
@DoneOrStatusRequired
@StatusMatchesDone
public record SetTaskDoneRequest(Boolean done, TaskStatus status) implements StatusAndDone {
}
