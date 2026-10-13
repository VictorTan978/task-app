package com.taskapp.security.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

class AuthRateLimiterTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-09T10:00:00Z"));
    private final AuthRateLimiter limiter = new AuthRateLimiter(
            new RateLimitProperties(5, Duration.ofMinutes(15), 20, 5, 100_000, 5, Duration.ofMinutes(15)), clock);

    @Test
    void fiveFailuresBlockThatEmailForFifteenMinutes() {
        for (int i = 0; i < 5; i++) {
            limiter.checkLoginAllowed("bob@example.com");
            limiter.recordLoginFailure("bob@example.com");
        }
        assertThatThrownBy(() -> limiter.checkLoginAllowed("bob@example.com"))
                .isInstanceOfSatisfying(RateLimitedException.class,
                        e -> assertThat(e.retryAfterSeconds()).isEqualTo(15 * 60));
        assertThatCode(() -> limiter.checkLoginAllowed("alice@example.com")).doesNotThrowAnyException();

        clock.advance(Duration.ofMinutes(15));
        assertThatCode(() -> limiter.checkLoginAllowed("bob@example.com")).doesNotThrowAnyException();
    }

    @Test
    void successResetsFailures() {
        for (int i = 0; i < 4; i++) {
            limiter.recordLoginFailure("bob@example.com");
        }
        limiter.recordLoginSuccess("bob@example.com");
        for (int i = 0; i < 4; i++) {
            limiter.recordLoginFailure("bob@example.com");
        }
        assertThatCode(() -> limiter.checkLoginAllowed("bob@example.com")).doesNotThrowAnyException();
    }

    @Test
    void loginPerIpIsTwentyPerMinute() {
        for (int i = 0; i < 20; i++) {
            limiter.acquireLogin("10.0.0.1");
        }
        assertThatThrownBy(() -> limiter.acquireLogin("10.0.0.1")).isInstanceOf(RateLimitedException.class);
        assertThatCode(() -> limiter.acquireLogin("10.0.0.2")).doesNotThrowAnyException();
        clock.advance(Duration.ofMinutes(1));
        assertThatCode(() -> limiter.acquireLogin("10.0.0.1")).doesNotThrowAnyException();
    }

    @Test
    void registerPerIpIsFivePerHourAndSeparateFromLogin() {
        for (int i = 0; i < 5; i++) {
            limiter.acquireRegister("10.0.0.1");
        }
        assertThatThrownBy(() -> limiter.acquireRegister("10.0.0.1"))
                .isInstanceOfSatisfying(RateLimitedException.class,
                        e -> assertThat(e.retryAfterSeconds()).isEqualTo(3600));
        assertThatCode(() -> limiter.acquireLogin("10.0.0.1")).doesNotThrowAnyException();
        clock.advance(Duration.ofHours(1));
        assertThatCode(() -> limiter.acquireRegister("10.0.0.1")).doesNotThrowAnyException();
    }

    @Test
    void retryAfterRoundsUpToWholeSecondsAndIsAtLeastOne() {
        assertThat(new RateLimitedException(Duration.ofMillis(1500), null, null).retryAfterSeconds()).isEqualTo(2);
        assertThat(new RateLimitedException(Duration.ofSeconds(3), null, null).retryAfterSeconds()).isEqualTo(3);
        assertThat(new RateLimitedException(Duration.ZERO, null, null).retryAfterSeconds()).isEqualTo(1);
    }

    @Test
    void fivePasswordCheckFailuresBlockThatUserForFifteenMinutes() {
        for (int i = 0; i < 5; i++) {
            limiter.checkPasswordCheckAllowed(1L, "bob@example.com");
            limiter.recordPasswordCheckFailure(1L);
        }
        assertThatThrownBy(() -> limiter.checkPasswordCheckAllowed(1L, "bob@example.com"))
                .isInstanceOfSatisfying(RateLimitedException.class, e -> {
                    assertThat(e.retryAfterSeconds()).isEqualTo(15 * 60);
                    assertThat(e.limit()).isEqualTo(RateLimitedException.Limit.PASSWORD_CHECK_USER);
                    assertThat(e.limit().logValue()).isEqualTo("password-check-user");
                    assertThat(e.email()).isEqualTo("bob@example.com");
                });
        assertThatCode(() -> limiter.checkPasswordCheckAllowed(2L, "alice@example.com")).doesNotThrowAnyException();

        clock.advance(Duration.ofMinutes(15));
        assertThatCode(() -> limiter.checkPasswordCheckAllowed(1L, "bob@example.com")).doesNotThrowAnyException();
    }

    @Test
    void passwordCheckSuccessResetsFailures() {
        for (int i = 0; i < 4; i++) {
            limiter.recordPasswordCheckFailure(1L);
        }
        limiter.recordPasswordCheckSuccess(1L);
        for (int i = 0; i < 4; i++) {
            limiter.recordPasswordCheckFailure(1L);
        }
        assertThatCode(() -> limiter.checkPasswordCheckAllowed(1L, "bob@example.com")).doesNotThrowAnyException();
    }

    @Test
    void passwordCheckCounterIsSeparateFromLoginCounters() {
        for (int i = 0; i < 5; i++) {
            limiter.recordPasswordCheckFailure(1L);
        }
        // A user id never collides with an email or IP key of the login counters.
        assertThatCode(() -> limiter.checkLoginAllowed("1")).doesNotThrowAnyException();
        assertThatCode(() -> limiter.acquireLogin("1")).doesNotThrowAnyException();
    }
}
