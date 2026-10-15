package com.taskapp.admin.dto;

import java.util.List;

/**
 * One page of {@code GET /api/admin/users} (contract 0.9.0 {@code AdminUserPage}).
 *
 * @param page          the requested zero-based page
 * @param size          the requested page size
 * @param totalElements matching accounts on all pages
 * @param totalPages    0 when nothing matches
 */
public record AdminUserPage(List<AdminUserResponse> content, int page, int size, long totalElements,
        int totalPages) {

    public AdminUserPage {
        content = List.copyOf(content);
    }
}
