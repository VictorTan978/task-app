package com.taskapp.task;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

/**
 * Access goes through the task's list: a task is the user's when they are a member of its list (V11; V10: when its
 * list was theirs). The list query lives in {@link TaskSearchRepository}.
 */
public interface TaskRepository extends JpaRepository<Task, Long>, TaskSearchRepository {

    /** The task, if {@code userId} is a member of its list. */
    @Query(value = """
            SELECT t.* FROM tasks t JOIN list_members m ON m.list_id = t.list_id AND m.user_id = :userId
            WHERE t.id = :id AND t.deleted_at IS NULL""", nativeQuery = true)
    Optional<Task> findAccessible(@Param("id") long id, @Param("userId") long userId);

    /** The trashed task, if {@code userId} is a member of its list (0.16.0). */
    @Query(value = """
            SELECT t.* FROM tasks t JOIN list_members m ON m.list_id = t.list_id AND m.user_id = :userId
            WHERE t.id = :id AND t.deleted_at IS NOT NULL""", nativeQuery = true)
    Optional<Task> findTrashedAccessible(@Param("id") long id, @Param("userId") long userId);

    /** One user's id and display name ({@code Task.addedBy}). */
    interface UserName {
        Long getId();

        String getDisplayName();
    }

    /** Display names of these users (one query); ids of deleted accounts are absent. */
    @Query(value = "SELECT id, display_name AS displayName FROM users WHERE id IN (:ids)", nativeQuery = true)
    List<UserName> findDisplayNames(@Param("ids") Collection<Long> ids);

    /**
     * The task, if it is (still) in this list, read FOR UPDATE: the row lock serialises writes of one task, so a
     * completion decides on the committed status (two completions of a repeating task create one copy) and never
     * writes a stale row back.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Task t where t.id = :id and t.listId = :listId and t.deletedAt is null")
    Optional<Task> findByIdAndListId(@Param("id") long id, @Param("listId") long listId);

    /** Like {@link #findByIdAndListId} but for a task in the trash (restore, delete forever). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Task t where t.id = :id and t.listId = :listId and t.deletedAt is not null")
    Optional<Task> findTrashedByIdAndListId(@Param("id") long id, @Param("listId") long listId);

    /** Moves the task to the trash. Row count 0 when it already is there. Caller holds the task row. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "UPDATE tasks SET deleted_at = :at, deleted_by = :by WHERE id = :id AND deleted_at IS NULL",
            nativeQuery = true)
    int trash(@Param("id") long id, @Param("by") long by, @Param("at") Instant at);

    /** Takes the task out of the trash; {@code updated_at} is not touched. Row count 0 when it is not trashed. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "UPDATE tasks SET deleted_at = NULL, deleted_by = NULL WHERE id = :id AND deleted_at IS NOT NULL",
            nativeQuery = true)
    int restore(@Param("id") long id);

    /**
     * Moves the task (an X lock on its row; the FK check takes an S lock on the target list's row, already held by
     * the caller). Row count: 0 when it is no longer in {@code fromListId}.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = "UPDATE tasks SET list_id = :toListId WHERE id = :id AND list_id = :fromListId"
            + " AND deleted_at IS NULL", nativeQuery = true)
    int moveToList(@Param("id") long id, @Param("fromListId") long fromListId, @Param("toListId") long toListId);
}
