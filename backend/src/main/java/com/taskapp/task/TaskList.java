package com.taskapp.task;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A list of tasks (V10, specs/sharing-team-lists.md T3a). Every user has exactly one {@code personal} list ("My
 * tasks", created with the account; the database allows at most one per owner). The owner never changes. The name
 * is changed only through {@link ListRepository#rename} (a row-count UPDATE), so this entity is never written after
 * its insert.
 */
@Entity
@Table(name = "lists")
public class TaskList {

    public static final String PERSONAL_NAME = "My tasks";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "owner_id", nullable = false, updatable = false)
    private Long ownerId;

    @Column(nullable = false, length = 100, updatable = false)
    private String name;

    @Column(nullable = false, updatable = false)
    private boolean personal;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected TaskList() {
    }

    public TaskList(long ownerId, String name, boolean personal, Instant createdAt) {
        this.ownerId = ownerId;
        this.name = name;
        this.personal = personal;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public Long getOwnerId() {
        return ownerId;
    }

    public String getName() {
        return name;
    }

    public boolean isPersonal() {
        return personal;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
