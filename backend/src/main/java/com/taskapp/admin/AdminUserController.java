package com.taskapp.admin;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.taskapp.admin.dto.AdminUserPage;
import com.taskapp.admin.dto.AdminUserQuery;
import com.taskapp.admin.dto.AdminUserResponse;
import com.taskapp.admin.dto.ChangeRoleRequest;
import com.taskapp.security.AuthenticatedUser;

/**
 * Admin users page (specs/roles-admin.md, contract 0.9.0). The security chain lets only signed-in ADMIN / SUPER_ADMIN
 * sessions reach this; {@link AdminUserService} checks the per-target rules. Change requests have no body except the
 * role change (a body sent to the others is ignored).
 */
@RestController
@RequestMapping("/api/admin/users")
public class AdminUserController {

    private final AdminUserService service;

    public AdminUserController(AdminUserService service) {
        this.service = service;
    }

    @GetMapping
    public AdminUserPage list(@RequestParam(required = false) String q,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return service.list(AdminUserQuery.of(q, page, size));
    }

    @PostMapping("/{id}/disable")
    public AdminUserResponse disable(@AuthenticationPrincipal AuthenticatedUser principal, @PathVariable long id) {
        return service.disable(principal, id);
    }

    @PostMapping("/{id}/enable")
    public AdminUserResponse enable(@AuthenticationPrincipal AuthenticatedUser principal, @PathVariable long id) {
        return service.enable(principal, id);
    }

    @PostMapping("/{id}/revoke-sessions")
    public AdminUserResponse revokeSessions(@AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable long id) {
        return service.revokeSessions(principal, id);
    }

    /** {@code role} is validated by the service (400 {@code role} / {@code INVALID_ROLE}). */
    @PutMapping("/{id}/role")
    public AdminUserResponse changeRole(@AuthenticationPrincipal AuthenticatedUser principal, @PathVariable long id,
            @RequestBody ChangeRoleRequest body) {
        return service.changeRole(principal, id, body);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AuthenticatedUser principal, @PathVariable long id) {
        service.delete(principal, id);
    }
}
