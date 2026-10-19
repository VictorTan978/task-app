package com.taskapp.task;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Lists and the row locks of the T3a concurrency design. Lock order everywhere: the user's {@code users} row, then
 * {@code lists} rows in ascending id, then task / tag / link rows. The locking reads below are by primary key and
 * are only used on lists already known to be the caller's (owners never change), so they never lock another user's
 * row.
 */
public interface ListRepository extends JpaRepository<TaskList, Long> {

    Optional<TaskList> findByIdAndOwnerId(long id, long ownerId);

    Optional<TaskList> findByOwnerIdAndPersonalTrue(long ownerId);

    /** "My tasks" first, then by name (column collation utf8mb4_0900_ai_ci), then id. */
    @Query(value = "SELECT * FROM lists WHERE owner_id = :ownerId ORDER BY personal DESC, name, id",
            nativeQuery = true)
    List<TaskList> findAllOwned(@Param("ownerId") long ownerId);

    long countByOwnerId(long ownerId);

    @Query(value = "SELECT COUNT(*) FROM tasks WHERE list_id = :listId", nativeQuery = true)
    long countTasks(@Param("listId") long listId);

    /**
     * S lock on the user's row: taken first by every transaction that writes a list's tasks or tags or deletes a
     * list, so account deletion (X lock on the same row, then the cascade) waits for them instead of deadlocking.
     * Empty when the account is gone.
     */
    @Query(value = "SELECT id FROM users WHERE id = :userId FOR SHARE", nativeQuery = true)
    Optional<Long> shareUser(@Param("userId") long userId);

    /** X lock on the user's row: serialises creating lists (the 50-list limit). Empty when the account is gone. */
    @Query(value = "SELECT id FROM users WHERE id = :userId FOR UPDATE", nativeQuery = true)
    Optional<Long> lockUser(@Param("userId") long userId);

    /**
     * X lock on the list's row: serialises every write of the list's tags and task moves into or out of it, and
     * deleting it. Empty when the list is gone (deleted meanwhile).
     */
    @Query(value = "SELECT id FROM lists WHERE id = :listId FOR UPDATE", nativeQuery = true)
    Optional<Long> lockList(@Param("listId") long listId);

    /** S lock on the list's row: a task insert waits for a delete of the list in progress. */
    @Query(value = "SELECT id FROM lists WHERE id = :listId FOR SHARE", nativeQuery = true)
    Optional<Long> shareList(@Param("listId") long listId);

    /** Row count: 0 when the list is not the owner's (or gone). Never an optimistic-lock exception. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "UPDATE lists SET name = :name WHERE id = :listId AND owner_id = :ownerId", nativeQuery = true)
    int rename(@Param("ownerId") long ownerId, @Param("listId") long listId, @Param("name") String name);

    /** The database deletes the list's tasks, their task_tags rows and the list's tags (ON DELETE CASCADE). */
    @Modifying(flushAutomatically = true)
    @Query(value = "DELETE FROM lists WHERE id = :listId", nativeQuery = true)
    int deleteNow(@Param("listId") long listId);
}
