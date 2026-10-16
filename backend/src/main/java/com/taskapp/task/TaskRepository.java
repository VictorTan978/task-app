package com.taskapp.task;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

/** Every lookup is scoped by owner. The list query lives in {@link TaskSearchRepository}. */
public interface TaskRepository extends JpaRepository<Task, Long>, TaskSearchRepository {

    Optional<Task> findByIdAndOwnerId(long id, long ownerId);
}
