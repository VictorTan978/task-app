package com.taskapp.task;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Access goes through the task's list (V10): a task is the user's when its list is. The list query lives in
 * {@link TaskSearchRepository}.
 */
public interface TaskRepository extends JpaRepository<Task, Long>, TaskSearchRepository {

    /** The task, if it is in one of {@code userId}'s lists. */
    @Query(value = "SELECT t.* FROM tasks t JOIN lists l ON l.id = t.list_id WHERE t.id = :id AND l.owner_id = :userId",
            nativeQuery = true)
    Optional<Task> findAccessible(@Param("id") long id, @Param("userId") long userId);

    /** The task, if it is (still) in this list. */
    Optional<Task> findByIdAndListId(long id, long listId);

    /**
     * Moves the task (an X lock on its row; the FK check takes an S lock on the target list's row, already held by
     * the caller). Row count: 0 when it is no longer in {@code fromListId}.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = "UPDATE tasks SET list_id = :toListId WHERE id = :id AND list_id = :fromListId", nativeQuery = true)
    int moveToList(@Param("id") long id, @Param("fromListId") long fromListId, @Param("toListId") long toListId);
}
