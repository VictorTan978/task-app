package com.taskapp.task;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Access goes through the task's list: a task is the user's when they are a member of its list (V11; V10: when its
 * list was theirs). The list query lives in {@link TaskSearchRepository}.
 */
public interface TaskRepository extends JpaRepository<Task, Long>, TaskSearchRepository {

    /** The task, if {@code userId} is a member of its list. */
    @Query(value = """
            SELECT t.* FROM tasks t JOIN list_members m ON m.list_id = t.list_id AND m.user_id = :userId
            WHERE t.id = :id""", nativeQuery = true)
    Optional<Task> findAccessible(@Param("id") long id, @Param("userId") long userId);

    /** One user's id and display name ({@code Task.addedBy}). */
    interface UserName {
        Long getId();

        String getDisplayName();
    }

    /** Display names of these users (one query); ids of deleted accounts are absent. */
    @Query(value = "SELECT id, display_name AS displayName FROM users WHERE id IN (:ids)", nativeQuery = true)
    List<UserName> findDisplayNames(@Param("ids") Collection<Long> ids);

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
