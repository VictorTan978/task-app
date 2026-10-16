package com.taskapp.task;

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

    /** Every (task, tag name) pair of the owner in one query, ordered by task id then name. */
    @Query(value = """
            SELECT tt.task_id AS taskId, t.name AS name FROM tags t JOIN task_tags tt ON tt.tag_id = t.id
            WHERE t.owner_id = :ownerId
            ORDER BY tt.task_id, t.name""", nativeQuery = true)
    List<TaskTagName> findTaskTagNames(@Param("ownerId") long ownerId);

    @Modifying(flushAutomatically = true)
    @Query(value = "DELETE FROM task_tags WHERE task_id = :taskId", nativeQuery = true)
    int unlinkAll(@Param("taskId") long taskId);

    @Modifying(flushAutomatically = true)
    @Query(value = "INSERT INTO task_tags (task_id, tag_id) VALUES (:taskId, :tagId)", nativeQuery = true)
    int link(@Param("taskId") long taskId, @Param("tagId") long tagId);

    /** Deletes the owner's tags that no task uses any more. */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            DELETE FROM tags
            WHERE owner_id = :ownerId
              AND NOT EXISTS (SELECT 1 FROM task_tags tt WHERE tt.tag_id = tags.id)""", nativeQuery = true)
    int deleteUnused(@Param("ownerId") long ownerId);
}
