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
import com.taskapp.auth.dto.SetReminderEmailsRequest;
import com.taskapp.auth.dto.SetTimeZoneRequest;
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

    /** Contract 0.14.0. Nothing about the session changes (the principal does not carry the zone). */
    @PutMapping("/time-zone")
    public UserResponse setTimeZone(@AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody SetTimeZoneRequest body) {
        return accountService.setTimeZone(principal.id(), body.timeZone(), body.onlyIfUnset());
    }

    @PutMapping("/reminder-emails")
    public UserResponse setReminderEmails(@AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody SetReminderEmailsRequest body) {
        return accountService.setReminderEmails(principal.id(), body.enabled());
    }

    /** Also ends all other sessions; this one stays signed in with a new session id. */
    @PutMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(@AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody ChangePasswordRequest body, HttpServletRequest request) {
        UserResponse user = sessionAuthenticator.keepOnlyThisSession(request,
                renewal -> accountService.changePassword(principal.id(), body, renewal));
        SecurityEventLogger.log(SecurityEvent.PASSWORD_CHANGED, request, user.email());
        SecurityEventLogger.log(SecurityEvent.OTHER_SESSIONS_LOGGED_OUT, "trigger", "password_change", request,
                user.email());
    }

    /** No request body (ignored if sent). This session stays signed in with a new session id. */
    @PostMapping("/logout-other-sessions")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logoutOtherSessions(@AuthenticationPrincipal AuthenticatedUser principal,
            HttpServletRequest request) {
        UserResponse user = sessionAuthenticator.keepOnlyThisSession(request,
                renewal -> accountService.logoutOtherSessions(principal.id(), renewal));
        SecurityEventLogger.log(SecurityEvent.OTHER_SESSIONS_LOGGED_OUT, "trigger", "button", request, user.email());
    }

    /** The body is validated by the service: which field is required depends on the account (contract 0.8.0). */
    @PostMapping("/delete")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAccount(@AuthenticationPrincipal AuthenticatedUser principal,
            @RequestBody DeleteAccountRequest body, HttpServletRequest request,
            HttpServletResponse response) {
        UserResponse deleted = accountService.deleteAccount(principal.id(), body);
        sessionAuthenticator.signOut(request, response);
        SecurityEventLogger.log(SecurityEvent.ACCOUNT_DELETED, request, deleted.email());
    }
}
