package com.taskapp.auth;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.taskapp.auth.dto.ProvidersResponse;
import com.taskapp.auth.dto.UserResponse;
import com.taskapp.security.AuthenticatedUser;
import com.taskapp.security.google.GoogleSignIn;
import com.taskapp.security.audit.SecurityEvent;
import com.taskapp.security.audit.SecurityEventLogger;

import jakarta.servlet.http.HttpServletRequest;

/**
 * External sign-in providers (contract 0.8.0). The Google flow itself (start and callback) runs in the security
 * filter chain, see {@link com.taskapp.security.google.GoogleFlowSecurity}.
 */
@RestController
public class ConnectionsController {

    private final AccountService accountService;
    private final GoogleSignIn googleSignIn;

    public ConnectionsController(AccountService accountService, GoogleSignIn googleSignIn) {
        this.accountService = accountService;
        this.googleSignIn = googleSignIn;
    }

    /** Public, never 401. Changes only with a restart. */
    @GetMapping("/api/auth/providers")
    public ProvidersResponse providers() {
        return new ProvidersResponse(googleSignIn.enabled());
    }

    /** 404 when Google sign-in is off or nothing is connected; 409 PASSWORD_REQUIRED without a password. */
    @DeleteMapping("/api/account/connections/google")
    public UserResponse disconnectGoogle(@AuthenticationPrincipal AuthenticatedUser principal,
            HttpServletRequest request) {
        if (!googleSignIn.enabled()) {
            throw new ConnectionNotFoundException("Google sign-in is not available");
        }
        UserResponse user = accountService.disconnectGoogle(principal.id());
        SecurityEventLogger.log(SecurityEvent.GOOGLE_UNLINKED, request, user.email());
        return user;
    }
}
