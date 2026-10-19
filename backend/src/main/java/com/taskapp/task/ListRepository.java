package com.taskapp.task;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Lists and the row locks of the concurrency design (T3a, extended for sharing in T3b). Lock order everywhere:
 * {@code users} rows FOR SHARE (the caller and the owners of the lists touched, ascending id; an account deletion
 * takes its own row FOR UPDATE), then {@code lists} rows in ascending id, then the caller's {@code list_members} row
 * FOR SHARE ({@link MembershipRepository#shareMember}), then task / tag / link / member / invitation rows by primary
 * key. The locking reads below are by primary key; owners never change.
 * <p>
 * Access (0.13.0) is membership: a list is the user's when they have a {@code list_members} row for it (owners have
 * one for each of their lists).
 */
public interface ListRepository extends JpaRepository<TaskList, Long> {

    Optional<TaskList> findByIdAndOwnerId(long id, long ownerId);

    /** The list, if {@code userId} is a member of it (owner or member). Plain read, two primary-key lookups. */
    @Query(value = """
            SELECT l.* FROM lists l JOIN list_members m ON m.list_id = l.id AND m.user_id = :userId
            WHERE l.id = :listId""", nativeQuery = true)
    Optional<TaskList> findAccessible(@Param("listId") long listId, @Param("userId") long userId);

    /** Every list {@code userId} is a member of: "My tasks" first, then by name (column collation), then id. */
    @Query(value = """
            SELECT l.* FROM list_members m JOIN lists l ON l.id = m.list_id
            WHERE m.user_id = :userId ORDER BY l.personal DESC, l.name, l.id""", nativeQuery = true)
    List<TaskList> findAllForMember(@Param("userId") long userId);

    Optional<TaskList> findByOwnerIdAndPersonalTrue(long ownerId);

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
