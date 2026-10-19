package com.taskapp.task;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A list's tag (V9; per list since V10). {@code name} is already normalised (see {@link TagNames}); the column
 * collation (utf8mb4_0900_ai_ci) makes it unique per list ignoring case and accents, and the stored spelling is the
 * first one used in that list. {@code ownerId} is always the list's owner (not the user who added the tag). Which tasks have it lives in {@code task_tags}, managed by {@link TagRepository}.
 */
@Entity
@Table(name = "tags")
public class Tag {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "owner_id", nullable = false, updatable = false)
    private Long ownerId;

    @Column(name = "list_id", nullable = false, updatable = false)
    private Long listId;

    @Column(nullable = false, length = 30, updatable = false)
    private String name;

    protected Tag() {
    }

    public Tag(long ownerId, long listId, String name) {
        this.ownerId = ownerId;
        this.listId = listId;
        this.name = name;
    }

    public Long getId() {
        return id;
    }

    public Long getOwnerId() {
        return ownerId;
    }

    public Long getListId() {
        return listId;
    }

    public String getName() {
        return name;
    }
}
