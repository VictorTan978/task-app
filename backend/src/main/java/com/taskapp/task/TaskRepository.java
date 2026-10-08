package com.taskapp.task;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

/** Every lookup is scoped by owner. Sort order: not done first, then newest first (createdAt, id). */
public interface TaskRepository extends JpaRepository<Task, Long> {

    List<Task> findByOwnerIdOrderByDoneAscCreatedAtDescIdDesc(long ownerId);

    List<Task> findByOwnerIdAndDoneOrderByCreatedAtDescIdDesc(long ownerId, boolean done);

    Optional<Task> findByIdAndOwnerId(long id, long ownerId);
}
