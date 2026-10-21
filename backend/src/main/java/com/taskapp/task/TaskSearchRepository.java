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

    /** A task id and its "My order" position (null = never ordered). */
    record GroupRow(long id, Long position) {
    }

    /**
     * The live tasks of one group of a list ({@code done} or not), in "My order" (the order of
     * {@code sort=manual}). Ids and positions only.
     */
    List<GroupRow> manualGroup(long listId, boolean done);

    /** Locks the rows of that group FOR UPDATE, in ascending id (the last step of the lock order). */
    void lockGroup(long listId, boolean done);

    /**
     * Gives these tasks (live, locked) the positions {@code step, 2 * step, ...} in the order given; {@code
     * updated_at} is not touched.
     */
    void assignPositions(List<Long> orderedIds, long step);
}
