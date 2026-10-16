package com.taskapp.task;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Every lookup is scoped by owner. Sort order: not done first, then newest first (createdAt, id). */
public interface TaskRepository extends JpaRepository<Task, Long> {

    List<Task> findByOwnerIdOrderByDoneAscCreatedAtDescIdDesc(long ownerId);

    List<Task> findByOwnerIdAndDoneOrderByCreatedAtDescIdDesc(long ownerId, boolean done);

    List<Task> findByOwnerIdAndStatusOrderByCreatedAtDescIdDesc(long ownerId, TaskStatus status);

    /** The owner's tasks that have the tag and one of the statuses (names), in the list order. */
    @Query(value = """
            SELECT t.* FROM tasks t JOIN task_tags tt ON tt.task_id = t.id
            WHERE t.owner_id = :ownerId AND tt.tag_id = :tagId AND t.status IN (:statuses)
            ORDER BY t.done, t.created_at DESC, t.id DESC""", nativeQuery = true)
    List<Task> findByOwnerIdAndTagId(@Param("ownerId") long ownerId, @Param("tagId") long tagId,
            @Param("statuses") Collection<String> statuses);

    Optional<Task> findByIdAndOwnerId(long id, long ownerId);
}
