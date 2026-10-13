package com.taskapp.auth;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.taskapp.auth.dto.ChangeDisplayNameRequest;
import com.taskapp.auth.dto.ChangePasswordRequest;
import com.taskapp.auth.dto.DeleteAccountRequest;
import com.taskapp.auth.dto.UserResponse;
import com.taskapp.security.AuthenticatedUser;
import com.taskapp.security.SessionAuthenticator;
import com.taskapp.security.audit.SecurityEvent;
import com.taskapp.security.audit.SecurityEventLogger;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

/** The signed-in user's own account. Anonymous callers never reach this: the security chain answers 401. */
@RestController
@RequestMapping("/api/account")
public class AccountController {

    private final AccountService accountService;
    private final SessionAuthenticator sessionAuthenticator;

    public AccountController(AccountService accountService, SessionAuthenticator sessionAuthenticator) {
        this.accountService = accountService;
        this.sessionAuthenticator = sessionAuthenticator;
    }

    @PutMapping("/display-name")
    public UserResponse changeDisplayName(@AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody ChangeDisplayNameRequest body, HttpServletRequest request,
            HttpServletResponse response) {
        DisplayNameChange change = accountService.changeDisplayName(principal.id(), body);
        sessionAuthenticator.refreshPrincipal(change.user(), request, response);
        if (change.changed()) {
            SecurityEventLogger.log(SecurityEvent.DISPLAY_NAME_CHANGED, request, change.user().email());
        }
        return change.user();
    }

    @PutMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(@AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody ChangePasswordRequest body, HttpServletRequest request) {
        UserResponse user = accountService.changePassword(principal.id(), body);
        SecurityEventLogger.log(SecurityEvent.PASSWORD_CHANGED, request, user.email());
    }

    @PostMapping("/delete")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAccount(@AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody DeleteAccountRequest body, HttpServletRequest request,
            HttpServletResponse response) {
        UserResponse deleted = accountService.deleteAccount(principal.id(), body);
        sessionAuthenticator.signOut(request, response);
        SecurityEventLogger.log(SecurityEvent.ACCOUNT_DELETED, request, deleted.email());
    }
}
