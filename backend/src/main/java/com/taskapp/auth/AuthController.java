package com.taskapp.auth;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.taskapp.auth.dto.LoginRequest;
import com.taskapp.auth.dto.RegisterRequest;
import com.taskapp.auth.dto.UserResponse;
import com.taskapp.security.AuthenticatedUser;
import com.taskapp.security.SessionAuthenticator;
import com.taskapp.security.audit.SecurityEvent;
import com.taskapp.security.audit.SecurityEventLogger;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final SessionAuthenticator sessionAuthenticator;

    public AuthController(AuthService authService, SessionAuthenticator sessionAuthenticator) {
        this.authService = authService;
        this.sessionAuthenticator = sessionAuthenticator;
    }

    /** Anonymous callers never reach this: the security chain answers 401. */
    @GetMapping("/me")
    public UserResponse me(@AuthenticationPrincipal AuthenticatedUser principal) {
        return authService.getUser(principal.id());
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse register(@Valid @RequestBody RegisterRequest body, HttpServletRequest request,
            HttpServletResponse response) {
        UserResponse user = authService.register(body);
        sessionAuthenticator.signIn(user, false, request, response);
        SecurityEventLogger.log(SecurityEvent.REGISTER_SUCCESS, request, user.email());
        return user;
    }

    @PostMapping("/login")
    public UserResponse login(@Valid @RequestBody LoginRequest body, HttpServletRequest request,
            HttpServletResponse response) {
        UserResponse user = authService.login(body);
        sessionAuthenticator.signIn(user, body.remember(), request, response);
        SecurityEventLogger.log(SecurityEvent.LOGIN_SUCCESS, request, user.email());
        return user;
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@AuthenticationPrincipal AuthenticatedUser principal, HttpServletRequest request,
            HttpServletResponse response) {
        sessionAuthenticator.signOut(request, response);
        // Logged for anonymous callers too (email=-): logout is public and idempotent.
        SecurityEventLogger.log(SecurityEvent.LOGOUT, request, principal == null ? null : principal.email());
    }
}
