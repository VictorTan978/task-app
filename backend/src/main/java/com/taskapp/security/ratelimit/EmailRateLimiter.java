package com.taskapp.security.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;

/**
 * Rate-limit policy of the email endpoints. Per-IP limits count every request that passed the CSRF check (see
 * {@link AuthRateLimitInterceptor}); the per-user and per-account limits are counted by the services.
 */
public class EmailRateLimiter {

    private static final Duration HOUR = Duration.ofHours(1);

    private final SlidingWindowLimiter forgotPasswordByIp;
    private final SlidingWindowLimiter resetEmailsByAccount;
    private final SlidingWindowLimiter resendVerificationByUser;
    private final SlidingWindowLimiter verifyEmailByIp;
    private final SlidingWindowLimiter checkResetTokenByIp;
    private final SlidingWindowLimiter resetPasswordByIp;

    public EmailRateLimiter(EmailRateLimitProperties properties, Clock clock) {
        int maxKeys = properties.maxTrackedKeys();
        this.forgotPasswordByIp = new SlidingWindowLimiter(properties.forgotPasswordPerIpPerHour(), HOUR, maxKeys,
                clock);
        this.resetEmailsByAccount = new SlidingWindowLimiter(properties.resetEmailsPerAccountPerHour(), HOUR,
                maxKeys, clock);
        this.resendVerificationByUser = new SlidingWindowLimiter(properties.resendVerificationPerUserPerHour(), HOUR,
                maxKeys, clock);
        this.verifyEmailByIp = new SlidingWindowLimiter(properties.verifyEmailPerIpPerHour(), HOUR, maxKeys, clock);
        this.checkResetTokenByIp = new SlidingWindowLimiter(properties.checkResetTokenPerIpPerHour(), HOUR, maxKeys,
                clock);
        this.resetPasswordByIp = new SlidingWindowLimiter(properties.resetPasswordPerIpPerHour(), HOUR, maxKeys,
                clock);
    }

    public void acquireForgotPassword(String clientIp) {
        enforce(forgotPasswordByIp.tryAcquire(clientIp), RateLimitedException.Limit.FORGOT_PASSWORD_IP, null);
    }

    public void acquireVerifyEmail(String clientIp) {
        enforce(verifyEmailByIp.tryAcquire(clientIp), RateLimitedException.Limit.VERIFY_EMAIL_IP, null);
    }

    public void acquireCheckResetToken(String clientIp) {
        enforce(checkResetTokenByIp.tryAcquire(clientIp), RateLimitedException.Limit.CHECK_RESET_TOKEN_IP, null);
    }

    public void acquireResetPassword(String clientIp) {
        enforce(resetPasswordByIp.tryAcquire(clientIp), RateLimitedException.Limit.RESET_PASSWORD_IP, null);
    }

    /** Counts one queued confirmation email for {@code userId}; throws (429) if over the limit. */
    public void acquireResendVerification(long userId, String email) {
        enforce(resendVerificationByUser.tryAcquire(Long.toString(userId)),
                RateLimitedException.Limit.RESEND_VERIFICATION_USER, email);
    }

    /** Silent per-account limit of reset emails: true (and counted) if one more may be sent. Never throws. */
    public boolean tryAcquireResetEmail(long userId) {
        return resetEmailsByAccount.tryAcquire(Long.toString(userId)).isEmpty();
    }

    private static void enforce(Optional<Duration> blockedFor, RateLimitedException.Limit limit, String email) {
        if (blockedFor.isPresent()) {
            throw new RateLimitedException(blockedFor.get(), limit, email);
        }
    }
}
