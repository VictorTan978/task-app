package com.taskapp.task.dto;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonUnwrapped;

/**
 * Schema {@code TrashItem} (0.16.0): the task as it was (including its {@code updatedAt}) plus when and by whom it
 * was trashed. {@code deletedBy} is null when that account was deleted; {@code purgeAt} = {@code deletedAt} + 30 days.
 */
public record TrashItem(@JsonUnwrapped TaskResponse task, Instant deletedAt, DeletedBy deletedBy, Instant purgeAt) {

    /** Schema {@code TrashItem.deletedBy}. */
    public record DeletedBy(String displayName) {
    }
}
