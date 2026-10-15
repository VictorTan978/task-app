package com.taskapp.admin.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * Query parameters of {@code GET /api/admin/users}, with defaults applied and {@code q} trimmed (blank = no filter).
 * Validated by the service (400 VALIDATION_FAILED: {@code q} TOO_LONG, {@code page} / {@code size} OUT_OF_RANGE).
 */
public record AdminUserQuery(
        @Size(max = 100, message = "Search must be at most 100 characters") String q,
        @Min(value = 0, message = "Page must be 0 or more") int page,
        @Min(value = 1, message = "Size must be between 1 and 100")
        @Max(value = 100, message = "Size must be between 1 and 100") int size) {

    public static final int DEFAULT_SIZE = 20;

    /** From the raw query parameters (any may be null). */
    public static AdminUserQuery of(String q, Integer page, Integer size) {
        String trimmed = q == null ? "" : q.trim();
        return new AdminUserQuery(trimmed, page == null ? 0 : page, size == null ? DEFAULT_SIZE : size);
    }

    /** The search text, or null for "everyone". */
    public String search() {
        return q == null || q.isEmpty() ? null : q;
    }
}
