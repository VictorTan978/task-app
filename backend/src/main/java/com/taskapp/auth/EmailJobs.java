package com.taskapp.auth;

import java.time.Duration;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.mail.MailException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import com.taskapp.email.EmailContent;
import com.taskapp.email.EmailTemplates;
import com.taskapp.email.MailDelivery;
import com.taskapp.security.audit.AuditOrigin;
import com.taskapp.security.audit.SecurityEvent;
import com.taskapp.security.audit.SecurityEventLogger;
import com.taskapp.security.ratelimit.EmailRateLimiter;
import com.taskapp.security.ratelimit.InviteRateLimiter;
import com.taskapp.security.ratelimit.RateLimitedException;

/**
 * Sends the confirmation, reset and (0.13.0) list invitation emails in the background, after the response
 * (specs/email-auth.md AC15): the
 * request only queues a job, so a slow or broken mail server never slows down or fails it, and forgot-password does
 * no database work in the request (same timing whether or not the account exists).
 * <p>
 * {@value #THREADS} threads and a queue of {@value #QUEUE_CAPACITY} jobs. On shutdown queued jobs get up to
 * {@value #DRAIN_SECONDS} seconds to finish. Jobs still queued after that, or lost in a crash, are not sent: the
 * user can ask again. A full queue drops the job. Every failure is logged as {@code EMAIL_SEND_FAILED}; nothing
 * here ever logs the token or the link.
 * <p>
 * This bean owns the executor and depends on everything the jobs use, so on shutdown it drains before the database
 * and the mail sender are closed.
 */
@Component
public class EmailJobs implements DisposableBean {

    static final int THREADS = 2;
    static final int QUEUE_CAPACITY = 500;
    static final int DRAIN_SECONDS = 20;

    private static final Logger log = LoggerFactory.getLogger(EmailJobs.class);

    /** Why a confirmation email is sent ({@code trigger=} in the security log). */
    public enum Trigger {
        REGISTER("register"),
        RESEND("resend");

        private final String logValue;

        Trigger(String logValue) {
            this.logValue = logValue;
        }
    }

    private static final String VERIFY_KIND = "verify_email";
    private static final String RESET_KIND = "reset_password";
    private static final String INVITE_KIND = "list_invitation";

    private final EmailTokenService tokens;
    private final UserRepository users;
    private final MailDelivery delivery;
    private final EmailRateLimiter rateLimiter;
    private final InviteRateLimiter inviteRateLimiter;
    private final ThreadPoolTaskExecutor executor;

    public EmailJobs(EmailTokenService tokens, UserRepository users, MailDelivery delivery,
            EmailRateLimiter rateLimiter, InviteRateLimiter inviteRateLimiter) {
        this.tokens = tokens;
        this.users = users;
        this.delivery = delivery;
        this.rateLimiter = rateLimiter;
        this.inviteRateLimiter = inviteRateLimiter;
        this.executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(THREADS);
        executor.setMaxPoolSize(THREADS);
        executor.setQueueCapacity(QUEUE_CAPACITY);
        executor.setThreadNamePrefix("email-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(DRAIN_SECONDS);
        executor.initialize();
    }

    /**
     * Queues a confirmation email for {@code userId}: a new token (older unused confirmation links stop working),
     * then the email. Nothing is sent if the email is confirmed by then. {@code email} is only for the log.
     */
    public void sendVerification(long userId, String email, Trigger trigger) {
        AuditOrigin origin = AuditOrigin.current();
        submit(VERIFY_KIND, email, origin, () -> {
            try {
                Optional<EmailTokenService.IssuedToken> issued = tokens.issue(userId, EmailTokenPurpose.VERIFY_EMAIL);
                if (issued.isEmpty()) {
                    return;
                }
                EmailTokenService.IssuedToken token = issued.get();
                send(token, EmailTemplates.verifyEmail(token.displayName(),
                        delivery.link("/verify-email", token.token()), token.validFor()));
                SecurityEventLogger.log(SecurityEvent.EMAIL_VERIFICATION_SENT, origin, token.email(),
                        "trigger", trigger.logValue);
            } catch (RuntimeException e) {
                failed(VERIFY_KIND, email, origin, e);
            }
        });
    }

    /**
     * Queues a forgot-password request for the email as typed (normalized): looks up the account (ignoring letter
     * case and accents), applies the silent per-account limit, creates a reset token and sends the email to the
     * account's stored address. A disabled account gets no email ({@code outcome=account_disabled}). Logs one
     * {@code PASSWORD_RESET_REQUESTED} line with the outcome.
     */
    public void sendPasswordReset(String typedEmail) {
        AuditOrigin origin = AuditOrigin.current();
        submit(RESET_KIND, typedEmail, origin, () -> {
            String outcome;
            try {
                outcome = resetJob(typedEmail, origin);
            } catch (RuntimeException e) {
                failed(RESET_KIND, typedEmail, origin, e);
                outcome = "failed";
            }
            SecurityEventLogger.log(SecurityEvent.PASSWORD_RESET_REQUESTED, origin, typedEmail, "outcome", outcome);
        });
    }

    private String resetJob(String typedEmail, AuditOrigin origin) {
        User user = users.findByEmail(typedEmail).orElse(null);
        if (user == null) {
            return "unknown_email";
        }
        if (user.isDisabled()) {
            // Before the per-account limit: not counted. issue() checks again under the lock.
            return "account_disabled";
        }
        if (!rateLimiter.tryAcquireResetEmail(user.getId())) {
            SecurityEventLogger.log(SecurityEvent.RATE_LIMITED, origin, user.getEmail(),
                    "limit", RateLimitedException.Limit.RESET_EMAIL_ACCOUNT.logValue());
            return "account_limit";
        }
        Optional<EmailTokenService.IssuedToken> issued = tokens.issue(user.getId(), EmailTokenPurpose.RESET_PASSWORD);
        if (issued.isEmpty()) {
            // Deleted or disabled since the lookup.
            return "unknown_email";
        }
        EmailTokenService.IssuedToken token = issued.get();
        send(token, EmailTemplates.resetPassword(token.displayName(),
                delivery.link("/reset-password", token.token()), token.validFor()));
        return "sent";
    }

    /**
     * Queues the email of a list invitation that was just created (call it after the transaction committed). The
     * account is looked up here, in the background, never in the request (AC18: the response is the same whether or
     * not an account has the address). Sent only to an active (not disabled) account, verified or not, within the
     * silent per-recipient limit. Logs one {@code LIST_INVITATION_EMAIL} line with the outcome. {@code email} is the
     * invited address (normalized); the owner's display name and the list name are as they were at invite time.
     */
    public void sendListInvitation(String email, String ownerDisplayName, String listName, Duration validFor) {
        AuditOrigin origin = AuditOrigin.current();
        submit(INVITE_KIND, email, origin, () -> {
            String outcome;
            try {
                outcome = invitationJob(email, ownerDisplayName, listName, validFor, origin);
            } catch (RuntimeException e) {
                failed(INVITE_KIND, email, origin, e);
                outcome = "failed";
            }
            SecurityEventLogger.log(SecurityEvent.LIST_INVITATION_EMAIL, origin, email, "outcome", outcome);
        });
    }

    private String invitationJob(String email, String ownerDisplayName, String listName, Duration validFor,
            AuditOrigin origin) {
        User user = users.findByEmail(email).orElse(null);
        if (user == null) {
            return "no_account";
        }
        if (user.isDisabled()) {
            // Before the per-recipient limit: not counted.
            return "account_disabled";
        }
        if (!inviteRateLimiter.tryAcquireInviteEmail(user.getId())) {
            SecurityEventLogger.log(SecurityEvent.RATE_LIMITED, origin, user.getEmail(),
                    "limit", RateLimitedException.Limit.INVITE_EMAIL_ACCOUNT.logValue());
            return "account_limit";
        }
        delivery.send(user.getEmail(), EmailTemplates.listInvitation(user.getDisplayName(), ownerDisplayName,
                listName, delivery.page("/invitations"), validFor));
        return "sent";
    }

    private void send(EmailTokenService.IssuedToken token, EmailContent content) {
        delivery.send(token.email(), content);
    }

    private void submit(String kind, String email, AuditOrigin origin, Runnable job) {
        try {
            executor.execute(job);
        } catch (TaskRejectedException e) {
            SecurityEventLogger.log(SecurityEvent.EMAIL_SEND_FAILED, origin, email, "kind", kind,
                    "reason", "queue_full");
            log.warn("Email {} not queued: the queue is full or shutting down", kind);
            if (RESET_KIND.equals(kind)) {
                SecurityEventLogger.log(SecurityEvent.PASSWORD_RESET_REQUESTED, origin, email, "outcome", "failed");
            }
            if (INVITE_KIND.equals(kind)) {
                SecurityEventLogger.log(SecurityEvent.LIST_INVITATION_EMAIL, origin, email, "outcome", "failed");
            }
        }
    }

    /** Logs only exception types and the root cause message: never the message being sent. */
    private static void failed(String kind, String email, AuditOrigin origin, RuntimeException e) {
        boolean smtp = e instanceof MailException;
        SecurityEventLogger.log(SecurityEvent.EMAIL_SEND_FAILED, origin, email, "kind", kind,
                "reason", smtp ? "smtp" : "error");
        Throwable root = NestedExceptionUtils.getMostSpecificCause(e);
        if (smtp) {
            log.warn("Email {} could not be sent: {} ({}: {})", kind, e.getClass().getSimpleName(),
                    root.getClass().getSimpleName(), root.getMessage());
        } else {
            log.error("Email {} job failed: {} ({}: {})", kind, e.getClass().getName(),
                    root.getClass().getName(), root.getMessage());
        }
    }

    /** True when no job is queued or running (for tests and monitoring). */
    public boolean isIdle() {
        java.util.concurrent.ThreadPoolExecutor pool = executor.getThreadPoolExecutor();
        return pool.getTaskCount() == pool.getCompletedTaskCount();
    }

    /** Stops taking jobs and waits up to {@value #DRAIN_SECONDS} seconds for the queued ones. */
    @Override
    public void destroy() {
        executor.shutdown();
    }
}
