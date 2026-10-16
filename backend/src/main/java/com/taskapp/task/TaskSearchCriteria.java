package com.taskapp.task;

/**
 * Which of the owner's tasks a list page shows, in which order. {@code tagId} null = no tag filter (it must be one of
 * the owner's tags); {@code search} null = no search, otherwise the trimmed text matched literally.
 */
public record TaskSearchCriteria(long ownerId, TaskFilter filter, TaskSort sort, Long tagId, String search) {
}
