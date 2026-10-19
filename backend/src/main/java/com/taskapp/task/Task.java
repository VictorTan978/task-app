package com.taskapp.task;

import java.time.Instant;
import java.time.LocalDate;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * {@code listId} (V10) is not updatable through the entity: a move changes it with
 * {@link TaskRepository#moveToList} only, so a concurrent PATCH/PUT that loaded the task before a move can never
 * write the old list back.
 */
@Entity
@Table(name = "tasks")
public class Task {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Who added the task ("added by", 0.13.0). NULL once that account was deleted (V11: ON DELETE SET NULL); the
     * database sets it, never this entity.
     */
    @Column(name = "owner_id", updatable = false)
    private Long ownerId;

    @Column(name = "list_id", nullable = false, updatable = false)
    private Long listId;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(length = 2000)
    private String description;

    /** NONE, LOW, MEDIUM or HIGH (V9). */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "priority", nullable = false, length = 16)
    private Priority priority;

    /** TODO, IN_PROGRESS or DONE (V9). Always written together with {@link #done}. */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 16)
    private TaskStatus status;

    /** Mirror of {@code status == DONE} (expand-then-contract; the V9 CHECK enforces it). */
    @Column(nullable = false)
    private boolean done;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Task() {
    }

    /** New tasks are NONE / TODO (never done); {@code createdAt} and {@code updatedAt} start equal. */
    public Task(long ownerId, long listId, String title, String description, LocalDate dueDate, Instant now) {
        this.ownerId = ownerId;
        this.listId = listId;
        this.title = title;
        this.description = description;
        this.dueDate = dueDate;
        this.priority = Priority.NONE;
        this.status = TaskStatus.TODO;
        this.done = false;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** Full replacement of the text fields and due date. Priority, status and tags are set separately. */
    public void replace(String title, String description, LocalDate dueDate, Instant now) {
        this.title = title;
        this.description = description;
        this.dueDate = dueDate;
        this.updatedAt = now;
    }

    public void changePriority(Priority priority, Instant now) {
        this.priority = priority;
        this.updatedAt = now;
    }

    /** Sets the status and its {@code done} mirror together, so they never disagree. */
    public void changeStatus(TaskStatus status, Instant now) {
        this.status = status;
        this.done = status == TaskStatus.DONE;
        this.updatedAt = now;
    }

    public Long getId() {
        return id;
    }

    /** The user who added the task; null when their account was deleted. */
    public Long getOwnerId() {
        return ownerId;
    }

    public Long getListId() {
        return listId;
    }

    /** Records a move already written by {@link TaskRepository#moveToList} (the column is not updatable here). */
    void movedTo(long listId) {
        this.listId = listId;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public Priority getPriority() {
        return priority;
    }

    public TaskStatus getStatus() {
        return status;
    }

    public boolean isDone() {
        return done;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
