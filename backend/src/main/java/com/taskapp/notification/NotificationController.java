package com.taskapp.notification;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.taskapp.security.AuthenticatedUser;

import jakarta.servlet.http.HttpServletRequest;

/** The signed-in user's own notifications. Anonymous callers never reach this: the security chain answers 401. */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationService service;

    public NotificationController(NotificationService service) {
        this.service = service;
    }

    /** {@code limit} and {@code cursor} are read with {@code getParameter} (the first value of a repeated one). */
    @GetMapping
    public NotificationPage list(@AuthenticationPrincipal AuthenticatedUser user, HttpServletRequest request) {
        return service.list(user.id(), request.getParameter("limit"), request.getParameter("cursor"));
    }

    @GetMapping("/count")
    public NotificationCount count(@AuthenticationPrincipal AuthenticatedUser user) {
        return service.count(user.id());
    }

    @PostMapping("/{id}/read")
    public NotificationResponse read(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable("id") long id) {
        return service.markRead(user.id(), id);
    }

    @PostMapping("/read-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void readAll(@AuthenticationPrincipal AuthenticatedUser user) {
        service.markAllRead(user.id());
    }
}
