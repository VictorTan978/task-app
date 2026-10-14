package com.taskapp.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import com.taskapp.auth.dto.ForgotPasswordRequest;
import com.taskapp.auth.dto.ResetPasswordRequest;
import com.taskapp.auth.dto.TokenRequest;
import com.taskapp.security.ratelimit.AuthRateLimiter;
import com.taskapp.security.ratelimit.EmailRateLimitProperties;
import com.taskapp.security.ratelimit.EmailRateLimiter;
import com.taskapp.security.ratelimit.RateLimitedException;

import jakarta.validation.Validation;

/** Order of checks and side effects of {@link EmailAuthService}; tokens and jobs are mocked. */
class EmailAuthServiceTest {

    private static final String TOKEN = "Zr0b1y6p6eS4lJx3n9QhVt2Kc7aWm5dUo8fXg1iHsNk";
    private static final EmailToken RESET_ROW = new EmailToken(new byte[32], 1L, EmailTokenPurpose.RESET_PASSWORD,
            Instant.parse("2030-01-01T00:00:00Z"), null);

    private final EmailTokenService tokens = mock(EmailTokenService.class);
    private final EmailJobs jobs = mock(EmailJobs.class);
    private final UserRepository users = mock(UserRepository.class);
    private final AuthRateLimiter authRateLimiter = mock(AuthRateLimiter.class);
    private EmailAuthService service;

    @BeforeEach
    void setUp() {
        EmailRateLimiter emailRateLimiter = new EmailRateLimiter(
                new EmailRateLimitProperties(10, 3, 2, 30, 30, 20, 1000), Clock.systemUTC());
        service = new EmailAuthService(tokens, jobs, users, new BCryptPasswordEncoder(4), authRateLimiter,
                emailRateLimiter, Validation.buildDefaultValidatorFactory().getValidator());
    }

    private static User user(long id, boolean verified) {
        User user = new User("bob@example.com", "$2a$04$hash", "Bob");
        ReflectionTestUtils.setField(user, "id", id);
        if (verified) {
            ReflectionTestUtils.setField(user, "emailVerifiedAt", Instant.now());
        }
        return user;
    }

    @Test
    void verifyEmailChecksThenConsumes() {
        EmailToken row = new EmailToken(new byte[32], 1L, EmailTokenPurpose.VERIFY_EMAIL, Instant.MAX, null);
        when(tokens.check(TOKEN, EmailTokenPurpose.VERIFY_EMAIL)).thenReturn(row);
        when(tokens.verifyEmail(row)).thenReturn("bob@example.com");

        assertThat(service.verifyEmail(new TokenRequest(TOKEN))).isEqualTo("bob@example.com");
    }

    @Test
    void resetChecksTheTokenBeforeThePassword() {
        when(tokens.check(any(), eq(EmailTokenPurpose.RESET_PASSWORD)))
                .thenThrow(new EmailTokenInvalidException("unknown", null));

        assertThatThrownBy(() -> service.resetPassword(new ResetPasswordRequest(TOKEN, "short")))
                .isInstanceOf(EmailTokenInvalidException.class);
        verify(tokens, never()).resetPassword(any(), anyString());
    }

    @Test
    void invalidNewPasswordWithAValidTokenIsValidationFailedAndUsesNothing() {
        when(tokens.check(TOKEN, EmailTokenPurpose.RESET_PASSWORD)).thenReturn(RESET_ROW);

        assertThatThrownBy(() -> service.resetPassword(new ResetPasswordRequest(TOKEN, "short")))
                .isInstanceOfSatisfying(InvalidFieldsException.class, e -> {
                    assertThat(e.bindingResult().getFieldErrors()).hasSize(1);
                    assertThat(e.bindingResult().getFieldError("newPassword").getCode()).isEqualTo("PasswordLength");
                });
        assertThatThrownBy(() -> service.resetPassword(new ResetPasswordRequest(TOKEN, "   ")))
                .isInstanceOfSatisfying(InvalidFieldsException.class, e -> assertThat(
                        e.bindingResult().getFieldErrors("newPassword")).extracting("code").contains("NotBlank"));
        verify(tokens, never()).resetPassword(any(), anyString());
        verifyNoInteractions(authRateLimiter);
    }

    @Test
    void resetStoresABcryptHashAndResetsTheFailedLoginCounter() {
        when(tokens.check(TOKEN, EmailTokenPurpose.RESET_PASSWORD)).thenReturn(RESET_ROW);
        when(tokens.resetPassword(eq(RESET_ROW), anyString())).thenReturn("bob@example.com");

        assertThat(service.resetPassword(new ResetPasswordRequest(TOKEN, "battery-staple-42")))
                .isEqualTo("bob@example.com");

        var hash = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(tokens).resetPassword(eq(RESET_ROW), hash.capture());
        assertThat(new BCryptPasswordEncoder().matches("battery-staple-42", hash.getValue())).isTrue();
        verify(authRateLimiter).recordLoginSuccess("bob@example.com");
    }

    @Test
    void resendForAGoneUserIs401() {
        when(users.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resendVerification(1L)).isInstanceOf(UnauthenticatedException.class);
        verifyNoInteractions(jobs);
    }

    @Test
    void resendWhenConfirmedIs409AndIsNotCounted() {
        when(users.findById(1L)).thenReturn(Optional.of(user(1L, true)));

        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> service.resendVerification(1L)).isInstanceOf(AlreadyVerifiedException.class);
        }
        verifyNoInteractions(jobs);

        // Not counted: once unconfirmed again (e.g. a test account), the full limit is still available.
        when(users.findById(1L)).thenReturn(Optional.of(user(1L, false)));
        service.resendVerification(1L);
        service.resendVerification(1L);
        verify(jobs, org.mockito.Mockito.times(2)).sendVerification(1L, "bob@example.com", EmailJobs.Trigger.RESEND);
    }

    @Test
    void resendOverThePerUserLimitIs429AndQueuesNothingMore() {
        when(users.findById(1L)).thenReturn(Optional.of(user(1L, false)));
        service.resendVerification(1L);
        service.resendVerification(1L);

        assertThatThrownBy(() -> service.resendVerification(1L))
                .isInstanceOfSatisfying(RateLimitedException.class, e -> {
                    assertThat(e.limit()).isEqualTo(RateLimitedException.Limit.RESEND_VERIFICATION_USER);
                    assertThat(e.retryAfter()).isPositive().isLessThanOrEqualTo(Duration.ofHours(1));
                });
        verify(jobs, org.mockito.Mockito.times(2)).sendVerification(anyLong(), anyString(), any());
    }

    @Test
    void forgotPasswordOnlyQueuesAJobAndTouchesNoDatabase() {
        service.requestPasswordReset(new ForgotPasswordRequest("Bob@Example.com"));

        verify(jobs).sendPasswordReset("bob@example.com");
        verifyNoInteractions(users, tokens);
    }
}
