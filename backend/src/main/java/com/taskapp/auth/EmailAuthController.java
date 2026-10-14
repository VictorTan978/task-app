package com.taskapp.auth;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.taskapp.auth.dto.ForgotPasswordRequest;
import com.taskapp.auth.dto.ResetPasswordRequest;
import com.taskapp.auth.dto.TokenRequest;
import com.taskapp.security.AuthenticatedUser;
import com.taskapp.security.SessionAuthenticator;
import com.taskapp.security.audit.SecurityEvent;
import com.taskapp.security.audit.SecurityEventLogger;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

/**
 * Email verification and password reset (contract 0.7.0). The four {@code /api/auth/*} endpoints are public (CSRF
 * still required) and never answer 401; resend needs a signed-in user. Tokens only ever travel in request bodies.
 */
@RestController
public class EmailAuthController {

    private final EmailAuthService emailAuthService;
    private final SessionAuthenticator sessionAuthenticator;

    public EmailAuthController(EmailAuthService emailAuthService, SessionAuthenticator sessionAuthenticator) {
        this.emailAuthService = emailAuthService;
        this.sessionAuthenticator = sessionAuthenticator;
    }

    /** Never changes the session: the token may belong to another account than the signed-in one. */
    @PostMapping("/api/auth/verify-email")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void verifyEmail(@RequestBody TokenRequest body, HttpServletRequest request) {
        String email = emailAuthService.verifyEmail(body);
        SecurityEventLogger.log(SecurityEvent.EMAIL_VERIFIED, request, email);
    }

    /** Always 202 for a well-formed email; the audit line is written by the background job. */
    @PostMapping("/api/auth/forgot-password")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void forgotPassword(@Valid @RequestBody ForgotPasswordRequest body) {
        emailAuthService.requestPasswordReset(body);
    }

    @PostMapping("/api/auth/check-reset-token")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void checkResetToken(@RequestBody TokenRequest body) {
        emailAuthService.checkResetToken(body);
    }

    /** On success this browser is always signed out, like logout (whichever account it was signed in as). */
    @PostMapping("/api/auth/reset-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resetPassword(@RequestBody ResetPasswordRequest body, HttpServletRequest request,
            HttpServletResponse response) {
        String email = emailAuthService.resetPassword(body);
        sessionAuthenticator.signOut(request, response);
        SecurityEventLogger.log(SecurityEvent.PASSWORD_RESET_COMPLETED, request, email);
    }

    /** No request body (ignored if sent). 204 = queued. */
    @PostMapping("/api/account/resend-verification")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resendVerification(@AuthenticationPrincipal AuthenticatedUser principal) {
        emailAuthService.resendVerification(principal.id());
    }
}
