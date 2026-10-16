package com.taskapp.task;

import java.util.List;

/** The task list query of {@code GET /api/tasks} (contract 0.11.0), implemented in {@link TaskSearchRepositoryImpl}. */
public interface TaskSearchRepository {

    /**
     * Up to {@code limit} of the matching tasks in list order, starting right after {@code after} (null = from the
     * start).
     */
    List<Task> findPage(TaskSearchCriteria criteria, TaskCursor after, int limit);

    /** Number of matching tasks (all pages). */
    long countMatching(TaskSearchCriteria criteria);
}
