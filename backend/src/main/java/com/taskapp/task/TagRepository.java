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
 * (utf8mb4_0900_ai_ci): equal ignoring case and accents, sorted the same way. Every query is scoped by list (V10:
 * tags belong to a list, not to a user).
 */
public interface TagRepository extends JpaRepository<Tag, Long> {

    /** One task's tag name together with the task id. */
    interface TaskTagName {
        Long getTaskId();

        String getName();
    }

    /** The list's tag that equals {@code name} ignoring case and accents. {@code name} must be normalised. */
    Optional<Tag> findByListIdAndName(long listId, String name);

    List<Tag> findByListIdOrderByNameAsc(long listId);

    long countByListId(long listId);

    @Query(value = """
            SELECT t.name FROM task_tags tt JOIN tags t ON t.id = tt.tag_id
            WHERE tt.task_id = :taskId AND t.list_id = :listId
            ORDER BY t.name""", nativeQuery = true)
    List<String> findNamesByTask(@Param("listId") long listId, @Param("taskId") long taskId);

    /** Every (task, tag name) pair of these tasks in one query, ordered by task id then name. Scoped by list. */
    @Query(value = """
            SELECT tt.task_id AS taskId, t.name AS name FROM task_tags tt JOIN tags t ON t.id = tt.tag_id
            WHERE tt.task_id IN (:taskIds) AND t.list_id = :listId
            ORDER BY tt.task_id, t.name""", nativeQuery = true)
    List<TaskTagName> findTaskTagNames(@Param("listId") long listId, @Param("taskIds") Collection<Long> taskIds);

    /*
     * Locking rules (bug: concurrent tag saves of different users deadlocked). Under REPEATABLE READ a locking
     * statement that scans a range, or finds no row, takes next-key/gap locks, and those gaps can border another
     * list's rows (or the supremum, for the newest task ids). So the writes below only touch rows by their exact
     * primary key, and the reads that decide what to write are plain (non-locking) reads. They are still exact
     * because the caller holds the list's row lock ({@link ListRepository#lockList}) and its snapshot starts after
     * taking it.
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
     * Deletes one of the list's tags by primary key: an X record lock on that row only. (The FK check on task_tags
     * then takes a gap-only lock, which never waits; it is the last write of the transaction.)
     */
    @Modifying(flushAutomatically = true)
    @Query(value = "DELETE FROM tags WHERE id = :tagId AND list_id = :listId", nativeQuery = true)
    int deleteTag(@Param("listId") long listId, @Param("tagId") long tagId);
}
