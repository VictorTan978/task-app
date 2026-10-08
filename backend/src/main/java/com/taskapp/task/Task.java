package com.taskapp.task;

import java.time.Instant;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "tasks")
public class Task {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "owner_id", nullable = false, updatable = false)
    private Long ownerId;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(length = 2000)
    private String description;

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

    /** New tasks are never done; {@code createdAt} and {@code updatedAt} start equal. */
    public Task(long ownerId, String title, String description, LocalDate dueDate, Instant now) {
        this.ownerId = ownerId;
        this.title = title;
        this.description = description;
        this.dueDate = dueDate;
        this.done = false;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** Full replacement of the editable fields. */
    public void replace(String title, String description, LocalDate dueDate, boolean done, Instant now) {
        this.title = title;
        this.description = description;
        this.dueDate = dueDate;
        this.done = done;
        this.updatedAt = now;
    }

    public void setDone(boolean done, Instant now) {
        this.done = done;
        this.updatedAt = now;
    }

    public Long getId() {
        return id;
    }

    public Long getOwnerId() {
        return ownerId;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
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
