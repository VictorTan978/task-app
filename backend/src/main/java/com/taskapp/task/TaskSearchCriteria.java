package com.taskapp.task;

/**
 * Which of a list's tasks a list page shows, in which order. {@code listId} is already checked to be the user's.
 * {@code tagId} null = no tag filter (it must be one of the list's tags); {@code search} null = no search, otherwise
 * the trimmed text matched literally.
 */
public record TaskSearchCriteria(long listId, TaskFilter filter, TaskSort sort, Long tagId, String search) {
}
