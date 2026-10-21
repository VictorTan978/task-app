package com.taskapp.task.dto;

/** The action of {@code POST /api/tasks/bulk} (contract 0.17.0). */
public enum BulkAction {
    COMPLETE, REOPEN, SET_STATUS, SET_PRIORITY, ADD_TAG, REMOVE_TAG, MOVE, DELETE
}
