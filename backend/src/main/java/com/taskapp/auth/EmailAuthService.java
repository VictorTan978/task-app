package com.taskapp.auth;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.validation.DirectFieldBindingResult;
import org.springframework.validation.beanvalidation.SpringValidatorAdapter;

import com.taskapp.auth.dto.ForgotPasswordRequest;
import com.taskapp.auth.dto.ResetPasswordRequest;
import com.taskapp.auth.dto.TokenRequest;
import com.taskapp.security.ratelimit.AuthRateLimiter;
import com.taskapp.security.ratelimit.EmailRateLimiter;

import jakarta.validation.Validator;

/**
 * Email verification and password reset (specs/email-auth.md), as called by {@link EmailAuthController}. Emails are
 * only queued here ({@link EmailJobs}); tokens are handled by {@link EmailTokenService}.
 */
@Service
public class EmailAuthService {

    private final EmailTokenService tokens;
    private final EmailJobs jobs;
    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final AuthRateLimiter authRateLimiter;
    private final EmailRateLimiter emailRateLimiter;
    private final SpringValidatorAdapter validator;

    public EmailAuthService(EmailTokenService tokens, EmailJobs jobs, UserRepository users,
            PasswordEncoder passwordEncoder, AuthRateLimiter authRateLimiter, EmailRateLimiter emailRateLimiter,
            Validator validator) {
        this.tokens = tokens;
        this.jobs = jobs;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.authRateLimiter = authRateLimiter;
        this.emailRateLimiter = emailRateLimiter;
        this.validator = new SpringValidatorAdapter(validator);
    }

    /** Uses a confirmation token. Returns the confirmed account's email (for the security log). */
    public String verifyEmail(TokenRequest request) {
        return tokens.verifyEmail(tokens.check(request.token(), EmailTokenPurpose.VERIFY_EMAIL));
    }

    /** Throws {@link EmailTokenInvalidException} unless the token is a usable reset token. Changes nothing. */
    public void checkResetToken(TokenRequest request) {
        tokens.check(request.token(), EmailTokenPurpose.RESET_PASSWORD);
    }

    /**
     * Order: token (400 TOKEN_INVALID) > {@code newPassword} (400 VALIDATION_FAILED) > reset. A 4xx changes nothing
     * and leaves the token usable. On success the password is changed, every session and remember-me login of the
     * account ends, the email counts as confirmed and the account's failed-login counter is reset. Returns the
     * account's email.
     */
    public String resetPassword(ResetPasswordRequest request) {
        EmailToken checked = tokens.check(request.token(), EmailTokenPurpose.RESET_PASSWORD);
        DirectFieldBindingResult errors = new DirectFieldBindingResult(request, "resetPasswordRequest");
        validator.validate(request, errors);
        if (errors.hasErrors()) {
            throw new InvalidFieldsException(errors);
        }
        // BCrypt before the transaction, so the user's row isn't locked while hashing.
        String email = tokens.resetPassword(checked, passwordEncoder.encode(request.newPassword()));
        authRateLimiter.recordLoginSuccess(email);
        return email;
    }

    /** Order: 401 (user gone) > 409 ALREADY_VERIFIED (not counted) > 429 per user > queued. */
    public void resendVerification(long userId) {
        User user = users.findById(userId).orElseThrow(UnauthenticatedException::new);
        if (user.isEmailVerified()) {
            throw new AlreadyVerifiedException();
        }
        emailRateLimiter.acquireResendVerification(userId, user.getEmail());
        jobs.sendVerification(userId, user.getEmail(), EmailJobs.Trigger.RESEND);
    }

    /** Only queues the job: no database work here, so the response time never depends on the account existing. */
    public void requestPasswordReset(ForgotPasswordRequest request) {
        jobs.sendPasswordReset(request.email());
    }
}
