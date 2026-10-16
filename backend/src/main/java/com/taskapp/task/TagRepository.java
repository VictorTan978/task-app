package com.taskapp.task;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Tags and the {@code task_tags} join table. Name comparisons and {@code ORDER BY name} use the column collation
 * (utf8mb4_0900_ai_ci): equal ignoring case and accents, sorted the same way. Every query is scoped by owner.
 */
public interface TagRepository extends JpaRepository<Tag, Long> {

    /** One task's tag name together with the task id. */
    interface TaskTagName {
        Long getTaskId();

        String getName();
    }

    /**
     * Locks the owner's {@code users} row until the transaction ends. Every transaction that changes the owner's
     * tags or task_tags takes it first, so creating, reusing, counting and deleting tags never race. Read-only on
     * {@code users}.
     */
    @Query(value = "SELECT id FROM users WHERE id = :ownerId FOR UPDATE", nativeQuery = true)
    Optional<Long> lockOwner(@Param("ownerId") long ownerId);

    /** The owner's tag that equals {@code name} ignoring case and accents. {@code name} must be normalised. */
    Optional<Tag> findByOwnerIdAndName(long ownerId, String name);

    List<Tag> findByOwnerIdOrderByNameAsc(long ownerId);

    long countByOwnerId(long ownerId);

    @Query(value = """
            SELECT t.name FROM task_tags tt JOIN tags t ON t.id = tt.tag_id
            WHERE tt.task_id = :taskId AND t.owner_id = :ownerId
            ORDER BY t.name""", nativeQuery = true)
    List<String> findNamesByTask(@Param("ownerId") long ownerId, @Param("taskId") long taskId);

    /** Every (task, tag name) pair of these tasks in one query, ordered by task id then name. Scoped by owner. */
    @Query(value = """
            SELECT tt.task_id AS taskId, t.name AS name FROM task_tags tt JOIN tags t ON t.id = tt.tag_id
            WHERE tt.task_id IN (:taskIds) AND t.owner_id = :ownerId
            ORDER BY tt.task_id, t.name""", nativeQuery = true)
    List<TaskTagName> findTaskTagNames(@Param("ownerId") long ownerId, @Param("taskIds") Collection<Long> taskIds);

    /*
     * Locking rules (bug: concurrent tag saves of different users deadlocked). Under REPEATABLE READ a locking
     * statement that scans a range, or finds no row, takes next-key/gap locks, and those gaps can border another
     * user's rows (or the supremum, for the newest task ids). So the writes below only touch rows by their exact
     * primary key, and the reads that decide what to write are plain (non-locking) reads. They are still exact
     * because the caller holds the owner lock ({@link #lockOwner}) and its snapshot starts after taking it.
     */

    /** The task's tag ids. Plain read: no row or gap locks. */
    @Query(value = "SELECT tag_id FROM task_tags WHERE task_id = :taskId", nativeQuery = true)
    List<Long> findTagIdsByTask(@Param("taskId") long taskId);

    /** How many tasks have the tag. Plain read: no row or gap locks. */
    @Query(value = "SELECT COUNT(*) FROM task_tags WHERE tag_id = :tagId", nativeQuery = true)
    long countLinks(@Param("tagId") long tagId);

    /** Exact primary key of an existing row: an X record lock on that row only, no gap lock. */
    @Modifying(flushAutomatically = true)
    @Query(value = "DELETE FROM task_tags WHERE task_id = :taskId AND tag_id = :tagId", nativeQuery = true)
    int unlink(@Param("taskId") long taskId, @Param("tagId") long tagId);

    /** Insert-intention locks only (they never conflict with each other) plus S record locks on the parent rows. */
    @Modifying(flushAutomatically = true)
    @Query(value = "INSERT INTO task_tags (task_id, tag_id) VALUES (:taskId, :tagId)", nativeQuery = true)
    int link(@Param("taskId") long taskId, @Param("tagId") long tagId);

    /**
     * Deletes one of the owner's tags by primary key: an X record lock on that row only. (The FK check on
     * task_tags then takes a gap-only lock, which never waits; it is the last write of the transaction.)
     */
    @Modifying(flushAutomatically = true)
    @Query(value = "DELETE FROM tags WHERE id = :tagId AND owner_id = :ownerId", nativeQuery = true)
    int deleteTag(@Param("ownerId") long ownerId, @Param("tagId") long tagId);
}
